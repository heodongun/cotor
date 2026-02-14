package com.cotor.chat

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class ChatTranscriptWriter(
    private val saveDir: Path
) {
    fun ensureDir(): Path {
        saveDir.createDirectories()
        return saveDir
    }

    fun writeMarkdown(
        session: ChatSession,
        headerLines: List<String> = emptyList()
    ) {
        ensureDir()
        val md = buildString {
            appendLine("# Cotor Interactive Session")
            appendLine()
            appendLine("- SavedAt: ${Instant.now()}")
            headerLines.forEach { appendLine("- $it") }
            appendLine()
            session.snapshot().forEach { msg ->
                when (msg.role) {
                    ChatRole.USER -> {
                        appendLine("## User")
                        appendLine()
                        appendLine(msg.content)
                        appendLine()
                    }
                    ChatRole.ASSISTANT -> {
                        appendLine("## Assistant")
                        appendLine()
                        appendLine(msg.content)
                        appendLine()
                    }
                }
            }
        }
        saveDir.resolve("transcript.md").writeText(md)
    }

    fun writeRawText(session: ChatSession) {
        ensureDir()
        val txt = buildString {
            session.snapshot().forEach { msg ->
                val prefix = when (msg.role) {
                    ChatRole.USER -> "USER"
                    ChatRole.ASSISTANT -> "ASSISTANT"
                }
                appendLine("[$prefix] ${msg.timestamp}")
                appendLine(msg.content)
                appendLine()
            }
        }
        saveDir.resolve("transcript.txt").writeText(txt)
    }
}

