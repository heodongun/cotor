package com.cotor.data.config

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.cotor.model.CotorConfig
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Parser for YAML configuration files
 */
class YamlParser {
    private val yaml = Yaml(configuration = YamlConfiguration(strictMode = false))

    /**
     * Parse YAML content into CotorConfig
     * @param content YAML string content
     * @return Parsed CotorConfig
     * @throws SerializationException if parsing fails
     */
    fun parse(content: String): CotorConfig {
        return yaml.decodeFromString(CotorConfig.serializer(), content)
    }

    /**
     * Serialize CotorConfig to YAML string
     * @param config CotorConfig to serialize
     * @return YAML string
     */
    fun serialize(config: CotorConfig): String {
        return yaml.encodeToString(CotorConfig.serializer(), config)
    }
}

/**
 * Parser for JSON configuration files
 */
class JsonParser {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Parse JSON content into CotorConfig
     * @param content JSON string content
     * @return Parsed CotorConfig
     * @throws SerializationException if parsing fails
     */
    fun parse(content: String): CotorConfig {
        return json.decodeFromString(CotorConfig.serializer(), content)
    }

    /**
     * Serialize CotorConfig to JSON string
     * @param config CotorConfig to serialize
     * @return JSON string
     */
    fun serialize(config: CotorConfig): String {
        return json.encodeToString(CotorConfig.serializer(), config)
    }
}
