package com.cotor.verification

import com.cotor.app.CompanyIssue
import com.cotor.app.DesktopAppState
import com.cotor.app.ReviewQueueItem
import com.cotor.knowledge.KnowledgeService
import com.cotor.provenance.ProvenanceService

class VerificationBundleService(
    private val provenanceService: ProvenanceService = ProvenanceService(),
    private val knowledgeService: KnowledgeService = KnowledgeService(),
    private val store: VerificationStore = VerificationStore()
) {
    fun buildForIssue(
        state: DesktopAppState,
        issue: CompanyIssue,
        queueItem: ReviewQueueItem? = null
    ): VerificationBundle {
        val effectiveQueue = queueItem ?: state.reviewQueue
            .filter { it.issueId == issue.id }
            .maxByOrNull { it.updatedAt }
        val contract = buildContract(issue, effectiveQueue)
        val evidenceRefs = buildArtifactRefs(issue, effectiveQueue)
        val observations = buildObservations(issue, effectiveQueue)
        val outcome = evaluate(contract, observations, evidenceRefs)

        return bundleFromEvaluation(issue, contract, observations, outcome, evidenceRefs)
    }

    fun persistForIssue(
        state: DesktopAppState,
        issue: CompanyIssue,
        queueItem: ReviewQueueItem? = null
    ): VerificationBundle {
        val effectiveQueue = queueItem ?: state.reviewQueue
            .filter { it.issueId == issue.id }
            .maxByOrNull { it.updatedAt }
        val contract = buildContract(issue, effectiveQueue)
        val evidenceRefs = buildArtifactRefs(issue, effectiveQueue)
        val observations = buildObservations(issue, effectiveQueue)
        val outcome = evaluate(contract, observations, evidenceRefs)
        store.saveOutcome(outcome)
        observations.forEach { observation -> store.appendObservation(issue.id, observation) }

        return bundleFromEvaluation(issue, contract, observations, outcome, evidenceRefs)
    }

    private fun bundleFromEvaluation(
        issue: CompanyIssue,
        contract: VerificationContract,
        observations: List<VerificationObservation>,
        outcome: VerificationOutcome,
        evidenceRefs: List<VerificationArtifactRef>
    ): VerificationBundle {
        val evidenceSummary = evidenceRefs.takeIf { it.isNotEmpty() }
            ?.joinToString(" | ") { ref -> "${ref.kind}:${ref.label}" }
        val knowledgeSummary = when {
            issue.kind.equals("review", ignoreCase = true) -> knowledgeService.retrieveForReview(issue.id)
            issue.kind.equals("approval", ignoreCase = true) -> knowledgeService.retrieveForApproval(issue.id)
            else -> knowledgeService.retrieveForExecution(issue.id, issue.companyId)
        }
            .joinToString("\n") { record -> "${record.kind}: ${record.title} -> ${record.content}" }
            .ifBlank { null }

        return VerificationBundle(
            issueId = issue.id,
            issueTitle = issue.title,
            contract = contract,
            observations = observations,
            outcome = outcome,
            evidenceSummary = evidenceSummary,
            knowledgeSummary = knowledgeSummary
        )
    }

    fun loadOutcome(issueId: String): VerificationOutcome? = store.loadOutcome(issueId)

    private fun buildContract(
        issue: CompanyIssue,
        queueItem: ReviewQueueItem?
    ): VerificationContract =
        VerificationContract(
            issueId = issue.id,
            requiredChecks = parseChecks(queueItem?.checksSummary),
            acceptanceCriteria = issue.acceptanceCriteria,
            requiresQa = issue.kind.equals("review", ignoreCase = true) ||
                queueItem?.qaIssueId != null ||
                queueItem?.status?.name in setOf("AWAITING_QA", "FAILED_CHECKS"),
            requiresCeoApproval = issue.kind.equals("approval", ignoreCase = true) ||
                queueItem?.approvalIssueId != null ||
                queueItem?.status?.name in setOf("READY_FOR_CEO", "READY_TO_MERGE"),
            requiresCleanMergeability = queueItem?.pullRequestUrl != null || queueItem?.pullRequestNumber != null
        )

    private fun buildObservations(
        issue: CompanyIssue,
        queueItem: ReviewQueueItem?
    ): List<VerificationObservation> = buildList {
        add(
            VerificationObservation(
                source = "issue",
                signal = VerificationSignal(
                    key = "acceptance-criteria",
                    status = if (issue.acceptanceCriteria.isNotEmpty()) VerificationSignalStatus.PASS else VerificationSignalStatus.UNKNOWN,
                    detail = if (issue.acceptanceCriteria.isNotEmpty()) {
                        "${issue.acceptanceCriteria.size} acceptance criteria attached."
                    } else {
                        "No explicit acceptance criteria attached."
                    }
                )
            )
        )
        queueItem?.checksSummary?.let { summary ->
            add(
                VerificationObservation(
                    source = "github",
                    signal = VerificationSignal(
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
            )
        }
        queueItem?.mergeability?.let { mergeability ->
            add(
                VerificationObservation(
                    source = "github",
                    signal = VerificationSignal(
                        key = "mergeability",
                        status = when {
                            mergeability.equals("DIRTY", ignoreCase = true) -> VerificationSignalStatus.FAIL
                            mergeability.equals("CLEAN", ignoreCase = true) -> VerificationSignalStatus.PASS
                            else -> VerificationSignalStatus.UNKNOWN
                        },
                        detail = mergeability
                    )
                )
            )
        }
        issue.qaVerdict?.let { verdict ->
            add(
                VerificationObservation(
                    source = "qa",
                    signal = VerificationSignal(
                        key = "qa-verdict",
                        status = if (verdict.equals("PASS", ignoreCase = true)) VerificationSignalStatus.PASS else VerificationSignalStatus.FAIL,
                        detail = verdict
                    )
                )
            )
        }
        issue.ceoVerdict?.let { verdict ->
            add(
                VerificationObservation(
                    source = "ceo",
                    signal = VerificationSignal(
                        key = "ceo-verdict",
                        status = if (verdict.equals("APPROVE", ignoreCase = true)) VerificationSignalStatus.PASS else VerificationSignalStatus.FAIL,
                        detail = verdict
                    )
                )
            )
        }
    }

    private fun evaluate(
        contract: VerificationContract,
        observations: List<VerificationObservation>,
        artifactRefs: List<VerificationArtifactRef>
    ): VerificationOutcome {
        val signals = observations.map { it.signal }
        val failingSignals = signals.filter { it.status == VerificationSignalStatus.FAIL }
        val passedSignals = signals.filter { it.status == VerificationSignalStatus.PASS }
        val status = when {
            failingSignals.isNotEmpty() -> VerificationOutcomeStatus.FAIL
            contract.requiresCleanMergeability &&
                signals.none { it.key == "mergeability" && it.status == VerificationSignalStatus.PASS } ->
                VerificationOutcomeStatus.BLOCKED
            contract.requiresQa &&
                signals.none { it.key == "qa-verdict" && it.status == VerificationSignalStatus.PASS } ->
                VerificationOutcomeStatus.PARTIAL
            contract.requiresCeoApproval &&
                signals.none { it.key == "ceo-verdict" && it.status == VerificationSignalStatus.PASS } ->
                VerificationOutcomeStatus.PARTIAL
            contract.acceptanceCriteria.isEmpty() &&
                passedSignals.none { it.key == "qa-verdict" || it.key == "github-checks" } ->
                VerificationOutcomeStatus.UNKNOWN
            passedSignals.isNotEmpty() -> VerificationOutcomeStatus.PASS
            else -> VerificationOutcomeStatus.UNKNOWN
        }
        val summary = when (status) {
            VerificationOutcomeStatus.FAIL -> "Verification failed on ${failingSignals.joinToString { it.key }}."
            VerificationOutcomeStatus.BLOCKED -> "Verification is blocked because mergeability is not yet clean."
            VerificationOutcomeStatus.PARTIAL -> "Verification is partially satisfied but still waiting for required review or approval."
            VerificationOutcomeStatus.PASS -> "Verification passed with the currently available evidence."
            VerificationOutcomeStatus.UNKNOWN -> "Verification could not reach a strong conclusion from the available evidence."
        }
        return VerificationOutcome(
            issueId = contract.issueId,
            status = status,
            summary = summary,
            failingSignals = failingSignals,
            passedSignals = passedSignals,
            artifactRefs = artifactRefs
        )
    }

    private fun buildArtifactRefs(issue: CompanyIssue, queueItem: ReviewQueueItem?): List<VerificationArtifactRef> = buildList {
        issue.durableRunId?.let { runId ->
            provenanceService.bundleForRun(runId).nodes.forEach { node ->
                add(
                    VerificationArtifactRef(
                        kind = node.kind.name.lowercase(),
                        ref = node.ref,
                        label = node.label
                    )
                )
            }
        }
        queueItem?.pullRequestUrl?.let { url ->
            add(VerificationArtifactRef(kind = "pr", ref = url, label = url))
        }
    }

    private fun parseChecks(summary: String?): List<String> {
        if (summary.isNullOrBlank()) return emptyList()
        return summary.split(';')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { token -> token.substringBefore('=').trim() }
    }
}
