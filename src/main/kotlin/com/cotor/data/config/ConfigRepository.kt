package com.cotor.data.config

import com.cotor.model.ConfigurationException
import com.cotor.model.CotorConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.io.path.createDirectories

/**
 * Repository interface for configuration management
 */
interface ConfigRepository {
    /**
     * Load configuration from file
     * @param path Path to configuration file
     * @return Parsed CotorConfig
     */
    suspend fun loadConfig(path: Path): CotorConfig

    /**
     * Save configuration to file
     * @param config CotorConfig to save
     * @param path Path to save configuration
     */
    suspend fun saveConfig(config: CotorConfig, path: Path)
}

/**
 * File-based configuration repository implementation
 */
class FileConfigRepository(
    private val yamlParser: YamlParser,
    private val jsonParser: JsonParser
) : ConfigRepository {

    override suspend fun loadConfig(path: Path): CotorConfig = withContext(Dispatchers.IO) {
        try {
            if (!path.exists()) {
                throw ConfigurationException("Configuration file not found: $path")
            }
            val content = path.readText()

            when (path.extension.lowercase()) {
                "yaml", "yml" -> yamlParser.parse(content, path.toString())
                "json" -> jsonParser.parse(content)
                else -> throw ConfigurationException("Unsupported config format: ${path.extension}")
            }
        } catch (e: ConfigurationException) {
            throw e
        } catch (e: Exception) {
            throw ConfigurationException("Failed to load config from $path", e)
        }
    }

    override suspend fun saveConfig(config: CotorConfig, path: Path) = withContext(Dispatchers.IO) {
        try {
            val content = when (path.extension.lowercase()) {
                "yaml", "yml" -> yamlParser.serialize(config)
                "json" -> jsonParser.serialize(config)
                else -> throw ConfigurationException("Unsupported config format: ${path.extension}")
            }

            path.parent?.let { parent ->
                if (!parent.exists()) {
                    parent.createDirectories()
                }
            }
            path.writeText(content)
        } catch (e: ConfigurationException) {
            throw e
        } catch (e: Exception) {
            throw ConfigurationException("Failed to save config to $path", e)
        }
    }
}
