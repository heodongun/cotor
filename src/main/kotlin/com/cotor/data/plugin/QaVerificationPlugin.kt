package com.cotor.data.plugin

import com.cotor.data.process.ProcessManager
import com.cotor.model.AgentMetadata
import com.cotor.model.AgentParameter
import com.cotor.model.AgentParameterSchema
import com.cotor.model.DataFormat
import com.cotor.model.ExecutionContext
import com.cotor.model.ParameterType
import com.cotor.model.PluginExecutionOutput
import com.cotor.model.ProcessExecutionException
import com.cotor.model.ValidationResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * Repository-aware QA verifier that runs the repo's test/check command.
 *
 * This keeps the "verified" workflow reusable across common stacks without forcing
 * each pipeline author to hardcode one project-specific shell command up front.
 */
class QaVerificationPlugin : AgentPlugin {
    override val metadata = AgentMetadata(
        name = "qa",
        version = "1.0.0",
        description = "Run repository test/verification commands with auto-detection or an explicit override",
        author = "Cotor Team",
        supportedFormats = listOf(DataFormat.TEXT, DataFormat.JSON)
    )

    override val parameterSchema = AgentParameterSchema(
        parameters = listOf(
            AgentParameter(
                name = "commandJson",
                type = ParameterType.STRING,
                required = false,
                description = "Optional JSON argv override, e.g. [\"./gradlew\",\"test\",\"--console=plain\"]"
            )
        )
    )

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(
        context: ExecutionContext,
        processManager: ProcessManager
    ): PluginExecutionOutput {
        val workingDirectory = context.workingDirectory?.toAbsolutePath()?.normalize()
            ?: Path.of(".").toAbsolutePath().normalize()
        val command = resolveCommand(workingDirectory, context.parameters["commandJson"])
            ?: throw IllegalArgumentException(
                "No supported QA verification command found in $workingDirectory. " +
                    "Set parameters.commandJson to override detection."
            )

        val result = processManager.executeProcess(
            command = command,
            input = null,
            environment = context.environment,
            timeout = context.timeout,
            workingDirectory = workingDirectory
        )

        if (!result.isSuccess) {
            throw ProcessExecutionException(
                message = "QA verification failed: ${command.joinToString(" ")}",
                exitCode = result.exitCode,
                stdout = result.stdout,
                stderr = result.stderr
            )
        }

        return PluginExecutionOutput(
            output = mergeOutput(result.stdout, result.stderr, command),
            processId = result.processId
        )
    }

    override fun validateInput(input: String?): ValidationResult = ValidationResult.Success

    private fun resolveCommand(workingDirectory: Path, commandJson: String?): List<String>? {
        commandJson?.takeIf { it.isNotBlank() }?.let { return parseArgvJson(it) }

        detectPackageScript(workingDirectory)?.let { return it }
        detectGradle(workingDirectory)?.let { return it }
        detectMaven(workingDirectory)?.let { return it }
        detectCargo(workingDirectory)?.let { return it }
        detectGo(workingDirectory)?.let { return it }
        detectPython(workingDirectory)?.let { return it }
        detectMake(workingDirectory)?.let { return it }

        return null
    }

    private fun detectPackageScript(workingDirectory: Path): List<String>? {
        val packageJson = workingDirectory.resolve("package.json")
        if (!packageJson.exists()) return null

        val scripts = parsePackageScripts(packageJson)
        val targetScript = listOf("verify", "check", "test").firstOrNull { scripts.contains(it) } ?: return null

        return when {
            workingDirectory.resolve("pnpm-lock.yaml").exists() -> listOf("pnpm", "run", targetScript)
            workingDirectory.resolve("yarn.lock").exists() -> listOf("yarn", targetScript)
            else -> listOf("npm", "run", targetScript)
        }
    }

    private fun detectGradle(workingDirectory: Path): List<String>? {
        return when {
            workingDirectory.resolve("gradlew").exists() -> listOf("./gradlew", "test", "--console=plain")
            workingDirectory.resolve("build.gradle.kts").exists() || workingDirectory.resolve("build.gradle").exists() ->
                listOf("gradle", "test", "--console=plain")
            else -> null
        }
    }

    private fun detectMaven(workingDirectory: Path): List<String>? {
        if (!workingDirectory.resolve("pom.xml").exists()) return null
        return when {
            workingDirectory.resolve("mvnw").exists() -> listOf("./mvnw", "test")
            else -> listOf("mvn", "test")
        }
    }

    private fun detectCargo(workingDirectory: Path): List<String>? {
        return if (workingDirectory.resolve("Cargo.toml").exists()) {
            listOf("cargo", "test", "--quiet")
        } else {
            null
        }
    }

    private fun detectGo(workingDirectory: Path): List<String>? {
        return if (workingDirectory.resolve("go.mod").exists()) {
            listOf("go", "test", "./...")
        } else {
            null
        }
    }

    private fun detectPython(workingDirectory: Path): List<String>? {
        val signals = listOf("pyproject.toml", "pytest.ini", "tox.ini", "requirements.txt", "requirements-dev.txt")
        return if (signals.any { workingDirectory.resolve(it).exists() }) {
            listOf("python3", "-m", "pytest")
        } else {
            null
        }
    }

    private fun detectMake(workingDirectory: Path): List<String>? {
        val makefile = listOf("GNUmakefile", "Makefile", "makefile")
            .map { workingDirectory.resolve(it) }
            .firstOrNull { it.exists() }
            ?: return null
        val content = runCatching { java.nio.file.Files.readString(makefile) }.getOrDefault("")
        return when {
            Regex("""(?m)^verify\s*:""").containsMatchIn(content) -> listOf("make", "verify")
            Regex("""(?m)^check\s*:""").containsMatchIn(content) -> listOf("make", "check")
            Regex("""(?m)^test\s*:""").containsMatchIn(content) -> listOf("make", "test")
            else -> null
        }
    }

    private fun parsePackageScripts(packageJson: Path): Set<String> {
        val root = runCatching { json.parseToJsonElement(java.nio.file.Files.readString(packageJson)) }
            .getOrElse { return emptySet() }
        val scripts = (root as? JsonObject)?.get("scripts") as? JsonObject ?: return emptySet()
        return scripts.keys
    }

    private fun parseArgvJson(raw: String): List<String> {
        val element = runCatching { json.parseToJsonElement(raw) }
            .getOrElse { throw IllegalArgumentException("Invalid commandJson (must be a JSON array of strings): ${it.message}") }
        val array = element as? JsonArray
            ?: throw IllegalArgumentException("Invalid commandJson (must be a JSON array of strings)")
        return array.map { jsonString(it) }
    }

    private fun jsonString(element: JsonElement): String {
        val primitive = element as? JsonPrimitive
            ?: throw IllegalArgumentException("Invalid commandJson entry (must be string): $element")
        if (!primitive.isString) {
            throw IllegalArgumentException("Invalid commandJson entry (must be string): $element")
        }
        return primitive.content
    }

    private fun mergeOutput(stdout: String, stderr: String, command: List<String>): String {
        val trimmedStdout = stdout.trim()
        val trimmedStderr = stderr.trim()
        return when {
            trimmedStdout.isNotBlank() && trimmedStderr.isNotBlank() -> "$trimmedStdout\n$trimmedStderr"
            trimmedStdout.isNotBlank() -> trimmedStdout
            trimmedStderr.isNotBlank() -> trimmedStderr
            else -> "QA verification passed: ${command.joinToString(" ")}"
        }
    }
}
