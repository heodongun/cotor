package com.cotor.domain.planning

/**
 * File overview for GoalDrivenTaskPlannerTest.
 *
 * This file belongs to the test suite that documents expected behavior and protects against regressions.
 * It groups declarations around goal driven task planner test so readers can find the owning runtime area quickly.
 * Read here first when tracing behavior that flows through this part of the codebase.
 */


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
    fun `buildPlan ignores workflow history bullets inside context sections`() {
        val prompt = """
            Recently completed goals:
            - AI끼리 연애하는 웹을 만들어
            Recently completed issues:
            - QA review Deliver the smallest complete repository change for "AI끼리 연애하는 웹을 만들어"
            - CEO approve Deliver the smallest complete repository change for "AI끼리 연애하는 웹을 만들어"

            Next actions:
            - Deliver the next reviewed product slice
            - Harden the backend and integration path
            - Validate the results and capture next-step guidance
        """.trimIndent()

        val plan = planner.buildPlan(
            title = "Advance the next company cycle",
            prompt = prompt,
            agents = listOf("claude", "codex")
        )

        assertEquals("prompt-checklist", plan.decompositionSource)
        val subtaskTitles = plan.assignments.flatMap { assignment -> assignment.subtasks.map { it.title } }
        assertTrue(subtaskTitles.contains("Deliver the next reviewed product slice"))
        assertTrue(subtaskTitles.contains("Harden the backend and integration path"))
        assertTrue(subtaskTitles.contains("Validate the results and capture next-step guidance"))
        assertTrue(subtaskTitles.none { it.startsWith("QA review ") })
        assertTrue(subtaskTitles.none { it.startsWith("CEO approve ") })
        assertTrue(subtaskTitles.none { it.contains("AI끼리 연애하는 웹을 만들어") })
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

    @Test
    fun `builder execution focus does not infer frontend from the word builder`() {
        val plan = planner.buildPlanForParticipants(
            title = "Ship a tiny smoke-tested change",
            prompt = "Create one tiny artifact, verify it, and report review status.",
            participants = listOf(
                GoalDrivenTaskPlanner.PlanningParticipant(
                    participantId = "ceo-1",
                    agentName = "codex",
                    title = "CEO",
                    roleSummary = "lead strategy, planning, triage, final merge",
                    capabilities = listOf("planning", "triage"),
                    mergeAuthority = true
                ),
                GoalDrivenTaskPlanner.PlanningParticipant(
                    participantId = "builder-1",
                    agentName = "codex",
                    title = "Builder",
                    roleSummary = "implementation, integration, delivery",
                    capabilities = listOf("implementation", "integration")
                ),
                GoalDrivenTaskPlanner.PlanningParticipant(
                    participantId = "qa-1",
                    agentName = "codex",
                    title = "QA",
                    roleSummary = "qa, review, verification",
                    capabilities = listOf("qa", "review")
                )
            )
        )

        val executionAssignment = plan.assignments.first { it.phase == "execution" }
        assertEquals(
            "implementation depth, integration quality, and completing the assigned slice",
            executionAssignment.focus
        )
        assertTrue(executionAssignment.subtasks.none { it.title.contains("Clarify the success conditions") })
    }

    @Test
    fun `generic goal with enterprise roster falls back to Builder instead of UX roles`() {
        val plan = planner.buildPlanForParticipants(
            title = "sayhello",
            prompt = "sayhello",
            participants = listOf(
                GoalDrivenTaskPlanner.PlanningParticipant(
                    participantId = "ceo-1",
                    agentName = "codex",
                    title = "CEO",
                    roleSummary = "lead strategy, planning, triage, final merge",
                    capabilities = listOf("planning", "triage"),
                    mergeAuthority = true
                ),
                GoalDrivenTaskPlanner.PlanningParticipant(
                    participantId = "ux-1",
                    agentName = "codex",
                    title = "UX Builder",
                    roleSummary = "shape product flows, usability, interaction clarity, and user-facing experience",
                    specialties = listOf("ux", "research", "flows", "usability"),
                    capabilities = listOf("ux", "research", "flows", "usability")
                ),
                GoalDrivenTaskPlanner.PlanningParticipant(
                    participantId = "ui-1",
                    agentName = "codex",
                    title = "UI Builder",
                    roleSummary = "craft visual interface details, component polish, layout quality, and design fidelity",
                    specialties = listOf("ui", "design", "components", "visual polish"),
                    capabilities = listOf("ui", "design", "components")
                ),
                GoalDrivenTaskPlanner.PlanningParticipant(
                    participantId = "builder-1",
                    agentName = "codex",
                    title = "Builder",
                    roleSummary = "implement assigned product slices, integrate changes, and deliver reviewable work",
                    specialties = listOf("implementation", "integration", "delivery"),
                    capabilities = listOf("implementation", "integration", "delivery")
                ),
                GoalDrivenTaskPlanner.PlanningParticipant(
                    participantId = "qa-1",
                    agentName = "codex",
                    title = "QA",
                    roleSummary = "qa, review, verification",
                    capabilities = listOf("qa", "review")
                )
            )
        )

        val executionAssignments = plan.assignments.filter { it.phase == "execution" }
        assertTrue(executionAssignments.isNotEmpty())
        assertEquals("Builder", executionAssignments.first().role)
    }

    @Test
    fun `generic builder fallback still fans out when multiple work items are available`() {
        val plan = planner.buildPlanForParticipants(
            title = "Advance the next company cycle",
            prompt = """
                - Deliver the next reviewed product slice
                - Harden the backend and integration path
                - Improve the user-facing quality bar
                - Validate the results and capture next-step guidance
            """.trimIndent(),
            participants = listOf(
                GoalDrivenTaskPlanner.PlanningParticipant(
                    participantId = "ceo-1",
                    agentName = "codex",
                    title = "CEO",
                    roleSummary = "lead strategy, planning, triage, final merge",
                    capabilities = listOf("planning", "triage"),
                    mergeAuthority = true
                ),
                GoalDrivenTaskPlanner.PlanningParticipant(
                    participantId = "builder-1",
                    agentName = "codex",
                    title = "Builder",
                    roleSummary = "implement assigned product slices, integrate changes, and deliver reviewable work",
                    capabilities = listOf("implementation", "integration", "delivery")
                ),
                GoalDrivenTaskPlanner.PlanningParticipant(
                    participantId = "backend-1",
                    agentName = "codex",
                    title = "Backend Builder",
                    roleSummary = "implement backend behavior, orchestration logic, APIs, and integration reliability",
                    capabilities = listOf("backend", "implementation", "integration")
                ),
                GoalDrivenTaskPlanner.PlanningParticipant(
                    participantId = "ui-1",
                    agentName = "codex",
                    title = "UI Builder",
                    roleSummary = "craft visual interface details, component polish, layout quality, and design fidelity",
                    capabilities = listOf("frontend", "implementation")
                ),
                GoalDrivenTaskPlanner.PlanningParticipant(
                    participantId = "qa-1",
                    agentName = "codex",
                    title = "QA",
                    roleSummary = "qa, review, verification",
                    capabilities = listOf("qa", "review")
                )
            )
        )

        val executionAssignments = plan.assignments.filter { it.phase == "execution" }
        assertTrue(executionAssignments.size >= 2)
        assertEquals("Builder", executionAssignments.first().role)
    }

    @Test
    fun `short natural language prompts are enriched into a larger execution portfolio`() {
        val plan = planner.buildPlanForParticipants(
            title = "Loop smoke goal",
            prompt = "Keep the company moving like a real organization. Create multiple branchable issues, run them in parallel when safe, and hand reviewed results back to the CEO for the next wave.",
            participants = listOf(
                GoalDrivenTaskPlanner.PlanningParticipant(
                    participantId = "ceo-1",
                    agentName = "codex",
                    title = "CEO",
                    roleSummary = "lead strategy, planning, triage, final merge",
                    capabilities = listOf("planning", "triage"),
                    mergeAuthority = true
                ),
                GoalDrivenTaskPlanner.PlanningParticipant(
                    participantId = "product-1",
                    agentName = "codex",
                    title = "Product Strategist",
                    roleSummary = "requirements clarity, product scope, and discovery",
                    capabilities = listOf("product", "research")
                ),
                GoalDrivenTaskPlanner.PlanningParticipant(
                    participantId = "builder-1",
                    agentName = "codex",
                    title = "Builder",
                    roleSummary = "implement assigned product slices, integrate changes, and deliver reviewable work",
                    capabilities = listOf("implementation", "integration")
                ),
                GoalDrivenTaskPlanner.PlanningParticipant(
                    participantId = "backend-1",
                    agentName = "codex",
                    title = "Backend Builder",
                    roleSummary = "implement backend behavior, orchestration logic, APIs, and integration reliability",
                    capabilities = listOf("backend", "implementation", "integration")
                ),
                GoalDrivenTaskPlanner.PlanningParticipant(
                    participantId = "qa-1",
                    agentName = "codex",
                    title = "QA",
                    roleSummary = "qa, review, verification",
                    capabilities = listOf("qa", "review")
                )
            )
        )

        val executionAssignments = plan.assignments.filter { it.phase == "execution" }
        assertTrue(executionAssignments.size >= 3)
        assertEquals("Builder", executionAssignments.first().role)
    }
}
