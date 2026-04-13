package com.cotor.app

import com.cotor.model.AgentResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

class DesktopAppServiceAiOnlyIntegrationTest : FunSpec({
    test("company issue run completes with real stores and git worktree while only agent execution is mocked") {
        System.setProperty("cotor.experimental.durableRuntimeV2", "true")
        try {
            val harness = createDesktopAppServiceIntegrationHarness(
                agentResultFactory = {
                    AgentResult(
                        agentName = "opencode",
                        isSuccess = true,
                        output = "CONTEXT_NOTE: Infra validation completed successfully.",
                        error = null,
                        duration = 25,
                        metadata = emptyMap(),
                        processId = 7001
                    )
                }
            )

            val service = harness.service
            val company = service.createCompany(
                name = "AI Only Integration Co",
                rootPath = harness.repositoryRoot.toString(),
                defaultBaseBranch = "master"
            )
            val goal = service.createGoal(
                companyId = company.id,
                title = "Validate infra-only issue flow",
                description = "Run a non-code issue through the full company runtime path.",
                autonomyEnabled = false,
                startRuntimeIfNeeded = false
            )
            val issue = service.createIssue(
                companyId = company.id,
                goalId = goal.id,
                title = "Infra runtime validation",
                description = "Validate runtime orchestration without publishing.",
                kind = "infra"
            )
            val seeded = harness.stateStore.load()
            harness.stateStore.save(
                seeded.copy(
                    issues = seeded.issues.map { existing ->
                        if (existing.id == issue.id) {
                            existing.copy(
                                acceptanceCriteria = listOf("Validation command completes", "Evidence is recorded"),
                                codeProducing = false
                            )
                        } else {
                            existing
                        }
                    }
                )
            )

            service.runIssue(issue.id)

            withTimeout(10_000) {
                while (true) {
                    val currentIssue = harness.stateStore.load().issues.first { it.id == issue.id }
                    if (currentIssue.status == IssueStatus.DONE && currentIssue.durableRunId != null) {
                        break
                    }
                    delay(50)
                }
            }

            val settledState = harness.stateStore.load()
            val settledIssue = settledState.issues.first { it.id == issue.id }
            settledIssue.status shouldBe IssueStatus.DONE
            settledIssue.durableRunId.shouldNotBeNull()
            service.verificationBundle(issue.id).outcome.status shouldBe com.cotor.verification.VerificationOutcomeStatus.PASS

            val durableRun = harness.durableRuntimeService.inspectRun(settledIssue.durableRunId!!)
            durableRun.shouldNotBeNull()
            durableRun.status.name shouldBe "COMPLETED"

            val projection = service.issueRuntimeProjection(issue.id)
            projection.issue.runtimeDisposition shouldBe "TERMINAL"
            projection.runtime.pendingIssueIds.contains(issue.id) shouldBe false

            service.shutdown()
        } finally {
            System.clearProperty("cotor.experimental.durableRuntimeV2")
        }
    }
})
