package com.cotor.data.plugin

import com.cotor.data.process.ProcessManager
import com.cotor.model.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.io.path.exists

/**
 * QA verification plugin that runs a repository test/verification command.
 *
 * Parameter behavior:
 * - argvJson (optional): explicit command override as JSON argv array.
 * - stdin (optional): pass stage input to stdin when true.
 *
 * If argvJson is not provided, a command is auto-detected from common repo markers.
 */
class QaVerificationPlugin : AgentPlugin {
    override val metadata = AgentMetadata(
        name = "qa-verification",
        version = "1.0.0",
        description = "Run repository test/verification command with autodetect + override",
        author = "Cotor Team",
        supportedFormats = listOf(DataFormat.TEXT, DataFormat.JSON)
    )

    override val parameterSchema = AgentParameterSchema(
        parameters = listOf(
            AgentParameter(
                name = "argvJson",
                type = ParameterType.STRING,
                required = false,
                description = "Optional explicit override command as JSON argv array."
            ),
            AgentParameter(
                name = "stdin",
                type = ParameterType.BOOLEAN,
                required = false,
                description = "If true, send stage input to stdin.",
                defaultValue = "false"
            )
        )
    )

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(context: ExecutionContext, processManager: ProcessManager): PluginExecutionOutput {
        val argv = context.parameters["argvJson"]
            ?.takeIf { it.isNotBlank() }
            ?.let { parseArgvJson(it) }
            ?: detectCommand(context)

        val stdin = if (context.parameters["stdin"]?.lowercase() == "true") context.input else null

        val result = processManager.executeProcess(
            command = argv,
            input = stdin,
            environment = context.environment,
            timeout = context.timeout,
            workingDirectory = context.workingDirectory ?: context.repoRoot,
            onStart = context.onProcessStarted
        )

        if (!result.isSuccess) {
            throw ProcessExecutionException(
                message = "QA verification command failed",
                exitCode = result.exitCode,
                stdout = result.stdout,
                stderr = result.stderr
            )
        }

        return PluginExecutionOutput(
            output = result.stdout,
            processId = result.processId
        )
    }

    private fun detectCommand(context: ExecutionContext): List<String> {
        val root = context.workingDirectory ?: context.repoRoot

        if (root != null) {
            if (root.resolve("gradlew").exists()) return listOf("./gradlew", "test", "--console=plain")
            if (root.resolve("mvnw").exists()) return listOf("./mvnw", "test", "-q")
            if (root.resolve("pom.xml").exists()) return listOf("mvn", "test", "-q")
            if (root.resolve("package.json").exists()) return listOf("npm", "test", "--", "--watch=false")
            if (root.resolve("pytest.ini").exists() || root.resolve("pyproject.toml").exists()) return listOf("pytest", "-q")
            if (root.resolve("go.mod").exists()) return listOf("go", "test", "./...")
            if (root.resolve("Cargo.toml").exists()) return listOf("cargo", "test", "--quiet")
        }

        return listOf("./gradlew", "test", "--console=plain")
    }

    private fun parseArgvJson(raw: String): List<String> {
        val element = runCatching { json.parseToJsonElement(raw) }
            .getOrElse { throw IllegalArgumentException("Invalid argvJson (must be a JSON array of strings): ${it.message}") }

        val array = element as? JsonArray
            ?: throw IllegalArgumentException("Invalid argvJson (must be a JSON array of strings)")

        val argv = array.map { jsonString(it) }
        if (argv.isEmpty()) throw IllegalArgumentException("argvJson must contain at least one element")
        return argv
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
