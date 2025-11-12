package com.cotor.presentation.formatter

import com.cotor.model.AggregatedResult
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Interface for output formatting
 */
interface OutputFormatter {
    /**
     * Format aggregated result
     * @param result AggregatedResult to format
     * @return Formatted string
     */
    fun format(result: AggregatedResult): String
}

/**
 * JSON output formatter
 */
class JsonOutputFormatter : OutputFormatter {
    private val json = Json { prettyPrint = true }

    override fun format(result: AggregatedResult): String {
        // Build JSON manually to avoid serialization issues
        val resultsJson = result.results.joinToString(",\n    ") { agentResult ->
            """
            {
              "agentName": "${agentResult.agentName}",
              "isSuccess": ${agentResult.isSuccess},
              "output": ${if (agentResult.output != null) "\"${escapeJson(agentResult.output)}\"" else "null"},
              "error": ${if (agentResult.error != null) "\"${escapeJson(agentResult.error)}\"" else "null"},
              "duration": ${agentResult.duration},
              "metadata": ${formatMetadata(agentResult.metadata)}
            }
            """.trimIndent()
        }

        return """
        {
          "totalAgents": ${result.totalAgents},
          "successCount": ${result.successCount},
          "failureCount": ${result.failureCount},
          "totalDuration": ${result.totalDuration},
          "timestamp": "${result.timestamp}",
          "results": [
            $resultsJson
          ]
        }
        """.trimIndent()
    }

    private fun escapeJson(str: String): String {
        return str.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    private fun formatMetadata(metadata: Map<String, String>): String {
        if (metadata.isEmpty()) return "{}"
        val entries = metadata.entries.joinToString(", ") { (key, value) ->
            "\"$key\": \"${escapeJson(value)}\""
        }
        return "{ $entries }"
    }
}

/**
 * CSV output formatter
 */
class CsvOutputFormatter : OutputFormatter {
    override fun format(result: AggregatedResult): String {
        val sb = StringBuilder()

        // Header
        sb.appendLine("AgentName,IsSuccess,Duration,Output,Error")

        // Data rows
        result.results.forEach { agentResult ->
            sb.appendLine(
                "${agentResult.agentName}," +
                        "${agentResult.isSuccess}," +
                        "${agentResult.duration}," +
                        "\"${agentResult.output?.replace("\"", "\"\"")}\"," +
                        "\"${agentResult.error?.replace("\"", "\"\"")}\""
            )
        }

        // Summary
        sb.appendLine()
        sb.appendLine("Summary")
        sb.appendLine("Total Agents,${result.totalAgents}")
        sb.appendLine("Success Count,${result.successCount}")
        sb.appendLine("Failure Count,${result.failureCount}")
        sb.appendLine("Total Duration,${result.totalDuration}ms")

        return sb.toString()
    }
}

/**
 * Text output formatter (human-readable)
 */
class TextOutputFormatter : OutputFormatter {
    override fun format(result: AggregatedResult): String {
        val sb = StringBuilder()

        sb.appendLine("=" * 80)
        sb.appendLine("Pipeline Execution Results")
        sb.appendLine("=" * 80)
        sb.appendLine()

        sb.appendLine("Summary:")
        sb.appendLine("  Total Agents:  ${result.totalAgents}")
        sb.appendLine("  Success Count: ${result.successCount}")
        sb.appendLine("  Failure Count: ${result.failureCount}")
        sb.appendLine("  Total Duration: ${result.totalDuration}ms")
        sb.appendLine("  Timestamp:     ${result.timestamp}")
        sb.appendLine()

        sb.appendLine("Agent Results:")
        result.results.forEachIndexed { index, agentResult ->
            sb.appendLine()
            sb.appendLine("  [${index + 1}] ${agentResult.agentName}")
            sb.appendLine("      Status:   ${if (agentResult.isSuccess) "✓ SUCCESS" else "✗ FAILED"}")
            sb.appendLine("      Duration: ${agentResult.duration}ms")

            if (agentResult.isSuccess && agentResult.output != null) {
                sb.appendLine("      Output:")
                agentResult.output.lines().forEach { line ->
                        sb.appendLine("        $line")
                }
            }

            if (!agentResult.isSuccess && agentResult.error != null) {
                sb.appendLine("      Error: ${agentResult.error}")
            }
        }

        sb.appendLine()
        sb.appendLine("=" * 80)

        return sb.toString()
    }

    private operator fun String.times(n: Int): String = this.repeat(n)
}
