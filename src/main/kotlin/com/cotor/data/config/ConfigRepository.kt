package com.cotor.data.config

import com.cotor.model.ConfigurationException
import com.cotor.model.CotorConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.streams.toList

interface ConfigRepository {
    suspend fun loadConfig(path: Path): CotorConfig
    suspend fun saveConfig(config: CotorConfig, path: Path)
}

class FileConfigRepository(
    private val yamlParser: YamlParser,
    private val jsonParser: JsonParser
) : ConfigRepository {

    private data class ParsedConfig(
        val config: CotorConfig,
        val explicitCollections: Set<String>,
    )

    override suspend fun loadConfig(path: Path): CotorConfig = withContext(Dispatchers.IO) {
        val baseConfig = parseConfig(path)
        val cotorDir = path.parent?.resolve(".cotor")

        if (cotorDir == null || !cotorDir.isDirectory()) {
            return@withContext baseConfig.config
        }

        val overrideFiles = Files.walk(cotorDir)
            .filter { it.isRegularFile() && it.extension.lowercase() in listOf("yaml", "yml") }
            .sorted()
            .toList()

        overrideFiles.fold(baseConfig.config) { acc, overrideFile ->
            val overrideConfig = parseConfig(overrideFile)
            mergeConfigs(acc, overrideConfig)
        }
    }

    private fun parseConfig(path: Path): ParsedConfig {
        if (!path.exists()) {
            throw ConfigurationException("Configuration file not found: $path")
        }
        val content = path.readText()
        return when (path.extension.lowercase()) {
            "yaml", "yml" -> ParsedConfig(
                config = yamlParser.parse(content, path.toString()),
                explicitCollections = detectExplicitCollectionsFromYaml(content),
            )
            "json" -> ParsedConfig(
                config = jsonParser.parse(content),
                explicitCollections = detectExplicitCollectionsFromJson(content),
            )
            else -> throw ConfigurationException("Unsupported config format: ${path.extension}")
        }
    }

    private fun mergeConfigs(base: CotorConfig, override: ParsedConfig): CotorConfig {
        val overrideConfig = override.config
        val mergedAgents = (base.agents + overrideConfig.agents)
            .groupBy { it.name }
            .map { (_, agents) -> agents.last() }

        val mergedPipelines = (base.pipelines + overrideConfig.pipelines)
            .groupBy { it.name }
            .map { (_, pipelines) -> pipelines.last() }

        val defaultConfig = CotorConfig()

        return base.copy(
            version = if (overrideConfig.version != defaultConfig.version) overrideConfig.version else base.version,
            agents = mergedAgents,
            pipelines = mergedPipelines,
            security = base.security.copy(
                useWhitelist = if (overrideConfig.security.useWhitelist != defaultConfig.security.useWhitelist) overrideConfig.security.useWhitelist else base.security.useWhitelist,
                // For collection types: treat empty override as explicit empty when base has items
                allowedExecutables = mergeCollection(
                    base.security.allowedExecutables,
                    overrideConfig.security.allowedExecutables,
                    defaultConfig.security.allowedExecutables,
                    "security.allowedExecutables" in override.explicitCollections,
                ),
                allowedDirectories = mergeCollection(
                    base.security.allowedDirectories,
                    overrideConfig.security.allowedDirectories,
                    defaultConfig.security.allowedDirectories,
                    "security.allowedDirectories" in override.explicitCollections,
                ),
                maxCommandLength = if (overrideConfig.security.maxCommandLength != defaultConfig.security.maxCommandLength) overrideConfig.security.maxCommandLength else base.security.maxCommandLength,
                enablePathValidation = if (overrideConfig.security.enablePathValidation != defaultConfig.security.enablePathValidation) overrideConfig.security.enablePathValidation else base.security.enablePathValidation
            ),
            logging = base.logging.copy(
                level = if (overrideConfig.logging.level != defaultConfig.logging.level) overrideConfig.logging.level else base.logging.level,
                file = if (overrideConfig.logging.file != defaultConfig.logging.file) overrideConfig.logging.file else base.logging.file,
                maxFileSize = if (overrideConfig.logging.maxFileSize != defaultConfig.logging.maxFileSize) overrideConfig.logging.maxFileSize else base.logging.maxFileSize,
                maxHistory = if (overrideConfig.logging.maxHistory != defaultConfig.logging.maxHistory) overrideConfig.logging.maxHistory else base.logging.maxHistory,
                format = if (overrideConfig.logging.format != defaultConfig.logging.format) overrideConfig.logging.format else base.logging.format
            ),
            performance = base.performance.copy(
                maxConcurrentAgents = if (overrideConfig.performance.maxConcurrentAgents != defaultConfig.performance.maxConcurrentAgents) overrideConfig.performance.maxConcurrentAgents else base.performance.maxConcurrentAgents,
                coroutinePoolSize = if (overrideConfig.performance.coroutinePoolSize != defaultConfig.performance.coroutinePoolSize) overrideConfig.performance.coroutinePoolSize else base.performance.coroutinePoolSize,
                memoryThresholdMB = if (overrideConfig.performance.memoryThresholdMB != defaultConfig.performance.memoryThresholdMB) overrideConfig.performance.memoryThresholdMB else base.performance.memoryThresholdMB
            )
        )
    }

    /**
     * Merge collections with proper handling of explicit empty overrides.
     * If override is empty but base has items, treat as explicit empty override.
     * If override has items, use override.
     * If both are empty (default), use base (which is also empty).
     */
    private fun <T, C : Collection<T>> mergeCollection(base: C, override: C, default: C, explicitOverride: Boolean): C {
        return when {
            explicitOverride -> override
            override != default -> override
            else -> base
        }
    }

    private fun detectExplicitCollectionsFromYaml(content: String): Set<String> = buildSet {
        if (hasYamlNestedKey(content, "security", "allowedExecutables")) {
            add("security.allowedExecutables")
        }
        if (hasYamlNestedKey(content, "security", "allowedDirectories")) {
            add("security.allowedDirectories")
        }
    }

    private fun detectExplicitCollectionsFromJson(content: String): Set<String> {
        return runCatching {
            val root = Json.parseToJsonElement(content).jsonObject
            val security = root["security"]?.jsonObject ?: return emptySet()

            buildSet {
                if ("allowedExecutables" in security) add("security.allowedExecutables")
                if ("allowedDirectories" in security) add("security.allowedDirectories")
            }
        }.getOrElse { emptySet() }
    }

    private fun hasYamlNestedKey(content: String, parentKey: String, childKey: String): Boolean {
        var parentIndent: Int? = null

        for (line in content.lines()) {
            val withoutComments = line.substringBefore('#').trimEnd()
            if (withoutComments.isBlank()) continue

            val indent = line.takeWhile { it == ' ' || it == '\t' }.length
            val trimmed = withoutComments.trimStart()

            if (parentIndent == null) {
                if (trimmed.matches(Regex("^${Regex.escape(parentKey)}\\s*:\\s*$"))) {
                    parentIndent = indent
                }
                continue
            }

            if (indent <= parentIndent) {
                parentIndent = null
                if (trimmed.matches(Regex("^${Regex.escape(parentKey)}\\s*:\\s*$"))) {
                    parentIndent = indent
                }
                continue
            }

            if (trimmed.matches(Regex("^${Regex.escape(childKey)}\\s*:.*$"))) {
                return true
            }
        }

        return false
    }

    override suspend fun saveConfig(config: CotorConfig, path: Path) = withContext(Dispatchers.IO) {
        val content = when (path.extension.lowercase()) {
            "yaml", "yml" -> yamlParser.serialize(config)
            "json" -> jsonParser.serialize(config)
            else -> throw ConfigurationException("Unsupported config format: ${path.extension}")
        }
        path.parent?.let { Files.createDirectories(it) }
        path.writeText(content)
    }
}
