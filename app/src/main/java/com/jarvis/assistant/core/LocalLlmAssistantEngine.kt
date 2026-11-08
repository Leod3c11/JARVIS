package com.jarvis.assistant.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Local llama.cpp inference engine. Model weights are kept in app-private storage.
 */
class LocalLlmAssistantEngine(context: Context) : AssistantEngine {
    private val preferences = context.applicationContext
        .getSharedPreferences("jarvis_llm", Context.MODE_PRIVATE)

    private var activeModelPath: String? = null
    var modelName: String? = null
        private set
    var lastMetrics: String = ""
        private set

    private val nativeAvailable: Boolean = runCatching {
        System.loadLibrary("jarvis_llama")
    }.isSuccess

    suspend fun restoreModel(): Boolean = withContext(Dispatchers.IO) {
        if (!nativeAvailable) return@withContext false
        val path = preferences.getString(KEY_MODEL_PATH, null) ?: return@withContext false
        val file = File(path)
        if (!file.isFile || file.length() <= 0L) {
            preferences.edit().remove(KEY_MODEL_PATH).apply()
            return@withContext false
        }

        val loadError = nativeLoadModel(file.absolutePath, DEFAULT_CONTEXT, optimalThreadCount())
        if (loadError.isNullOrEmpty()) {
            activeModelPath = file.absolutePath
            modelName = file.name
            true
        } else {
            preferences.edit().remove(KEY_MODEL_PATH).apply()
            false
        }
    }

    suspend fun loadModel(path: String): String = withContext(Dispatchers.IO) {
        if (!nativeAvailable) {
            return@withContext "A biblioteca nativa do JARVIS não foi carregada."
        }

        val file = File(path)
        if (!file.isFile || !file.name.endsWith(".gguf", ignoreCase = true)) {
            return@withContext "Selecione um arquivo GGUF válido."
        }

        // A tentativa de troca descarrega o modelo anterior no lado nativo.
        activeModelPath = null
        modelName = null
        val loadError = nativeLoadModel(file.absolutePath, DEFAULT_CONTEXT, optimalThreadCount())
        if (!loadError.isNullOrEmpty()) {
            preferences.edit().remove(KEY_MODEL_PATH).apply()
            return@withContext loadError
        }

        activeModelPath = file.absolutePath
        modelName = file.name
        preferences.edit().putString(KEY_MODEL_PATH, file.absolutePath).apply()
        ""
    }

    override suspend fun respond(userText: String): String = respondStreaming(userText) {}

    suspend fun respondStreaming(userText: String, onToken: (String) -> Unit): String =
        withContext(Dispatchers.IO) {
            if (activeModelPath == null) {
                return@withContext "Ainda não há um modelo carregado. Importe um modelo GGUF nas configurações."
            }
            if (!nativeAvailable) {
                return@withContext "O motor nativo não está disponível nesta instalação."
            }

            runCatching {
                nativeGenerate(userText, outputLimitFor(userText), 0.55f, 24, 0.88f, NativeTokenCallback(onToken)).also {
                    lastMetrics = nativeGetLastMetrics()
                }
            }.getOrElse { error ->
                "Não consegui gerar uma resposta: ${error.message ?: "erro nativo"}"
            }
        }

    private fun optimalThreadCount(): Int =
        Runtime.getRuntime().availableProcessors().coerceIn(4, 6)

    private fun outputLimitFor(text: String): Int {
        val normalized = text.trim().lowercase()
        val asksForDetail = normalized.length > 180 ||
            listOf("explique detalhadamente", "passo a passo", "em detalhes", "compare", "analise", "análise",
                "resuma", "liste", "tutorial", "código", "programa", "programação").any(normalized::contains)
        return if (asksForDetail) 128 else 48
    }

    /** Interrompe a geração em andamento (o texto parcial já emitido é mantido). */
    fun cancel() {
        if (nativeAvailable) runCatching { nativeCancel() }
    }

    fun unload() {
        if (nativeAvailable) runCatching { nativeUnloadModel() }
        activeModelPath = null
        modelName = null
    }

    private external fun nativeLoadModel(path: String, contextSize: Int, threads: Int): String?
    private external fun nativeGenerate(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topK: Int,
        topP: Float,
        callback: NativeTokenCallback
    ): String
    private external fun nativeGetLastMetrics(): String
    private external fun nativeUnloadModel()
    private external fun nativeCancel()

    fun interface NativeTokenCallback {
        fun onToken(token: String)
    }

    private companion object {
        const val KEY_MODEL_PATH = "model_path"
        const val DEFAULT_CONTEXT = 1536
    }
}
