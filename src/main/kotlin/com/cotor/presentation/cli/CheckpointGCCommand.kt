package com.cotor.presentation.cli

import com.cotor.checkpoint.CheckpointConfig
import com.cotor.checkpoint.CheckpointManager
import com.cotor.data.config.FileConfigRepository
import com.cotor.data.config.JsonParser
import com.cotor.data.config.YamlParser
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import kotlinx.coroutines.runBlocking
import java.nio.file.Paths

class CheckpointGCCommand(
    private val checkpointManager: CheckpointManager
) : CliktCommand(
    name = "gc",
    help = "Garbage collect checkpoints based on retention policies."
) {
    private val maxCount by option("--max-count", help = "Maximum number of checkpoints to keep.").int()
    private val maxAgeDays by option("--max-age-days", help = "Maximum age of checkpoints in days.").int()
    private val configPath by option("--config", help = "Path to the configuration file.").path()

    override fun run() {
        val configRepository = FileConfigRepository(YamlParser(), JsonParser())

        val cotorConfig = runBlocking {
            val path = configPath ?: Paths.get("cotor.yaml")
            if (path.toFile().exists()) {
                configRepository.loadConfig(path)
            } else {
                null
            }
        }

        val retentionConfig = cotorConfig?.checkpointRetention

        val finalMaxCount = maxCount ?: retentionConfig?.maxCount
        val finalMaxAgeDays = maxAgeDays ?: retentionConfig?.maxAgeDays

        val config = CheckpointConfig(
            maxCount = finalMaxCount,
            maxAgeDays = finalMaxAgeDays,
        )

        val deletedCount = checkpointManager.gc(config)
        echo("$deletedCount checkpoints deleted.")
    }
}
