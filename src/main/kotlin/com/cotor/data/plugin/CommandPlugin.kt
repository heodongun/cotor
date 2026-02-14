package com.cotor.data.plugin

import com.cotor.data.process.ProcessManager
import com.cotor.model.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Generic command runner plugin.
 *
 * Use this to define new "sub-agents" without writing Kotlin code.
 *
 * Agent parameters:
 * - argvJson: JSON array of argv tokens, e.g. ["mycli", "--flag", "{input}"]
 * - stdin: "true" to send the stage input to stdin instead of substituting {input}
 */
class CommandPlugin : AgentPlugin {
    override val metadata = AgentMetadata(
        name = "command",
        version = "1.0.0",
        description = "Run an arbitrary command as a sub-agent (configured via argvJson)",
        author = "Cotor Team",
        supportedFormats = listOf(DataFormat.TEXT, DataFormat.JSON, DataFormat.CSV)
    )

    override val parameterSchema = AgentParameterSchema(
        parameters = listOf(
            AgentParameter(
                name = "argvJson",
                type = ParameterType.STRING,
                required = true,
                description = "JSON array of argv tokens (first element is the executable). Supports {input} substitution."
            ),
            AgentParameter(
                name = "stdin",
                type = ParameterType.BOOLEAN,
                required = false,
                description = "If true, send the stage input to stdin instead of substituting {input}.",
                defaultValue = "false"
            )
        )
    )

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(
        context: ExecutionContext,
        processManager: ProcessManager
    ): String {
        val argvJson = context.parameters["argvJson"]
            ?: throw IllegalArgumentException("Parameter 'argvJson' is required")

        val argv = parseArgvJson(argvJson).map { token ->
            if (token.contains("{input}")) {
                token.replace("{input}", context.input.orEmpty())
            } else {
                token
            }
        }

        val useStdin = context.parameters["stdin"]?.lowercase() == "true"
        val stdin = if (useStdin) context.input else null

        if (argv.isEmpty()) {
            throw IllegalArgumentException("argvJson must contain at least one element (the executable)")
        }

        val result = processManager.executeProcess(
            command = argv,
            input = stdin,
            environment = context.environment,
            timeout = context.timeout
        )

        if (!result.isSuccess) {
            throw ProcessExecutionException(
                message = "Command failed",
                exitCode = result.exitCode,
                stdout = result.stdout,
                stderr = result.stderr
            )
        }

        return result.stdout
    }

    override fun validateInput(input: String?): ValidationResult {
        // Input is optional; depending on argvJson it may be unused.
        return ValidationResult.Success
    }

    private fun parseArgvJson(raw: String): List<String> {
        val element = runCatching { json.parseToJsonElement(raw) }
            .getOrElse { throw IllegalArgumentException("Invalid argvJson (must be a JSON array of strings): ${it.message}") }

        val array = element as? JsonArray
            ?: throw IllegalArgumentException("Invalid argvJson (must be a JSON array of strings)")

        return array.map { e -> jsonString(e) }
    }

    private fun jsonString(element: JsonElement): String {
        val primitive = element as? JsonPrimitive
            ?: throw IllegalArgumentException("Invalid argvJson entry (must be string): $element")
        if (!primitive.isString) {
            throw IllegalArgumentException("Invalid argvJson entry (must be string): $element")
        }
        return primitive.content
    }
}
