package com.cotor.verification

import com.cotor.app.defaultDesktopAppHome
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText

class VerificationStore(
    private val appHomeProvider: () -> Path = { defaultDesktopAppHome() }
) {
    private val lock = Any()
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun dir(): Path = appHomeProvider().resolve("verification")

    private fun outcomeFile(issueId: String): Path = dir().resolve("$issueId.outcome.json")

    private fun observationFile(issueId: String): Path = dir().resolve("$issueId.observations.json")

    fun saveOutcome(outcome: VerificationOutcome) {
        synchronized(lock) {
            val root = dir()
            root.createDirectories()
            outcomeFile(outcome.issueId).writeText(json.encodeToString(VerificationOutcome.serializer(), outcome))
        }
    }

    fun loadOutcome(issueId: String): VerificationOutcome? {
        return synchronized(lock) {
            val path = outcomeFile(issueId)
            if (!path.exists()) return@synchronized null
            runCatching { json.decodeFromString(VerificationOutcome.serializer(), path.readText()) }.getOrNull()
        }
    }

    fun appendObservation(issueId: String, observation: VerificationObservation) {
        synchronized(lock) {
            val current = loadObservations(issueId)
            val updated = (current + observation).takeLast(50)
            val root = dir()
            root.createDirectories()
            observationFile(issueId).writeText(
                json.encodeToString(ListSerializer(VerificationObservation.serializer()), updated)
            )
        }
    }

    fun loadObservations(issueId: String): List<VerificationObservation> {
        return synchronized(lock) {
            val path = observationFile(issueId)
            if (!path.exists()) return@synchronized emptyList()
            runCatching {
                json.decodeFromString(ListSerializer(VerificationObservation.serializer()), path.readText())
            }.getOrDefault(emptyList())
        }
    }

    fun listOutcomes(): List<VerificationOutcome> {
        val root = dir()
        if (!root.exists()) return emptyList()
        return root.listDirectoryEntries("*.outcome.json")
            .mapNotNull { file ->
                runCatching { json.decodeFromString(VerificationOutcome.serializer(), file.readText()) }.getOrNull()
            }
            .sortedByDescending { it.verifiedAt }
    }
}
