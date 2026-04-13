package com.cotor.policy

import com.cotor.runtime.actions.ActionInterceptor
import com.cotor.runtime.actions.ActionInterceptorDecision
import com.cotor.runtime.actions.ActionKind
import com.cotor.runtime.actions.ActionRequest
import kotlinx.serialization.Serializable

@Serializable
data class RiskSignal(
    val key: String,
    val weight: Int,
    val detail: String
)

@Serializable
data class RiskScore(
    val total: Int,
    val signals: List<RiskSignal> = emptyList()
)

@Serializable
data class ApprovalRequirement(
    val required: Boolean,
    val reason: String,
    val score: RiskScore
)

class RiskApprovalInterceptor(
    private val threshold: Int = 80
) : ActionInterceptor {
    override suspend fun before(request: ActionRequest): ActionInterceptorDecision {
        val requirement = evaluate(request)
        return if (requirement.required) {
            ActionInterceptorDecision.requireApproval(requirement.reason)
        } else {
            ActionInterceptorDecision.allow()
        }
    }

    fun evaluate(request: ActionRequest): ApprovalRequirement {
        val signals = mutableListOf<RiskSignal>()
        when (request.kind) {
            ActionKind.GITHUB_MERGE -> signals += RiskSignal("github-merge", 90, "Merging a pull request changes repository state permanently.")
            ActionKind.GIT_PUBLISH -> signals += RiskSignal("git-publish", 70, "Publishing a branch or PR affects shared repository state.")
            ActionKind.SHELL_EXEC -> signals += RiskSignal("shell-exec", 40, "Shell execution may mutate local state.")
            ActionKind.SECRET_READ -> signals += RiskSignal("secret-read", 95, "Secret access is highly sensitive.")
            else -> Unit
        }
        val commandText = request.command.joinToString(" ").lowercase()
        if (commandText.contains("rm ") || commandText.contains("git push") || commandText.contains("git rebase")) {
            signals += RiskSignal("dangerous-command", 35, "Command includes a destructive or publish-like operation.")
        }
        val path = request.path?.lowercase().orEmpty()
        if (listOf("auth", "secret", "config", "migration").any { path.contains(it) }) {
            signals += RiskSignal("sensitive-path", 35, "Path suggests auth/config/migration-sensitive changes.")
        }
        if (request.networkTarget?.contains("github.com", ignoreCase = true) == true &&
            request.kind in setOf(ActionKind.GITHUB_MERGE, ActionKind.GIT_PUBLISH, ActionKind.GITHUB_REVIEW)
        ) {
            signals += RiskSignal("external-side-effect", 20, "Action mutates external GitHub state.")
        }
        val total = signals.sumOf { it.weight }
        return ApprovalRequirement(
            required = total >= threshold,
            reason = if (total >= threshold) {
                "Risk approval required (score=$total): ${signals.joinToString { it.key }}"
            } else {
                "Risk score $total is below the approval threshold."
            },
            score = RiskScore(total = total, signals = signals)
        )
    }
}
