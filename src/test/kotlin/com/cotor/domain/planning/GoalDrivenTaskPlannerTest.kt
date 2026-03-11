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
        assertEquals("goal-template", plan.decompositionSource)
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
}
