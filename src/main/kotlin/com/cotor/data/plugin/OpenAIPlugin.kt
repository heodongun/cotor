package com.cotor.data.plugin

import com.cotor.data.process.ProcessManager
import com.cotor.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * OpenAI API plugin.
 *
 * Uses Chat Completions API. Configure API key via env:
 * - context.environment[OPENAI_API_KEY] or system env OPENAI_API_KEY
 */
class OpenAIPlugin : AgentPlugin {
    override val metadata = AgentMetadata(
        name = "openai",
        version = "1.0.0",
        description = "OpenAI API (Chat Completions) for code generation and analysis",
        author = "Cotor Team",
        supportedFormats = listOf(DataFormat.TEXT, DataFormat.JSON)
    )

    override val parameterSchema = AgentParameterSchema(
        parameters = listOf(
            AgentParameter(
                name = "model",
                type = ParameterType.STRING,
                required = false,
                description = "OpenAI model id",
                defaultValue = "gpt-4o-mini"
            ),
            AgentParameter(
                name = "baseUrl",
                type = ParameterType.STRING,
                required = false,
                description = "API base URL (default: https://api.openai.com/v1)",
                defaultValue = "https://api.openai.com/v1"
            ),
            AgentParameter(
                name = "apiKeyEnv",
                type = ParameterType.STRING,
                required = false,
                description = "Environment variable name that holds the API key",
                defaultValue = "OPENAI_API_KEY"
            ),
            AgentParameter(
                name = "system",
                type = ParameterType.STRING,
                required = false,
                description = "Optional system prompt",
                defaultValue = ""
            ),
            AgentParameter(
                name = "temperature",
                type = ParameterType.NUMBER,
                required = false,
                description = "Sampling temperature",
                defaultValue = "0.2"
            ),
            AgentParameter(
                name = "maxTokens",
                type = ParameterType.NUMBER,
                required = false,
                description = "Max output tokens",
                defaultValue = "1024"
            )
        )
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .build()

    override suspend fun execute(
        context: ExecutionContext,
        processManager: ProcessManager
    ): String = withContext(Dispatchers.IO) {
        val prompt = context.input ?: throw IllegalArgumentException("Input prompt is required")

        val model = context.parameters["model"] ?: "gpt-4o-mini"
        val baseUrl = (context.parameters["baseUrl"] ?: "https://api.openai.com/v1").trimEnd('/')
        val apiKeyEnv = context.parameters["apiKeyEnv"] ?: "OPENAI_API_KEY"
        val apiKey = resolveApiKey(context, apiKeyEnv)
        val system = context.parameters["system"].orEmpty()
        val temperature = context.parameters["temperature"]?.toDoubleOrNull() ?: 0.2
        val maxTokens = context.parameters["maxTokens"]?.toIntOrNull() ?: 1024

        val messages = buildJsonArray {
            if (system.isNotBlank()) {
                add(buildMessage("system", system))
            }
            add(buildMessage("user", prompt))
        }

        val requestBody = buildJsonObject {
            put("model", JsonPrimitive(model))
            put("messages", messages)
            put("temperature", JsonPrimitive(temperature))
            put("max_tokens", JsonPrimitive(maxTokens))
        }.toString()

        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/chat/completions"))
            .timeout(Duration.ofMillis(context.timeout))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build()

        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        val status = response.statusCode()
        val body = response.body()

        if (status !in 200..299) {
            throw AgentExecutionException("OpenAI API failed: HTTP $status: ${body.take(1000)}")
        }

        val content = extractContent(body)
            ?: throw AgentExecutionException("OpenAI API response missing content: ${body.take(1000)}")

        content
    }

    override fun validateInput(input: String?): ValidationResult {
        if (input.isNullOrBlank()) {
            return ValidationResult.Failure(listOf("Input prompt is required for OpenAI"))
        }
        return ValidationResult.Success
    }

    private fun resolveApiKey(context: ExecutionContext, envName: String): String {
        return context.environment[envName]
            ?: System.getenv(envName)
            ?: throw IllegalArgumentException("Missing API key env '$envName' (set it via agent.environment or system env)")
    }

    private fun buildMessage(role: String, content: String): JsonObject {
        return buildJsonObject {
            put("role", JsonPrimitive(role))
            put("content", JsonPrimitive(content))
        }
    }

    private fun extractContent(body: String): String? {
        val element = json.parseToJsonElement(body)
        val root = element.jsonObject

        // Prefer normal success path
        val choices = root["choices"] as? JsonArray
        val first = choices?.firstOrNull() as? JsonObject
        val message = first?.get("message") as? JsonObject
        val content = message?.get("content") as? JsonPrimitive
        if (content != null) return content.content

        // Some clients may return a top-level "error"
        val error = root["error"]?.jsonObject
        val messageText = error?.get("message")?.jsonPrimitive?.content
        return messageText?.let { "Error: $it" }
    }
}
