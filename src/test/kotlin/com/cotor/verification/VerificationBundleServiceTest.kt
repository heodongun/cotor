package com.cotor.verification

import com.cotor.app.CompanyIssue
import com.cotor.app.DesktopAppState
import com.cotor.app.IssueStatus
import com.cotor.app.ReviewQueueItem
import com.cotor.app.ReviewQueueStatus
import com.cotor.knowledge.KnowledgeRecord
import com.cotor.knowledge.KnowledgeService
import com.cotor.knowledge.KnowledgeStore
import com.cotor.provenance.EvidenceNode
import com.cotor.provenance.EvidenceEdge
import com.cotor.provenance.EvidenceGraph
import com.cotor.provenance.EvidenceNodeKind
import com.cotor.provenance.ProvenanceService
import com.cotor.provenance.ProvenanceStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class VerificationBundleServiceTest : FunSpec({
    test("bundle includes acceptance criteria, checks, evidence, and knowledge") {
        val appHome = Files.createTempDirectory("verification-bundle-test")
        val provenanceStore = ProvenanceStore { appHome }
        provenanceStore.save(
            EvidenceGraph(
                nodes = listOf(
                    EvidenceNode(kind = EvidenceNodeKind.RUN, ref = "run:run-1", label = "run-1"),
                    EvidenceNode(kind = EvidenceNodeKind.FILE, ref = "file:src/App.kt", label = "src/App.kt")
                ),
                edges = listOf(
                    EvidenceEdge(fromRef = "run:run-1", toRef = "file:src/App.kt", relation = "touched")
                )
            )
        )
        val knowledgeStore = KnowledgeStore { appHome }
        val knowledgeService = KnowledgeService(knowledgeStore)
        knowledgeService.remember(
            KnowledgeRecord(
                subjectType = "issue",
                subjectId = "issue-1",
                kind = "qa-feedback",
                title = "Prior QA finding",
                content = "Watch for regressions in the app entrypoint."
            )
        )

        val service = VerificationBundleService(
            provenanceService = ProvenanceService(provenanceStore),
            knowledgeService = knowledgeService
        )

        val bundle = service.buildForIssue(
            state = DesktopAppState(),
            issue = CompanyIssue(
                id = "issue-1",
                goalId = "goal-1",
                workspaceId = "workspace-1",
                title = "Issue",
                description = "desc",
                status = IssueStatus.IN_REVIEW,
                durableRunId = "run-1",
                acceptanceCriteria = listOf("Pass CI", "Keep entrypoint stable"),
                createdAt = 1L,
                updatedAt = 1L
            ),
            queueItem = ReviewQueueItem(
                id = "review-1",
                issueId = "issue-1",
                runId = "run-1",
                status = ReviewQueueStatus.AWAITING_QA,
                checksSummary = "ci=COMPLETED/SUCCESS",
                mergeability = "CLEAN",
                createdAt = 1L,
                updatedAt = 1L
            )
        )

        bundle.contract.acceptanceCriteria.size shouldBe 2
        bundle.outcome.status shouldBe VerificationOutcomeStatus.PARTIAL
        bundle.outcome.passedSignals.any { it.key == "github-checks" && it.status == VerificationSignalStatus.PASS } shouldBe true
        bundle.outcome.passedSignals.any { it.key == "mergeability" && it.status == VerificationSignalStatus.PASS } shouldBe true
        bundle.evidenceSummary?.contains("file:src/App.kt") shouldBe true
        bundle.knowledgeSummary?.contains("Prior QA finding") shouldBe true
    }
})
