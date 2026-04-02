package com.cotor.integrations.linear

/**
 * File overview for LinearIssueMirror.
 *
 * This file belongs to the integration layer for external systems such as Linear.
 * It groups declarations around linear tracker adapter so readers can find the owning runtime area quickly.
 * Read here first when tracing behavior that flows through this part of the codebase.
 */

import com.cotor.app.CompanyIssue
import com.cotor.app.LinearConnectionConfig
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

data class LinearIssueMirror(
    val id: String,
    val identifier: String? = null,
    val url: String? = null,
    val stateName: String? = null
)

interface LinearTrackerAdapter {
    suspend fun health(config: LinearConnectionConfig): Result<String>

    suspend fun syncIssue(
        config: LinearConnectionConfig,
        issue: CompanyIssue,
        desiredStateName: String?,
        assigneeId: String? = null
    ): Result<LinearIssueMirror>

    suspend fun createComment(
        config: LinearConnectionConfig,
        linearIssueId: String,
        body: String
    ): Result<Unit>
}

class LinearClient(
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val httpClient: HttpClient = HttpClient.newBuilder().build()
) {
    suspend fun graphql(
        config: LinearConnectionConfig,
        query: String,
        variables: JsonObject = buildJsonObject { }
    ): Result<JsonObject> = withContext(Dispatchers.IO) {
        runCatching {
            val token = config.apiToken?.trim().orEmpty()
            require(token.isNotBlank()) { "Linear API token is not configured" }
            val payload = buildJsonObject {
                put("query", JsonPrimitive(query))
                put("variables", variables)
            }
            val request = HttpRequest.newBuilder()
                .uri(URI.create(config.endpoint))
                .timeout(Duration.ofSeconds(30))
                .header(HttpHeaders.Authorization, token)
                .header(HttpHeaders.ContentType, "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(JsonObject.serializer(), payload)))
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            require(response.statusCode() in 200..299) {
                "Linear returned HTTP ${response.statusCode()}: ${response.body()}"
            }
            val body = json.parseToJsonElement(response.body()).jsonObject
            val errors = body["errors"] as? JsonArray
            require(errors == null || errors.isEmpty()) {
                "Linear GraphQL error: ${errors?.joinToString { it.toString() }}"
            }
            body
        }
    }
}

class DefaultLinearTrackerAdapter(
    private val client: LinearClient = LinearClient()
) : LinearTrackerAdapter {
    override suspend fun health(config: LinearConnectionConfig): Result<String> =
        client.graphql(
            config = config,
            query = """
                query CotorLinearViewer {
                  viewer { id name }
                }
            """.trimIndent()
        ).map { body ->
            val viewer = body["data"]?.jsonObject?.get("viewer")?.jsonObject
            viewer?.get("name")?.jsonPrimitive?.contentOrNull
                ?: viewer?.get("id")?.jsonPrimitive?.contentOrNull
                ?: "connected"
        }

    override suspend fun syncIssue(
        config: LinearConnectionConfig,
        issue: CompanyIssue,
        desiredStateName: String?,
        assigneeId: String?
    ): Result<LinearIssueMirror> {
        return if (issue.linearIssueId.isNullOrBlank()) {
            createIssue(config, issue, desiredStateName, assigneeId)
        } else {
            updateIssue(config, issue.linearIssueId, desiredStateName, assigneeId)
        }
    }

    override suspend fun createComment(
        config: LinearConnectionConfig,
        linearIssueId: String,
        body: String
    ): Result<Unit> {
        if (body.isBlank()) return Result.success(Unit)
        return client.graphql(
            config = config,
            query = """
                mutation CotorCreateLinearComment(${'$'}issueId: String!, ${'$'}body: String!) {
                  commentCreate(input: {issueId: ${'$'}issueId, body: ${'$'}body}) {
                    success
                  }
                }
            """.trimIndent(),
            variables = buildJsonObject {
                put("issueId", JsonPrimitive(linearIssueId))
                put("body", JsonPrimitive(body))
            }
        ).map { bodyObject ->
            val success = bodyObject["data"]?.jsonObject
                ?.get("commentCreate")?.jsonObject
                ?.get("success")?.jsonPrimitive
                ?.contentOrNull == "true"
            require(success) { "Linear commentCreate did not succeed" }
        }
    }

    private suspend fun createIssue(
        config: LinearConnectionConfig,
        issue: CompanyIssue,
        desiredStateName: String?,
        assigneeId: String?
    ): Result<LinearIssueMirror> {
        val teamId = config.teamId?.trim().orEmpty()
        if (teamId.isBlank()) {
            return Result.failure(IllegalStateException("Linear teamId is not configured"))
        }
        val projectId = config.projectId?.trim()?.takeIf { it.isNotBlank() }
        val response = client.graphql(
            config = config,
            query = """
                mutation CotorCreateLinearIssue(
                  ${'$'}teamId: String!,
                  ${'$'}projectId: String,
                  ${'$'}title: String!,
                  ${'$'}description: String!,
                  ${'$'}assigneeId: String
                ) {
                  issueCreate(
                    input: {
                      teamId: ${'$'}teamId,
                      projectId: ${'$'}projectId,
                      title: ${'$'}title,
                      description: ${'$'}description,
                      assigneeId: ${'$'}assigneeId
                    }
                  ) {
                    success
                    issue {
                      id
                      identifier
                      url
                      state { name }
                    }
                  }
                }
            """.trimIndent(),
            variables = buildJsonObject {
                put("teamId", JsonPrimitive(teamId))
                put("projectId", projectId?.let(::JsonPrimitive) ?: JsonPrimitive(null as String?))
                put("title", JsonPrimitive(issue.title))
                put("description", JsonPrimitive(issue.description))
                put("assigneeId", assigneeId?.let(::JsonPrimitive) ?: JsonPrimitive(null as String?))
            }
        )
        return response.mapCatching { body ->
            val issueObject = body["data"]?.jsonObject
                ?.get("issueCreate")?.jsonObject
                ?.also {
                    require(it["success"]?.jsonPrimitive?.contentOrNull == "true") {
                        "Linear issueCreate did not succeed"
                    }
                }
                ?.get("issue")?.jsonObject
                ?: error("Linear issueCreate returned no issue")
            val mirror = parseIssue(issueObject)
            val desired = desiredStateName?.takeIf { it.isNotBlank() }
            if (desired == null) {
                mirror
            } else {
                updateIssue(config, mirror.id, desired, assigneeId).getOrThrow()
            }
        }
    }

    private suspend fun updateIssue(
        config: LinearConnectionConfig,
        linearIssueId: String,
        desiredStateName: String?,
        assigneeId: String?
    ): Result<LinearIssueMirror> = runCatching {
        val stateId = desiredStateName?.takeIf { it.isNotBlank() }?.let {
            resolveStateId(config, linearIssueId, it).getOrThrow()
        }
        val response = client.graphql(
            config = config,
            query = """
                mutation CotorUpdateLinearIssue(
                  ${'$'}issueId: String!,
                  ${'$'}stateId: String,
                  ${'$'}assigneeId: String
                ) {
                  issueUpdate(
                    id: ${'$'}issueId,
                    input: {
                      stateId: ${'$'}stateId,
                      assigneeId: ${'$'}assigneeId
                    }
                  ) {
                    success
                    issue {
                      id
                      identifier
                      url
                      state { name }
                    }
                  }
                }
            """.trimIndent(),
            variables = buildJsonObject {
                put("issueId", JsonPrimitive(linearIssueId))
                put("stateId", stateId?.let(::JsonPrimitive) ?: JsonPrimitive(null as String?))
                put("assigneeId", assigneeId?.let(::JsonPrimitive) ?: JsonPrimitive(null as String?))
            }
        ).getOrThrow()
        val issueObject = response["data"]?.jsonObject
            ?.get("issueUpdate")?.jsonObject
            ?.also {
                require(it["success"]?.jsonPrimitive?.contentOrNull == "true") {
                    "Linear issueUpdate did not succeed"
                }
            }
            ?.get("issue")?.jsonObject
            ?: error("Linear issueUpdate returned no issue")
        parseIssue(issueObject)
    }

    private suspend fun resolveStateId(
        config: LinearConnectionConfig,
        linearIssueId: String,
        desiredStateName: String
    ): Result<String> = client.graphql(
        config = config,
        query = """
            query CotorResolveLinearState(${'$'}issueId: String!, ${'$'}stateName: String!) {
              issue(id: ${'$'}issueId) {
                team {
                  states(filter: {name: {eq: ${'$'}stateName}}, first: 1) {
                    nodes { id }
                  }
                }
              }
            }
        """.trimIndent(),
        variables = buildJsonObject {
            put("issueId", JsonPrimitive(linearIssueId))
            put("stateName", JsonPrimitive(desiredStateName))
        }
    ).mapCatching { body ->
        body["data"]?.jsonObject
            ?.get("issue")?.jsonObject
            ?.get("team")?.jsonObject
            ?.get("states")?.jsonObject
            ?.get("nodes")?.jsonArray
            ?.firstOrNull()?.jsonObject
            ?.get("id")?.jsonPrimitive?.contentOrNull
            ?: error("Linear state \"$desiredStateName\" was not found for issue $linearIssueId")
    }

    private fun parseIssue(issueObject: JsonObject): LinearIssueMirror =
        LinearIssueMirror(
            id = issueObject["id"]?.jsonPrimitive?.content ?: error("Linear issue id missing"),
            identifier = issueObject["identifier"]?.jsonPrimitive?.contentOrNull,
            url = issueObject["url"]?.jsonPrimitive?.contentOrNull,
            stateName = issueObject["state"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
        )
}

private val JsonPrimitive.contentOrNull: String?
    get() = if (isString) content else content.takeIf { it != "null" }
