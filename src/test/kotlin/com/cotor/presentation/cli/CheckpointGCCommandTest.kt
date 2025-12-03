package com.cotor.presentation.cli

import com.cotor.checkpoint.CheckpointManager
import com.cotor.checkpoint.PipelineCheckpoint
import com.cotor.checkpoint.StageCheckpoint
import com.github.ajalt.clikt.testing.test
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit

class CheckpointGCCommandTest : FunSpec({
    val testDir = ".cotor/test_checkpoints"
    lateinit var checkpointManager: CheckpointManager

    beforeTest {
        File(testDir).mkdirs()
        checkpointManager = CheckpointManager(testDir)
    }

    afterTest {
        File(testDir).deleteRecursively()
    }

    fun createDummyCheckpoint(pipelineId: String, daysAgo: Long) {
        val checkpoint = PipelineCheckpoint(
            pipelineId = pipelineId,
            pipelineName = "test-pipeline",
            createdAt = Instant.now().minus(daysAgo, ChronoUnit.DAYS).toString(),
            cotorVersion = "1.0.0",
            gitCommit = "test-commit",
            os = "test-os",
            jvm = "test-jvm",
            completedStages = listOf(
                StageCheckpoint(
                    stageId = "test-stage",
                    agentName = "test-agent",
                    output = "test-output",
                    isSuccess = true,
                    duration = 100,
                    timestamp = Instant.now().toString()
                )
            )
        )
        val checkpointFile = File(testDir, "$pipelineId.json")
        checkpointFile.writeText(Json.encodeToString(checkpoint))
    }

    test("gc command should delete old checkpoints based on maxCount") {
        createDummyCheckpoint("p1", 1)
        createDummyCheckpoint("p2", 2)
        createDummyCheckpoint("p3", 3)
        createDummyCheckpoint("p4", 4)
        createDummyCheckpoint("p5", 5)

        val command = CheckpointGCCommand(checkpointManager)
        command.test("--max-count 3 --config dummy.yaml")

        File(testDir).listFiles()?.size shouldBe 3
    }

    test("gc command should delete old checkpoints based on maxAgeDays") {
        createDummyCheckpoint("p1", 1)
        createDummyCheckpoint("p2", 5)
        createDummyCheckpoint("p3", 10)
        createDummyCheckpoint("p4", 15)

        val command = CheckpointGCCommand(checkpointManager)
        command.test("--max-age-days 7 --config dummy.yaml")


        File(testDir).listFiles()?.size shouldBe 2
    }

    test("gc command should combine maxCount and maxAgeDays") {
        createDummyCheckpoint("p1", 1)
        createDummyCheckpoint("p2", 2)
        createDummyCheckpoint("p3", 8)
        createDummyCheckpoint("p4", 9)
        createDummyCheckpoint("p5", 10)

        val command = CheckpointGCCommand(checkpointManager)
        command.test("--max-count 3 --max-age-days 7 --config dummy.yaml")

        File(testDir).listFiles()?.size shouldBe 2
    }
})
