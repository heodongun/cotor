package com.cotor.app.runtime

sealed interface RuntimeCommand {
    data class EnsurePlanningIssue(val goalId: String) : RuntimeCommand
    data class StartIssue(val issueId: String) : RuntimeCommand
}
