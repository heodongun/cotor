package com.cotor.checkpoint

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit

class CheckpointManagerTest {

    private lateinit var checkpointDir: File
    private lateinit var checkpointManager: CheckpointManager

    @BeforeEach
    fun setup() {
        checkpointDir = File.createTempFile("cotor-test-", "")
        checkpointDir.delete()
        checkpointDir.mkdir()
        checkpointManager = CheckpointManager(checkpointDir.absolutePath)
    }

    @AfterEach
    fun teardown() {
        checkpointDir.deleteRecursively()
    }

    @Test
    fun `gc should delete checkpoints based on maxCount`() {
        // Given
        for (i in 1..5) {
            checkpointManager.saveCheckpoint("pipeline-$i", "test-pipeline", emptyList())
            Thread.sleep(10) // ensure different timestamps
        }

        // When
        val deletedCount = checkpointManager.gc(CheckpointConfig(maxCount = 2))

        // Then
        assertEquals(3, deletedCount)
        assertEquals(2, checkpointManager.getCheckpoints().size)
        val remaining = checkpointManager.getCheckpoints().map { it.pipelineId }
        assert(remaining.contains("pipeline-5"))
        assert(remaining.contains("pipeline-4"))
    }

    @Test
    fun `gc should delete checkpoints based on maxAgeDays`() {
        // Given
        checkpointManager.saveCheckpoint("pipeline-1", "test-pipeline", emptyList())
        val oldCheckpoint = PipelineCheckpoint(
            pipelineId = "pipeline-old",
            pipelineName = "test-pipeline",
            timestamp = Instant.now().minus(10, ChronoUnit.DAYS).toString(),
            completedStages = emptyList()
        )
        val oldFile = File(checkpointDir, "pipeline-old.json")
        oldFile.writeText(kotlinx.serialization.json.Json.encodeToString(PipelineCheckpoint.serializer(), oldCheckpoint))


        // When
        val deletedCount = checkpointManager.gc(CheckpointConfig(maxAgeDays = 7))

        // Then
        assertEquals(1, deletedCount)
        assertEquals(1, checkpointManager.getCheckpoints().size)
        assertEquals("pipeline-1", checkpointManager.getCheckpoints().first().pipelineId)
    }

    @Test
    fun `gc should delete checkpoints based on both maxCount and maxAgeDays`() {
        // Given
        // 3 new checkpoints
        for (i in 1..3) {
            checkpointManager.saveCheckpoint("pipeline-$i", "test-pipeline", emptyList())
            Thread.sleep(10)
        }
        // 3 old checkpoints
        for (i in 4..6) {
            val oldCheckpoint = PipelineCheckpoint(
                pipelineId = "pipeline-$i",
                pipelineName = "test-pipeline",
                timestamp = Instant.now().minus(10, ChronoUnit.DAYS).toString(),
                completedStages = emptyList()
            )
            val oldFile = File(checkpointDir, "pipeline-$i.json")
            oldFile.writeText(kotlinx.serialization.json.Json.encodeToString(PipelineCheckpoint.serializer(), oldCheckpoint))
            Thread.sleep(10)
        }

        // When
        val deletedCount = checkpointManager.gc(CheckpointConfig(maxCount = 2, maxAgeDays = 7))

        // Then
        // 3 old ones are deleted by age.
        // from the 3 new ones, the oldest one is deleted by count.
        assertEquals(4, deletedCount)
        assertEquals(2, checkpointManager.getCheckpoints().size)
        val remaining = checkpointManager.getCheckpoints().map { it.pipelineId }
        assert(remaining.contains("pipeline-3"))
        assert(remaining.contains("pipeline-2"))
    }

    @Test
    fun `gc should not delete anything if no policies are specified`() {
        // Given
        for (i in 1..5) {
            checkpointManager.saveCheckpoint("pipeline-$i", "test-pipeline", emptyList())
        }

        // When
        val deletedCount = checkpointManager.gc(CheckpointConfig())

        // Then
        assertEquals(0, deletedCount)
        assertEquals(5, checkpointManager.getCheckpoints().size)
    }
}
