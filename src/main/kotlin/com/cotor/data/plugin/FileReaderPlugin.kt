package com.cotor.data.plugin

/**
 * File overview for FileReaderPlugin.
 *
 * This file belongs to the plugin integration layer. It provides a process-free,
 * read-only utility agent for examples and local fan-out pipelines.
 */

import com.cotor.data.process.ProcessManager
import com.cotor.model.AgentMetadata
import com.cotor.model.AgentParameter
import com.cotor.model.AgentParameterSchema
import com.cotor.model.DataFormat
import com.cotor.model.ExecutionContext
import com.cotor.model.ParameterType
import com.cotor.model.PluginExecutionOutput
import com.cotor.model.ValidationResult
import java.nio.charset.MalformedInputException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Reads a text file relative to the execution root.
 *
 * The plugin deliberately refuses paths outside the resolved root and large files so
 * examples can be runnable without shelling out or exposing arbitrary filesystem reads.
 */
class FileReaderPlugin : AgentPlugin {
    override val metadata = AgentMetadata(
        name = "file-reader",
        version = "1.0.0",
        description = "Read a small text file relative to the pipeline working directory",
        author = "Cotor Team",
        supportedFormats = listOf(DataFormat.TEXT)
    )

    override val parameterSchema = AgentParameterSchema(
        parameters = listOf(
            AgentParameter(
                name = "maxBytes",
                type = ParameterType.STRING,
                required = false,
                description = "Maximum file size to read, in bytes",
                defaultValue = DEFAULT_MAX_BYTES.toString()
            )
        )
    )

    override suspend fun execute(
        context: ExecutionContext,
        processManager: ProcessManager
    ): PluginExecutionOutput {
        val rawPath = context.input?.trim().orEmpty()
        val root = executionRoot(context)
        val candidate = resolveInsideRoot(root, rawPath)
        val maxBytes = parseMaxBytes(context.parameters["maxBytes"])

        if (!Files.isRegularFile(candidate)) {
            throw IllegalArgumentException("File not found or not a regular file: ${root.relativize(candidate)}")
        }
        val rootRealPath = root.toRealPath()
        val candidateRealPath = candidate.toRealPath()
        if (!candidateRealPath.startsWith(rootRealPath)) {
            throw IllegalArgumentException("File path escapes the execution root: $rawPath")
        }

        val size = Files.size(candidate)
        if (size > maxBytes) {
            throw IllegalArgumentException("File is too large to read safely: $size bytes > $maxBytes bytes")
        }

        val content = try {
            Files.readString(candidate, StandardCharsets.UTF_8)
        } catch (e: MalformedInputException) {
            throw IllegalArgumentException("File is not valid UTF-8 text: ${root.relativize(candidate)}", e)
        }

        return PluginExecutionOutput(
            output = "FILE: ${root.relativize(candidate)}\n$content"
        )
    }

    override fun validateInput(input: String?): ValidationResult {
        if (input.isNullOrBlank()) {
            return ValidationResult.Failure(listOf("Input file path is required"))
        }
        if (input.indexOf('\u0000') >= 0) {
            return ValidationResult.Failure(listOf("Input file path contains a NUL byte"))
        }
        return ValidationResult.Success
    }

    private fun executionRoot(context: ExecutionContext): Path =
        (context.workingDirectory ?: context.repoRoot ?: Path.of(""))
            .toAbsolutePath()
            .normalize()

    private fun resolveInsideRoot(root: Path, rawPath: String): Path {
        val inputPath = Path.of(rawPath)
        val candidate = if (inputPath.isAbsolute) {
            inputPath.toAbsolutePath().normalize()
        } else {
            root.resolve(inputPath).normalize()
        }

        if (!candidate.startsWith(root) || Files.isSymbolicLink(candidate) && !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalArgumentException("File path escapes the execution root: $rawPath")
        }
        return candidate
    }

    private fun parseMaxBytes(raw: String?): Long {
        val value = raw?.toLongOrNull() ?: DEFAULT_MAX_BYTES
        require(value in 1..MAX_ALLOWED_BYTES) {
            "maxBytes must be between 1 and $MAX_ALLOWED_BYTES"
        }
        return value
    }

    private companion object {
        const val DEFAULT_MAX_BYTES = 64 * 1024L
        const val MAX_ALLOWED_BYTES = 1024 * 1024L
    }
}
