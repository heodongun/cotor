package com.cotor.providers.github

import com.cotor.knowledge.KnowledgeService
import com.cotor.knowledge.KnowledgeStore
import com.cotor.provenance.ProvenanceService
import com.cotor.provenance.ProvenanceStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class GitHubControlPlaneServiceTest : FunSpec({
    test("recordSnapshot upserts PR state and keeps one current snapshot per PR") {
        val appHome = Files.createTempDirectory("github-control-plane-test")
        val service = GitHubControlPlaneService(
            store = GitHubControlPlaneStore { appHome },
            provenanceService = ProvenanceService(ProvenanceStore { appHome }),
            knowledgeService = KnowledgeService(KnowledgeStore { appHome })
        )

        service.recordSnapshot(
            snapshot = PullRequestSnapshot(
                number = 12,
                url = "https://example.test/pr/12",
                state = "OPEN",
                companyId = "company-1",
                issueId = "issue-1",
                runId = "run-1"
            ),
            eventType = "sync",
            detail = "first"
        )
        service.recordSnapshot(
            snapshot = PullRequestSnapshot(
                number = 12,
                url = "https://example.test/pr/12",
                state = "MERGED",
                companyId = "company-1",
                issueId = "issue-1",
                runId = "run-1"
            ),
            eventType = "merge",
            detail = "second"
        )

        service.listPullRequests("company-1") shouldHaveSize 1
        service.inspectPullRequest(12)?.state shouldBe "MERGED"
        service.listEvents("company-1") shouldHaveSize 2
    }
})
