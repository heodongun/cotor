package com.cotor.verification

import kotlinx.serialization.Serializable

@Serializable
enum class VerificationSignalStatus {
    PASS,
    FAIL,
    UNKNOWN
}

@Serializable
enum class VerificationOutcomeStatus {
    PASS,
    FAIL,
    PARTIAL,
    BLOCKED,
    UNKNOWN
}

@Serializable
data class VerificationSignal(
    val key: String,
    val status: VerificationSignalStatus,
    val detail: String
)

@Serializable
data class VerificationArtifactRef(
    val kind: String,
    val ref: String,
    val label: String
)

@Serializable
data class VerificationContract(
    val issueId: String,
    val requiredChecks: List<String> = emptyList(),
    val acceptanceCriteria: List<String> = emptyList(),
    val requiresQa: Boolean = false,
    val requiresCeoApproval: Boolean = false,
    val requiresCleanMergeability: Boolean = false
)

@Serializable
data class VerificationObservation(
    val source: String,
    val signal: VerificationSignal,
    val observedAt: Long = System.currentTimeMillis()
)

@Serializable
data class VerificationOutcome(
    val issueId: String,
    val status: VerificationOutcomeStatus,
    val summary: String,
    val failingSignals: List<VerificationSignal> = emptyList(),
    val passedSignals: List<VerificationSignal> = emptyList(),
    val artifactRefs: List<VerificationArtifactRef> = emptyList(),
    val verifiedAt: Long = System.currentTimeMillis()
)

@Serializable
data class VerificationBundle(
    val issueId: String,
    val issueTitle: String,
    val contract: VerificationContract,
    val observations: List<VerificationObservation> = emptyList(),
    val outcome: VerificationOutcome,
    val evidenceSummary: String? = null,
    val knowledgeSummary: String? = null
)
