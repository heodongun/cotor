package com.cotor.chat

/**
 * File overview for ChatTranscriptWriter.
 *
 * This file belongs to the interactive chat layer used by the terminal-based assistant experience.
 * It groups declarations around chat transcript writer so readers can find the owning runtime area quickly.
 * Read here first when tracing behavior that flows through this part of the codebase.
 */

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.writeText

class ChatTranscriptWriter(
    private val saveDir: java.nio.file.Path
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonlFile = saveDir.resolve("session.jsonl")
    private val memoryFile = saveDir.resolve("MEMORY.md")

    fun ensureDir(): java.nio.file.Path {
        saveDir.createDirectories()
        return saveDir
    }

    fun loadSessionMessages(): List<ChatMessage> {
        ensureDir()
        if (!jsonlFile.exists()) return emptyList()
        return jsonlFile.readLines()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                runCatching { json.decodeFromString<ChatMessage>(line) }.getOrNull()
            }
    }

    fun writeJsonl(session: ChatSession) {
        ensureDir()
        val body = buildString {
            session.snapshot().forEach { msg ->
                appendLine(json.encodeToString(msg))
            }
        }
        jsonlFile.writeText(body)
    }

    fun clearJsonl() {
        ensureDir()
        jsonlFile.writeText("")
    }

    fun flushMemoryIfNeeded(session: ChatSession, flushThreshold: Int = 80, keepTail: Int = 30) {
        val snapshot = session.snapshot()
        if (snapshot.size <= flushThreshold) return

        val flushChunk = snapshot.dropLast(keepTail)
        if (flushChunk.isEmpty()) return

        val bullets = flushChunk
            .chunked(2)
            .map { pair ->
                pair.joinToString(" | ") {
                    val role = if (it.role == ChatRole.USER) "USER" else "ASSISTANT"
                    "$role:${it.content.replace("\n", " ").take(100)}"
                }
            }
            .take(20)

        val entry = buildString {
            appendLine("## Memory Flush ${Instant.now()}")
            bullets.forEach { appendLine("- $it") }
            appendLine()
        }

        val existing = if (memoryFile.exists()) memoryFile.readLines().joinToString("\n") else ""
        memoryFile.writeText((existing + "\n" + entry).trimStart())

        session.compactHistory(keepHead = 4, keepTail = keepTail)
        writeJsonl(session)
    }

    fun searchMemory(query: String, limit: Int = 3): List<String> {
        if (!memoryFile.exists() || query.isBlank()) return emptyList()
        val tokens = query.lowercase().split(" ").filter { it.isNotBlank() }
        if (tokens.isEmpty()) return emptyList()

        return memoryFile.readLines()
            .filter { it.startsWith("-") }
            .map { it.removePrefix("-").trim() }
            .map { line ->
                val score = tokens.count { token -> line.lowercase().contains(token) }
                line to score
            }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
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
