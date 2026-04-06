package com.cotor.app.runtime

import com.cotor.app.CompanyGoal
import com.cotor.app.CompanyIssue
import com.cotor.app.IssueStatus
import com.cotor.app.OrgAgentProfile

object CompanyRuntimeMachine {
    fun planGoalDecomposition(
        companyId: String,
        goals: List<CompanyGoal>,
        issues: List<CompanyIssue>
    ): List<RuntimeCommand.EnsurePlanningIssue> =
        goals
            .filter { it.companyId == companyId && it.autonomyEnabled }
            .filter { goal ->
                issues.none { issue ->
                    issue.goalId == goal.id &&
                        !issue.kind.equals("planning", ignoreCase = true) &&
                        issue.status != IssueStatus.DONE &&
                        issue.status != IssueStatus.CANCELED
                }
            }
            .map { RuntimeCommand.EnsurePlanningIssue(it.id) }

    fun planIssueStarts(
        runnableIssues: List<CompanyIssue>,
        companyProfiles: List<OrgAgentProfile>,
        occupiedProfileIds: Set<String>,
        occupiedExecutionAgents: Set<String>
    ): List<RuntimeCommand.StartIssue> {
        val profileById = companyProfiles.associateBy { it.id }
        val usedProfiles = occupiedProfileIds.toMutableSet()
        val commands = mutableListOf<RuntimeCommand.StartIssue>()
        runnableIssues.forEach { issue ->
            val profileId = issue.assigneeProfileId
            val canUseProfileSlot = profileId == null || usedProfiles.add(profileId)
            if (canUseProfileSlot) {
                commands += RuntimeCommand.StartIssue(issue.id)
            }
        }
        return commands
    }
}
