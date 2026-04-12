package com.cotor.runtime.durable

import com.cotor.model.PipelineContext

/**
 * Feature gate for the durable runtime foundation.
 *
 * The automatic journal/checkpoint graph should stay opt-in until the storage and replay
 * semantics have proven safe for existing users, but explicit resume/fork flows may still
 * force-enable it through pipeline metadata.
 */
object DurableRuntimeFlags {
    private const val SYSTEM_PROPERTY = "cotor.experimental.durableRuntimeV2"
    private const val ENVIRONMENT_VARIABLE = "COTOR_EXPERIMENTAL_DURABLE_RUNTIME_V2"
    private const val PIPELINE_CONTEXT_KEY = "durableRuntimeV2"

    fun isEnabled(): Boolean =
        System.getProperty(SYSTEM_PROPERTY)?.equals("true", ignoreCase = true) == true ||
            System.getenv(ENVIRONMENT_VARIABLE)?.let { it == "1" || it.equals("true", ignoreCase = true) } == true

    fun isEnabled(context: PipelineContext?): Boolean =
        context?.metadata?.get(PIPELINE_CONTEXT_KEY)?.toString()?.equals("true", ignoreCase = true) == true ||
            isEnabled()

    fun enable(context: PipelineContext) {
        context.metadata[PIPELINE_CONTEXT_KEY] = "true"
    }
}
