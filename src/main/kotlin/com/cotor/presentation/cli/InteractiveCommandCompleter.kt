package com.cotor.presentation.cli

/**
 * File overview for InteractiveCommandCompleter.
 *
 * This file belongs to the CLI presentation layer for interactive and command-driven flows.
 * It groups declarations around interactive command completer so readers can find the owning runtime area quickly.
 * Read here first when tracing behavior that flows through this part of the codebase.
 */


import org.jline.reader.Candidate
import org.jline.reader.Completer
import org.jline.reader.LineReader
import org.jline.reader.ParsedLine

class InteractiveCommandCompleter(
    private val agentNamesProvider: () -> List<String>
) : Completer {
    private val commands = listOf("help", "agents", "clear", "save", "exit", "mode", "use", "model", "include")
    private val modeValues = listOf("auto", "compare", "single")

    override fun complete(reader: LineReader?, line: ParsedLine, candidates: MutableList<Candidate>) {
        val values = completionValues(line.line(), line.cursor())
        values.forEach { candidates += Candidate(it) }
    }

    fun suggest(input: String, cursor: Int = input.length): List<String> {
        val values = completionValues(input, cursor)
        val safeCursor = cursor.coerceIn(0, input.length)
        val uptoCursor = input.substring(0, safeCursor)
        if (!uptoCursor.startsWith(":")) return emptyList()

        val raw = uptoCursor.removePrefix(":")
        if (!raw.contains(" ")) return values

        val command = raw.substringBefore(" ").lowercase()
        return when (command) {
            "mode", "use", "model" -> values.map { ":$command $it" }
            "include" -> values.map { ":include $it" }
            else -> emptyList()
        }
    }

    private fun completionValues(input: String, cursor: Int): List<String> {
        val safeCursor = cursor.coerceIn(0, input.length)
        val uptoCursor = input.substring(0, safeCursor)
        if (!uptoCursor.startsWith(":")) return emptyList()

        val raw = uptoCursor.removePrefix(":")
        val normalizedAgents = agentNamesProvider().distinct().sorted()

        if (!raw.contains(" ")) {
            return commands
                .map { ":$it" }
                .filter { it.startsWith(":$raw", ignoreCase = true) }
        }

        val command = raw.substringBefore(" ").lowercase()
        val arg = raw.substringAfter(" ", "")

        return when (command) {
            "mode" -> completeSimpleArgValue(arg, modeValues)
            "use", "model" -> completeSimpleArgValue(arg, normalizedAgents)
            "include" -> completeIncludeValue(arg, normalizedAgents)
            else -> emptyList()
        }
    }

    private fun completeSimpleArgValue(arg: String, options: List<String>): List<String> {
        val token = arg.trimStart()
        return options.filter { it.startsWith(token, ignoreCase = true) }
    }

    private fun completeIncludeValue(arg: String, options: List<String>): List<String> {
        val parts = arg.split(",")
        val current = parts.lastOrNull()?.trimStart().orEmpty()
        val selected = parts.dropLast(1).map { it.trim() }.filter { it.isNotBlank() }.toSet()
        val prefix = parts.dropLast(1).joinToString(",")
        val prefixWithComma = if (prefix.isBlank()) "" else "$prefix,"

        return options
            .filterNot { selected.contains(it) }
            .filter { it.startsWith(current, ignoreCase = true) }
            .map { "$prefixWithComma$it" }
    }
}
