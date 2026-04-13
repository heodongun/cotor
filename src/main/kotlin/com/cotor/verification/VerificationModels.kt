package com.cotor.verification

import kotlinx.serialization.Serializable

@Serializable
enum class VerificationSignalStatus {
    PASS,
    FAIL,
    UNKNOWN
}

@Serializable
data class VerificationSignal(
    val key: String,
    val status: VerificationSignalStatus,
    val detail: String
)

@Serializable
data class VerificationBundle(
    val issueId: String,
    val issueTitle: String,
    val acceptanceCriteria: List<String> = emptyList(),
    val signals: List<VerificationSignal> = emptyList(),
    val evidenceSummary: String? = null,
    val knowledgeSummary: String? = null
)
