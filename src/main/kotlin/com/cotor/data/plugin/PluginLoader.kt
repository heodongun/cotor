package com.cotor.data.plugin

import com.cotor.model.PluginLoadException
import org.slf4j.Logger
import java.util.ServiceLoader
import java.util.concurrent.ConcurrentHashMap

/**
 * Agent plugin interface - must be implemented by all agent plugins
 */
interface AgentPlugin {
    /**
     * Static metadata used by discovery UIs, validation, and documentation output.
     */
    val metadata: com.cotor.model.AgentMetadata

    /**
     * The schema for the parameters that this agent accepts.
     */
    val parameterSchema: com.cotor.model.AgentParameterSchema
        get() = com.cotor.model.AgentParameterSchema(emptyList())

    /**
     * Execute the agent with given context
     * @param context Execution context
     * @param processManager Process manager for executing external processes
     * @return Structured output including the final text and any captured runtime metadata
     */
    suspend fun execute(
        context: com.cotor.model.ExecutionContext,
        processManager: com.cotor.data.process.ProcessManager
    ): com.cotor.model.PluginExecutionOutput

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

    // Plugin instances are cached because most plugins are stateless wrappers around
    // CLIs or HTTP clients and do not need to be reflectively recreated per execution.
    private val pluginCache = ConcurrentHashMap<String, AgentPlugin>()

    override fun loadPlugin(pluginClass: String): AgentPlugin {
        return pluginCache.computeIfAbsent(pluginClass) {
            try {
                // Reflection keeps the plugin contract simple: adding a plugin only
                // requires the class to be on the classpath and implement AgentPlugin.
                val clazz = Class.forName(pluginClass)
                val instance = clazz.getDeclaredConstructor().newInstance()

                if (instance !is AgentPlugin) {
                    throw PluginLoadException("Class $pluginClass does not implement AgentPlugin")
                }

                // Plugin discovery is useful during debugging but too noisy for the
                // embedded desktop TUI, where each extra console line pollutes the
                // terminal transcript and makes prompt/input flow harder to follow.
                logger.debug("Loaded plugin: $pluginClass")
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
        // ServiceLoader is the bulk discovery path used when we want to enumerate the
        // whole plugin surface instead of loading a single named implementation.
        val serviceLoader = ServiceLoader.load(AgentPlugin::class.java)
        return serviceLoader.toList().also { plugins ->
            logger.info("Discovered ${plugins.size} plugins")
            plugins.forEach { plugin ->
                pluginCache[plugin::class.java.name] = plugin
            }
        }
    }
}
