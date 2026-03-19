package com.cotor.integration

/**
 * File overview for CheckpointFixtureProcess.
 *
 * This file belongs to the test suite that documents expected behavior and protects against regressions.
 * It groups declarations around checkpoint fixture process so readers can find the owning runtime area quickly.
 * Read here first when tracing behavior that flows through this part of the codebase.
 */


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
