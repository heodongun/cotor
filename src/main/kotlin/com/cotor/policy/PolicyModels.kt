package com.cotor.policy

import com.cotor.runtime.actions.ActionKind
import com.cotor.runtime.actions.ActionRequest
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class PolicyScopeLevel {
    GLOBAL,
    COMPANY,
    AGENT,
    GOAL,
    ISSUE
}

@Serializable
enum class PolicyEffect {
    ALLOW,
    DENY,
    REQUIRE_APPROVAL
}

@Serializable
data class PolicyRule(
    val id: String = UUID.randomUUID().toString(),
    val description: String,
    val scopeLevel: PolicyScopeLevel = PolicyScopeLevel.GLOBAL,
    val scopeId: String? = null,
    val effect: PolicyEffect,
    val actionKinds: List<ActionKind> = emptyList(),
    val commandPrefixes: List<String> = emptyList(),
    val pathPrefixes: List<String> = emptyList(),
    val networkTargets: List<String> = emptyList()
)

@Serializable
data class PolicyDocument(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val defaultEffect: PolicyEffect = PolicyEffect.ALLOW,
    val rules: List<PolicyRule> = emptyList()
)

@Serializable
data class PolicyExplanation(
    val summary: String,
    val matchedRuleIds: List<String> = emptyList()
)

@Serializable
data class PolicyDecision(
    val id: String = UUID.randomUUID().toString(),
    val request: ActionRequest,
    val effect: PolicyEffect,
    val explanation: PolicyExplanation,
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
data class PolicyAuditLog(
    val decisions: List<PolicyDecision> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis()
)

