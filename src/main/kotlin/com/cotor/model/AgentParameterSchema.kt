package com.cotor.model

/**
 * File overview for ParameterType.
 *
 * This file belongs to the shared model layer that defines configuration and execution contracts.
 * It groups declarations around agent parameter schema so readers can find the owning runtime area quickly.
 * Read here first when tracing behavior that flows through this part of the codebase.
 */


enum class ParameterType {
    STRING,
    NUMBER,
    BOOLEAN,
    MAP,
    LIST
}

data class AgentParameter(
    val name: String,
    val type: ParameterType,
    val required: Boolean,
    val description: String? = null,
    val defaultValue: Any? = null
)

data class AgentParameterSchema(
    val parameters: List<AgentParameter>
)
