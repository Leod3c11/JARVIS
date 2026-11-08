package com.jarvis.assistant.core

/**
 * Contrato do núcleo conversacional.
 *
 * A implementação atual é deliberadamente transparente: ainda não existe LLM
 * integrado. A próxima etapa implementará LocalLlmAssistantEngine via JNI.
 */
interface AssistantEngine {
    suspend fun respond(userText: String): String
}

class BootstrapAssistantEngine : AssistantEngine {
    override suspend fun respond(userText: String): String {
        return "Recebi: “${userText.trim()}”. Meu núcleo de linguagem local ainda será integrado. " +
            "Esta versão é a fundação do JARVIS Core 0.1."
    }
}
