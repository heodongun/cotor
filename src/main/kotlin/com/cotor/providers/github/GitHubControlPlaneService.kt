package com.cotor.providers.github

import com.cotor.knowledge.KnowledgeRecord
import com.cotor.knowledge.KnowledgeService
import com.cotor.provenance.ProvenanceService

class GitHubControlPlaneService(
    private val store: GitHubControlPlaneStore = GitHubControlPlaneStore(),
    private val provenanceService: ProvenanceService = ProvenanceService(),
    private val knowledgeService: KnowledgeService = KnowledgeService()
) {
    fun recordSnapshot(snapshot: PullRequestSnapshot, eventType: String, detail: String): PullRequestSnapshot {
        val current = store.load()
        val mergedPullRequests = current.pullRequests
            .filterNot { it.number == snapshot.number }
            .plus(snapshot)
            .sortedByDescending { it.updatedAt }
        val event = GitHubProviderEvent(
            type = eventType,
            pullRequestNumber = snapshot.number,
            companyId = snapshot.companyId,
            issueId = snapshot.issueId,
            runId = snapshot.runId,
            detail = detail
        )
        store.save(
            current.copy(
                pullRequests = mergedPullRequests,
                events = (current.events + event).takeLast(500),
                updatedAt = System.currentTimeMillis()
            )
        )
        provenanceService.recordPullRequestState(
            runId = snapshot.runId,
            pullRequestNumber = snapshot.number,
            url = snapshot.url,
            state = snapshot.state,
            checksSummary = snapshot.checksSummary
        )
        snapshot.issueId?.let { issueId ->
            knowledgeService.remember(
                KnowledgeRecord(
                    subjectType = "issue",
                    subjectId = issueId,
                    kind = "github-pr",
                    title = "GitHub PR ${snapshot.number}",
                    content = buildString {
                        append("state=")
                        append(snapshot.state ?: "unknown")
                        snapshot.mergeability?.let {
                            append(", mergeability=")
                            append(it)
                        }
                        snapshot.checksSummary?.takeIf { it.isNotBlank() }?.let {
                            append(", checks=")
                            append(it)
                        }
                    },
                    confidence = 0.85
                )
            )
        }
        return snapshot
    }

    fun inspectPullRequest(number: Int): PullRequestSnapshot? =
        store.load().pullRequests.firstOrNull { it.number == number }

    fun listPullRequests(companyId: String? = null): List<PullRequestSnapshot> =
        store.load().pullRequests
            .filter { companyId == null || it.companyId == companyId }
            .sortedByDescending { it.updatedAt }

    fun listEvents(companyId: String? = null): List<GitHubProviderEvent> =
        store.load().events
            .filter { companyId == null || it.companyId == companyId }
            .sortedByDescending { it.createdAt }
}
