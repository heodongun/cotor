package com.cotor.domain.planning

import com.cotor.app.AgentAssignmentPlan
import com.cotor.app.TaskExecutionPlan
import com.cotor.app.TaskSubtask

/**
 * Builds a deterministic execution plan so desktop-created tasks can move from a
 * raw goal into per-agent assignments without needing an extra planning service.
 *
 * Company-mode orchestration uses roster-aware participants instead of raw agent names
 * so multiple agents can share the same CLI while still receiving separate assignments.
 */
class GoalDrivenTaskPlanner {

    data class PlanningParticipant(
        val participantId: String,
        val agentName: String,
        val title: String,
        val roleSummary: String,
        val specialties: List<String> = emptyList(),
        val collaborationInstructions: String? = null,
        val preferredCollaborators: List<String> = emptyList(),
        val memoryNotes: String? = null,
        val capabilities: List<String> = emptyList(),
        val mergeAuthority: Boolean = false
    )

    fun buildPlan(
        title: String?,
        prompt: String,
        agents: List<String>
    ): TaskExecutionPlan {
        val participants = agents.mapIndexed { index, agentName ->
            PlanningParticipant(
                participantId = "agent-$index-${agentName.trim().lowercase()}",
                agentName = agentName,
                title = agentName,
                roleSummary = "general execution"
            )
        }
        return buildPlanForParticipants(title, prompt, participants)
    }

    fun buildPlanForParticipants(
        title: String?,
        prompt: String,
        participants: List<PlanningParticipant>
    ): TaskExecutionPlan {
        val normalizedPrompt = prompt.trim()
        val goalSummary = title?.trim()?.takeIf { it.isNotBlank() }
            ?: summarizeGoal(normalizedPrompt)
        val normalizedParticipants = participants
            .map { participant ->
                participant.copy(
                    agentName = participant.agentName.trim().lowercase(),
                    title = participant.title.trim().ifEmpty { participant.agentName },
                    roleSummary = participant.roleSummary.trim().ifEmpty { "general execution" }
                )
            }
            .filter { it.agentName.isNotBlank() }
        val extractedWorkItems = extractWorkItems(normalizedPrompt)
        val workItems = when {
            extractedWorkItems.isNotEmpty() -> extractedWorkItems
            else -> defaultWorkItems(goalSummary)
        }
        val sharedChecklist = listOf(
            "Keep the branch scoped to the assigned focus while still delivering a usable end-to-end result.",
            "Validate the changed behavior before finishing and call out any residual risk."
        )
        if (normalizedParticipants.isEmpty()) {
            return TaskExecutionPlan(
                goalSummary = goalSummary,
                decompositionSource = "goal-template",
                sharedChecklist = sharedChecklist,
                assignments = emptyList()
            )
        }

        val chief = selectChief(normalizedParticipants)
        val reviewers = normalizedParticipants.filter { participant ->
            participant.participantId != chief?.participantId && isReviewer(participant)
        }
        val executionParticipants = selectExecutionParticipants(normalizedParticipants, chief, reviewers)

        val assignments = mutableListOf<AgentAssignmentPlan>()
        val executionAssignments = buildExecutionAssignments(
            executionParticipants = executionParticipants,
            workItems = workItems,
            goalSummary = goalSummary,
            originalPrompt = normalizedPrompt,
            sharedChecklist = sharedChecklist
        )
        assignments += executionAssignments

        val reviewAssignments = buildReviewAssignments(
            reviewers = reviewers,
            executionAssignments = executionAssignments,
            goalSummary = goalSummary,
            originalPrompt = normalizedPrompt,
            sharedChecklist = sharedChecklist
        )
        assignments += reviewAssignments

        val approvalAssignment = buildApprovalAssignment(
            chief = chief,
            executionAssignments = executionAssignments,
            reviewAssignments = reviewAssignments,
            goalSummary = goalSummary,
            originalPrompt = normalizedPrompt,
            sharedChecklist = sharedChecklist
        )
        if (approvalAssignment != null) {
            assignments += approvalAssignment
        }

        return TaskExecutionPlan(
            goalSummary = goalSummary,
            decompositionSource = when {
                extractedWorkItems.isNotEmpty() -> "prompt-checklist"
                normalizedParticipants.size > 1 -> "roster-aware"
                else -> "goal-template"
            },
            sharedChecklist = sharedChecklist,
            assignments = assignments
        )
    }

    private fun buildExecutionAssignments(
        executionParticipants: List<PlanningParticipant>,
        workItems: List<String>,
        goalSummary: String,
        originalPrompt: String,
        sharedChecklist: List<String>
    ): List<AgentAssignmentPlan> {
        if (executionParticipants.isEmpty()) {
            return emptyList()
        }
        val groupedItems = executionParticipants.associateWith { mutableListOf<String>() }
        workItems.forEachIndexed { index, item ->
            groupedItems[executionParticipants[index % executionParticipants.size]]?.add(item)
        }
        return executionParticipants.map { participant ->
            val ownedItems = groupedItems[participant].orEmpty().ifEmpty {
                mutableListOf(fallbackWorkItem(goalSummary, participantFocus(participant)))
            }
            val subtasks = buildSubtasks(participant, ownedItems, participantFocus(participant))
            AgentAssignmentPlan(
                participantId = participant.participantId,
                agentName = participant.agentName,
                role = participant.title,
                phase = "execution",
                focus = participantFocus(participant),
                subtasks = subtasks,
                assignedPrompt = buildAssignedPrompt(
                    participant = participant,
                    phase = "execution",
                    goalSummary = goalSummary,
                    originalPrompt = originalPrompt,
                    subtasks = subtasks,
                    sharedChecklist = sharedChecklist
                )
            )
        }
    }

    private fun buildReviewAssignments(
        reviewers: List<PlanningParticipant>,
        executionAssignments: List<AgentAssignmentPlan>,
        goalSummary: String,
        originalPrompt: String,
        sharedChecklist: List<String>
    ): List<AgentAssignmentPlan> {
        if (reviewers.isEmpty() || executionAssignments.isEmpty()) {
            return emptyList()
        }
        val groupedTargets = reviewers.associateWith { mutableListOf<String>() }
        executionAssignments.forEachIndexed { index, assignment ->
            groupedTargets[reviewers[index % reviewers.size]]?.add(assignment.role)
        }
        return reviewers.map { reviewer ->
            val targets = groupedTargets[reviewer].orEmpty()
            val subtasks = listOf(
                TaskSubtask(
                    id = "${reviewer.participantId}-review-1",
                    title = "Review completed implementation work",
                    details = "Inspect the delivered branches for: ${targets.joinToString(", ")}."
                ),
                TaskSubtask(
                    id = "${reviewer.participantId}-review-2",
                    title = "Summarize risks and remaining gaps",
                    details = "Report regressions, missing checks, and any follow-up work before merge."
                )
            )
            AgentAssignmentPlan(
                participantId = reviewer.participantId,
                agentName = reviewer.agentName,
                role = reviewer.title,
                phase = "review",
                focus = "quality assurance, verification, and risk triage",
                subtasks = subtasks,
                assignedPrompt = buildAssignedPrompt(
                    participant = reviewer,
                    phase = "review",
                    goalSummary = goalSummary,
                    originalPrompt = originalPrompt,
                    subtasks = subtasks,
                    sharedChecklist = sharedChecklist
                )
            )
        }
    }

    private fun buildApprovalAssignment(
        chief: PlanningParticipant?,
        executionAssignments: List<AgentAssignmentPlan>,
        reviewAssignments: List<AgentAssignmentPlan>,
        goalSummary: String,
        originalPrompt: String,
        sharedChecklist: List<String>
    ): AgentAssignmentPlan? {
        if (chief == null || executionAssignments.isEmpty()) {
            return null
        }
        if (reviewAssignments.isEmpty() && executionAssignments.singleOrNull()?.participantId == chief.participantId) {
            return null
        }
        val dependencies = if (reviewAssignments.isNotEmpty()) {
            reviewAssignments.map { it.role }
        } else {
            executionAssignments.map { it.role }
        }
        val subtasks = listOf(
            TaskSubtask(
                id = "${chief.participantId}-approval-1",
                title = "Review downstream company status",
                details = "Inspect completed work from: ${dependencies.joinToString(", ")}."
            ),
            TaskSubtask(
                id = "${chief.participantId}-approval-2",
                title = "Decide merge readiness and next actions",
                details = "Approve completion, request follow-up work, or escalate blocked issues."
            )
        )
        return AgentAssignmentPlan(
            participantId = chief.participantId,
            agentName = chief.agentName,
            role = chief.title,
            phase = "approval",
            focus = "company-wide coordination, final review, and merge decisions",
            subtasks = subtasks,
            assignedPrompt = buildAssignedPrompt(
                participant = chief,
                phase = "approval",
                goalSummary = goalSummary,
                originalPrompt = originalPrompt,
                subtasks = subtasks,
                sharedChecklist = sharedChecklist
            )
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
        return if (sentenceItems.size >= 2) sentenceItems.take(8) else emptyList()
    }

    private fun defaultWorkItems(goalSummary: String): List<String> = listOf(
        "Clarify the success conditions and constraints for \"$goalSummary\"",
        "Implement the repository changes required to deliver \"$goalSummary\"",
        "Validate the outcome for \"$goalSummary\" and capture any remaining risks"
    )

    private fun fallbackWorkItem(goalSummary: String, focus: String): String =
        "Advance \"$goalSummary\" with extra emphasis on $focus"

    private fun buildSubtasks(
        participant: PlanningParticipant,
        workItems: List<String>,
        focus: String
    ): List<TaskSubtask> {
        val primarySubtasks = workItems.mapIndexed { index, item ->
            TaskSubtask(
                id = "${participant.participantId}-${index + 1}",
                title = toSubtaskTitle(item),
                details = "Own this slice in your branch with extra attention to $focus."
            )
        }
        val validationSubtask = TaskSubtask(
            id = "${participant.participantId}-${primarySubtasks.size + 1}",
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
        participant: PlanningParticipant,
        phase: String,
        goalSummary: String,
        originalPrompt: String,
        subtasks: List<TaskSubtask>,
        sharedChecklist: List<String>
    ): String = buildString {
        appendLine("Goal-driven assignment for ${participant.title} (${participant.agentName})")
        appendLine()
        appendLine("Phase:")
        appendLine(phase)
        appendLine()
        appendLine("Goal summary:")
        appendLine(goalSummary)
        appendLine()
        appendLine("Role definition:")
        appendLine(participant.roleSummary)
        appendLine()
        if (participant.specialties.isNotEmpty()) {
            appendLine("Specialties:")
            appendLine(participant.specialties.joinToString(", "))
            appendLine()
        }
        if (participant.preferredCollaborators.isNotEmpty() || !participant.collaborationInstructions.isNullOrBlank()) {
            appendLine("A2A collaboration contract:")
            if (participant.preferredCollaborators.isNotEmpty()) {
                appendLine("Preferred collaborators: ${participant.preferredCollaborators.joinToString(", ")}")
            }
            participant.collaborationInstructions?.takeIf { it.isNotBlank() }?.let { instructions ->
                appendLine("Handoff guidance: $instructions")
            }
            appendLine()
        }
        participant.memoryNotes?.takeIf { it.isNotBlank() }?.let { memory ->
            appendLine("Persistent agent memory:")
            appendLine(memory)
            appendLine()
        }
        appendLine("Primary focus:")
        appendLine(participantFocus(participant))
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

    private fun selectChief(participants: List<PlanningParticipant>): PlanningParticipant? =
        participants.firstOrNull { it.mergeAuthority } ?: participants.firstOrNull { isChief(it) }

    private fun selectExecutionParticipants(
        participants: List<PlanningParticipant>,
        chief: PlanningParticipant?,
        reviewers: List<PlanningParticipant>
    ): List<PlanningParticipant> {
        val reviewerIds = reviewers.map { it.participantId }.toSet()
        val chiefId = chief?.participantId
        val explicitExecutors = participants.filter { participant ->
            participant.participantId != chiefId &&
                participant.participantId !in reviewerIds &&
                isExecutionParticipant(participant)
        }
        if (explicitExecutors.isNotEmpty()) {
            return explicitExecutors
        }
        val fallbackExecutors = participants.filter {
            it.participantId != chiefId && it.participantId !in reviewerIds
        }
        return if (fallbackExecutors.isNotEmpty()) fallbackExecutors else listOfNotNull(chief).ifEmpty { participants }
    }

    private fun isChief(participant: PlanningParticipant): Boolean {
        val summary = participantRoutingDescriptor(participant)
        val title = participant.title.lowercase()
        return participant.mergeAuthority ||
            summary.contains("ceo") ||
            summary.contains("lead") ||
            summary.contains("chief") ||
            summary.contains("head") ||
            title.contains("ceo") ||
            title.contains("lead") ||
            title.contains("chief")
    }

    private fun isReviewer(participant: PlanningParticipant): Boolean {
        val tags = participant.capabilities.map { it.lowercase() }
        val summary = participantRoutingDescriptor(participant)
        return tags.any { it in REVIEW_TAGS } ||
            REVIEW_TAGS.any { summary.contains(it) }
    }

    private fun isExecutionParticipant(participant: PlanningParticipant): Boolean {
        val tags = participant.capabilities.map { it.lowercase() }
        val summary = participantRoutingDescriptor(participant)
        return tags.any { it in EXECUTION_TAGS } ||
            EXECUTION_TAGS.any { summary.contains(it) } ||
            (!isReviewer(participant) && !isChief(participant))
    }

    private fun participantRoutingDescriptor(participant: PlanningParticipant): String =
        buildString {
            append(participant.roleSummary)
            if (participant.specialties.isNotEmpty()) {
                append(' ')
                append(participant.specialties.joinToString(" "))
            }
        }.lowercase()

    private fun participantFocus(participant: PlanningParticipant): String {
        val tags = participant.capabilities.map { it.lowercase() }
        return when {
            "planning" in tags || "strategy" in tags -> "planning, prioritization, and company orchestration"
            tags.any { it in listOf("qa", "review", "verification", "test") } -> "quality assurance, regression detection, and release safety"
            "design" in tags -> "design fidelity, UX clarity, and presentation quality"
            "research" in tags || "product" in tags -> "requirements clarity, product scope, and user-facing outcomes"
            "docs" in tags || "documentation" in tags -> "documentation accuracy, onboarding clarity, and operator guidance"
            "infra" in tags || "ops" in tags || "release" in tags -> "delivery infrastructure, reliability, and operational readiness"
            "frontend" in tags -> "front-end behavior, interaction quality, and visual completeness"
            "backend" in tags -> "backend behavior, correctness, and integration reliability"
            else -> "implementation depth, integration quality, and completing the assigned slice"
        }
    }

    private companion object {
        val CHECKLIST_PATTERN = Regex("""(?:[-*]|\d+[.)])\s+(.+)""")
        val SENTENCE_BOUNDARY = Regex("""(?:\r?\n)+|(?<=[.!?])\s+""")
        val REVIEW_TAGS = setOf("qa", "review", "verification", "test", "testing")
        val EXECUTION_TAGS = setOf(
            "implementation", "integration", "backend", "frontend", "design", "product",
            "research", "docs", "documentation", "infra", "ops", "release", "architecture"
        )
    }
}
