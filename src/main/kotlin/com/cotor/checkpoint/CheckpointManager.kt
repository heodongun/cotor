package com.cotor.checkpoint

import com.cotor.model.AgentResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonNames
import java.io.File
import java.time.Instant

/**
 * Manages pipeline checkpoints for resume functionality
 */
class CheckpointManager(
    private val checkpointDir: String = ".cotor/checkpoints"
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    init {
        File(checkpointDir).mkdirs()
    }

    /**
     * Save checkpoint for a pipeline execution
     */
    fun saveCheckpoint(
        pipelineId: String,
        pipelineName: String,
        completedStages: List<StageCheckpoint>,
        cotorVersion: String,
        gitCommit: String,
        os: String,
        jvm: String
    ): String {
        val checkpoint = PipelineCheckpoint(
            pipelineId = pipelineId,
            pipelineName = pipelineName,
            createdAt = Instant.now().toString(),
            cotorVersion = cotorVersion,
            gitCommit = gitCommit,
            os = os,
            jvm = jvm,
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
                        createdAt = checkpoint.createdAt,
                        cotorVersion = checkpoint.cotorVersion,
                        gitCommit = checkpoint.gitCommit,
                        os = checkpoint.os,
                        jvm = checkpoint.jvm,
                        completedStages = checkpoint.completedStages.size,
                        file = file.absolutePath
                    )
                } catch (e: Exception) {
                    null
                }
            }
            ?.sortedByDescending { it.createdAt }
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
     * Clean old checkpoints (older than specified days)
     */
    fun cleanOldCheckpoints(daysToKeep: Int = 7): Int {
        val dir = File(checkpointDir)
        if (!dir.exists()) return 0

        val cutoffTime = Instant.now().minusSeconds((daysToKeep * 24 * 60 * 60).toLong())
        var deletedCount = 0

        dir.listFiles()
            ?.filter { it.extension == "json" }
            ?.forEach { file ->
                try {
                    val checkpoint = json.decodeFromString<PipelineCheckpoint>(file.readText())
                    val checkpointTime = Instant.parse(checkpoint.createdAt)
                    if (checkpointTime.isBefore(cutoffTime)) {
                        if (file.delete()) {
                            deletedCount++
                        }
                    }
                } catch (e: Exception) {
                    // Skip invalid checkpoints
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
    @JsonNames("timestamp")
    val createdAt: String,
    val cotorVersion: String = "unknown",
    val gitCommit: String = "unknown",
    val os: String = "unknown",
    val jvm: String = "unknown",
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
    val createdAt: String,
    val cotorVersion: String = "unknown",
    val gitCommit: String = "unknown",
    val os: String = "unknown",
    val jvm: String = "unknown",
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
