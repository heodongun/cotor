package com.cotor.app.runtime

import com.cotor.app.CompanyGoal
import com.cotor.app.CompanyIssue
import com.cotor.app.GoalStatus
import com.cotor.app.IssueStatus
import com.cotor.app.OrgAgentProfile
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class CompanyRuntimeMachineTest : FunSpec({
    test("planGoalDecomposition emits planning commands only for autonomous goals without open work") {
        val goalA = CompanyGoal(
            id = "goal-a",
            companyId = "company-1",
            title = "Need planning",
            description = "Need planning",
            status = GoalStatus.ACTIVE,
            autonomyEnabled = true,
            createdAt = 1,
            updatedAt = 1
        )
        val goalB = CompanyGoal(
            id = "goal-b",
            companyId = "company-1",
            title = "Already has work",
            description = "Already has work",
            status = GoalStatus.ACTIVE,
            autonomyEnabled = true,
            createdAt = 1,
            updatedAt = 1
        )
        val issue = CompanyIssue(
            id = "issue-b",
            companyId = "company-1",
            goalId = "goal-b",
            workspaceId = "workspace-1",
            title = "Execution work",
            description = "work",
            status = IssueStatus.DELEGATED,
            kind = "execution",
            createdAt = 1,
            updatedAt = 1
        )

        CompanyRuntimeMachine.planGoalDecomposition(
            companyId = "company-1",
            goals = listOf(goalA, goalB),
            issues = listOf(issue)
        ) shouldContainExactly listOf(RuntimeCommand.EnsurePlanningIssue("goal-a"))
    }

    test("planIssueStarts limits concurrent work by profile and execution agent") {
        val profiles = listOf(
            OrgAgentProfile(
                id = "profile-a",
                companyId = "company-1",
                roleName = "Builder",
                executionAgentName = "opencode"
            ),
            OrgAgentProfile(
                id = "profile-b",
                companyId = "company-1",
                roleName = "QA",
                executionAgentName = "opencode"
            ),
            OrgAgentProfile(
                id = "profile-c",
                companyId = "company-1",
                roleName = "Backend",
                executionAgentName = "codex"
            )
        )
        val issues = listOf(
            CompanyIssue(
                id = "issue-a",
                companyId = "company-1",
                goalId = "goal-1",
                workspaceId = "workspace-1",
                title = "A",
                description = "A",
                status = IssueStatus.DELEGATED,
                kind = "execution",
                assigneeProfileId = "profile-a",
                createdAt = 1,
                updatedAt = 1
            ),
            CompanyIssue(
                id = "issue-b",
                companyId = "company-1",
                goalId = "goal-1",
                workspaceId = "workspace-1",
                title = "B",
                description = "B",
                status = IssueStatus.DELEGATED,
                kind = "execution",
                assigneeProfileId = "profile-b",
                createdAt = 2,
                updatedAt = 2
            ),
            CompanyIssue(
                id = "issue-c",
                companyId = "company-1",
                goalId = "goal-1",
                workspaceId = "workspace-1",
                title = "C",
                description = "C",
                status = IssueStatus.DELEGATED,
                kind = "execution",
                assigneeProfileId = "profile-c",
                createdAt = 3,
                updatedAt = 3
            )
        )

        val commands = CompanyRuntimeMachine.planIssueStarts(
            runnableIssues = issues,
            companyProfiles = profiles,
            occupiedProfileIds = emptySet(),
            occupiedExecutionAgents = emptySet()
        )

        commands.map { (it as RuntimeCommand.StartIssue).issueId } shouldContainExactly listOf("issue-a", "issue-c")
        commands.size shouldBe 2
    }

    test("planIssueStarts skips issues whose execution agent is already occupied") {
        val profiles = listOf(
            OrgAgentProfile(
                id = "profile-a",
                companyId = "company-1",
                roleName = "Builder",
                executionAgentName = "opencode"
            ),
            OrgAgentProfile(
                id = "profile-c",
                companyId = "company-1",
                roleName = "Backend",
                executionAgentName = "codex"
            )
        )
        val issues = listOf(
            CompanyIssue(
                id = "issue-a",
                companyId = "company-1",
                goalId = "goal-1",
                workspaceId = "workspace-1",
                title = "A",
                description = "A",
                status = IssueStatus.DELEGATED,
                kind = "execution",
                assigneeProfileId = "profile-a",
                createdAt = 1,
                updatedAt = 1
            ),
            CompanyIssue(
                id = "issue-c",
                companyId = "company-1",
                goalId = "goal-1",
                workspaceId = "workspace-1",
                title = "C",
                description = "C",
                status = IssueStatus.DELEGATED,
                kind = "execution",
                assigneeProfileId = "profile-c",
                createdAt = 2,
                updatedAt = 2
            )
        )

        val commands = CompanyRuntimeMachine.planIssueStarts(
            runnableIssues = issues,
            companyProfiles = profiles,
            occupiedProfileIds = emptySet(),
            occupiedExecutionAgents = setOf(" OpenCode ")
        )

        commands.map { (it as RuntimeCommand.StartIssue).issueId } shouldContainExactly listOf("issue-c")
    }
})
