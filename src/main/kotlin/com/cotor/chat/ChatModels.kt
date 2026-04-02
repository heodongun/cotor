package com.cotor.chat

/**
 * File overview for ChatRole.
 *
 * This file belongs to the interactive chat layer used by the terminal-based assistant experience.
 * It groups declarations around chat models so readers can find the owning runtime area quickly.
 * Read here first when tracing behavior that flows through this part of the codebase.
 */

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
