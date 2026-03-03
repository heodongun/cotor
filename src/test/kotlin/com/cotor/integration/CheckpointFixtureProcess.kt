package com.cotor.integration

import com.cotor.checkpoint.CheckpointManager
import com.cotor.checkpoint.StageCheckpoint
import java.time.Instant

/**
 * Separate JVM process used by integration tests to create a checkpoint file
 * and keep running until forcibly terminated.
 */
object CheckpointFixtureProcess {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size >= 2) { "Usage: CheckpointFixtureProcess <checkpointDir> <pipelineId>" }

        val checkpointDir = args[0]
        val pipelineId = args[1]
        val checkpointManager = CheckpointManager(checkpointDir)

        checkpointManager.saveCheckpoint(
            pipelineId = pipelineId,
            pipelineName = "checkpoint-resume-integration",
            completedStages = listOf(
                StageCheckpoint(
                    stageId = "stage-1",
                    agentName = "echo-agent",
                    output = "stage-1-output",
                    isSuccess = true,
                    duration = 10,
                    timestamp = Instant.now().toString()
                )
            ),
            cotorVersion = "test-version",
            gitCommit = "test-commit",
            os = System.getProperty("os.name"),
            jvm = System.getProperty("java.version")
        )

        println("CHECKPOINT_READY")
        System.out.flush()

        while (true) {
            Thread.sleep(1_000)
        }
    }
}
