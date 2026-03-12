package com.cotor.domain.planning

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GoalDrivenTaskPlannerTest {

    private val planner = GoalDrivenTaskPlanner()

    @Test
    fun `buildPlan creates deterministic assignments for a high-level goal`() {
        val plan = planner.buildPlan(
            title = "Improve desktop task orchestration",
            prompt = "Improve desktop task orchestration so each selected agent gets a clearer role and validation path.",
            agents = listOf("claude", "codex")
        )

        assertEquals("Improve desktop task orchestration", plan.goalSummary)
        assertEquals("roster-aware", plan.decompositionSource)
        assertEquals(listOf("claude", "codex"), plan.assignments.map { it.agentName })
        assertTrue(plan.assignments.all { it.subtasks.size >= 2 })
        assertTrue(plan.assignments.all { it.assignedPrompt.contains("Assigned subtasks:") })
        assertTrue(plan.assignments.all { it.assignedPrompt.contains("Original request:") })
    }

    @Test
    fun `buildPlan preserves explicit checklist items when present`() {
        val prompt = """
            Ship the feature with the following scope:
            - Add task planning metadata to the desktop task model
            - Persist per-agent assignments during task creation
            - Route each run through its assigned prompt
        """.trimIndent()

        val plan = planner.buildPlan(
            title = null,
            prompt = prompt,
            agents = listOf("claude", "codex")
        )

        assertEquals("prompt-checklist", plan.decompositionSource)
        val subtaskTitles = plan.assignments.flatMap { assignment -> assignment.subtasks.map { it.title } }
        assertTrue(subtaskTitles.contains("Add task planning metadata to the desktop task model"))
        assertTrue(subtaskTitles.contains("Persist per-agent assignments during task creation"))
        assertTrue(subtaskTitles.contains("Route each run through its assigned prompt"))
    }

    @Test
    fun `buildPlan includes A2A collaboration metadata in assigned prompts`() {
        val plan = planner.buildPlanForParticipants(
            title = "Run an autonomous company loop",
            prompt = "Let the CEO delegate builder work and have QA verify the result.",
            participants = listOf(
                GoalDrivenTaskPlanner.PlanningParticipant(
                    participantId = "ceo-1",
                    agentName = "codex",
                    title = "CEO",
                    roleSummary = "lead strategy and final approval",
                    specialties = listOf("planning", "delegation"),
                    collaborationInstructions = "Break work into slices and hand implementation to builders before asking QA for verification.",
                    preferredCollaborators = listOf("Builder A", "QA Lead"),
                    memoryNotes = "Own final merge decisions.",
                    mergeAuthority = true
                ),
                GoalDrivenTaskPlanner.PlanningParticipant(
                    participantId = "builder-1",
                    agentName = "claude",
                    title = "Builder A",
                    roleSummary = "implementation and delivery",
                    specialties = listOf("backend", "integration"),
                    collaborationInstructions = "Return completed work to QA after implementation."
                ),
                GoalDrivenTaskPlanner.PlanningParticipant(
                    participantId = "qa-1",
                    agentName = "gemini",
                    title = "QA Lead",
                    roleSummary = "qa, review, verification"
                )
            )
        )

        val approvalPrompt = plan.assignments.first { it.role == "CEO" }.assignedPrompt
        assertTrue(approvalPrompt.contains("A2A collaboration contract:"))
        assertTrue(approvalPrompt.contains("Preferred collaborators: Builder A, QA Lead"))
        assertTrue(approvalPrompt.contains("Persistent agent memory:"))
        assertTrue(approvalPrompt.contains("Own final merge decisions."))
    }

    @Test
    fun `collaboration notes do not accidentally turn builders into reviewers`() {
        val plan = planner.buildPlanForParticipants(
            title = "Ship a feature",
            prompt = "Have builders implement, then QA review.",
            participants = listOf(
                GoalDrivenTaskPlanner.PlanningParticipant(
                    participantId = "ceo-1",
                    agentName = "codex",
                    title = "CEO",
                    roleSummary = "lead strategy and approval",
                    specialties = listOf("planning"),
                    mergeAuthority = true
                ),
                GoalDrivenTaskPlanner.PlanningParticipant(
                    participantId = "builder-1",
                    agentName = "claude",
                    title = "Builder A",
                    roleSummary = "implementation and delivery",
                    specialties = listOf("implementation", "integration"),
                    collaborationInstructions = "Hand results to QA after implementation."
                ),
                GoalDrivenTaskPlanner.PlanningParticipant(
                    participantId = "qa-1",
                    agentName = "gemini",
                    title = "QA",
                    roleSummary = "qa review verification",
                    specialties = listOf("qa", "review")
                )
            )
        )

        assertEquals(1, plan.assignments.count { it.phase == "review" })
        assertEquals("QA", plan.assignments.first { it.phase == "review" }.role)
    }
}
