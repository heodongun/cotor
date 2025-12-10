package com.cotor.presentation.web.stream

import kotlinx.serialization.Serializable

@Serializable
data class RealtimeEvent(
    val type: String,
    val stageId: String,
    val status: String,
    val message: String? = null,
    val durationMs: Long? = null
)
