package com.cotor.app.persistence

/**
 * Minimal persistence abstraction for app/runtime state.
 *
 * The current implementation remains file-backed, but tests and future runtime
 * refactors can depend on this interface instead of a concrete state store.
 */
interface StateRepository<T> {
    suspend fun load(): T
    suspend fun save(state: T)
}
