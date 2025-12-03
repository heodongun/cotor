package com.cotor.checkpoint

import com.cotor.model.AgentResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.File
import java.time.Instant

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
        checkpoints.size shouldBe 2

        val cp1 = checkpoints.find { it.pipelineId == "p1" }
        cp1 shouldNotBe null
        cp1?.cotorVersion shouldBe "1.0.0"
        cp1?.gitCommit shouldBe "abc"

        val cp2 = checkpoints.find { it.pipelineId == "p2" }
        cp2 shouldNotBe null
        cp2?.cotorVersion shouldBe "1.1.0"
        cp2?.gitCommit shouldBe "def"
    }
})
