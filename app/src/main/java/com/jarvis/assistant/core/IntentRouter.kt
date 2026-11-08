package com.jarvis.assistant.core

import com.jarvis.assistant.platform.PerformanceProfile
import com.jarvis.assistant.platform.PrivilegedAction
import java.text.Normalizer

/**
 * Converte comandos curtos em português em UMA operação da allowlist de [PrivilegedAction].
 *
 * É determinístico de propósito: nenhum texto livre (nem do usuário, nem do modelo) vira comando
 * de shell. Frases longas ou ambíguas vão para o LLM como conversa normal.
 */
object IntentRouter {
    private val deviceWords = listOf("celular", "aparelho", "telefone", "dispositivo", "cpu", "processador", "jarvis")

    fun route(text: String): PrivilegedAction? {
        val t = normalize(text)
        if (t.isBlank() || t.length > 90) return null

        return when {
            any(t, "restaurar estado", "estado original", "desfazer ajustes", "reverter ajustes") ->
                PrivilegedAction.RestoreSnapshot

            any(t, "modo desempenho", "modo performance", "modo turbo") ||
                (any(t, "mais desempenho", "aumentar desempenho", "acelerar") && any(t, *deviceWords.toTypedArray())) ->
                PrivilegedAction.ApplyProfile(PerformanceProfile.PERFORMANCE)

            any(t, "modo economia", "economizar bateria", "economia de bateria", "poupar bateria") ->
                PrivilegedAction.ApplyProfile(PerformanceProfile.BATTERY_SAVER)

            any(t, "modo equilibrado", "modo normal", "perfil equilibrado") ->
                PrivilegedAction.ApplyProfile(PerformanceProfile.BALANCED)

            any(t, "liberar memoria", "limpar memoria", "liberar ram", "limpar ram") ->
                PrivilegedAction.FreeMemory

            any(t, "temperatura", "quente", "esquent", "termic") && any(t, *deviceWords.toTypedArray()) ->
                PrivilegedAction.ReadThermal

            any(t, "estado da cpu", "status da cpu", "frequencia da cpu", "governor") ->
                PrivilegedAction.ReadCpuState

            else -> null
        }
    }

    private fun any(text: String, vararg needles: String) = needles.any { text.contains(it) }

    private fun normalize(text: String): String =
        Normalizer.normalize(text.trim().lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
}
