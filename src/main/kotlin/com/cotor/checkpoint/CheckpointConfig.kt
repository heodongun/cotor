package com.cotor.checkpoint

import kotlinx.serialization.Serializable

@Serializable
data class CheckpointConfig(
    val maxCount: Int? = null,
    val maxAgeDays: Int? = null,
)

@Serializable
data class CheckpointRetentionConfig(
    val maxCount: Int? = null,
    val maxAgeDays: Int? = null,
)
