package com.cotor.policy

import com.cotor.app.defaultDesktopAppHome
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText

class PolicyStore(
    private val appHomeProvider: () -> Path = { defaultDesktopAppHome() }
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun policiesDir(): Path = appHomeProvider().resolve("policies")

    private fun auditFile(): Path = policiesDir().resolve("audit.json")

    fun listDocuments(): List<PolicyDocument> {
        val dir = policiesDir()
        if (!dir.exists()) {
            return emptyList()
        }
        return dir.listDirectoryEntries("*.policy.json")
            .sortedBy { it.fileName.toString() }
            .mapNotNull { path ->
                runCatching { json.decodeFromString(PolicyDocument.serializer(), path.readText()) }.getOrNull()
            }
    }

    fun loadDocument(path: Path): PolicyDocument =
        json.decodeFromString(PolicyDocument.serializer(), path.readText())

    fun saveDocument(document: PolicyDocument) {
        val dir = policiesDir()
        dir.createDirectories()
        dir.resolve("${document.name}.policy.json").writeText(
            json.encodeToString(PolicyDocument.serializer(), document)
        )
    }

    fun appendDecision(decision: PolicyDecision) {
        val current = loadAudit()
        val updated = current.copy(
            decisions = (current.decisions + decision).takeLast(500),
            updatedAt = System.currentTimeMillis()
        )
        val dir = policiesDir()
        dir.createDirectories()
        auditFile().writeText(json.encodeToString(PolicyAuditLog.serializer(), updated))
    }

    fun loadAudit(): PolicyAuditLog {
        val file = auditFile()
        if (!file.exists()) {
            return PolicyAuditLog()
        }
        return runCatching {
            json.decodeFromString(PolicyAuditLog.serializer(), file.readText())
        }.getOrDefault(PolicyAuditLog())
    }

    fun defaultPermissiveProfile(): PolicyDocument =
        PolicyDocument(
            name = "default",
            defaultEffect = PolicyEffect.ALLOW,
            rules = emptyList()
        )
}

