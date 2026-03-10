package com.cotor.data.process

import com.cotor.model.ProcessResult
import kotlinx.coroutines.*
import org.slf4j.Logger
import java.nio.file.Path

/**
 * Interface for managing external process execution
 */
interface ProcessManager {
    /**
     * Execute external process with coroutines
     * @param command Command and arguments to execute
     * @param input Optional input data to send to stdin
     * @param environment Environment variables
     * @param timeout Timeout in milliseconds
     * @param workingDirectory Optional working directory for the child process
     * @return ProcessResult containing exit code and output
     */
    suspend fun executeProcess(
        command: List<String>,
        input: String?,
        environment: Map<String, String>,
        timeout: Long,
        workingDirectory: Path? = null
    ): ProcessResult
}

/**
 * Coroutine-based process manager implementation
 */
class CoroutineProcessManager(
    private val logger: Logger
) : ProcessManager {

    override suspend fun executeProcess(
        command: List<String>,
        input: String?,
        environment: Map<String, String>,
        timeout: Long,
        workingDirectory: Path?
    ): ProcessResult = withContext(Dispatchers.IO) {
        val processBuilder = ProcessBuilder(command)
            .redirectErrorStream(false)

        workingDirectory?.let {
            // Every desktop agent run points this at its isolated worktree so file writes
            // from different agents never collide in the same checkout.
            processBuilder.directory(it.toFile())
        }

        // Set environment variables
        processBuilder.environment().putAll(environment)

        logger.debug("Starting process: ${command.joinToString(" ")}")
        val process = processBuilder.start()

        try {
            withTimeout(timeout) {
                coroutineScope {
                    // stdin/stdout/stderr are handled concurrently so we do not deadlock
                    // when a child process produces a lot of output while also waiting for input.
                    val stdinJob = launch {
                        if (input != null) {
                            process.outputStream.bufferedWriter().use { writer ->
                                writer.write(input)
                                writer.flush()
                            }
                        }
                        process.outputStream.close()
                    }

                    // Read from stdout
                    val stdoutDeferred = async {
                        process.inputStream.bufferedReader().use { reader ->
                            reader.readText()
                        }
                    }

                    // Read from stderr
                    val stderrDeferred = async {
                        process.errorStream.bufferedReader().use { reader ->
                            reader.readText()
                        }
                    }

                    // Wait for process to complete
                    val exitCodeDeferred = async {
                        process.waitFor()
                    }

                    // Ensure stdin is fully written before awaiting process completion so
                    // CLIs that block on EOF can actually start processing.
                    stdinJob.join()
                    val stdout = stdoutDeferred.await()
                    val stderr = stderrDeferred.await()
                    val exitCode = exitCodeDeferred.await()

                    logger.debug("Process completed with exit code: $exitCode")

                    ProcessResult(
                        exitCode = exitCode,
                        stdout = stdout,
                        stderr = stderr,
                        isSuccess = exitCode == 0,
                        processId = process.pid()
                    )
                }
            }
        } catch (e: TimeoutCancellationException) {
            logger.warn("Process timeout, destroying process")
            // Force-kill on timeout because some developer tools spawn interactive shells
            // that ignore polite termination and would otherwise leak in the background.
            process.destroyForcibly()
            throw e
        } catch (e: Exception) {
            logger.error("Process execution failed", e)
            process.destroyForcibly()
            throw e
        }
    }
}
