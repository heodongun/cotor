package com.cotor.chat

/**
 * Chat session state with simple long-context controls:
 * - Head/Tail preservation with middle summarization
 * - Prompt budget limiting
 * - Cache-aware pruning for large assistant/tool-like outputs
 */
class ChatSession(
    private val includeContext: Boolean,
    private val maxHistoryMessages: Int,
    initialMessages: List<ChatMessage> = emptyList(),
    private val maxPromptChars: Int = 20_000,
    private val toolOutputPruneChars: Int = 1_200
) {
    private val messages = initialMessages.toMutableList()

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

    /**
     * Summarize middle messages while preserving head/tail messages.
     */
    fun compactHistory(keepHead: Int = 4, keepTail: Int = 16) {
        if (messages.size <= keepHead + keepTail + 1) return

        val head = messages.take(keepHead)
        val middle = messages.drop(keepHead).dropLast(keepTail)
        val tail = messages.takeLast(keepTail)

        if (middle.isEmpty()) return

        val summaryLines = middle
            .chunked(2)
            .mapIndexed { idx, chunk ->
                val snippet = chunk.joinToString(" | ") {
                    val role = if (it.role == ChatRole.USER) "U" else "A"
                    "$role:${it.content.replace("\n", " ").take(80)}"
                }
                "${idx + 1}. $snippet"
            }
            .take(12)

        val summaryMessage = ChatMessage(
            role = ChatRole.ASSISTANT,
            content = "[Summary of earlier conversation]\n" + summaryLines.joinToString("\n")
        )

        messages.clear()
        messages.addAll(head)
        messages += summaryMessage
        messages.addAll(tail)
    }

    fun buildPrompt(
        currentUserInput: String,
        bootstrapContext: String = "",
        memoryContext: List<String> = emptyList()
    ): String {
        if (!includeContext) return currentUserInput

        val history = messages.takeLast(maxHistoryMessages)
        val sb = StringBuilder()
        sb.appendLine("You are Cotor, a master agent that can consult multiple CLI-based sub-agents.")
        sb.appendLine("Continue the conversation naturally. Prefer concrete, actionable answers.")
        if (bootstrapContext.isNotBlank()) {
            sb.appendLine()
            sb.appendLine("Workspace bootstrap context:")
            sb.appendLine(bootstrapContext.trim())
        }
        if (memoryContext.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Relevant long-term memory:")
            memoryContext.forEach { sb.appendLine("- $it") }
        }
        sb.appendLine()

        if (history.isNotEmpty()) {
            sb.appendLine("Conversation so far:")
            history.forEachIndexed { idx, msg ->
                val label = when (msg.role) {
                    ChatRole.USER -> "User"
                    ChatRole.ASSISTANT -> "Assistant"
                }
                val normalized = msg.content.replace("\r\n", "\n")
                val pruned = if (msg.role == ChatRole.ASSISTANT && normalized.length > toolOutputPruneChars && idx < history.lastIndex - 1) {
                    "[cache-aware-pruned assistant output, ${normalized.length} chars omitted]"
                } else {
                    normalized
                }
                sb.appendLine("$label: $pruned")
            }
            sb.appendLine()
        }
        sb.appendLine("User: $currentUserInput")
        sb.append("Assistant:")

        if (sb.length > maxPromptChars) {
            compactHistory()
            return buildPrompt(currentUserInput, bootstrapContext, memoryContext)
        }

        return sb.toString()
    }
}
