package com.cotor.data.config

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlException
import com.cotor.model.CotorConfig
import com.cotor.model.YamlParsingException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.serializer
import kotlinx.serialization.json.Json

/**
 * Parser for YAML configuration files
 */
class YamlParser {
    private val yaml = Yaml(configuration = YamlConfiguration(strictMode = false))

    /**
     * Parse YAML content into CotorConfig
     * @param content YAML string content
     * @param path File path for error reporting
     * @return Parsed CotorConfig
     * @throws YamlParsingException if parsing fails
     */
    fun parse(content: String, path: String): CotorConfig {
        try {
            return yaml.decodeFromString(CotorConfig.serializer(), content)
        } catch (e: YamlException) {
            val snippet = generateSnippet(content, e.line, e.column)
            throw YamlParsingException(
                path = path,
                line = e.line,
                column = e.column,
                originalMessage = e.message ?: "Unknown YAML parsing error",
                snippet = snippet,
            )
        } catch (e: SerializationException) {
            val snippet = generateSnippet(content, -1, -1) // SerializationException may not have line/col
            throw YamlParsingException(
                path = path,
                line = -1,
                column = -1,
                originalMessage = e.message ?: "Unknown serialization error",
                snippet = snippet,
            )
        }
    }

    private fun generateSnippet(content: String, line: Int, column: Int, contextLines: Int = 2): String {
        val lines = content.lines()
        val startLine = maxOf(0, line - contextLines - 1)
        val endLine = minOf(lines.size, line + contextLines)

        return lines.subList(startLine, endLine)
            .mapIndexed { index, lineContent ->
                val currentLine = startLine + index + 1
                val prefix = if (currentLine == line) "> " else "  "
                "$prefix$currentLine: $lineContent"
            }
            .joinToString("\n")
    }

    /**
     * Serialize CotorConfig to YAML string
     * @param config CotorConfig to serialize
     * @return YAML string
     */
    fun serialize(config: CotorConfig): String {
        return yaml.encodeToString(CotorConfig.serializer(), config)
    }
}

/**
 * Parser for JSON configuration files
 */
class JsonParser {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Parse JSON content into CotorConfig
     * @param content JSON string content
     * @return Parsed CotorConfig
     * @throws SerializationException if parsing fails
     */
    fun parse(content: String): CotorConfig {
        return json.decodeFromString(CotorConfig.serializer(), content)
    }

    /**
     * Serialize CotorConfig to JSON string
     * @param config CotorConfig to serialize
     * @return JSON string
     */
    fun serialize(config: CotorConfig): String {
        return json.encodeToString(CotorConfig.serializer(), config)
    }
}
