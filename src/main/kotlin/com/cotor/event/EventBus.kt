package com.cotor.event

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.reflect.KClass

/**
 * Interface for event bus
 */
data class EventSubscription(
    val eventType: KClass<out CotorEvent>,
    val handler: suspend (CotorEvent) -> Unit
)

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
     * @return Subscription that can be disposed
     */
    fun subscribe(eventType: KClass<out CotorEvent>, handler: suspend (CotorEvent) -> Unit): EventSubscription

    /**
     * Unsubscribe from event type
     * @param subscription Subscription to remove
     */
    fun unsubscribe(subscription: EventSubscription)
}

/**
 * Coroutine-based event bus implementation
 */
class CoroutineEventBus : EventBus {
    private val subscribers = ConcurrentHashMap<KClass<out CotorEvent>, MutableList<EventSubscription>>()
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

    override fun subscribe(eventType: KClass<out CotorEvent>, handler: suspend (CotorEvent) -> Unit): EventSubscription {
        val subscription = EventSubscription(eventType, handler)
        subscribers.computeIfAbsent(eventType) { CopyOnWriteArrayList() }
            .add(subscription)
        return subscription
    }

    override fun unsubscribe(subscription: EventSubscription) {
        val handlers = subscribers[subscription.eventType] ?: return
        handlers.remove(subscription)
        if (handlers.isEmpty()) {
            subscribers.remove(subscription.eventType)
        }
    }

    private suspend fun processEvent(event: CotorEvent) {
        val handlers = subscribers[event::class]?.toList() ?: return

        handlers.forEach { subscription ->
            scope.launch {
                try {
                    subscription.handler(event)
                } catch (e: Exception) {
                    // Log error but continue processing other handlers
                    println("Error processing event: ${e.message}")
                }
            }
        }
    }
}
