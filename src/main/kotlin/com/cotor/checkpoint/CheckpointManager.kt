package com.cotor.checkpoint

import com.cotor.model.AgentResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.io.File
import java.time.Instant

/**
 * Manages pipeline checkpoints for resume functionality
 */
class CheckpointManager(
    private val checkpointDir: String = ".cotor/checkpoints"
) {
    private val json = Json { prettyPrint = true }

    init {
        File(checkpointDir).mkdirs()
    }

    /**
     * Save checkpoint for a pipeline execution
     */
    fun saveCheckpoint(
        pipelineId: String,
        pipelineName: String,
        completedStages: List<StageCheckpoint>
    ): String {
        val checkpoint = PipelineCheckpoint(
            pipelineId = pipelineId,
            pipelineName = pipelineName,
            timestamp = Instant.now().toString(),
            completedStages = completedStages
        )

        val checkpointFile = File(checkpointDir, "$pipelineId.json")
        checkpointFile.writeText(json.encodeToString(checkpoint))

        return checkpointFile.absolutePath
    }

    /**
     * Load checkpoint for a pipeline
     */
    fun loadCheckpoint(pipelineId: String): PipelineCheckpoint? {
        val checkpointFile = File(checkpointDir, "$pipelineId.json")
        if (!checkpointFile.exists()) {
            return null
        }

        return try {
            json.decodeFromString<PipelineCheckpoint>(checkpointFile.readText())
        } catch (e: Exception) {
            null
        }
    }

    /**
     * List all available checkpoints
     */
    fun listCheckpoints(): List<CheckpointSummary> {
        val dir = File(checkpointDir)
        if (!dir.exists()) return emptyList()

        return dir.listFiles()
            ?.filter { it.extension == "json" }
            ?.mapNotNull { file ->
                try {
                    val checkpoint = json.decodeFromString<PipelineCheckpoint>(file.readText())
                    CheckpointSummary(
                        pipelineId = checkpoint.pipelineId,
                        pipelineName = checkpoint.pipelineName,
                        timestamp = checkpoint.timestamp,
                        completedStages = checkpoint.completedStages.size,
                        file = file.absolutePath
                    )
                } catch (e: Exception) {
                    null
                }
            }
            ?.sortedByDescending { it.timestamp }
            ?: emptyList()
    }

    /**
     * Delete checkpoint
     */
    fun deleteCheckpoint(pipelineId: String): Boolean {
        val checkpointFile = File(checkpointDir, "$pipelineId.json")
        return checkpointFile.delete()
    }


    /**
     * Get all checkpoints
     */
    fun getCheckpoints(): List<PipelineCheckpoint> {
        val dir = File(checkpointDir)
        if (!dir.exists()) return emptyList()

        return dir.listFiles()
            ?.filter { it.extension == "json" }
            ?.mapNotNull { file ->
                try {
                    json.decodeFromString<PipelineCheckpoint>(file.readText())
                } catch (e: Exception) {
                    null
                }
            }
            ?.sortedByDescending { it.timestamp }
            ?: emptyList()
    }

    /**
     * Garbage collect checkpoints
     */
    fun gc(config: CheckpointConfig): Int {
        val checkpoints = getCheckpoints()
        val toDelete = mutableSetOf<String>()

        config.maxCount?.let { maxCount ->
            if (checkpoints.size > maxCount) {
                checkpoints.drop(maxCount).forEach {
                    toDelete.add(it.pipelineId)
                }
            }
        }

        config.maxAgeDays?.let { maxAgeDays ->
            val cutoffTime = Instant.now().minusSeconds((maxAgeDays * 24 * 60 * 60).toLong())
            checkpoints.forEach {
                val checkpointTime = Instant.parse(it.timestamp)
                if (checkpointTime.isBefore(cutoffTime)) {
                    toDelete.add(it.pipelineId)
                }
            }
        }

        var deletedCount = 0
        toDelete.forEach {
            if (deleteCheckpoint(it)) {
                deletedCount++
            }
        }
        return deletedCount
    }
}

/**
 * Pipeline checkpoint data
 */
@Serializable
data class PipelineCheckpoint(
    val pipelineId: String,
    val pipelineName: String,
    val timestamp: String,
    val completedStages: List<StageCheckpoint>
)

/**
 * Stage checkpoint data
 */
@Serializable
data class StageCheckpoint(
    val stageId: String,
    val agentName: String,
    val output: String?,
    val isSuccess: Boolean,
    val duration: Long,
    val timestamp: String
)

/**
 * Checkpoint summary for listing
 */
data class CheckpointSummary(
    val pipelineId: String,
    val pipelineName: String,
    val timestamp: String,
    val completedStages: Int,
    val file: String
)

/**
 * Convert AgentResult to StageCheckpoint
 */
fun AgentResult.toCheckpoint(stageId: String): StageCheckpoint {
    return StageCheckpoint(
        stageId = stageId,
        agentName = agentName,
        output = output,
        isSuccess = isSuccess,
        duration = duration,
        timestamp = Instant.now().toString()
    )
}
