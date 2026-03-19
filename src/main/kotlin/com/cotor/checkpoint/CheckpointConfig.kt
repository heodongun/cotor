package com.cotor.checkpoint

/**
 * File overview for CheckpointConfig.
 *
 * This file belongs to the checkpointing layer that persists intermediate execution state.
 * It groups declarations around checkpoint config so readers can find the owning runtime area quickly.
 * Read here first when tracing behavior that flows through this part of the codebase.
 */


import kotlinx.serialization.Serializable

@Serializable
data class CheckpointConfig(
    val maxCount: Int? = null,
    val maxAgeDays: Int? = null,
)
