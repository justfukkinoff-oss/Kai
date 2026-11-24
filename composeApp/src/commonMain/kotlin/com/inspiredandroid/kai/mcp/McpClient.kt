package com.inspiredandroid.kai.mcp

import com.inspiredandroid.kai.httpClient
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement

class McpClient(
    private val url: String,
    private val headers: Map<String, String>,
    private val oauthBearer: (suspend (rejected: String?) -> String?)? = null,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val client: HttpClient = httpClient {
        if (oauthBearer != null) followRedirects = false
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 10_000
        }
    }
    private var sessionId: String? = null
    private var requestId = 0
    private var protocol: String? = null

    private fun nextId(): Int = ++requestId

    private suspend fun sendRequest(method: String, params: kotlinx.serialization.json.JsonElement? = null): JsonRpcResponse {
        val request = JsonRpcRequest(
            id = nextId(),
            method = method,
            params = params,
        )
        val requestBody = json.encodeToString(JsonRpcRequest.serializer(), request)

        val response = postRpc(requestBody)
        if (response.status.value !in 200..299) {
            throw McpException("MCP server returned HTTP ${response.status.value}." +
                if (response.status.value == 401) " Check credentials or use OAuth sign-in in Settings → Tools." else "")
        }

        // Track session ID from response
        response.headers["Mcp-Session-Id"]?.let { sessionId = it }
        val responseText = response.bodyAsText()
        if (response.headers["Content-Type"]?.contains("text/event-stream") == true) {
            return parseSseResponse(responseText)
        }
        return json.decodeFromString(JsonRpcResponse.serializer(), responseText)
    }

    private suspend fun postRpc(requestBody: String): HttpResponse {
        suspend fun requestWith(token: String?): HttpResponse = client.post(url) {
            contentType(ContentType.Application.Json)
            header("Accept", "application/json, text/event-stream")
            sessionId?.let { header("Mcp-Session-Id", it) }
            protocol?.let { header("MCP-Protocol-Version", it) }
            this@McpClient.headers.forEach { (key, value) ->
                if (token == null || !key.equals("Authorization", true)) header(key, value)
            }
            if (token != null) header("Authorization", "Bearer $token")
            setBody(requestBody)
        }
        val token = oauthBearer?.invoke(null)
        val response = requestWith(token)
        if (token != null && response.status.value == 401) {
            val renewed = oauthBearer?.invoke(token)
            if (renewed != null && renewed != token) return requestWith(renewed)
        }
        return response
    }

    private fun parseSseResponse(sseText: String): JsonRpcResponse {
        // Parse SSE format: look for "data: " lines and find the JSON-RPC response
        val lines = sseText.lines()
        val dataLines = lines.filter { it.startsWith("data: ") }
        for (line in dataLines) {
            val data = line.removePrefix("data: ").trim()
            if (data.isEmpty()) continue
            try {
                return json.decodeFromString(JsonRpcResponse.serializer(), data)
            } catch (_: Exception) {
                // Not a valid JSON-RPC response, continue
            }
        }
        throw McpException("No valid JSON-RPC response found in SSE stream")
    }

    suspend fun initialize() {
        val params = buildJsonObject {
            put("protocolVersion", JsonPrimitive("2025-11-25"))
            put(
                "capabilities",
                buildJsonObject {},
            )
            put(
                "clientInfo",
                buildJsonObject {
                    put("name", JsonPrimitive("Re-Kai"))
                    put("version", JsonPrimitive("1.0"))
                },
            )
        }
        val response = sendRequest("initialize", params)
        if (response.error != null) {
            throw McpException("Initialize failed: ${response.error.message}")
        }
        protocol = ((response.result as? JsonObject)?.get("protocolVersion") as? JsonPrimitive)?.content
            ?.takeIf { it.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) }

        // Send initialized notification (no id, no response expected)
        sendNotification("notifications/initialized")
    }

    private suspend fun sendNotification(method: String) {
        val body = buildJsonObject {
            put("jsonrpc", JsonPrimitive("2.0"))
            put("method", JsonPrimitive(method))
        }
        val requestBody = json.encodeToString(JsonObject.serializer(), body)

        val response = postRpc(requestBody)
        if (response.status.value !in 200..299) throw McpException("MCP initialization notification returned HTTP ${response.status.value}.")
    }

    suspend fun listTools(): List<McpToolDefinition> {
        val response = sendRequest("tools/list")
        if (response.error != null) {
            throw McpException("tools/list failed: ${response.error.message}")
        }
        val result = response.result ?: return emptyList()
        val toolsResult = json.decodeFromJsonElement<McpToolsResult>(result)
        return toolsResult.tools
    }

    suspend fun callTool(name: String, arguments: JsonObject): String {
        val params = buildJsonObject {
            put("name", JsonPrimitive(name))
            put("arguments", arguments)
        }
        val response = sendRequest("tools/call", params)
        if (response.error != null) {
            throw McpException("tools/call failed: ${response.error.message}")
        }
        val result = response.result ?: return ""
        val callResult = json.decodeFromJsonElement<McpCallToolResult>(result)
        if (callResult.isError) {
            val errorText = callResult.content.mapNotNull { it.text }.joinToString("\n")
            throw McpException("Tool error: $errorText")
        }
        return callResult.content.mapNotNull { it.text }.joinToString("\n")
    }

    fun close() {
        client.close()
    }
}

class McpException(message: String) : Exception(message)
