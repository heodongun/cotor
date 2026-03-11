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

        fun renderPrompt(
            history: List<ChatMessage>,
            bootstrap: String,
            memory: List<String>,
            messageCap: Int
        ): String {
            val sb = StringBuilder()
            sb.appendLine("You are Cotor, a master agent that can consult multiple CLI-based sub-agents.")
            sb.appendLine("Continue the conversation naturally. Prefer concrete, actionable answers.")
            if (bootstrap.isNotBlank()) {
                sb.appendLine()
                sb.appendLine("Workspace bootstrap context:")
                sb.appendLine(bootstrap.trim())
            }
            if (memory.isNotEmpty()) {
                sb.appendLine()
                sb.appendLine("Relevant long-term memory:")
                memory.forEach { sb.appendLine("- $it") }
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
                    val pruned = when {
                        msg.role == ChatRole.ASSISTANT && normalized.length > toolOutputPruneChars && idx < history.lastIndex - 1 ->
                            "[cache-aware-pruned assistant output, ${normalized.length} chars omitted]"

                        normalized.length > messageCap -> normalized.take(messageCap) + "…"
                        else -> normalized
                    }
                    sb.appendLine("$label: $pruned")
                }
                sb.appendLine()
            }
            sb.appendLine("User: $currentUserInput")
            sb.append("Assistant:")
            return sb.toString()
        }

        var history = messages.takeLast(maxHistoryMessages)
        var bootstrap = bootstrapContext.trim()
        var memory = memoryContext.map { it.trim() }.filter { it.isNotBlank() }
        var messageCap = toolOutputPruneChars
        var prompt = renderPrompt(history, bootstrap, memory, messageCap)

        if (prompt.length > maxPromptChars) {
            compactHistory()
            history = messages.takeLast(maxHistoryMessages)
            bootstrap = bootstrap.take(maxPromptChars / 3)
            memory = memory.take(3).map { if (it.length > 320) it.take(320) + "…" else it }
            messageCap = minOf(messageCap, 600)
            prompt = renderPrompt(history, bootstrap, memory, messageCap)
        }

        if (prompt.length > maxPromptChars) {
            bootstrap = bootstrap.take(maxPromptChars / 6)
            memory = memory.take(1).map { if (it.length > 160) it.take(160) + "…" else it }
            history = history.takeLast(minOf(history.size, 6)).map { msg ->
                msg.copy(content = msg.content.replace("\r\n", "\n").take(280))
            }
            prompt = renderPrompt(history, bootstrap, memory, 280)
        }

        if (prompt.length > maxPromptChars) {
            bootstrap = ""
            memory = emptyList()
            history = history.takeLast(minOf(history.size, 2)).map { msg ->
                msg.copy(content = msg.content.replace("\r\n", "\n").take(120))
            }
            prompt = renderPrompt(history, bootstrap, memory, 120)
        }

        // Never recurse forever when the bootstrap context alone is oversized.
        // The last-resort trim keeps interactive mode alive even in instruction-heavy repos.
        return if (prompt.length > maxPromptChars) {
            val currentUserSection = buildString {
                append("User: ")
                append(
                    if (currentUserInput.length > maxPromptChars / 2) {
                        currentUserInput.take(maxPromptChars / 2 - 1) + "…"
                    } else {
                        currentUserInput
                    }
                )
                appendLine()
                append("Assistant:")
            }
            val prefixBudget = (maxPromptChars - currentUserSection.length - 1).coerceAtLeast(0)
            prompt.take(prefixBudget) + "…" + currentUserSection
        } else {
            prompt
        }
    }
}
