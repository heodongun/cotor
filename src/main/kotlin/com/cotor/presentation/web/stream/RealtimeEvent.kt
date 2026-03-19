package com.cotor.presentation.web.stream

/**
 * File overview for RealtimeEvent.
 *
 * This file belongs to the web presentation layer for the browser-based editor and runtime surface.
 * It groups declarations around realtime event so readers can find the owning runtime area quickly.
 * Read here first when tracing behavior that flows through this part of the codebase.
 */


import kotlinx.serialization.Serializable

@Serializable
data class RealtimeEvent(
    val type: String,
    val stageId: String,
    val status: String,
    val message: String? = null,
    val durationMs: Long? = null
)
