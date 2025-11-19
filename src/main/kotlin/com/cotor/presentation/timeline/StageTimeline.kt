package com.cotor.presentation.timeline

import java.time.Instant

enum class StageTimelineState {
    STARTED,
    COMPLETED,
    FAILED
}

data class StageTimelineEntry(
    val stageId: String,
    val state: StageTimelineState,
    val timestamp: Instant = Instant.now(),
    val message: String,
    val durationMs: Long? = null,
    val outputPreview: String? = null
)
