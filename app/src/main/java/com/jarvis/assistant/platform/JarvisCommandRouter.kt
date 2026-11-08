package com.jarvis.assistant.platform

import android.content.Context
import android.content.Intent
import java.text.Normalizer
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Deterministic Android actions; never executes model-generated shell commands. */
object JarvisCommandRouter {
    fun handle(context: Context, rawText: String): String? {
        val text = normalize(rawText)
        if (text.isBlank()) return null
        if (asksForDateOrTime(text)) {
            val now = LocalDateTime.now()
            val wantsTime = listOf("hora", "horas", "horario").any(text::contains)
            val wantsDate = listOf("data", "dia", "hoje", "que dia", "qual dia").any(text::contains)
            val date = now.format(DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM 'de' yyyy", PT_BR))
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase(PT_BR) else it.toString() }
            val time = now.format(DateTimeFormatter.ofPattern("HH:mm", PT_BR))
            return when {
                wantsDate && wantsTime -> "Agora são $time de $date."
                wantsTime -> "Agora são $time."
                else -> "Hoje é $date."
            }
        }
        val requestedApp = extractAppRequest(text) ?: return null
        return launchApp(context, requestedApp)
    }

    private fun asksForDateOrTime(text: String): Boolean =
        listOf(
            "que dia e hoje", "qual e a data", "data de hoje", "dia de hoje",
            "que dia estamos", "em que dia estamos", "hoje e que dia", "qual o dia",
            "que horas sao", "que hora e", "hora atual", "horario atual", "horas agora"
        ).any(text::contains)

    private fun extractAppRequest(text: String): String? {
        val prefixes = listOf(
            "voce pode abrir ", "pode abrir ", "por favor abra ", "por favor abrir ",
            "abra ", "abrir ", "abre ", "inicie ", "iniciar ", "execute ", "executar ",
            "open ", "launch "
        )
        val prefix = prefixes.firstOrNull(text::startsWith) ?: return null
        return text.removePrefix(prefix)
            .removePrefix("o aplicativo ").removePrefix("o app ")
            .removePrefix("aplicativo ").removePrefix("app ")
            .trim().trimEnd('.', '!', '?').takeIf(String::isNotBlank)
    }

    private fun launchApp(context: Context, requested: String): String {
        val aliases = mapOf(
            "youtube" to listOf("youtube"),
            "whatsapp" to listOf("whatsapp"),
            "chrome" to listOf("chrome", "google chrome"),
            "configuracoes" to listOf("configuracoes", "settings"),
            "camera" to listOf("camera"),
            "galeria" to listOf("galeria", "gallery"),
            "fotos" to listOf("fotos", "photos", "google fotos"),
            "telefone" to listOf("telefone", "phone", "discador"),
            "mensagens" to listOf("mensagens", "messages", "sms"),
            "mapas" to listOf("maps", "google maps"),
            "play store" to listOf("play store", "google play")
        )
        val candidates = aliases[requested] ?: listOf(requested)
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val matches = context.packageManager.queryIntentActivities(launcherIntent, 0)
        val match = matches.firstOrNull { info ->
            val label = normalize(info.loadLabel(context.packageManager).toString())
            candidates.any { label == it }
        } ?: matches.firstOrNull { info ->
            val label = normalize(info.loadLabel(context.packageManager).toString())
            candidates.any { label.contains(it) || it.contains(label) }
        }
        if (match == null) return "Não encontrei o aplicativo “$requested” entre os apps disponíveis para abertura."
        val packageName = match.activityInfo.packageName
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return "Encontrei ${match.loadLabel(context.packageManager)}, mas o Android não forneceu uma tela de abertura."
        return runCatching {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            "Abrindo ${match.loadLabel(context.packageManager)}."
        }.getOrElse { "Não consegui abrir o aplicativo: ${it.message ?: "erro do Android"}." }
    }

    private fun normalize(value: String): String =
        Normalizer.normalize(value.lowercase(PT_BR), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

    private val PT_BR = Locale("pt", "BR")
}
