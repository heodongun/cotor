package com.cotor.runtime.durable

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

data class DurableRuntimeContext(
    val runId: String,
    val replayMode: ReplayMode,
    val sourceRunId: String? = null,
    val sourceCheckpointId: String? = null,
    val configPath: String? = null
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<DurableRuntimeContext>
}

suspend fun currentDurableRuntimeContext(): DurableRuntimeContext? = coroutineContext[DurableRuntimeContext]
