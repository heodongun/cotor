package com.cotor.presentation.cli

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
        val suggestions = suggest(line.line(), line.cursor())
        suggestions.forEach { candidates += Candidate(it) }
    }

    fun suggest(input: String, cursor: Int = input.length): List<String> {
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
            "mode" -> completeSimpleArg(command, arg, modeValues)
            "use", "model" -> completeSimpleArg(command, arg, normalizedAgents)
            "include" -> completeInclude(arg, normalizedAgents)
            else -> emptyList()
        }
    }

    private fun completeSimpleArg(command: String, arg: String, options: List<String>): List<String> {
        val token = arg.trimStart()
        return options
            .filter { it.startsWith(token, ignoreCase = true) }
            .map { ":$command $it" }
    }

    private fun completeInclude(arg: String, options: List<String>): List<String> {
        val parts = arg.split(",")
        val current = parts.lastOrNull()?.trimStart().orEmpty()
        val selected = parts.dropLast(1).map { it.trim() }.filter { it.isNotBlank() }.toSet()
        val prefix = parts.dropLast(1).joinToString(",")
        val prefixWithComma = if (prefix.isBlank()) "" else "$prefix,"

        return options
            .filterNot { selected.contains(it) }
            .filter { it.startsWith(current, ignoreCase = true) }
            .map { ":include $prefixWithComma$it" }
    }
}
