package com.cotor.data.http

import java.net.http.HttpClient
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Creates JDK HTTP clients whose helper threads cannot keep CLI/test JVMs alive.
 */
object CotorHttpClients {
    private val counter = AtomicInteger()
    private val clientsByConnectTimeout = ConcurrentHashMap<Duration, HttpClient>()
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "cotor-http-${counter.incrementAndGet()}").apply {
            isDaemon = true
        }
    }
    private val defaultClient: HttpClient by lazy { newBuilder().build() }

    fun newBuilder(): HttpClient.Builder = HttpClient.newBuilder().executor(executor)

    fun newClient(): HttpClient = defaultClient

    fun client(connectTimeout: Duration): HttpClient =
        clientsByConnectTimeout.computeIfAbsent(connectTimeout) {
            newBuilder()
                .connectTimeout(it)
                .build()
        }
}
