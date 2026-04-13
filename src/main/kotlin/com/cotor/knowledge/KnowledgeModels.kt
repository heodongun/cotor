package com.cotor.knowledge

import com.cotor.provenance.EvidenceReference
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class KnowledgeConflictStatus {
    CLEAR,
    STALE,
    CONFLICTING
}

@Serializable
data class KnowledgeRecord(
    val id: String = UUID.randomUUID().toString(),
    val subjectType: String,
    val subjectId: String,
    val kind: String,
    val title: String,
    val content: String,
    val evidenceRefs: List<EvidenceReference> = emptyList(),
    val confidence: Double = 0.5,
    val freshness: Long = System.currentTimeMillis(),
    val ttlMs: Long? = null,
    val conflictStatus: KnowledgeConflictStatus = KnowledgeConflictStatus.CLEAR,
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
data class KnowledgeSnapshot(
    val records: List<KnowledgeRecord> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis()
)

