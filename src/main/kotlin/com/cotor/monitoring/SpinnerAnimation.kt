package com.cotor.monitoring

import com.github.ajalt.mordant.rendering.TextColors.*
import com.github.ajalt.mordant.rendering.TextStyles.*
import com.github.ajalt.mordant.terminal.Terminal
import kotlinx.coroutines.*
import java.time.Duration
import java.time.Instant

/**
 * Spinner animation for long-running tasks
 */
class SpinnerAnimation(
    private val message: String,
    private val timeout: Long? = null,
    private val showElapsed: Boolean = true
) {
    private val terminal = Terminal()
    private val frames = listOf("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")
    private var currentFrame = 0
    private val startTime = Instant.now()
    private var job: Job? = null

    /**
     * Start the spinner animation
     */
    fun start() {
        job = CoroutineScope(Dispatchers.Default).launch {
            while (isActive) {
                render()
                delay(80)
                currentFrame = (currentFrame + 1) % frames.size
            }
        }
    }

    /**
     * Stop the spinner animation
     */
    fun stop(finalMessage: String? = null) {
        job?.cancel()
        clearLine()
        if (finalMessage != null) {
            terminal.println(finalMessage)
        }
    }

    /**
     * Render current frame
     */
    private fun render() {
        val elapsed = Duration.between(startTime, Instant.now())
        val frame = frames[currentFrame]

        val timeoutInfo = if (timeout != null) {
            val remaining = timeout - elapsed.toMillis()
            if (remaining > 0) {
                " (${formatDuration(Duration.ofMillis(remaining))} remaining)"
            } else {
                " (timeout exceeded)"
            }
        } else ""

        val elapsedInfo = if (showElapsed) {
            " [${formatDuration(elapsed)}]"
        } else ""

        clearLine()
        terminal.print("\r${cyan(frame)} $message$elapsedInfo${dim(timeoutInfo)}")
    }

    /**
     * Clear current line
     */
    private fun clearLine() {
        terminal.print("\r${" ".repeat(100)}\r")
    }

    /**
     * Format duration
     */
    private fun formatDuration(duration: Duration): String {
        val seconds = duration.seconds
        val minutes = seconds / 60
        val secs = seconds % 60

        return when {
            minutes > 0 -> "${minutes}m ${secs}s"
            else -> "${secs}s"
        }
    }
}

/**
 * Extension function for easy spinner usage
 */
suspend fun <T> withSpinner(
    message: String,
    timeout: Long? = null,
    block: suspend () -> T
): T {
    val spinner = SpinnerAnimation(message, timeout)
    spinner.start()

    return try {
        block()
    } finally {
        spinner.stop()
    }
}

/**
 * Dots animation for simpler visual feedback
 */
class DotsAnimation(private val message: String) {
    private val terminal = Terminal()
    private var dots = ""
    private val maxDots = 3
    private var job: Job? = null

    fun start() {
        job = CoroutineScope(Dispatchers.Default).launch {
            while (isActive) {
                clearLine()
                terminal.print("\r$message$dots")
                dots = if (dots.length >= maxDots) "" else "$dots."
                delay(500)
            }
        }
    }

    fun stop(finalMessage: String? = null) {
        job?.cancel()
        clearLine()
        if (finalMessage != null) {
            terminal.println(finalMessage)
        }
    }

    private fun clearLine() {
        terminal.print("\r${" ".repeat(100)}\r")
    }
}
