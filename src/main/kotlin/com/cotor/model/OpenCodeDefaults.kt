package com.cotor.model

/**
 * Shared OpenCode CLI defaults and compatibility helpers.
 *
 * Keep this aligned with the model the user wants as the default for
 * company-created agents so execution costs stay predictable.
 */
object OpenCodeDefaults {
    const val DEFAULT_MODEL = "opencode/qwen3.6-plus-free"

    fun normalizeModel(model: String?): String? {
        val trimmed = model?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return trimmed
    }
}
