package com.inspiredandroid.kai.mcp

import com.inspiredandroid.kai.data.AppSettings
import com.inspiredandroid.kai.data.SettingsJsonList
import com.inspiredandroid.kai.network.tools.Tool
import com.inspiredandroid.kai.network.tools.ToolInfo
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

private val serverIdRegex = Regex("[^a-z0-9]")

internal data class McpOAuthStatus(val serverId: String, val message: String, val connected: Boolean)

class McpServerManager(private val appSettings: AppSettings) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val mutex = Mutex()
    private val clients = mutableMapOf<String, McpClient>()
    private val discoveredTools = mutableMapOf<String, List<McpToolMetadata>>()
    private val oauth = McpOAuth(appSettings)
    private val oauthStatus = MutableStateFlow<McpOAuthStatus?>(null)
    internal val oauthEvents = oauthStatus.asStateFlow()

    internal fun hasOAuth(serverId: String) = getServers().firstOrNull { it.id == serverId }?.let(oauth::connected) == true

    internal suspend fun beginOAuth(serverId: String, clientId: String, scope: String): String {
        val server = getServers().find { it.id == serverId } ?: throw McpException("MCP server not found.")
        return oauth.begin(server, clientId, scope)
    }

    suspend fun handleOAuthRedirect(callback: String) {
        val (id, error) = oauth.finish(callback) { serverId -> getServers().find { it.id == serverId } } ?: return
        if (error != null) {
            oauthStatus.value = McpOAuthStatus(id, error, false)
            return
        }
        val tools = connectAndDiscoverTools(id)
        oauthStatus.value = McpOAuthStatus(id, if (tools.isSuccess) "Signed in and connected." else "Signed in, but tool discovery failed. Check the server URL and scopes.", tools.isSuccess)
    }

    internal fun forgetOAuth(serverId: String) {
        oauth.forget(serverId)
        clients.remove(serverId)?.close()
        discoveredTools.remove(serverId)
        oauthStatus.value = McpOAuthStatus(serverId, "Signed out on this device.", false)
    }

    // NOTE: the mutators below are read-modify-write without a lock. Making them safe means making
    // them suspend, which ripples through DataRepository and the settings UI — tracked separately.
    private val servers = SettingsJsonList(
        read = appSettings::getMcpServersJson,
        write = appSettings::setMcpServersJson,
        itemSerializer = McpServerConfig.serializer(),
        label = "McpServerManager",
        onCorrupt = { appSettings.backupCorruptJson("McpServerManager", it) },
        json = json,
    )

    init {
        // One-shot: fill missing popular default headers (e.g. Jina Authorization) without
        // overwriting headers the user already configured.
        migratePopularDefaultHeaders()
    }

    fun getServers(): List<McpServerConfig> = servers.get()

    private fun saveServers(servers: List<McpServerConfig>) = this.servers.set(servers)

    private fun migratePopularDefaultHeaders() {
        val current = servers.get()
        val updated = applyPopularDefaultHeaders(current)
        if (updated !== current) {
            saveServers(updated)
        }
    }

    fun addServer(name: String, url: String, headers: Map<String, String>): McpServerConfig {
        val servers = getServers().toMutableList()
        val id = generateServerId(name, servers)
        // If the user adds a popular endpoint manually without headers, still apply defaults
        // for missing keys only (never clobber explicit headers they typed).
        val popularDefaults = popularMcpServers
            .firstOrNull { matchesPopularMcpUrl(url, it.url) }
            ?.headers
            .orEmpty()
        val mergedHeaders = mergeMissingHeaders(headers, popularDefaults)
        val config = McpServerConfig(id = id, name = name, url = url, headers = mergedHeaders)
        servers.add(config)
        saveServers(servers)
        return config
    }

    fun removeServer(serverId: String) {
        val servers = getServers().toMutableList()
        servers.removeAll { it.id == serverId }
        saveServers(servers)
        oauth.forget(serverId)
        clients[serverId]?.close()
        clients.remove(serverId)
        discoveredTools.remove(serverId)
    }

    fun setServerEnabled(serverId: String, enabled: Boolean) {
        val servers = getServers().toMutableList()
        val index = servers.indexOfFirst { it.id == serverId }
        if (index >= 0) {
            servers[index] = servers[index].copy(isEnabled = enabled)
            saveServers(servers)
        }
        if (!enabled) {
            clients[serverId]?.close()
            clients.remove(serverId)
            discoveredTools.remove(serverId)
        }
    }

    suspend fun connectAndDiscoverTools(serverId: String): Result<List<McpToolMetadata>> {
        val server = getServers().find { it.id == serverId }
            ?: return Result.failure(McpException("Server not found: $serverId"))

        // Close existing client if any
        mutex.withLock { clients[serverId] }?.close()

        val client = if (oauth.connected(server)) McpClient(server.url, server.headers) { rejected -> oauth.bearer(server, rejected) }
        else McpClient(server.url, server.headers)
        return try {
            client.initialize()
            val toolDefs = client.listTools()
            val metadata = toolDefs.map { def ->
                McpToolMetadata(
                    serverId = serverId,
                    name = def.name,
                    description = def.description ?: "",
                    inputSchema = def.inputSchema,
                )
            }
            mutex.withLock {
                clients[serverId] = client
                discoveredTools[serverId] = metadata
            }
            Result.success(metadata)
        } catch (e: Exception) {
            client.close()
            mutex.withLock {
                clients.remove(serverId)
                discoveredTools.remove(serverId)
            }
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    /**
     * Enabled MCP tools, named so none collides with [reservedNames] (the built-in tools) or with
     * another server's tool. Duplicate function names make providers reject the request, and the
     * executor would always run the first match.
     */
    fun getEnabledMcpTools(reservedNames: Set<String> = emptySet()): List<Tool> {
        val enabledServers = getServers().filter { it.isEnabled }.map { it.id }.toSet()
        val taken = reservedNames.toMutableSet()
        return buildList {
            for ((serverId, tools) in discoveredTools) {
                if (serverId !in enabledServers) continue
                val client = clients[serverId] ?: continue
                for (meta in tools) {
                    val toolId = McpTool.toolId(serverId, meta.name)
                    if (appSettings.isToolEnabled(toolId)) {
                        val name = uniqueMcpToolName(serverId, meta.name, taken)
                        taken += name
                        add(McpTool(client, meta, advertisedName = name))
                    }
                }
            }
        }
    }

    fun getToolsForServer(serverId: String): List<ToolInfo> {
        val tools = discoveredTools[serverId] ?: return emptyList()
        return tools.map { meta ->
            val toolId = McpTool.toolId(serverId, meta.name)
            ToolInfo(
                id = toolId,
                name = meta.name,
                description = meta.description,
                isEnabled = appSettings.isToolEnabled(toolId),
            )
        }
    }

    internal fun compatibleTool(provider: String, candidates: List<String>, accept: (Tool) -> Boolean): Tool? {
        for (server in getServers()) {
            if (!server.isEnabled || !("${server.id} ${server.name}").contains(provider, ignoreCase = true)) continue
            val client = clients[server.id] ?: continue
            for (meta in discoveredTools[server.id].orEmpty()) {
                val name = meta.name.lowercase().replace('-', '_')
                if (candidates.none { name == it || name.endsWith("_$it") }) continue
                if (!appSettings.isToolEnabled(McpTool.toolId(server.id, meta.name))) continue
                val tool = McpTool(client, meta)
                if (accept(tool)) return tool
            }
        }
        return null
    }

    suspend fun connectEnabledServers() {
        val enabledServers = getServers().filter { it.isEnabled }
        coroutineScope {
            enabledServers
                .filter { !clients.containsKey(it.id) }
                .map { server ->
                    async {
                        try {
                            connectAndDiscoverTools(server.id)
                        } catch (_: Exception) {
                            // Individual server failures shouldn't block others
                        }
                    }
                }
                .awaitAll()
        }
    }

    fun isConnected(serverId: String): Boolean = clients.containsKey(serverId)

    private fun generateServerId(name: String, existing: List<McpServerConfig>): String {
        val base = name.lowercase().replace(serverIdRegex, "_").take(30)
        val existingIds = existing.map { it.id }.toSet()
        if (base !in existingIds) return base
        var counter = 2
        while ("${base}_$counter" in existingIds) counter++
        return "${base}_$counter"
    }
}

private val toolNameInvalidChars = Regex("[^A-Za-z0-9_-]")

/**
 * [toolName] itself when free; otherwise `<serverId>_<toolName>` (then `_2`, `_3`, …), restricted
 * to the characters and 64-char length providers accept for function names.
 */
internal fun uniqueMcpToolName(serverId: String, toolName: String, taken: Set<String>): String {
    if (toolName !in taken) return toolName
    val base = "${serverId}_$toolName".replace(toolNameInvalidChars, "_").take(64)
    if (base !in taken) return base
    var n = 2
    while (true) {
        val suffix = "_$n"
        val candidate = base.take(64 - suffix.length) + suffix
        if (candidate !in taken) return candidate
        n++
    }
}
