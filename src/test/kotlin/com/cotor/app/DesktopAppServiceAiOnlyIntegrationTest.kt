package com.cotor.app

import com.cotor.model.AgentResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.spyk
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files

class DesktopAppServiceAiOnlyIntegrationTest : FunSpec({
    test("company issue run creates durable run by default while only agent execution is mocked") {
        var service: DesktopAppService? = null
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

            service = harness.service
            val company = service!!.createCompany(
                name = "AI Only Integration Co",
                rootPath = harness.repositoryRoot.toString(),
                defaultBaseBranch = "master"
            )
            val goal = service!!.createGoal(
                companyId = company.id,
                title = "Validate infra-only issue flow",
                description = "Run a non-code issue through the full company runtime path.",
                autonomyEnabled = false,
                startRuntimeIfNeeded = false
            )
            val issue = service!!.createIssue(
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

            service!!.runIssue(issue.id)

            withTimeout(30_000) {
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
            service!!.verificationBundle(issue.id).outcome.status shouldBe com.cotor.verification.VerificationOutcomeStatus.PASS

            val durableRun = harness.durableRuntimeService.inspectRun(settledIssue.durableRunId!!)
            durableRun.shouldNotBeNull()
            durableRun.status.name shouldBe "COMPLETED"

            val projection = service!!.issueRuntimeProjection(issue.id)
            projection.issue.runtimeDisposition shouldBe "TERMINAL"
            projection.runtime.pendingIssueIds.contains(issue.id) shouldBe false
        } finally {
            service?.shutdown()
        }
    }

    test("canonical agent note prevents duplicate legacy stdout fallback replay") {
        System.setProperty("cotor.experimental.durableRuntimeV2", "true")
        var service: DesktopAppService? = null
        try {
            lateinit var serviceRef: DesktopAppService
            lateinit var issueRef: CompanyIssue
            lateinit var goalRef: CompanyGoal
            val harness = createDesktopAppServiceIntegrationHarness(
                agentResultFactory = {
                    runBlocking {
                        serviceRef.ingestAgentNote(
                            companyId = issueRef.companyId,
                            agentName = "opencode",
                            title = "Infra validation completed successfully.",
                            content = "Infra validation completed successfully.",
                            issueId = issueRef.id,
                            goalId = goalRef.id,
                            visibility = "goal"
                        )
                    }
                    AgentResult(
                        agentName = "opencode",
                        isSuccess = true,
                        output = "CONTEXT_NOTE: Infra validation completed successfully.",
                        error = null,
                        duration = 25,
                        metadata = emptyMap(),
                        processId = 7004
                    )
                }
            )

            service = harness.service
            serviceRef = service!!
            val company = service!!.createCompany(
                name = "Canonical Beats Legacy Co",
                rootPath = harness.repositoryRoot.toString(),
                defaultBaseBranch = "master"
            )
            val goal = service!!.createGoal(
                companyId = company.id,
                title = "Avoid duplicate communication replay",
                description = "Ensure canonical communication suppresses legacy stdout fallback duplication.",
                autonomyEnabled = false,
                startRuntimeIfNeeded = false
            )
            goalRef = goal
            val issue = service!!.createIssue(
                companyId = company.id,
                goalId = goal.id,
                title = "Canonical note should win",
                description = "Run a non-code issue with both canonical and legacy note signals.",
                kind = "infra"
            )
            issueRef = issue
            val seeded = harness.stateStore.load()
            harness.stateStore.save(
                seeded.copy(
                    issues = seeded.issues.map { existing ->
                        if (existing.id == issue.id) {
                            existing.copy(
                                acceptanceCriteria = listOf("Canonical note is stored once", "Legacy replay does not duplicate it"),
                                codeProducing = false
                            )
                        } else {
                            existing
                        }
                    }
                )
            )

            service!!.runIssueAndAwaitSettlement(issue.id, timeoutMs = 30_000)
            val currentIssue = harness.stateStore.load().issues.first { it.id == issue.id }
            currentIssue.status shouldBe IssueStatus.DONE
            currentIssue.durableRunId.shouldNotBeNull()

            val settledState = harness.stateStore.load()
            val notes = settledState.agentContextEntries.filter {
                it.companyId == company.id &&
                    it.issueId == issue.id &&
                    it.agentName == "opencode" &&
                    it.kind == "note" &&
                    it.content == "Infra validation completed successfully."
            }

            notes.size shouldBe 1
        } finally {
            service?.shutdown()
            System.clearProperty("cotor.experimental.durableRuntimeV2")
        }
    }

    test("code-producing execution enters review queue with real stores and mocked publish metadata") {
        System.setProperty("cotor.experimental.durableRuntimeV2", "true")
        try {
            lateinit var publishedBranchName: String
            lateinit var publishedWorktreePath: String
            val harness = createDesktopAppServiceIntegrationHarness(
                agentResultFactory = { metadata ->
                    val worktree = requireNotNull(metadata?.workingDirectory)
                    Files.writeString(worktree.resolve("feature.txt"), "generated by integration test\n")
                    AgentResult(
                        agentName = "opencode",
                        isSuccess = true,
                        output = "Implemented the requested code change and prepared it for review.",
                        error = null,
                        duration = 25,
                        metadata = emptyMap(),
                        processId = 7002
                    )
                },
                gitWorkspaceServiceTransform = { realService ->
                    spyk(realService).also { spyService ->
                        coEvery {
                            spyService.publishRun(any(), any(), any(), any(), any(), any())
                        } answers {
                            publishedWorktreePath = thirdArg<java.nio.file.Path>().toString()
                            publishedBranchName = arg<String>(3)
                            PublishMetadata(
                                commitSha = "abc123def456",
                                pushedBranch = publishedBranchName,
                                pullRequestNumber = 42,
                                pullRequestUrl = "https://github.com/example/cotor/pull/42",
                                pullRequestState = "OPEN",
                                mergeability = "CLEAN",
                                requestedReviewers = listOf("qa-user")
                            )
                        }
                    }
                }
            )

            val service = harness.service
            try {
                val company = service.createCompany(
                    name = "AI Only Review Queue Co",
                    rootPath = harness.repositoryRoot.toString(),
                    defaultBaseBranch = "master"
                )
                val goal = service.createGoal(
                    companyId = company.id,
                    title = "Ship a code change through review",
                    description = "Run a code-producing execution issue all the way into the QA review lane.",
                    autonomyEnabled = false,
                    startRuntimeIfNeeded = false
                )
                val issue = service.createIssue(
                    companyId = company.id,
                    goalId = goal.id,
                    title = "Implement reviewed change",
                    description = "Produce code that should open a review queue item.",
                    kind = "execution"
                )
                val seeded = harness.stateStore.load()
                harness.stateStore.save(
                    seeded.copy(
                        issues = seeded.issues.map { existing ->
                            if (existing.id == issue.id) {
                                existing.copy(
                                    acceptanceCriteria = listOf("A PR is opened", "QA review issue is created"),
                                    codeProducing = true,
                                    status = IssueStatus.DELEGATED
                                )
                            } else {
                                existing
                            }
                        }
                    )
                )

                service.runIssue(issue.id)

                withTimeout(30_000) {
                    while (true) {
                        val current = harness.stateStore.load()
                        val currentIssue = current.issues.first { it.id == issue.id }
                        val queueItem = current.reviewQueue.firstOrNull { it.issueId == issue.id }
                        if (
                            currentIssue.status == IssueStatus.IN_REVIEW &&
                            currentIssue.pullRequestNumber == 42 &&
                            queueItem != null &&
                            queueItem.status == ReviewQueueStatus.AWAITING_QA &&
                            queueItem.qaIssueId != null
                        ) {
                            break
                        }
                        delay(50)
                    }
                }

                val settled = harness.stateStore.load()
                val settledIssue = settled.issues.first { it.id == issue.id }
                val reviewQueueItem = settled.reviewQueue.first { it.issueId == issue.id }
                val qaIssue = settled.issues.first { it.id == reviewQueueItem.qaIssueId }

                settledIssue.status shouldBe IssueStatus.IN_REVIEW
                settledIssue.pullRequestNumber shouldBe 42
                settledIssue.pullRequestUrl shouldBe "https://github.com/example/cotor/pull/42"
                settledIssue.branchName shouldContain "codex/cotor/implement-reviewed-change"
                settledIssue.worktreePath shouldBe publishedWorktreePath
                settledIssue.durableRunId.shouldNotBeNull()

                reviewQueueItem.pullRequestNumber shouldBe 42
                reviewQueueItem.status shouldBe ReviewQueueStatus.AWAITING_QA
                reviewQueueItem.qaIssueId shouldBe qaIssue.id

                qaIssue.kind shouldBe "review"
                qaIssue.status shouldBe IssueStatus.PLANNED
                qaIssue.pullRequestNumber shouldBe 42
                qaIssue.pullRequestUrl shouldBe "https://github.com/example/cotor/pull/42"
            } finally {
                service.shutdown()
            }
        } finally {
            System.clearProperty("cotor.experimental.durableRuntimeV2")
        }
    }

    test("recreated service resumes a running company runtime from persisted state") {
        System.setProperty("cotor.experimental.durableRuntimeV2", "true")
        var initialService: DesktopAppService? = null
        var recreatedService: DesktopAppService? = null
        try {
            val initialHarness = createDesktopAppServiceIntegrationHarness(
                agentResultFactory = {
                    AgentResult(
                        agentName = "opencode",
                        isSuccess = true,
                        output = "Recovered autonomous work completed successfully.",
                        error = null,
                        duration = 20,
                        metadata = emptyMap(),
                        processId = 7003
                    )
                }
            )

            initialService = initialHarness.service
            val company = initialService!!.createCompany(
                name = "AI Only Restart Co",
                rootPath = initialHarness.repositoryRoot.toString(),
                defaultBaseBranch = "master"
            )
            val goal = initialService!!.createGoal(
                companyId = company.id,
                title = "Resume autonomous runtime after restart",
                description = "Persist a running runtime and ensure the recreated service resumes it.",
                autonomyEnabled = true,
                startRuntimeIfNeeded = false
            )
            val issue = initialService!!.createIssue(
                companyId = company.id,
                goalId = goal.id,
                title = "Recovered autonomous issue",
                description = "This delegated issue should resume after service recreation.",
                kind = "infra"
            )
            val seeded = initialHarness.stateStore.load()
            initialHarness.stateStore.save(
                seeded.copy(
                    issues = seeded.issues.map { existing ->
                        if (existing.id == issue.id) {
                            existing.copy(
                                codeProducing = false,
                                status = IssueStatus.DELEGATED
                            )
                        } else {
                            existing
                        }
                    },
                    companyRuntimes = seeded.companyRuntimes.map { runtime ->
                        if (runtime.companyId == company.id) {
                            runtime.copy(status = CompanyRuntimeStatus.RUNNING)
                        } else {
                            runtime
                        }
                    }
                )
            )
            initialService!!.shutdown()
            initialService = null

            val recreatedHarness = createDesktopAppServiceIntegrationHarness(
                agentResultFactory = {
                    AgentResult(
                        agentName = "opencode",
                        isSuccess = true,
                        output = "Recovered autonomous work completed successfully.",
                        error = null,
                        duration = 20,
                        metadata = emptyMap(),
                        processId = 7004
                    )
                },
                appHome = initialHarness.appHome,
                repositoryRoot = initialHarness.repositoryRoot
            )
            recreatedService = recreatedHarness.service

            recreatedService!!.companyDashboard(company.id)

            withTimeout(10_000) {
                while (true) {
                    val current = recreatedHarness.stateStore.load()
                    val currentIssue = current.issues.first { it.id == issue.id }
                    if (currentIssue.status == IssueStatus.DONE && currentIssue.durableRunId != null) {
                        break
                    }
                    delay(50)
                }
            }

            val settled = recreatedHarness.stateStore.load()
            val settledIssue = settled.issues.first { it.id == issue.id }
            val settledTasks = settled.tasks.filter { it.issueId == issue.id }
            val settledRuns = settled.runs.filter { run -> settledTasks.any { it.id == run.taskId } }
            settledIssue.status shouldBe IssueStatus.DONE
            settledIssue.durableRunId.shouldNotBeNull()
            settledTasks.size shouldBe 1
            settledTasks.single().status shouldBe DesktopTaskStatus.COMPLETED
            settledRuns.size shouldBe 1
            settledRuns.single().status shouldBe AgentRunStatus.COMPLETED
            settledIssue.durableRunId shouldBe settledRuns.single().id
            recreatedService!!.runtimeStatus(company.id).status shouldBe CompanyRuntimeStatus.RUNNING

            recreatedService!!.companyDashboard(company.id)

            val afterRetick = recreatedHarness.stateStore.load()
            afterRetick.tasks.count { it.issueId == issue.id } shouldBe 1
            afterRetick.runs.count { run -> afterRetick.tasks.any { it.issueId == issue.id && it.id == run.taskId } } shouldBe 1
            afterRetick.issues.first { it.id == issue.id }.status shouldBe IssueStatus.DONE
        } finally {
            recreatedService?.shutdown()
            initialService?.shutdown()
            System.clearProperty("cotor.experimental.durableRuntimeV2")
        }
    }
})
