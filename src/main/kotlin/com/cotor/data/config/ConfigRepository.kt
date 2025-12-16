package com.cotor.data.config

import com.cotor.model.ConfigurationException
import com.cotor.model.CotorConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    override suspend fun loadConfig(path: Path): CotorConfig = withContext(Dispatchers.IO) {
        val baseConfig = parseConfig(path)
        val cotorDir = path.parent?.resolve(".cotor")

        if (cotorDir == null || !cotorDir.isDirectory()) {
            return@withContext baseConfig
        }

        val overrideFiles = Files.walk(cotorDir)
            .filter { it.isRegularFile() && it.extension.lowercase() in listOf("yaml", "yml") }
            .sorted()
            .toList()

        overrideFiles.fold(baseConfig) { acc, overrideFile ->
            val overrideConfig = parseConfig(overrideFile)
            mergeConfigs(acc, overrideConfig)
        }
    }

    private fun parseConfig(path: Path): CotorConfig {
        if (!path.exists()) {
            throw ConfigurationException("Configuration file not found: $path")
        }
        val content = path.readText()
        return when (path.extension.lowercase()) {
            "yaml", "yml" -> yamlParser.parse(content, path.toString())
            "json" -> jsonParser.parse(content)
            else -> throw ConfigurationException("Unsupported config format: ${path.extension}")
        }
    }

    private fun mergeConfigs(base: CotorConfig, override: CotorConfig): CotorConfig {
        val mergedAgents = (base.agents + override.agents)
            .groupBy { it.name }
            .map { (_, agents) -> agents.last() }

        val mergedPipelines = (base.pipelines + override.pipelines)
            .groupBy { it.name }
            .map { (_, pipelines) -> pipelines.last() }

        val defaultConfig = CotorConfig()

        return base.copy(
            version = if (override.version != defaultConfig.version) override.version else base.version,
            agents = mergedAgents,
            pipelines = mergedPipelines,
            security = base.security.copy(
                useWhitelist = if (override.security.useWhitelist != defaultConfig.security.useWhitelist) override.security.useWhitelist else base.security.useWhitelist,
                // For collection types: treat empty override as explicit empty when base has items
                allowedExecutables = mergeCollection(
                    base.security.allowedExecutables,
                    override.security.allowedExecutables,
                    defaultConfig.security.allowedExecutables
                ),
                allowedDirectories = mergeCollection(
                    base.security.allowedDirectories,
                    override.security.allowedDirectories,
                    defaultConfig.security.allowedDirectories
                ),
                maxCommandLength = if (override.security.maxCommandLength != defaultConfig.security.maxCommandLength) override.security.maxCommandLength else base.security.maxCommandLength,
                enablePathValidation = if (override.security.enablePathValidation != defaultConfig.security.enablePathValidation) override.security.enablePathValidation else base.security.enablePathValidation
            ),
            logging = base.logging.copy(
                level = if (override.logging.level != defaultConfig.logging.level) override.logging.level else base.logging.level,
                file = if (override.logging.file != defaultConfig.logging.file) override.logging.file else base.logging.file,
                maxFileSize = if (override.logging.maxFileSize != defaultConfig.logging.maxFileSize) override.logging.maxFileSize else base.logging.maxFileSize,
                maxHistory = if (override.logging.maxHistory != defaultConfig.logging.maxHistory) override.logging.maxHistory else base.logging.maxHistory,
                format = if (override.logging.format != defaultConfig.logging.format) override.logging.format else base.logging.format
            ),
            performance = base.performance.copy(
                maxConcurrentAgents = if (override.performance.maxConcurrentAgents != defaultConfig.performance.maxConcurrentAgents) override.performance.maxConcurrentAgents else base.performance.maxConcurrentAgents,
                coroutinePoolSize = if (override.performance.coroutinePoolSize != defaultConfig.performance.coroutinePoolSize) override.performance.coroutinePoolSize else base.performance.coroutinePoolSize,
                memoryThresholdMB = if (override.performance.memoryThresholdMB != defaultConfig.performance.memoryThresholdMB) override.performance.memoryThresholdMB else base.performance.memoryThresholdMB
            )
        )
    }

    /**
     * Merge collections with proper handling of explicit empty overrides.
     * If override is empty but base has items, treat as explicit empty override.
     * If override has items, use override.
     * If both are empty (default), use base (which is also empty).
     */
    private fun <T, C : Collection<T>> mergeCollection(base: C, override: C, default: C): C {
        return when {
            override.isNotEmpty() -> override  // Override has items, use it
            override.isEmpty() && base.isNotEmpty() -> override  // Explicit empty override
            else -> base  // Both empty or override matches default
        }
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
