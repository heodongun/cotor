package com.cotor.domain.planning

import com.cotor.app.AgentAssignmentPlan
import com.cotor.app.TaskExecutionPlan
import com.cotor.app.TaskSubtask

/**
 * Builds a deterministic execution plan so desktop-created tasks can move from a
 * raw goal into per-agent assignments without needing an extra planning service.
 */
class GoalDrivenTaskPlanner {

    fun buildPlan(
        title: String?,
        prompt: String,
        agents: List<String>
    ): TaskExecutionPlan {
        val normalizedPrompt = prompt.trim()
        val normalizedAgents = agents.map { it.trim().lowercase() }.filter { it.isNotBlank() }
        val goalSummary = title?.trim()?.takeIf { it.isNotBlank() }
            ?: summarizeGoal(normalizedPrompt)
        val extractedWorkItems = extractWorkItems(normalizedPrompt)
        val workItems = when {
            extractedWorkItems.isNotEmpty() -> extractedWorkItems
            else -> defaultWorkItems(goalSummary)
        }
        val sharedChecklist = listOf(
            "Keep the branch scoped to the assigned focus while still delivering a usable end-to-end result.",
            "Validate the changed behavior before finishing and call out any residual risk."
        )

        val assignments = normalizedAgents.mapIndexed { index, agentName ->
            val focusLane = focusLaneFor(index, normalizedAgents.size)
            val ownedItems = workItems.filterIndexed { itemIndex, _ ->
                itemIndex % normalizedAgents.size == index
            }.ifEmpty {
                listOf(fallbackWorkItem(goalSummary, focusLane.focus))
            }
            val subtasks = buildSubtasks(agentName, ownedItems, focusLane.focus)
            AgentAssignmentPlan(
                agentName = agentName,
                role = focusLane.role,
                focus = focusLane.focus,
                subtasks = subtasks,
                assignedPrompt = buildAssignedPrompt(
                    agentName = agentName,
                    role = focusLane.role,
                    focus = focusLane.focus,
                    goalSummary = goalSummary,
                    originalPrompt = normalizedPrompt,
                    subtasks = subtasks,
                    sharedChecklist = sharedChecklist
                )
            )
        }

        return TaskExecutionPlan(
            goalSummary = goalSummary,
            decompositionSource = if (extractedWorkItems.isNotEmpty()) "prompt-checklist" else "goal-template",
            sharedChecklist = sharedChecklist,
            assignments = assignments
        )
    }

    private fun summarizeGoal(prompt: String): String {
        val firstLine = prompt.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return firstLine.take(140).ifBlank { "Complete the requested change" }
    }

    private fun extractWorkItems(prompt: String): List<String> {
        val checklistItems = prompt.lineSequence()
            .map { it.trim() }
            .mapNotNull { line ->
                val match = CHECKLIST_PATTERN.matchEntire(line) ?: return@mapNotNull null
                match.groupValues[1].trim().takeIf { it.isNotBlank() }
            }
            .toList()
        if (checklistItems.isNotEmpty()) {
            return checklistItems
        }

        val sentenceItems = prompt
            .split(SENTENCE_BOUNDARY)
            .map { it.trim().trimEnd('.', '!', '?') }
            .filter { it.length >= 20 }
            .distinct()
        return if (sentenceItems.size >= 2) sentenceItems.take(4) else emptyList()
    }

    private fun defaultWorkItems(goalSummary: String): List<String> = listOf(
        "Clarify the success conditions and constraints for \"$goalSummary\"",
        "Implement the repository changes required to deliver \"$goalSummary\"",
        "Validate the outcome for \"$goalSummary\" and capture any remaining risks"
    )

    private fun fallbackWorkItem(goalSummary: String, focus: String): String =
        "Advance \"$goalSummary\" with extra emphasis on $focus"

    private fun buildSubtasks(
        agentName: String,
        workItems: List<String>,
        focus: String
    ): List<TaskSubtask> {
        val primarySubtasks = workItems.mapIndexed { index, item ->
            TaskSubtask(
                id = "$agentName-${index + 1}",
                title = toSubtaskTitle(item),
                details = "Own this slice in your branch with extra attention to $focus."
            )
        }
        val validationSubtask = TaskSubtask(
            id = "$agentName-${primarySubtasks.size + 1}",
            title = "Validate the assigned branch outcome",
            details = "Run targeted checks for your implementation, note regressions, and summarize residual risks."
        )
        return primarySubtasks + validationSubtask
    }

    private fun toSubtaskTitle(item: String): String {
        val trimmed = item.trim()
        return trimmed.replaceFirstChar { current ->
            if (current.isLowerCase()) current.titlecase() else current.toString()
        }
    }

    private fun buildAssignedPrompt(
        agentName: String,
        role: String,
        focus: String,
        goalSummary: String,
        originalPrompt: String,
        subtasks: List<TaskSubtask>,
        sharedChecklist: List<String>
    ): String = buildString {
        appendLine("Goal-driven assignment for $agentName")
        appendLine()
        appendLine("Goal summary:")
        appendLine(goalSummary)
        appendLine()
        appendLine("Role:")
        appendLine("$role")
        appendLine()
        appendLine("Primary focus:")
        appendLine(focus)
        appendLine()
        appendLine("Assigned subtasks:")
        subtasks.forEachIndexed { index, subtask ->
            appendLine("${index + 1}. ${subtask.title}")
            appendLine("   ${subtask.details}")
        }
        appendLine()
        appendLine("Shared completion checklist:")
        sharedChecklist.forEach { item ->
            appendLine("- $item")
        }
        appendLine()
        appendLine("Original request:")
        appendLine(originalPrompt)
    }

    private fun focusLaneFor(index: Int, agentCount: Int): FocusLane {
        if (agentCount <= 1) {
            return FocusLane(
                role = "end-to-end owner",
                focus = "delivering the complete change with implementation and validation in one branch"
            )
        }

        val lanes = listOf(
            FocusLane(
                role = "primary implementer",
                focus = "the highest-leverage code path and the core behavior change"
            ),
            FocusLane(
                role = "validation-focused implementer",
                focus = "edge cases, regression coverage, and failure handling"
            ),
            FocusLane(
                role = "architecture-focused implementer",
                focus = "interfaces, maintainability, and keeping the change set coherent"
            ),
            FocusLane(
                role = "polish-focused implementer",
                focus = "developer ergonomics, documentation, and finish quality"
            )
        )
        return lanes[index % lanes.size]
    }

    private data class FocusLane(
        val role: String,
        val focus: String
    )

    private companion object {
        val CHECKLIST_PATTERN = Regex("""(?:[-*]|\d+[.)])\s+(.+)""")
        val SENTENCE_BOUNDARY = Regex("""(?:\r?\n)+|(?<=[.!?])\s+""")
    }
}
