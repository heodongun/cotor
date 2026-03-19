package com.cotor.presentation.cli

/**
 * File overview for CheckpointGCCommand.
 *
 * This file belongs to the CLI presentation layer for interactive and command-driven flows.
 * It groups declarations around checkpoint g c command so readers can find the owning runtime area quickly.
 * Read here first when tracing behavior that flows through this part of the codebase.
 */


import com.cotor.checkpoint.CheckpointConfig
import com.cotor.checkpoint.CheckpointManager
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int

class CheckpointGCCommand : CliktCommand(
    name = "gc",
    help = "Garbage collect checkpoints based on retention policies."
) {
    private val maxCount by option("--max-count", help = "Maximum number of checkpoints to keep.").int()
    private val maxAgeDays by option("--max-age-days", help = "Maximum age of checkpoints in days.").int()

    override fun run() {
        val checkpointManager = CheckpointManager()
        val config = CheckpointConfig(
            maxCount = maxCount,
            maxAgeDays = maxAgeDays,
        )

        val deletedCount = checkpointManager.gc(config)
        echo("$deletedCount checkpoints deleted.")
    }
}
