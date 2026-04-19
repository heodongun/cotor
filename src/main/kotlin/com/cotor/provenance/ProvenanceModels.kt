package com.cotor.provenance

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class EvidenceNodeKind {
    COMPANY,
    GOAL,
    ISSUE,
    RUN,
    CHECKPOINT,
    ACTION,
    ARTIFACT,
    BRANCH,
    PR,
    CI,
    FILE
}

@Serializable
data class EvidenceNode(
    val id: String = UUID.randomUUID().toString(),
    val kind: EvidenceNodeKind,
    val ref: String,
    val label: String,
    val metadata: Map<String, String> = emptyMap(),
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
data class EvidenceEdge(
    val fromRef: String,
    val toRef: String,
    val relation: String,
    val metadata: Map<String, String> = emptyMap(),
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
data class EvidenceReference(
    val ref: String,
    val kind: EvidenceNodeKind
)

@Serializable
data class EvidenceBundle(
    val query: String,
    val nodes: List<EvidenceNode> = emptyList(),
    val edges: List<EvidenceEdge> = emptyList()
)

@Serializable
data class EvidenceGraph(
    val nodes: List<EvidenceNode> = emptyList(),
    val edges: List<EvidenceEdge> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis()
)

