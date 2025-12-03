package com.cotor.checkpoint

import com.cotor.model.AgentResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.serialization.json.Json

class CheckpointManagerTest : FunSpec({
    val checkpointDir = ".cotor/test-checkpoints"
    lateinit var checkpointManager: CheckpointManager

    beforeTest {
        checkpointManager = CheckpointManager(checkpointDir)
        File(checkpointDir).mkdirs()
    }

    afterTest {
        File(checkpointDir).deleteRecursively()
    }

    test("save and load checkpoint with metadata") {
        val pipelineId = "test-pipeline-1"
        val stageCheckpoint = AgentResult("test-agent", true, "output", null, 100, emptyMap()).toCheckpoint("stage-1")

        checkpointManager.saveCheckpoint(
            pipelineId = pipelineId,
            pipelineName = "Test Pipeline",
            completedStages = listOf(stageCheckpoint),
            cotorVersion = "1.0.0",
            gitCommit = "abcdef",
            os = "Test OS",
            jvm = "11"
        )

        val loadedCheckpoint = checkpointManager.loadCheckpoint(pipelineId)
        loadedCheckpoint shouldNotBe null
        loadedCheckpoint?.pipelineId shouldBe pipelineId
        loadedCheckpoint?.cotorVersion shouldBe "1.0.0"
        loadedCheckpoint?.gitCommit shouldBe "abcdef"
        loadedCheckpoint?.os shouldBe "Test OS"
        loadedCheckpoint?.jvm shouldBe "11"
        loadedCheckpoint?.completedStages?.size shouldBe 1
    }

    test("list checkpoints with metadata") {
        val stageCheckpoint = AgentResult("test-agent", true, "output", null, 100, emptyMap()).toCheckpoint("stage-1")
        checkpointManager.saveCheckpoint("p1", "Pipeline 1", listOf(stageCheckpoint), "1.0.0", "abc", "os1", "jvm1")
        checkpointManager.saveCheckpoint("p2", "Pipeline 2", listOf(stageCheckpoint), "1.1.0", "def", "os2", "jvm2")

        val checkpoints = checkpointManager.listCheckpoints()
        checkpoints shouldHaveSize 2

        val cp1 = checkpoints.find { it.pipelineId == "p1" }
        cp1 shouldNotBe null
        cp1?.cotorVersion shouldBe "1.0.0"
        cp1?.gitCommit shouldBe "abc"

        val cp2 = checkpoints.find { it.pipelineId == "p2" }
        cp2 shouldNotBe null
        cp2?.cotorVersion shouldBe "1.1.0"
        cp2?.gitCommit shouldBe "def"
    }

    test("gc deletes checkpoints based on maxCount") {
        for (i in 1..5) {
            checkpointManager.saveCheckpoint(
                pipelineId = "pipeline-$i",
                pipelineName = "test-pipeline",
                completedStages = emptyList(),
                cotorVersion = "1.0.0",
                gitCommit = "abc",
                os = "os",
                jvm = "jvm"
            )
            Thread.sleep(10) // ensure different timestamps
        }

        val deletedCount = checkpointManager.gc(CheckpointConfig(maxCount = 2))

        deletedCount shouldBe 3
        checkpointManager.getCheckpoints() shouldHaveSize 2
        val remaining = checkpointManager.getCheckpoints().map { it.pipelineId }
        remaining.shouldContain("pipeline-5")
        remaining.shouldContain("pipeline-4")
    }

    test("gc deletes checkpoints based on maxAgeDays") {
        checkpointManager.saveCheckpoint(
            pipelineId = "pipeline-1",
            pipelineName = "test-pipeline",
            completedStages = emptyList(),
            cotorVersion = "1.0.0",
            gitCommit = "abc",
            os = "os",
            jvm = "jvm"
        )
        val oldCheckpoint = PipelineCheckpoint(
            pipelineId = "pipeline-old",
            pipelineName = "test-pipeline",
            createdAt = Instant.now().minus(10, ChronoUnit.DAYS).toString(),
            cotorVersion = "1.0.0",
            gitCommit = "abc",
            os = "os",
            jvm = "jvm",
            completedStages = emptyList()
        )
        File(checkpointDir, "pipeline-old.json").writeText(Json.encodeToString(PipelineCheckpoint.serializer(), oldCheckpoint))

        val deletedCount = checkpointManager.gc(CheckpointConfig(maxAgeDays = 7))

        deletedCount shouldBe 1
        checkpointManager.getCheckpoints() shouldHaveSize 1
        checkpointManager.getCheckpoints().first().pipelineId shouldBe "pipeline-1"
    }

    test("gc deletes using both maxCount and maxAgeDays") {
        for (i in 1..3) {
            checkpointManager.saveCheckpoint(
                pipelineId = "pipeline-$i",
                pipelineName = "test-pipeline",
                completedStages = emptyList(),
                cotorVersion = "1.0.0",
                gitCommit = "abc",
                os = "os",
                jvm = "jvm"
            )
            Thread.sleep(10)
        }
        for (i in 4..6) {
            val oldCheckpoint = PipelineCheckpoint(
                pipelineId = "pipeline-$i",
                pipelineName = "test-pipeline",
                createdAt = Instant.now().minus(10, ChronoUnit.DAYS).toString(),
                cotorVersion = "1.0.0",
                gitCommit = "abc",
                os = "os",
                jvm = "jvm",
                completedStages = emptyList()
            )
            File(checkpointDir, "pipeline-$i.json").writeText(Json.encodeToString(PipelineCheckpoint.serializer(), oldCheckpoint))
            Thread.sleep(10)
        }

        val deletedCount = checkpointManager.gc(CheckpointConfig(maxCount = 2, maxAgeDays = 7))

        deletedCount shouldBe 4
        checkpointManager.getCheckpoints() shouldHaveSize 2
        val remaining = checkpointManager.getCheckpoints().map { it.pipelineId }
        remaining.shouldContain("pipeline-3")
        remaining.shouldContain("pipeline-2")
    }

    test("gc does nothing when no policies are specified") {
        for (i in 1..5) {
            checkpointManager.saveCheckpoint(
                pipelineId = "pipeline-$i",
                pipelineName = "test-pipeline",
                completedStages = emptyList(),
                cotorVersion = "1.0.0",
                gitCommit = "abc",
                os = "os",
                jvm = "jvm"
            )
        }

        val deletedCount = checkpointManager.gc(CheckpointConfig())

        deletedCount shouldBe 0
        checkpointManager.getCheckpoints() shouldHaveSize 5
    }
})
