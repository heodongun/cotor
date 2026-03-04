package com.cotor.chat

import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
enum class ChatRole {
    USER,
    ASSISTANT
}

@Serializable
data class ChatMessage(
    val role: ChatRole,
    val content: String,
    val timestamp: String = Instant.now().toString()
)

enum class ChatMode {
    SINGLE,
    COMPARE,
    AUTO;

    companion object {
        fun parse(value: String): ChatMode {
            return when (value.trim().lowercase()) {
                "single" -> SINGLE
                "compare" -> COMPARE
                "auto" -> AUTO
                else -> throw IllegalArgumentException("Unknown chat mode: $value")
            }
        }
    }
}
