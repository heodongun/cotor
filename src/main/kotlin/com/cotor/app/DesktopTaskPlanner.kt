package com.cotor.app

/**
 * Generates a stable lead/worker plan from the saved task goal.
 *
 * The first selected agent remains the lead so the desktop client's authoring
 * contract stays intact, while every run receives a role-specific prompt instead
 * of the original user goal verbatim.
 */
object DesktopTaskPlanner {
    fun createAssignments(prompt: String, agents: List<String>): List<TaskAssignment> {
        val normalizedAgents = agents
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }

        if (normalizedAgents.isEmpty()) {
            return emptyList()
        }

        val goal = prompt.trim()
        val leadAgent = normalizedAgents.first()
        val workers = normalizedAgents.drop(1)
        val workerTitles = workerTitles(workers.size)

        val workerAssignments = workers.mapIndexed { index, agentName ->
            val title = workerTitles[index]
            TaskAssignment(
                id = "worker-${index + 1}-$agentName",
                agentName = agentName,
                role = TaskAssignmentRole.WORKER,
                title = title,
                prompt = buildWorkerPrompt(goal, title, index + 1, workers.size),
                order = index + 1
            )
        }

        val leadAssignment = TaskAssignment(
            id = "lead-$leadAgent",
            agentName = leadAgent,
            role = TaskAssignmentRole.LEAD,
            title = if (workerAssignments.isEmpty()) "End-to-end execution" else "Plan and integrate",
            prompt = buildLeadPrompt(goal, workerAssignments),
            order = 0
        )

        return listOf(leadAssignment) + workerAssignments
    }

    private fun buildLeadPrompt(goal: String, workerAssignments: List<TaskAssignment>): String {
        val workerRoster = if (workerAssignments.isEmpty()) {
            "- No worker agents were selected. Own the task end-to-end in your isolated worktree."
        } else {
            workerAssignments.joinToString("\n") { assignment ->
                "- ${assignment.agentName}: ${assignment.title}"
            }
        }

        return """
            Goal:
            $goal

            Role:
            You are the lead agent for this desktop task.

            Delegation roster:
            $workerRoster

            Assignment:
            - Turn the goal into an executable plan for this run.
            - Own the integration strategy and final implementation decisions in your isolated worktree.
            - Keep your output focused on coordination, concrete edits, and reviewer-facing rationale.
        """.trimIndent()
    }

    private fun buildWorkerPrompt(goal: String, title: String, workerNumber: Int, totalWorkers: Int): String {
        val coordinationHint = if (totalWorkers <= 1) {
            "You are the primary worker for this goal."
        } else {
            "You are worker $workerNumber of $totalWorkers running in parallel with other workers."
        }

        return """
            Goal:
            $goal

            Role:
            You are a worker agent for this desktop task.

            Assignment:
            $title

            Operating constraints:
            - $coordinationHint
            - Work only in your isolated git worktree.
            - Make concrete progress for your slice of the goal and leave reviewer-friendly notes in your output.
        """.trimIndent()
    }

    private fun workerTitles(workerCount: Int): List<String> {
        if (workerCount <= 0) {
            return emptyList()
        }
        if (workerCount == 1) {
            return listOf("Implement the requested change")
        }

        return buildList {
            add("Inspect the goal and map the relevant code paths")
            repeat(workerCount - 2) { index ->
                add("Implement workstream ${index + 1}")
            }
            add("Validate the change and capture regression risks")
        }
    }
}
