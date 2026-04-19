package com.cotor.policy

import com.cotor.runtime.actions.ActionInterceptor
import com.cotor.runtime.actions.ActionInterceptorDecision
import com.cotor.runtime.actions.ActionRequest

class PolicyEngine(
    private val store: PolicyStore = PolicyStore()
) : ActionInterceptor {
    fun evaluate(request: ActionRequest): PolicyDecision {
        val documents = store.listDocuments().ifEmpty { listOf(store.defaultPermissiveProfile()) }
        val matchingRules = documents
            .flatMap { document -> document.rules.map { document to it } }
            .filter { (_, rule) -> rule.matches(request) }
            .sortedBy { (_, rule) -> rule.scopeLevel.ordinal }

        val selectedRule = matchingRules.lastOrNull()?.second
        val effect = selectedRule?.effect ?: documents.lastOrNull()?.defaultEffect ?: PolicyEffect.ALLOW
        val explanation = when {
            selectedRule != null -> PolicyExplanation(
                summary = selectedRule.description,
                matchedRuleIds = listOf(selectedRule.id)
            )
            else -> PolicyExplanation(
                summary = "No matching policy rule; using default ${effect.name.lowercase().replace('_', ' ')}.",
                matchedRuleIds = emptyList()
            )
        }
        return PolicyDecision(
            request = request,
            effect = effect,
            explanation = explanation
        )
    }

    override suspend fun before(request: ActionRequest): ActionInterceptorDecision {
        val decision = evaluate(request)
        store.appendDecision(decision)
        return when (decision.effect) {
            PolicyEffect.ALLOW -> ActionInterceptorDecision.allow(decision.id)
            PolicyEffect.DENY -> ActionInterceptorDecision.deny(decision.explanation.summary, decision.id)
            PolicyEffect.REQUIRE_APPROVAL -> ActionInterceptorDecision.requireApproval(decision.explanation.summary, decision.id)
        }
    }

    fun decisions(runId: String? = null, issueId: String? = null): List<PolicyDecision> =
        store.loadAudit().decisions
            .filter { decision ->
                (runId == null || decision.request.subject.runId == runId) &&
                    (issueId == null || decision.request.subject.issueId == issueId)
            }
            .sortedByDescending { it.createdAt }

    private fun PolicyRule.matches(request: ActionRequest): Boolean {
        if (actionKinds.isNotEmpty() && request.kind !in actionKinds) {
            return false
        }
        if (scopeId != null) {
            val actualScopeId = when (scopeLevel) {
                PolicyScopeLevel.GLOBAL -> null
                PolicyScopeLevel.COMPANY -> request.subject.companyId
                PolicyScopeLevel.AGENT -> request.subject.agentName
                PolicyScopeLevel.GOAL -> request.subject.goalId
                PolicyScopeLevel.ISSUE -> request.subject.issueId
            }
            if (!scopeId.equals(actualScopeId, ignoreCase = true)) {
                return false
            }
        }
        if (commandPrefixes.isNotEmpty()) {
            val commandString = request.command.joinToString(" ")
            if (commandPrefixes.none { prefix ->
                    commandString.startsWith(prefix) || request.command.firstOrNull()?.startsWith(prefix) == true
                }
            ) {
                return false
            }
        }
        if (pathPrefixes.isNotEmpty()) {
            val path = request.path.orEmpty()
            if (pathPrefixes.none { path.startsWith(it) }) {
                return false
            }
        }
        if (networkTargets.isNotEmpty()) {
            val target = request.networkTarget.orEmpty()
            if (networkTargets.none { target.contains(it, ignoreCase = true) }) {
                return false
            }
        }
        return true
    }
}
