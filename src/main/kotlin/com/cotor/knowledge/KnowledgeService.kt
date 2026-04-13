package com.cotor.knowledge

import com.cotor.app.CompanyIssue
import com.cotor.app.DesktopAppState
import com.cotor.app.ReviewQueueItem
import com.cotor.provenance.EvidenceNodeKind
import com.cotor.provenance.EvidenceReference

class KnowledgeService(
    private val store: KnowledgeStore = KnowledgeStore()
) {
    fun remember(record: KnowledgeRecord) {
        val current = store.load()
        val updated = current.copy(
            records = mergeRecords(current.records, listOf(record)),
            updatedAt = System.currentTimeMillis()
        )
        store.save(updated)
    }

    fun inspectIssue(issueId: String): List<KnowledgeRecord> {
        val now = System.currentTimeMillis()
        return store.load().records
            .filter { it.subjectType == "issue" && it.subjectId == issueId }
            .map { record ->
                val expired = record.ttlMs?.let { now - record.freshness > it } ?: false
                if (expired && record.conflictStatus == KnowledgeConflictStatus.CLEAR) {
                    record.copy(conflictStatus = KnowledgeConflictStatus.STALE)
                } else {
                    record
                }
            }
            .sortedByDescending { it.createdAt }
    }

    fun retrieveForExecution(issueId: String, companyId: String? = null): List<KnowledgeRecord> =
        rankedRecords(
            primary = { it.subjectType == "issue" && it.subjectId == issueId },
            secondary = { record ->
                companyId != null &&
                    record.evidenceRefs.any { ref -> ref.ref == "company:$companyId" || ref.ref == "issue:$issueId" }
            }
        )

    fun retrieveForReview(issueId: String): List<KnowledgeRecord> =
        rankedRecords(
            primary = { it.subjectType == "issue" && it.subjectId == issueId && it.kind.contains("qa", ignoreCase = true) },
            secondary = { it.subjectType == "issue" && it.subjectId == issueId }
        )

    fun retrieveForApproval(issueId: String): List<KnowledgeRecord> =
        rankedRecords(
            primary = { it.subjectType == "issue" && it.subjectId == issueId && (it.kind.contains("merge", ignoreCase = true) || it.kind.contains("ceo", ignoreCase = true)) },
            secondary = { it.subjectType == "issue" && it.subjectId == issueId }
        )

    fun synchronizeFromState(state: DesktopAppState) {
        val harvested = buildList {
            state.reviewQueue.forEach { item ->
                addAll(recordsForReviewQueueItem(item))
            }
            state.issues.forEach { issue ->
                addAll(recordsForIssue(issue))
            }
        }
        val current = store.load()
        store.save(
            current.copy(
                records = mergeRecords(current.records, harvested),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    private fun recordsForIssue(issue: CompanyIssue): List<KnowledgeRecord> = buildList {
        issue.qaFeedback?.takeIf { it.isNotBlank() }?.let { feedback ->
            add(
                KnowledgeRecord(
                    subjectType = "issue",
                    subjectId = issue.id,
                    kind = "qa-feedback",
                    title = "QA feedback for ${issue.title}",
                    content = feedback,
                    evidenceRefs = listOf(EvidenceReference("issue:${issue.id}", EvidenceNodeKind.ISSUE)),
                    confidence = 0.8
                )
            )
        }
        issue.ceoFeedback?.takeIf { it.isNotBlank() }?.let { feedback ->
            add(
                KnowledgeRecord(
                    subjectType = "issue",
                    subjectId = issue.id,
                    kind = "ceo-feedback",
                    title = "CEO feedback for ${issue.title}",
                    content = feedback,
                    evidenceRefs = listOf(EvidenceReference("issue:${issue.id}", EvidenceNodeKind.ISSUE)),
                    confidence = 0.9
                )
            )
        }
        if (!issue.mergeResult.isNullOrBlank()) {
            add(
                KnowledgeRecord(
                    subjectType = "issue",
                    subjectId = issue.id,
                    kind = "merge-outcome",
                    title = "Merge outcome for ${issue.title}",
                    content = issue.mergeResult,
                    evidenceRefs = listOf(EvidenceReference("issue:${issue.id}", EvidenceNodeKind.ISSUE)),
                    confidence = 0.9
                )
            )
        }
    }

    private fun recordsForReviewQueueItem(item: ReviewQueueItem): List<KnowledgeRecord> = buildList {
        val issueRef = EvidenceReference("issue:${item.issueId}", EvidenceNodeKind.ISSUE)
        item.checksSummary?.takeIf { it.isNotBlank() }?.let { checks ->
            add(
                KnowledgeRecord(
                    subjectType = "issue",
                    subjectId = item.issueId,
                    kind = "ci-summary",
                    title = "CI summary for PR ${item.pullRequestNumber ?: item.id}",
                    content = checks,
                    evidenceRefs = listOf(issueRef) + listOfNotNull(
                        item.pullRequestNumber?.let { EvidenceReference("pr:$it", EvidenceNodeKind.PR) }
                    ),
                    confidence = 0.7,
                    ttlMs = 6L * 60L * 60L * 1000L
                )
            )
        }
        item.mergeability?.takeIf { it.isNotBlank() }?.let { mergeability ->
            add(
                KnowledgeRecord(
                    subjectType = "issue",
                    subjectId = item.issueId,
                    kind = "mergeability",
                    title = "Mergeability for PR ${item.pullRequestNumber ?: item.id}",
                    content = mergeability,
                    evidenceRefs = listOf(issueRef) + listOfNotNull(
                        item.pullRequestNumber?.let { EvidenceReference("pr:$it", EvidenceNodeKind.PR) }
                    ),
                    confidence = 0.8,
                    ttlMs = 2L * 60L * 60L * 1000L
                )
            )
        }
    }

    private fun mergeRecords(existing: List<KnowledgeRecord>, incoming: List<KnowledgeRecord>): List<KnowledgeRecord> {
        val merged = linkedMapOf<String, KnowledgeRecord>()
        (existing + incoming).forEach { record ->
            val key = "${record.subjectType}|${record.subjectId}|${record.kind}|${record.title}"
            val previous = merged[key]
            merged[key] = when {
                previous == null -> record
                previous.content == record.content -> record.copy(conflictStatus = previous.conflictStatus)
                else -> record.copy(conflictStatus = KnowledgeConflictStatus.CONFLICTING)
            }
        }
        return merged.values.sortedByDescending { it.createdAt }
    }

    private fun rankedRecords(
        primary: (KnowledgeRecord) -> Boolean,
        secondary: (KnowledgeRecord) -> Boolean
    ): List<KnowledgeRecord> {
        val now = System.currentTimeMillis()
        return store.load().records
            .map { record ->
                val expired = record.ttlMs?.let { now - record.freshness > it } ?: false
                if (expired && record.conflictStatus == KnowledgeConflictStatus.CLEAR) {
                    record.copy(conflictStatus = KnowledgeConflictStatus.STALE)
                } else {
                    record
                }
            }
            .sortedWith(
                compareByDescending<KnowledgeRecord> { primary(it) }
                    .thenByDescending { secondary(it) }
                    .thenByDescending { it.confidence }
                    .thenByDescending { it.createdAt }
            )
            .take(3)
    }
}
