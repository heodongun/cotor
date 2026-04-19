package com.cotor.provenance

import com.cotor.runtime.actions.ActionExecutionRecord
import com.cotor.runtime.actions.ActionRequest
import com.cotor.runtime.actions.ActionStatus

class ProvenanceService(
    private val store: ProvenanceStore = ProvenanceStore()
) {
    fun recordAction(request: ActionRequest, record: ActionExecutionRecord) {
        if (record.status !in setOf(ActionStatus.SUCCEEDED, ActionStatus.FAILED, ActionStatus.DENIED, ActionStatus.WAITING_FOR_APPROVAL)) {
            return
        }
        val graph = store.load()
        val runRef = "run:${record.runId}"
        val actionRef = "action:${record.id}"
        val checkpointRef = request.metadata["checkpointId"]?.let { "checkpoint:$it" }
        val runNode = EvidenceNode(
            kind = EvidenceNodeKind.RUN,
            ref = runRef,
            label = record.runId
        )
        val actionNode = EvidenceNode(
            kind = EvidenceNodeKind.ACTION,
            ref = actionRef,
            label = request.label,
            metadata = buildMap {
                put("kind", request.kind.wireValue)
                put("status", record.status.name)
                putAll(request.metadata)
            }
        )
        val newNodes = mutableListOf(runNode, actionNode)
        if (checkpointRef != null) {
            newNodes += EvidenceNode(
                kind = EvidenceNodeKind.CHECKPOINT,
                ref = checkpointRef,
                label = request.metadata["checkpointId"] ?: checkpointRef.removePrefix("checkpoint:")
            )
        }
        request.evidence.filePaths.forEach { filePath ->
            newNodes += EvidenceNode(
                kind = EvidenceNodeKind.FILE,
                ref = "file:$filePath",
                label = filePath
            )
        }
        request.evidence.branchName?.let { branchName ->
            newNodes += EvidenceNode(
                kind = EvidenceNodeKind.BRANCH,
                ref = "branch:$branchName",
                label = branchName
            )
        }
        request.evidence.pullRequestNumber?.let { number ->
            newNodes += EvidenceNode(
                kind = EvidenceNodeKind.PR,
                ref = "pr:$number",
                label = request.evidence.pullRequestUrl ?: "#$number",
                metadata = mapOf("url" to (request.evidence.pullRequestUrl ?: ""))
            )
        }
        val mergedNodes = mergeNodes(graph.nodes, newNodes)
        val edges = buildList {
            add(EvidenceEdge(fromRef = runRef, toRef = actionRef, relation = "executed"))
            checkpointRef?.let { add(EvidenceEdge(fromRef = it, toRef = actionRef, relation = "triggered")) }
            request.evidence.filePaths.forEach { add(EvidenceEdge(fromRef = actionRef, toRef = "file:$it", relation = "touched")) }
            request.evidence.branchName?.let { add(EvidenceEdge(fromRef = runRef, toRef = "branch:$it", relation = "published-branch")) }
            request.evidence.pullRequestNumber?.let { add(EvidenceEdge(fromRef = runRef, toRef = "pr:$it", relation = "published-pr")) }
        }
        store.save(
            graph.copy(
                nodes = mergedNodes,
                edges = mergeEdges(graph.edges, edges),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    fun recordIssueRunLink(companyId: String, goalId: String?, issueId: String, runId: String) {
        val graph = store.load()
        val nodes = mergeNodes(
            graph.nodes,
            listOfNotNull(
                EvidenceNode(kind = EvidenceNodeKind.COMPANY, ref = "company:$companyId", label = companyId),
                goalId?.let { EvidenceNode(kind = EvidenceNodeKind.GOAL, ref = "goal:$it", label = it) },
                EvidenceNode(kind = EvidenceNodeKind.ISSUE, ref = "issue:$issueId", label = issueId),
                EvidenceNode(kind = EvidenceNodeKind.RUN, ref = "run:$runId", label = runId)
            )
        )
        val edges = mergeEdges(
            graph.edges,
            buildList {
                goalId?.let { add(EvidenceEdge(fromRef = "goal:$it", toRef = "issue:$issueId", relation = "owns")) }
                add(EvidenceEdge(fromRef = "issue:$issueId", toRef = "run:$runId", relation = "executed-by"))
                add(EvidenceEdge(fromRef = "company:$companyId", toRef = "issue:$issueId", relation = "contains"))
            }
        )
        store.save(graph.copy(nodes = nodes, edges = edges, updatedAt = System.currentTimeMillis()))
    }

    fun recordPullRequestState(
        runId: String?,
        pullRequestNumber: Int,
        url: String?,
        state: String?,
        checksSummary: String?
    ) {
        val graph = store.load()
        val nodes = mergeNodes(
            graph.nodes,
            listOf(
                EvidenceNode(
                    kind = EvidenceNodeKind.PR,
                    ref = "pr:$pullRequestNumber",
                    label = url ?: "#$pullRequestNumber",
                    metadata = buildMap {
                        url?.let { put("url", it) }
                        state?.let { put("state", it) }
                        checksSummary?.let { put("checksSummary", it) }
                    }
                )
            ) + listOfNotNull(
                runId?.let { EvidenceNode(kind = EvidenceNodeKind.RUN, ref = "run:$it", label = it) }
            )
        )
        val edges = if (runId != null) {
            mergeEdges(graph.edges, listOf(EvidenceEdge(fromRef = "run:$runId", toRef = "pr:$pullRequestNumber", relation = "linked-pr")))
        } else {
            graph.edges
        }
        store.save(graph.copy(nodes = nodes, edges = edges, updatedAt = System.currentTimeMillis()))
    }

    fun bundleForRun(runId: String): EvidenceBundle = bundleForRef("run:$runId")

    fun bundleForFile(path: String): EvidenceBundle = bundleForRef("file:$path")

    fun bundleForPullRequest(number: Int): EvidenceBundle = bundleForRef("pr:$number")

    private fun bundleForRef(ref: String): EvidenceBundle {
        val graph = store.load()
        val relatedEdges = graph.edges.filter { it.fromRef == ref || it.toRef == ref }
        val relatedRefs = relatedEdges.flatMap { listOf(it.fromRef, it.toRef) }.toSet() + ref
        val nodes = graph.nodes.filter { it.ref in relatedRefs }
        return EvidenceBundle(query = ref, nodes = nodes, edges = relatedEdges)
    }

    private fun mergeNodes(existing: List<EvidenceNode>, incoming: List<EvidenceNode>): List<EvidenceNode> {
        val byRef = existing.associateBy { it.ref }.toMutableMap()
        incoming.forEach { node -> byRef[node.ref] = node }
        return byRef.values.sortedBy { it.ref }
    }

    private fun mergeEdges(existing: List<EvidenceEdge>, incoming: List<EvidenceEdge>): List<EvidenceEdge> {
        val seen = linkedSetOf<String>()
        return (existing + incoming)
            .filter { edge ->
                val key = "${edge.fromRef}|${edge.toRef}|${edge.relation}"
                seen.add(key)
            }
    }
}
