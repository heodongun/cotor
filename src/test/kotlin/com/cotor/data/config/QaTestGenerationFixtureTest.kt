package com.cotor.data.config

/**
 * File overview for QaTestGenerationFixtureTest.
 *
 * This file belongs to the test suite that documents expected behavior and protects against regressions.
 * It groups declarations around qa test generation fixture test so readers can find the owning runtime area quickly.
 * Read here first when tracing behavior that flows through this part of the codebase.
 */

import com.cotor.model.ExecutionMode
import com.cotor.model.StageType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.io.path.Path

class QaTestGenerationFixtureTest : FunSpec({
    val repo = FileConfigRepository(YamlParser(), JsonParser()) { Files.createTempDirectory("fake-home") }

    test("loads the QA test generation fixture with decision and loop stages") {
        val config = runBlocking {
            repo.loadConfig(Path("test/qa-test-generation/qa-test-generation.yaml"))
        }

        val pipeline = config.pipelines.single { it.name == "qa-test-generation" }

        pipeline.executionMode shouldBe ExecutionMode.SEQUENTIAL
        pipeline.stages.map { it.id } shouldContainExactly listOf(
            "analyze-target",
            "generate-tests",
            "review-tests",
            "quality-gate",
            "improve-brief",
            "qa-loop",
            "handoff-notes"
        )

        val generateStage = pipeline.stages.single { it.id == "generate-tests" }
        generateStage.validation?.requiresCodeBlock shouldBe true
        generateStage.validation?.requiredKeywords.orEmpty() shouldContain "@Test"
        generateStage.validation?.requiredKeywords.orEmpty() shouldContain "MockK"
        generateStage.validation?.requiredKeywords.orEmpty() shouldContain "should"

        pipeline.stages.single { it.id == "quality-gate" }.type shouldBe StageType.DECISION
        pipeline.stages.single { it.id == "qa-loop" }.type shouldBe StageType.LOOP

        config.security.allowedExecutables shouldContain "codex"
        config.security.allowedExecutables shouldContain "gemini"
    }
})
