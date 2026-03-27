package com.cotor.model

/**
 * Shared Codex CLI defaults and compatibility helpers.
 *
 * Keep legacy aliases here so desktop/company automation, interactive sessions,
 * and CLI preset generation stay aligned when OpenAI retires older model ids.
 */
object CodexDefaults {
    const val DEFAULT_MODEL = "gpt-5.4"
    private val requestedModelFailureRegex = Regex(
        "requested model '([^']+)' does not exist",
        RegexOption.IGNORE_CASE
    )

    private val legacyModelAliases = mapOf(
        "gpt-5.3-codex-spark" to DEFAULT_MODEL
    )

    fun normalizeModel(model: String?): String? {
        val trimmed = model?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return legacyModelAliases[trimmed.lowercase()] ?: trimmed
    }

    fun isRecoverableModelSelectionFailure(message: String): Boolean {
        val normalized = message.lowercase()
        return normalized.contains("model_not_found") ||
            (
                normalized.contains("requested model") &&
                    normalized.contains("does not exist")
                )
    }

    fun isRetiredModelAliasFailure(message: String): Boolean {
        val requested = requestedModelFailureRegex.find(message)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return false
        return normalizeModel(requested) != requested
    }
}
