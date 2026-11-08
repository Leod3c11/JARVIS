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

        val loadError = nativeLoadModel(file.absolutePath, DEFAULT_CONTEXT, DEFAULT_THREADS)
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
        val loadError = nativeLoadModel(file.absolutePath, DEFAULT_CONTEXT, DEFAULT_THREADS)
        if (!loadError.isNullOrEmpty()) {
            preferences.edit().remove(KEY_MODEL_PATH).apply()
            return@withContext loadError
        }

        activeModelPath = file.absolutePath
        modelName = file.name
        preferences.edit().putString(KEY_MODEL_PATH, file.absolutePath).apply()
        ""
    }

    override suspend fun respond(userText: String): String = withContext(Dispatchers.IO) {
        if (activeModelPath == null) {
            return@withContext "Ainda não há um modelo carregado. Use “Importar modelo GGUF” para selecionar um modelo local."
        }
        if (!nativeAvailable) {
            return@withContext "O motor nativo não está disponível nesta instalação."
        }

        runCatching {
            nativeGenerate(userText, MAX_OUTPUT_TOKENS, 0.7f, 40, 0.9f)
        }.getOrElse { error ->
            "Não consegui gerar uma resposta: ${error.message ?: "erro nativo"}"
        }
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
        topP: Float
    ): String
    private external fun nativeUnloadModel()

    private companion object {
        const val KEY_MODEL_PATH = "model_path"
        const val DEFAULT_CONTEXT = 2048
        const val DEFAULT_THREADS = 4
        const val MAX_OUTPUT_TOKENS = 256
    }
}
