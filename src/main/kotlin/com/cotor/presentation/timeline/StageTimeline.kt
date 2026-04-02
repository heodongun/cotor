package com.cotor.presentation.timeline

/**
 * File overview for StageTimelineState.
 *
 * This file belongs to the shared implementation area for the product runtime.
 * It groups declarations around stage timeline so readers can find the owning runtime area quickly.
 * Read here first when tracing behavior that flows through this part of the codebase.
 */

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
