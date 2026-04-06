package com.cotor.app.persistence

/**
 * Minimal persistence abstraction for long-lived desktop/runtime state.
 *
 * The initial implementation keeps DesktopStateStore as the only concrete
 * backend, but this interface makes it possible to move toward SQLite or
 * segmented entity stores without rewriting the runtime orchestration layer.
 */
interface StateRepository<T> {
    suspend fun load(): T
    suspend fun save(state: T)
}
