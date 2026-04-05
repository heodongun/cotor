package com.cotor.app.runtime

class WorkQueue {
    private val commands = ArrayDeque<RuntimeCommand>()

    fun enqueue(command: RuntimeCommand) {
        commands += command
    }

    fun isEmpty(): Boolean = commands.isEmpty()

    suspend fun drain(executor: suspend (RuntimeCommand) -> Unit) {
        while (commands.isNotEmpty()) {
            executor(commands.removeFirst())
        }
    }
}
