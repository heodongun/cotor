package com.cotor.model

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
