package com.cotor.data.registry

import com.cotor.model.AgentConfig
import java.util.concurrent.ConcurrentHashMap

/**
 * Registry interface for managing agent metadata
 */
interface AgentRegistry {
    /**
     * Register an agent
     * @param agent AgentConfig to register
     */
    fun registerAgent(agent: AgentConfig)

    /**
     * Unregister an agent
     * @param agentName Name of agent to unregister
     */
    fun unregisterAgent(agentName: String)

    /**
     * Get agent by name
     * @param agentName Name of agent
     * @return AgentConfig or null if not found
     */
    fun getAgent(agentName: String): AgentConfig?

    /**
     * Get all registered agents
     * @return List of all AgentConfigs
     */
    fun getAllAgents(): List<AgentConfig>

    /**
     * Find agents by tag
     * @param tag Tag to search for
     * @return List of matching AgentConfigs
     */
    fun findAgentsByTag(tag: String): List<AgentConfig>
}

/**
 * In-memory implementation of AgentRegistry
 */
class InMemoryAgentRegistry : AgentRegistry {
    private val agents = ConcurrentHashMap<String, AgentConfig>()
    private val tagIndex = ConcurrentHashMap<String, MutableSet<String>>()

    override fun registerAgent(agent: AgentConfig) {
        agents[agent.name] = agent

        // Index by tags
        agent.tags.forEach { tag ->
            tagIndex.computeIfAbsent(tag) { ConcurrentHashMap.newKeySet() }
                .add(agent.name)
        }
    }

    override fun unregisterAgent(agentName: String) {
        val agent = agents.remove(agentName)

        // Remove from tag index
        agent?.tags?.forEach { tag ->
            tagIndex[tag]?.remove(agentName)
        }
    }

    override fun getAgent(agentName: String): AgentConfig? {
        return agents[agentName]
    }

    override fun getAllAgents(): List<AgentConfig> {
        return agents.values.toList()
    }

    override fun findAgentsByTag(tag: String): List<AgentConfig> {
        val agentNames = tagIndex[tag] ?: return emptyList()
        return agentNames.mapNotNull { agents[it] }
    }
}
