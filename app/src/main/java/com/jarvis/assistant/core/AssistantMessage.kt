package com.jarvis.assistant.core

data class AssistantMessage(
    val id: Long,
    val text: String,
    val role: Role,
    val timestamp: Long = System.currentTimeMillis()
) {
    enum class Role { USER, ASSISTANT, SYSTEM }
}
