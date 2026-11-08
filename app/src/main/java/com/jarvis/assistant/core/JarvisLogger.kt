package com.jarvis.assistant.core

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local diagnostic journal. No prompt/response content is stored by default.
 * Keeps a bounded JSONL file so diagnostics cannot grow without limit.
 */
class JarvisLogger(context: Context) {
    private val file = File(context.applicationContext.filesDir, "diagnostics/jarvis.log").apply {
        parentFile?.mkdirs()
    }
    private val lock = Any()
    private val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)

    fun record(event: String, details: String = "", level: String = "INFO") {
        synchronized(lock) {
            runCatching {
                val row = JSONObject()
                    .put("time", timestamp.format(Date()))
                    .put("level", level)
                    .put("event", event.take(80))
                    .put("details", details.take(2000))
                file.appendText(row.toString() + "\n", Charsets.UTF_8)
                if (file.length() > MAX_BYTES) {
                    val lines = file.readLines(Charsets.UTF_8).takeLast(MAX_LINES)
                    file.writeText(lines.joinToString("\n", postfix = if (lines.isEmpty()) "" else "\n"), Charsets.UTF_8)
                }
            }
        }
    }

    fun readAll(): String = synchronized(lock) {
        if (file.isFile) file.readText(Charsets.UTF_8) else ""
    }

    fun entryCount(): Int = synchronized(lock) {
        if (file.isFile) file.useLines { it.count() } else 0
    }

    companion object {
        private const val MAX_BYTES = 1024 * 1024
        private const val MAX_LINES = 2500
    }
}
