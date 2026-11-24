@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.inspiredandroid.kai.mcp

import com.inspiredandroid.kai.data.AppSettings
import com.inspiredandroid.kai.httpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.time.Clock

internal const val MCP_OAUTH_REDIRECT = "rekai://mcp-oauth/callback"
internal data class OAuthProof(val state: String, val verifier: String, val challenge: String)
internal expect fun mcpOAuthProof(): OAuthProof?

@Serializable
private data class PendingMcpAuthorization(
    val serverId: String, val serverUrl: String, val state: String, val verifier: String,
    val tokenEndpoint: String, val clientId: String, val resource: String, val started: Long,
)

@Serializable
private data class McpAuthorization(
    val serverUrl: String, val tokenEndpoint: String, val clientId: String, val resource: String,
    val access: String, val refresh: String?, val expires: Long?,
)

internal class McpOAuth(private val settings: AppSettings) {
    private val json = Json { ignoreUnknownKeys = true }
    private val tokenLock = Mutex()
    private val client by lazy {
        httpClient {
            followRedirects = false
            install(HttpTimeout) {
                requestTimeoutMillis = 15_000
                connectTimeoutMillis = 8_000
            }
        }
    }

    private fun now() = Clock.System.now().epochSeconds
    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.strings(key: String) = (get(key) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.orEmpty()
    private fun secureUrl(raw: String): Url = try {
        Url(raw).also {
            if (!raw.startsWith("https://", ignoreCase = true) || it.protocol != URLProtocol.HTTPS || it.host.isBlank() ||
                it.user != null || it.password != null || '#' in raw || raw.any { c -> c.isWhitespace() || c == '\\' }) {
                throw McpException("MCP OAuth requires HTTPS endpoints without embedded credentials or fragments.")
            }
        }
    } catch (e: McpException) {
        throw e
    } catch (_: Exception) {
        throw McpException("Invalid OAuth HTTPS endpoint.")
    }

    private fun parameters(values: Map<String, String>) = values.entries.joinToString("&") {
        "${it.key.encodeURLParameter()}=${it.value.encodeURLParameter()}"
    }
    private fun appendQuery(url: String, values: Map<String, String>) = url + (if ('?' in url) "&" else "?") + parameters(values)
    private fun origin(url: Url): String {
        val host = if (':' in url.host && !url.host.startsWith("[")) "[${url.host}]" else url.host
        return "https://$host" + if (url.port == 443) "" else ":${url.port}"
    }
    private fun path(raw: String): String = raw.substringAfter("://").substringBefore('?').substringAfter('/', "").trimEnd('/').let {
        if (it.isBlank()) "" else "/$it"
    }

    private suspend fun getMetadata(raw: String): JsonObject? = try {
        secureUrl(raw)
        val response = client.get(raw) { header("Accept", "application/json") }
        if (response.status.value in 200..299) json.parseToJsonElement(response.bodyAsText().take(250_000)) as? JsonObject else null
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    internal suspend fun begin(server: McpServerConfig, enteredClientId: String, enteredScope: String): String {
        val proof = mcpOAuthProof() ?: throw McpException("OAuth browser sign-in is available on Android.")
        val endpoint = secureUrl(server.url)
        val challenge = try {
            client.post(server.url) {
                contentType(ContentType.Application.Json)
                header("Accept", "application/json, text/event-stream")
                server.headers.filterKeys { !it.equals("Authorization", true) }.forEach { (key, value) -> header(key, value) }
                setBody("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"Re-Kai","version":"1.0"}}}""")
            }.headers.getAll("WWW-Authenticate").orEmpty().joinToString(", ")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ""
        }
        fun challenged(field: String): String? =
            Regex("""(?:^|[,\s])${field}\s*=\s*(?:"([^"]*)"|([^,\s]+))""", RegexOption.IGNORE_CASE).find(challenge)?.let {
                it.groupValues[1].ifBlank { it.groupValues[2] }.ifBlank { null }
            }
        val metadataUrls = listOfNotNull(challenged("resource_metadata"),
            "${origin(endpoint)}/.well-known/oauth-protected-resource${path(server.url)}",
            "${origin(endpoint)}/.well-known/oauth-protected-resource").distinct()
        val resource = metadataUrls.firstNotNullOfOrNullSuspend { raw ->
            getMetadata(raw)?.takeIf { it.strings("authorization_servers").isNotEmpty() }
        } ?: throw McpException("The server did not publish usable OAuth protected-resource metadata.")
        val resourceUri = resource.string("resource") ?: throw McpException("OAuth resource metadata is missing its resource URI.")
        val claimed = secureUrl(resourceUri)
        if (!claimed.host.equals(endpoint.host, true) || claimed.port != endpoint.port || path(resourceUri) != path(server.url)) {
            throw McpException("OAuth metadata describes a different MCP resource. Use the server's canonical URL.")
        }
        val provider = resource.strings("authorization_servers").firstNotNullOfOrNullSuspend { issuer ->
            val base = try { secureUrl(issuer) } catch (_: Exception) { return@firstNotNullOfOrNullSuspend null }
            val prefix = origin(base)
            val tenant = path(issuer)
            listOf("$prefix/.well-known/oauth-authorization-server$tenant", "$prefix/.well-known/openid-configuration$tenant",
                "$prefix$tenant/.well-known/openid-configuration").distinct().firstNotNullOfOrNullSuspend { raw ->
                getMetadata(raw)?.takeIf { it.string("issuer") == issuer }
            }
        } ?: throw McpException("Could not discover the MCP authorization server.")
        if ("S256" !in provider.strings("code_challenge_methods_supported")) {
            throw McpException("The OAuth provider does not advertise S256 PKCE.")
        }
        val authorize = provider.string("authorization_endpoint") ?: throw McpException("OAuth authorization endpoint missing.")
        val token = provider.string("token_endpoint") ?: throw McpException("OAuth token endpoint missing.")
        secureUrl(authorize)
        secureUrl(token)
        val clientId = enteredClientId.trim().ifBlank { register(provider) }
        val pending = PendingMcpAuthorization(server.id, server.url, proof.state, proof.verifier, token, clientId, resourceUri, now())
        settings.setLibraryValue("mcp.oauth.pending", json.encodeToString(PendingMcpAuthorization.serializer(), pending))
        val values = linkedMapOf("response_type" to "code", "client_id" to clientId, "redirect_uri" to MCP_OAUTH_REDIRECT,
            "state" to proof.state, "code_challenge" to proof.challenge, "code_challenge_method" to "S256", "resource" to resourceUri)
        val scope = challenged("scope") ?: enteredScope.trim().ifBlank { resource.strings("scopes_supported").joinToString(" ") }
        if (scope.isNotBlank()) values["scope"] = scope
        return appendQuery(authorize, values)
    }

    private suspend fun register(provider: JsonObject): String {
        val url = provider.string("registration_endpoint")
            ?: throw McpException("This provider requires a registered client ID. Register the displayed callback URL, then enter its public client ID.")
        secureUrl(url)
        val configuration = buildJsonObject {
            put("client_name", "Re-Kai")
            put("redirect_uris", JsonArray(listOf(JsonPrimitive(MCP_OAUTH_REDIRECT))))
            put("grant_types", JsonArray(listOf(JsonPrimitive("authorization_code"), JsonPrimitive("refresh_token"))))
            put("response_types", JsonArray(listOf(JsonPrimitive("code"))))
            put("token_endpoint_auth_method", "none")
        }
        val response = client.post(url) {
            contentType(ContentType.Application.Json)
            setBody(configuration.toString())
        }
        if (response.status.value !in 200..299) throw McpException("Dynamic client registration failed (HTTP ${response.status.value}). Enter a registered public client ID.")
        val result = json.parseToJsonElement(response.bodyAsText().take(250_000)) as? JsonObject
        return result?.string("client_id")?.takeIf { it.isNotBlank() } ?: throw McpException("Dynamic registration returned no client ID.")
    }

    private fun authorization(serverId: String): McpAuthorization? = runCatching {
        json.decodeFromString<McpAuthorization>(settings.libraryValue("mcp.oauth.$serverId"))
    }.getOrNull()

    internal fun connected(server: McpServerConfig) = authorization(server.id)?.serverUrl == server.url
    internal fun forget(serverId: String) = settings.setLibraryValue("mcp.oauth.$serverId", "")

    internal suspend fun finish(callback: String, serverFor: (String) -> McpServerConfig?): Pair<String, String?>? {
        if (callback.substringBefore('?') != MCP_OAUTH_REDIRECT || '#' in callback) return null
        val pending = runCatching { json.decodeFromString<PendingMcpAuthorization>(settings.libraryValue("mcp.oauth.pending")) }.getOrNull() ?: return null
        val params = try { Url(callback).parameters } catch (_: Exception) { return null }
        if (params.getAll("state")?.singleOrNull() != pending.state) return null
        settings.setLibraryValue("mcp.oauth.pending", "")
        if (now() - pending.started !in 0..600) return pending.serverId to "Sign-in expired. Start it again."
        if (serverFor(pending.serverId)?.url != pending.serverUrl) return pending.serverId to "The MCP server changed during sign-in."
        val code = params.getAll("code")?.singleOrNull()
        if (params["error"] != null || code.isNullOrBlank()) return pending.serverId to "The provider declined or cancelled sign-in."
        return try {
            val original = McpAuthorization(pending.serverUrl, pending.tokenEndpoint, pending.clientId, pending.resource, "", null, null)
            tokenLock.withLock {
                val updated = exchange(original, mapOf("grant_type" to "authorization_code", "code" to code,
                    "redirect_uri" to MCP_OAUTH_REDIRECT, "code_verifier" to pending.verifier))
                store(pending.serverId, updated)
            }
            pending.serverId to null
        } catch (e: CancellationException) {
            throw e
        } catch (e: McpException) {
            pending.serverId to (e.message ?: "Token exchange failed.")
        } catch (_: Exception) {
            pending.serverId to "Could not complete OAuth token exchange."
        }
    }

    private fun store(id: String, value: McpAuthorization) {
        settings.setLibraryValue("mcp.oauth.$id", json.encodeToString(McpAuthorization.serializer(), value))
    }

    internal suspend fun bearer(server: McpServerConfig, rejected: String? = null): String? = tokenLock.withLock {
        val prior = authorization(server.id)?.takeIf { it.serverUrl == server.url } ?: return@withLock null
        val expired = prior.expires?.let { it <= now() + 30 } == true
        if (!expired && prior.access != rejected) return@withLock prior.access
        val refresh = prior.refresh ?: throw McpException("The OAuth session has expired. Sign in again in Settings → Tools.")
        val fresh = exchange(prior, mapOf("grant_type" to "refresh_token", "refresh_token" to refresh))
        store(server.id, fresh)
        fresh.access
    }

    private suspend fun exchange(prior: McpAuthorization, fields: Map<String, String>): McpAuthorization {
        secureUrl(prior.tokenEndpoint)
        val response = client.post(prior.tokenEndpoint) {
            contentType(ContentType.Application.FormUrlEncoded)
            header("Accept", "application/json")
            setBody(parameters(fields + mapOf("client_id" to prior.clientId, "resource" to prior.resource)))
        }
        if (response.status.value !in 200..299) throw McpException("OAuth token request failed (HTTP ${response.status.value}). Sign in again or review provider configuration.")
        val payload = json.parseToJsonElement(response.bodyAsText().take(250_000)) as? JsonObject ?: throw McpException("Invalid token response.")
        val access = payload.string("access_token")?.takeIf { it.isNotBlank() && it.none(Char::isWhitespace) }
        if (access == null || payload.string("token_type")?.equals("Bearer", true) != true) throw McpException("OAuth provider did not return a Bearer token.")
        val expires = (payload["expires_in"] as? JsonPrimitive)?.longOrNull?.coerceIn(0, 31536000)?.let { now() + it }
        return prior.copy(access = access, refresh = payload.string("refresh_token") ?: prior.refresh, expires = expires)
    }
}

private suspend fun <T, R : Any> Iterable<T>.firstNotNullOfOrNullSuspend(block: suspend (T) -> R?): R? {
    for (value in this) block(value)?.let { return it }
    return null
}
