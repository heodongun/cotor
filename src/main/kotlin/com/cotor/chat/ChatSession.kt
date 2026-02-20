package com.cotor.chat

/**
 * Minimal chat session state. This is intentionally simple so it can be reused by both:
 * - TUI interactive mode (readLine loop)
 * - Non-interactive mode (--prompt / --prompt-file), which is easier to test
 */
class ChatSession(
    private val includeContext: Boolean,
    private val maxHistoryMessages: Int
) {
    private val messages = mutableListOf<ChatMessage>()

    fun snapshot(): List<ChatMessage> = messages.toList()

    fun clear() {
        messages.clear()
    }

    fun addUser(content: String) {
        messages += ChatMessage(ChatRole.USER, content)
    }

    fun addAssistant(content: String) {
        messages += ChatMessage(ChatRole.ASSISTANT, content)
    }

    fun buildPrompt(currentUserInput: String): String {
        if (!includeContext) return currentUserInput

        val history = messages.takeLast(maxHistoryMessages)
        val sb = StringBuilder()
        sb.appendLine("You are Cotor, a master agent that can consult multiple CLI-based sub-agents.")
        sb.appendLine("Continue the conversation naturally. Prefer concrete, actionable answers.")
        sb.appendLine()
        if (history.isNotEmpty()) {
            sb.appendLine("Conversation so far:")
            history.forEach { msg ->
                val label = when (msg.role) {
                    ChatRole.USER -> "User"
                    ChatRole.ASSISTANT -> "Assistant"
                }
                sb.appendLine("$label: ${msg.content}")
            }
            sb.appendLine()
        }
        sb.appendLine("User: $currentUserInput")
        sb.append("Assistant:")
        return sb.toString()
    }
}

