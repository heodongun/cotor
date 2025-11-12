package com.cotor.event

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

/**
 * Interface for event bus
 */
interface EventBus {
    /**
     * Emit an event
     * @param event Event to emit
     */
    suspend fun emit(event: CotorEvent)

    /**
     * Subscribe to event type
     * @param eventType Type of event to subscribe to
     * @param handler Handler function for event
     */
    fun subscribe(eventType: KClass<out CotorEvent>, handler: suspend (CotorEvent) -> Unit)

    /**
     * Unsubscribe from event type
     * @param eventType Type of event to unsubscribe from
     */
    fun unsubscribe(eventType: KClass<out CotorEvent>)
}

/**
 * Coroutine-based event bus implementation
 */
class CoroutineEventBus : EventBus {
    private val subscribers = ConcurrentHashMap<KClass<out CotorEvent>, MutableList<suspend (CotorEvent) -> Unit>>()
    private val eventChannel = Channel<CotorEvent>(Channel.UNLIMITED)
    private val scope = CoroutineScope(Dispatchers.Default)

    init {
        // Start event processing coroutine
        scope.launch {
            for (event in eventChannel) {
                processEvent(event)
            }
        }
    }

    override suspend fun emit(event: CotorEvent) {
        eventChannel.send(event)
    }

    override fun subscribe(eventType: KClass<out CotorEvent>, handler: suspend (CotorEvent) -> Unit) {
        subscribers.computeIfAbsent(eventType) { mutableListOf() }
            .add(handler)
    }

    override fun unsubscribe(eventType: KClass<out CotorEvent>) {
        subscribers.remove(eventType)
    }

    private suspend fun processEvent(event: CotorEvent) {
        val handlers = subscribers[event::class] ?: return

        handlers.forEach { handler ->
            scope.launch {
                try {
                    handler(event)
                } catch (e: Exception) {
                    // Log error but continue processing other handlers
                    println("Error processing event: ${e.message}")
                }
            }
        }
    }
}
