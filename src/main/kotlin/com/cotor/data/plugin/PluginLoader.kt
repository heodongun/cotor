package com.cotor.data.plugin

import com.cotor.model.PluginLoadException
import org.slf4j.Logger
import java.util.ServiceLoader
import java.util.concurrent.ConcurrentHashMap

/**
 * Agent plugin interface - must be implemented by all agent plugins
 */
interface AgentPlugin {
    val metadata: com.cotor.model.AgentMetadata

    /**
     * Execute the agent with given context
     * @param context Execution context
     * @param processManager Process manager for executing external processes
     * @return Output string from agent execution
     */
    suspend fun execute(
        context: com.cotor.model.ExecutionContext,
        processManager: com.cotor.data.process.ProcessManager
    ): String

    /**
     * Validate input data
     * @param input Input string to validate
     * @return ValidationResult indicating success or failure with errors
     */
    fun validateInput(input: String?): com.cotor.model.ValidationResult {
        return com.cotor.model.ValidationResult.Success
    }

    /**
     * Check if plugin supports given data format
     * @param format DataFormat to check
     * @return true if supported, false otherwise
     */
    fun supportsFormat(format: com.cotor.model.DataFormat): Boolean {
        return format == com.cotor.model.DataFormat.JSON
    }
}

/**
 * Interface for loading agent plugins
 */
interface PluginLoader {
    /**
     * Load plugin by class name
     * @param pluginClass Fully qualified class name
     * @return AgentPlugin instance
     */
    fun loadPlugin(pluginClass: String): AgentPlugin

    /**
     * Load all plugins using ServiceLoader
     * @return List of all discovered plugins
     */
    fun loadAllPlugins(): List<AgentPlugin>
}

/**
 * Reflection-based plugin loader implementation
 */
class ReflectionPluginLoader(
    private val logger: Logger
) : PluginLoader {

    private val pluginCache = ConcurrentHashMap<String, AgentPlugin>()

    override fun loadPlugin(pluginClass: String): AgentPlugin {
        return pluginCache.computeIfAbsent(pluginClass) {
            try {
                val clazz = Class.forName(pluginClass)
                val instance = clazz.getDeclaredConstructor().newInstance()

                if (instance !is AgentPlugin) {
                    throw PluginLoadException("Class $pluginClass does not implement AgentPlugin")
                }

                logger.info("Loaded plugin: $pluginClass")
                instance
            } catch (e: PluginLoadException) {
                throw e
            } catch (e: Exception) {
                logger.error("Failed to load plugin: $pluginClass", e)
                throw PluginLoadException("Cannot load plugin: $pluginClass", e)
            }
        }
    }

    override fun loadAllPlugins(): List<AgentPlugin> {
        // Use ServiceLoader for automatic plugin discovery
        val serviceLoader = ServiceLoader.load(AgentPlugin::class.java)
        return serviceLoader.toList().also { plugins ->
            logger.info("Discovered ${plugins.size} plugins")
            plugins.forEach { plugin ->
                pluginCache[plugin::class.java.name] = plugin
            }
        }
    }
}
