package com.cotor.chat

import java.time.Instant

enum class ChatRole {
    USER,
    ASSISTANT
}

data class ChatMessage(
    val role: ChatRole,
    val content: String,
    val timestamp: Instant = Instant.now()
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

