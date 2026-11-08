package com.jarvis.assistant.platform

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.jarvis.assistant.core.JarvisLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

enum class PerformanceProfile(val label: String) {
    PERFORMANCE("Desempenho"),
    BALANCED("Equilibrado"),
    BATTERY_SAVER("Economia")
}

/**
 * Allowlist de operações privilegiadas. O modelo de linguagem NUNCA produz comandos:
 * ele (ou o roteador de intenção) apenas escolhe uma destas operações nomeadas, e os
 * parâmetros são validados aqui, no lado Android, contra valores lidos do próprio sistema.
 */
sealed class PrivilegedAction(
    val id: String,
    val title: String,
    val description: String,
    val mutating: Boolean
) {
    data object ReadCpuState : PrivilegedAction(
        "read_cpu_state", "Ler estado da CPU", "Lê governor e frequências de cada cluster.", false
    )

    data object ReadThermal : PrivilegedAction(
        "read_thermal", "Ler temperaturas", "Lê as zonas térmicas do aparelho.", false
    )

    data class ApplyProfile(val profile: PerformanceProfile) : PrivilegedAction(
        "apply_profile_${profile.name.lowercase()}",
        "Perfil ${profile.label}",
        when (profile) {
            PerformanceProfile.PERFORMANCE ->
                "Ajusta o governor da CPU para desempenho máximo. Aumenta consumo e calor. " +
                    "Pode ser revertido a qualquer momento."
            PerformanceProfile.BALANCED ->
                "Volta a CPU ao estado original salvo (ou a um perfil equilibrado padrão)."
            PerformanceProfile.BATTERY_SAVER ->
                "Limita a frequência máxima a cerca de 70% para economizar bateria."
        },
        true
    )

    data object FreeMemory : PrivilegedAction(
        "free_memory", "Liberar memória",
        "Sincroniza o disco, limpa caches do kernel e encerra processos em segundo plano. " +
            "Apps em segundo plano serão fechados.",
        true
    )

    data object RestoreSnapshot : PrivilegedAction(
        "restore_snapshot", "Restaurar estado original",
        "Devolve governor e frequências ao estado salvo antes da primeira alteração.", true
    )
}

data class ActionResult(val ok: Boolean, val message: String)

data class CpuPolicy(
    val id: Int,
    val governor: String,
    val curKhz: Long,
    val minKhz: Long,
    val maxKhz: Long,
    val hwMinKhz: Long,
    val hwMaxKhz: Long,
    val governors: List<String>,
    val freqs: List<Long>
)

private data class PolicyTarget(val id: Int, val governor: String?, val minKhz: Long?, val maxKhz: Long?)

/**
 * Ponte controlada para root (`su`). Cada operação:
 *  1. é uma entrada fixa da allowlist [PrivilegedAction];
 *  2. tem parâmetros validados contra o que o kernel anuncia (governors/frequências reais);
 *  3. passa por guardas de bateria e temperatura quando aumenta consumo;
 *  4. salva um snapshot restaurável e registra auditoria local.
 */
class RootBroker(context: Context, private val logger: JarvisLogger) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("jarvis_root", Context.MODE_PRIVATE)
    private val mutex = Mutex()

    @Volatile private var rootCache: Boolean? = null
    @Volatile private var rootCheckedAt = 0L

    fun hasSnapshot(): Boolean = prefs.contains(KEY_SNAPSHOT)

    /** Verifica (e pede ao gerenciador, ex.: Magisk) acesso root. */
    suspend fun isRootAvailable(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val cached = rootCache
        if (!force && cached != null && System.currentTimeMillis() - rootCheckedAt < 30_000) return@withContext cached
        val (code, out) = runRoot(listOf("id"), timeoutSec = 20)
        val ok = code == 0 && out.contains("uid=0")
        rootCache = ok
        rootCheckedAt = System.currentTimeMillis()
        logger.record("root_check", "disponivel=$ok")
        ok
    }

    suspend fun execute(action: PrivilegedAction): ActionResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            val result = runCatching { dispatch(action) }
                .getOrElse { ActionResult(false, "Falha inesperada: ${it.message ?: "erro"}") }
            logger.record(
                "root_action",
                "id=${action.id}; ok=${result.ok}; ${result.message.take(300)}",
                if (result.ok) "INFO" else "WARN"
            )
            result
        }
    }

    /** Boost opcional durante a geração: guarda o estado atual, aplica desempenho e permite restaurar. */
    suspend fun beginInferenceBoost(): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                if (!isRootAvailable()) return@runCatching false
                if (guardFor(PerformanceProfile.PERFORMANCE) != null) return@runCatching false
                val policies = readPolicies()
                if (policies.isEmpty()) return@runCatching false
                prefs.edit().putString(KEY_BOOST, targetsToJson(captureTargets(policies)).toString()).apply()
                val ok = applyTargets(targetsFor(PerformanceProfile.PERFORMANCE, policies, null), policies).ok
                if (!ok) prefs.edit().remove(KEY_BOOST).apply()
                logger.record("inference_boost_begin", "ok=$ok")
                ok
            }.getOrDefault(false)
        }
    }

    suspend fun endInferenceBoost() = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching { restoreBoostLocked("inference_boost_end") }
        }
        Unit
    }

    /** Se o app foi encerrado durante um boost, devolve a CPU ao estado anterior na próxima abertura. */
    suspend fun recoverIfNeeded() = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (prefs.contains(KEY_BOOST)) runCatching { restoreBoostLocked("inference_boost_recover") }
        }
        Unit
    }

    // ------------------------------------------------------------------ despacho

    private suspend fun dispatch(action: PrivilegedAction): ActionResult = when (action) {
        PrivilegedAction.ReadCpuState -> readCpuSummary()
        PrivilegedAction.ReadThermal -> readThermalSummary()
        is PrivilegedAction.ApplyProfile -> applyProfile(action.profile)
        PrivilegedAction.FreeMemory -> freeMemory()
        PrivilegedAction.RestoreSnapshot -> restoreSnapshot()
    }

    private suspend fun readCpuSummary(): ActionResult {
        val policies = readPolicies()
        if (policies.isEmpty()) {
            return ActionResult(false, "Não consegui ler a interface cpufreq. Verifique o acesso root em CONFIG.")
        }
        val text = policies.joinToString("\n") { p ->
            "cluster ${p.id}: ${p.governor} • ${mhz(p.curKhz)} MHz (limites ${mhz(p.minKhz)}–${mhz(p.maxKhz)}, " +
                "hardware ${mhz(p.hwMinKhz)}–${mhz(p.hwMaxKhz)})"
        }
        return ActionResult(true, text)
    }

    private suspend fun readThermalSummary(): ActionResult {
        var readings = readThermalDirect()
        if (readings.isEmpty() && rootCache == true) readings = readThermalViaRoot()
        if (readings.isEmpty()) {
            return ActionResult(false, "Sem acesso às zonas térmicas. Verifique o acesso root em CONFIG.")
        }
        val text = readings.sortedByDescending { it.second }.take(10)
            .joinToString("\n") { (type, c) -> "$type: ${"%.1f".format(c)} °C" }
        return ActionResult(true, text)
    }

    private fun toCelsius(raw: Long): Double? {
        val c = if (raw > 1000) raw / 1000.0 else raw.toDouble()
        return c.takeIf { it in -20.0..150.0 }
    }

    private fun readThermalDirect(): List<Pair<String, Double>> =
        File(THERMAL_BASE).listFiles().orEmpty()
            .filter { THERMAL_RE.matches(it.name) }
            .mapNotNull { zone ->
                val type = readSys("${zone.path}/type") ?: return@mapNotNull null
                val raw = readSys("${zone.path}/temp")?.toLongOrNull() ?: return@mapNotNull null
                toCelsius(raw)?.let { type to it }
            }

    /** Um único script fixo em root (sem parâmetros externos). */
    private fun readThermalViaRoot(): List<Pair<String, Double>> {
        val script = "for z in $THERMAL_BASE/thermal_zone*; do " +
            "echo \"\$(cat \$z/type 2>/dev/null)|\$(cat \$z/temp 2>/dev/null)\"; done"
        val (code, out) = runRoot(listOf(script))
        if (code != 0) return emptyList()
        return out.lineSequence().mapNotNull { line ->
            val parts = line.split('|')
            if (parts.size != 2) return@mapNotNull null
            val raw = parts[1].trim().toLongOrNull() ?: return@mapNotNull null
            val type = parts[0].trim().takeIf { it.matches(NAME_RE) } ?: return@mapNotNull null
            toCelsius(raw)?.let { type to it }
        }.toList()
    }

    private suspend fun applyProfile(profile: PerformanceProfile): ActionResult {
        if (!isRootAvailable()) return ActionResult(false, "Root não está disponível ou não foi autorizado.")
        guardFor(profile)?.let { return ActionResult(false, it) }

        val policies = readPolicies()
        if (policies.isEmpty()) return ActionResult(false, "Não consegui ler a interface cpufreq deste aparelho.")

        // O snapshot original só é gravado uma vez; trocar de perfil várias vezes não o sobrescreve.
        if (!prefs.contains(KEY_SNAPSHOT)) {
            prefs.edit().putString(KEY_SNAPSHOT, targetsToJson(captureTargets(policies)).toString()).apply()
        }
        val original = if (profile == PerformanceProfile.BALANCED) loadSnapshot() else null
        val result = applyTargets(targetsFor(profile, policies, original), policies)
        if (result.ok && profile == PerformanceProfile.BALANCED && original != null) {
            prefs.edit().remove(KEY_SNAPSHOT).apply()
        }
        return if (result.ok) ActionResult(true, "Perfil ${profile.label} aplicado.\n${result.message}") else result
    }

    private suspend fun restoreSnapshot(): ActionResult {
        val saved = loadSnapshot() ?: return ActionResult(false, "Não há estado original salvo para restaurar.")
        if (!isRootAvailable()) return ActionResult(false, "Root não está disponível ou não foi autorizado.")
        val policies = readPolicies()
        val result = applyTargets(saved, policies)
        if (result.ok) prefs.edit().remove(KEY_SNAPSHOT).apply()
        return if (result.ok) ActionResult(true, "Estado original restaurado.\n${result.message}") else result
    }

    private suspend fun freeMemory(): ActionResult {
        if (!isRootAvailable()) return ActionResult(false, "Root não está disponível ou não foi autorizado.")
        val before = availableRamMb()
        val (code, out) = runRoot(
            listOf("sync", "echo 3 > /proc/sys/vm/drop_caches", "am kill-all"),
            timeoutSec = 20
        )
        if (code != 0) return ActionResult(false, "O comando falhou: ${out.take(200)}")
        val after = availableRamMb()
        return ActionResult(true, "RAM disponível: $before MB → $after MB.")
    }

    // ------------------------------------------------------------------ perfis e guardas

    private fun guardFor(profile: PerformanceProfile): String? {
        if (profile != PerformanceProfile.PERFORMANCE) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val power = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (power.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE) {
                return "Recusado: o aparelho já está quente (estado térmico elevado)."
            }
        }
        val battery = appContext.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (level in 0..14) return "Recusado: bateria abaixo de 15%."
        return null
    }

    private fun targetsFor(
        profile: PerformanceProfile,
        policies: List<CpuPolicy>,
        original: List<PolicyTarget>?
    ): List<PolicyTarget> {
        if (profile == PerformanceProfile.BALANCED && original != null) return original
        return policies.map { p ->
            when (profile) {
                PerformanceProfile.PERFORMANCE -> {
                    if ("performance" in p.governors) {
                        PolicyTarget(p.id, "performance", p.hwMinKhz, p.hwMaxKhz)
                    } else {
                        PolicyTarget(p.id, null, pickFreq(p, 0.6f), p.hwMaxKhz)
                    }
                }
                PerformanceProfile.BALANCED ->
                    PolicyTarget(p.id, preferredGovernor(p), p.hwMinKhz, p.hwMaxKhz)
                PerformanceProfile.BATTERY_SAVER ->
                    PolicyTarget(p.id, preferredGovernor(p), p.hwMinKhz, pickFreq(p, 0.7f))
            }
        }
    }

    private fun preferredGovernor(p: CpuPolicy): String? =
        GOVERNOR_PREFERENCE.firstOrNull { it in p.governors } ?: p.governors.firstOrNull()

    /** Maior frequência anunciada que não passe de [fraction] do máximo do hardware. */
    private fun pickFreq(p: CpuPolicy, fraction: Float): Long {
        val target = (p.hwMaxKhz * fraction).toLong()
        if (p.freqs.isNotEmpty()) {
            return p.freqs.filter { it <= target }.maxOrNull() ?: p.freqs.min()
        }
        return target.coerceIn(p.hwMinKhz, p.hwMaxKhz)
    }

    // ------------------------------------------------------------------ cpufreq

    private fun readPolicies(): List<CpuPolicy> {
        val direct = readPoliciesDirect()
        // Sem governors legíveis não dá para validar nada: prefere a leitura completa via root.
        if (direct.isNotEmpty() && direct.all { it.governors.isNotEmpty() }) return direct
        if (rootCache == true) {
            val viaRoot = readPoliciesViaRoot()
            if (viaRoot.isNotEmpty()) return viaRoot
        }
        return direct
    }

    private fun policyFromFields(id: Int, f: (String) -> String?): CpuPolicy? {
        val governor = f("scaling_governor")?.takeIf { it.matches(NAME_RE) } ?: return null
        val hwMax = f("cpuinfo_max_freq")?.toLongOrNull() ?: return null
        val hwMin = f("cpuinfo_min_freq")?.toLongOrNull() ?: 0L
        return CpuPolicy(
            id = id,
            governor = governor,
            curKhz = f("scaling_cur_freq")?.toLongOrNull() ?: 0L,
            minKhz = f("scaling_min_freq")?.toLongOrNull() ?: hwMin,
            maxKhz = f("scaling_max_freq")?.toLongOrNull() ?: hwMax,
            hwMinKhz = hwMin,
            hwMaxKhz = hwMax,
            governors = f("scaling_available_governors")
                ?.split(WHITESPACE)?.filter { it.matches(NAME_RE) }.orEmpty(),
            freqs = f("scaling_available_frequencies")
                ?.split(WHITESPACE)?.mapNotNull { it.toLongOrNull() }.orEmpty().sorted()
        )
    }

    private fun readPoliciesDirect(): List<CpuPolicy> {
        val ids = File(CPUFREQ).listFiles().orEmpty()
            .mapNotNull { POLICY_RE.matchEntire(it.name)?.groupValues?.get(1)?.toIntOrNull() }
            .sorted()
        return ids.mapNotNull { id -> policyFromFields(id) { name -> readSys("$CPUFREQ/policy$id/$name") } }
    }

    /** Um único script fixo em root que imprime `campo=valor` por cluster. */
    private fun readPoliciesViaRoot(): List<CpuPolicy> {
        val script = "for p in $CPUFREQ/policy*; do echo \"P=\${p##*/}\"; " +
            "for f in $POLICY_FILES; do echo \"\$f=\$(cat \$p/\$f 2>/dev/null)\"; done; done"
        val (code, out) = runRoot(listOf(script))
        if (code != 0) return emptyList()

        val policies = mutableListOf<CpuPolicy>()
        var currentId: Int? = null
        var fields = mutableMapOf<String, String>()
        fun flush() {
            currentId?.let { id -> policyFromFields(id) { fields[it]?.takeIf(String::isNotBlank) }?.let(policies::add) }
        }
        for (line in out.lineSequence()) {
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq).trim()
            val value = line.substring(eq + 1).trim()
            if (key == "P") {
                flush()
                currentId = POLICY_RE.matchEntire(value)?.groupValues?.get(1)?.toIntOrNull()
                fields = mutableMapOf()
            } else {
                fields[key] = value
            }
        }
        flush()
        return policies.sortedBy { it.id }
    }

    private fun captureTargets(policies: List<CpuPolicy>) =
        policies.map { PolicyTarget(it.id, it.governor, it.minKhz, it.maxKhz) }

    /** Valida cada valor e grava. Ordem min→max→min evita que o kernel rejeite faixas cruzadas. */
    private suspend fun applyTargets(targets: List<PolicyTarget>, policies: List<CpuPolicy>): ActionResult {
        val byId = policies.associateBy { it.id }
        val commands = mutableListOf<String>()
        for (t in targets) {
            val p = byId[t.id] ?: continue
            t.governor?.let { require(it in p.governors && it.matches(NAME_RE)) { "governor inválido: $it" } }
            t.minKhz?.let { require(it in p.hwMinKhz..p.hwMaxKhz) { "frequência mínima fora do hardware: $it" } }
            t.maxKhz?.let { require(it in p.hwMinKhz..p.hwMaxKhz) { "frequência máxima fora do hardware: $it" } }
            val base = "$CPUFREQ/policy${p.id}"
            if (t.minKhz != null) commands += "echo ${p.hwMinKhz} > $base/scaling_min_freq"
            if (t.maxKhz != null) commands += "echo ${t.maxKhz} > $base/scaling_max_freq"
            if (t.minKhz != null) commands += "echo ${t.minKhz} > $base/scaling_min_freq"
            if (t.governor != null) commands += "echo ${t.governor} > $base/scaling_governor"
        }
        if (commands.isEmpty()) return ActionResult(false, "Nada para aplicar.")
        val (code, out) = runRoot(commands)
        if (code != 0) return ActionResult(false, "Falha ao gravar no kernel: ${out.take(200)}")

        // Confere o resultado real em vez de presumir sucesso.
        val after = readPolicies().associateBy { it.id }
        val mismatches = targets.mapNotNull { t ->
            val p = after[t.id] ?: return@mapNotNull null
            val wrongGovernor = t.governor != null && p.governor != t.governor
            val wrongMax = t.maxKhz != null && p.maxKhz != t.maxKhz
            if (wrongGovernor || wrongMax) "cluster ${t.id}" else null
        }
        val summary = after.values.sortedBy { it.id }.joinToString("\n") {
            "cluster ${it.id}: ${it.governor} • máx ${mhz(it.maxKhz)} MHz"
        }
        return if (mismatches.isEmpty()) {
            ActionResult(true, summary)
        } else {
            ActionResult(false, "O kernel não aceitou todas as alterações (${mismatches.joinToString()}).\n$summary")
        }
    }

    private suspend fun restoreBoostLocked(event: String) {
        val saved = prefs.getString(KEY_BOOST, null)?.let { runCatching { jsonToTargets(JSONArray(it)) }.getOrNull() }
        prefs.edit().remove(KEY_BOOST).apply()
        if (saved.isNullOrEmpty() || !isRootAvailable()) return
        val result = applyTargets(saved, readPolicies())
        logger.record(event, "ok=${result.ok}")
    }

    // ------------------------------------------------------------------ persistência

    private fun loadSnapshot(): List<PolicyTarget>? =
        prefs.getString(KEY_SNAPSHOT, null)
            ?.let { runCatching { jsonToTargets(JSONArray(it)) }.getOrNull() }
            ?.takeIf { it.isNotEmpty() }

    private fun targetsToJson(targets: List<PolicyTarget>): JSONArray = JSONArray().also { array ->
        targets.forEach { t ->
            array.put(
                JSONObject().put("id", t.id)
                    .put("gov", t.governor ?: JSONObject.NULL)
                    .put("min", t.minKhz ?: JSONObject.NULL)
                    .put("max", t.maxKhz ?: JSONObject.NULL)
            )
        }
    }

    private fun jsonToTargets(array: JSONArray): List<PolicyTarget> = (0 until array.length()).map { i ->
        val o = array.getJSONObject(i)
        PolicyTarget(
            id = o.getInt("id"),
            governor = if (o.isNull("gov")) null else o.getString("gov"),
            minKhz = if (o.isNull("min")) null else o.getLong("min"),
            maxKhz = if (o.isNull("max")) null else o.getLong("max")
        )
    }

    // ------------------------------------------------------------------ baixo nível

    /** Leitura direta restrita a caminhos conhecidos de sysfs (sem root). */
    private fun readSys(path: String): String? {
        if (!SAFE_PATH_RE.matches(path) || ".." in path) return null
        if (!(path.startsWith("$CPUFREQ/policy") || path.startsWith("$THERMAL_BASE/thermal_zone"))) return null
        return runCatching { File(path).readText().trim() }.getOrNull()?.ifEmpty { null }
    }

    private fun availableRamMb(): Long {
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.availMem / (1024L * 1024L)
    }

    private fun mhz(khz: Long) = khz / 1000

    /**
     * Executa uma lista de comandos FIXOS em um shell root. As strings são montadas apenas a partir
     * de constantes e de valores já validados (inteiros, governors anunciados pelo kernel).
     */
    private fun runRoot(commands: List<String>, timeoutSec: Long = 10): Pair<Int, String> {
        return try {
            val process = ProcessBuilder("su").redirectErrorStream(true).start()
            val output = StringBuilder()
            val reader = Thread {
                runCatching { process.inputStream.bufferedReader().forEachLine { output.appendLine(it) } }
            }.apply { isDaemon = true; start() }

            process.outputStream.bufferedWriter().use { writer ->
                commands.forEach { writer.write(it); writer.newLine() }
                writer.write("exit"); writer.newLine()
            }
            if (!process.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return -1 to "tempo esgotado"
            }
            reader.join(1000)
            process.exitValue() to output.toString()
        } catch (e: Exception) {
            -1 to (e.message ?: "su indisponível")
        }
    }

    private companion object {
        const val CPUFREQ = "/sys/devices/system/cpu/cpufreq"
        const val THERMAL_BASE = "/sys/class/thermal"
        const val POLICY_FILES = "scaling_governor cpuinfo_max_freq cpuinfo_min_freq scaling_cur_freq " +
            "scaling_min_freq scaling_max_freq scaling_available_governors scaling_available_frequencies"
        const val KEY_SNAPSHOT = "cpu_snapshot"
        const val KEY_BOOST = "boost_restore"
        val POLICY_RE = Regex("policy(\\d+)")
        val THERMAL_RE = Regex("thermal_zone\\d+")
        val NAME_RE = Regex("[A-Za-z0-9_\\-]+")
        val SAFE_PATH_RE = Regex("[A-Za-z0-9_/.\\-]+")
        val WHITESPACE = Regex("\\s+")
        val GOVERNOR_PREFERENCE = listOf("schedutil", "walt", "sched_pixel", "interactive", "ondemand")
    }
}
