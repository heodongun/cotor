package com.cotor.verification

import com.cotor.app.CompanyIssue
import com.cotor.app.DesktopAppState
import com.cotor.app.ReviewQueueItem
import com.cotor.knowledge.KnowledgeService
import com.cotor.provenance.ProvenanceService

class VerificationBundleService(
    private val provenanceService: ProvenanceService = ProvenanceService(),
    private val knowledgeService: KnowledgeService = KnowledgeService()
) {
    fun buildForIssue(
        state: DesktopAppState,
        issue: CompanyIssue,
        queueItem: ReviewQueueItem? = null
    ): VerificationBundle {
        val effectiveQueue = queueItem ?: state.reviewQueue
            .filter { it.issueId == issue.id }
            .maxByOrNull { it.updatedAt }
        val evidenceSummary = listOfNotNull(
            issue.durableRunId?.let { runId ->
                provenanceService.bundleForRun(runId)
                    .nodes
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString(" | ") { node -> "${node.kind.name.lowercase()}:${node.label}" }
            },
            effectiveQueue?.pullRequestUrl?.let { "pr:$it" },
            effectiveQueue?.checksSummary?.takeIf { it.isNotBlank() }?.let { "checks:${it.trim()}" }
        ).joinToString("\n").ifBlank { null }
        val knowledgeSummary = knowledgeService.inspectIssue(issue.id)
            .take(4)
            .joinToString("\n") { record ->
                "${record.kind}: ${record.title} -> ${record.content}"
            }
            .ifBlank { null }

        val signals = buildList {
            add(
                VerificationSignal(
                    key = "acceptance-criteria",
                    status = if (issue.acceptanceCriteria.isNotEmpty()) VerificationSignalStatus.PASS else VerificationSignalStatus.UNKNOWN,
                    detail = if (issue.acceptanceCriteria.isNotEmpty()) {
                        "${issue.acceptanceCriteria.size} acceptance criteria attached."
                    } else {
                        "No explicit acceptance criteria attached."
                    }
                )
            )
            effectiveQueue?.checksSummary?.let { summary ->
                add(
                    VerificationSignal(
                        key = "github-checks",
                        status = when {
                            summary.contains("FAILURE", ignoreCase = true) || summary.contains("ERROR", ignoreCase = true) ->
                                VerificationSignalStatus.FAIL
                            summary.contains("SUCCESS", ignoreCase = true) ->
                                VerificationSignalStatus.PASS
                            else -> VerificationSignalStatus.UNKNOWN
                        },
                        detail = summary
                    )
                )
            }
            effectiveQueue?.mergeability?.let { mergeability ->
                add(
                    VerificationSignal(
                        key = "mergeability",
                        status = when {
                            mergeability.equals("DIRTY", ignoreCase = true) -> VerificationSignalStatus.FAIL
                            mergeability.equals("CLEAN", ignoreCase = true) -> VerificationSignalStatus.PASS
                            else -> VerificationSignalStatus.UNKNOWN
                        },
                        detail = mergeability
                    )
                )
            }
            issue.qaVerdict?.let { verdict ->
                add(
                    VerificationSignal(
                        key = "qa-verdict",
                        status = if (verdict.equals("PASS", ignoreCase = true)) VerificationSignalStatus.PASS else VerificationSignalStatus.FAIL,
                        detail = verdict
                    )
                )
            }
            issue.ceoVerdict?.let { verdict ->
                add(
                    VerificationSignal(
                        key = "ceo-verdict",
                        status = if (verdict.equals("APPROVE", ignoreCase = true)) VerificationSignalStatus.PASS else VerificationSignalStatus.FAIL,
                        detail = verdict
                    )
                )
            }
        }

        return VerificationBundle(
            issueId = issue.id,
            issueTitle = issue.title,
            acceptanceCriteria = issue.acceptanceCriteria,
            signals = signals,
            evidenceSummary = evidenceSummary,
            knowledgeSummary = knowledgeSummary
        )
    }
}
