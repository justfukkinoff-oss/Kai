package com.inspiredandroid.kai.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inspiredandroid.kai.mcp.PopularMcpServer
import com.inspiredandroid.kai.Platform
import com.inspiredandroid.kai.currentPlatform
import com.inspiredandroid.kai.mcp.MCP_OAUTH_REDIRECT
import com.inspiredandroid.kai.mcp.REVISE_MCP_URL
import com.inspiredandroid.kai.mcp.McpException
import com.inspiredandroid.kai.mcp.McpServerManager
import com.inspiredandroid.kai.mcp.authorizationHeaderValue
import com.inspiredandroid.kai.mcp.popularMcpServers
import com.inspiredandroid.kai.ui.KaiOutlinedTextField
import com.inspiredandroid.kai.ui.components.VerticalScrollbarForScroll
import com.inspiredandroid.kai.ui.handCursor
import com.inspiredandroid.kai.ui.kaiAdaptiveCardBorder
import com.inspiredandroid.kai.ui.kaiAdaptiveCardColors
import kai.composeapp.generated.resources.Res
import kai.composeapp.generated.resources.ic_arrow_drop_down
import kai.composeapp.generated.resources.settings_mcp_add
import kai.composeapp.generated.resources.settings_mcp_add_header
import kai.composeapp.generated.resources.settings_mcp_add_server
import kai.composeapp.generated.resources.settings_mcp_api_key
import kai.composeapp.generated.resources.settings_mcp_api_key_help
import kai.composeapp.generated.resources.settings_mcp_header_key
import kai.composeapp.generated.resources.settings_mcp_header_value
import kai.composeapp.generated.resources.settings_mcp_no_tools
import kai.composeapp.generated.resources.settings_mcp_popular_servers
import kai.composeapp.generated.resources.settings_mcp_refresh
import kai.composeapp.generated.resources.settings_mcp_remove
import kai.composeapp.generated.resources.settings_mcp_server_name
import kai.composeapp.generated.resources.settings_mcp_server_url
import kai.composeapp.generated.resources.settings_mcp_servers
import kai.composeapp.generated.resources.settings_mcp_servers_description
import kai.composeapp.generated.resources.settings_mcp_status_connected
import kai.composeapp.generated.resources.settings_mcp_status_connecting
import kai.composeapp.generated.resources.settings_mcp_status_error
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource
import org.koin.compose.koinInject

@Composable
internal fun McpServersSection(
    mcpServers: ImmutableList<McpServerUiState>,
    onAddMcpServer: (String, String, Map<String, String>) -> Unit,
    onRemoveMcpServer: (String) -> Unit,
    onToggleMcpServer: (String, Boolean) -> Unit,
    onRefreshMcpServer: (String) -> Unit,
    onToggleTool: (String, Boolean) -> Unit,
    showAddDialog: Boolean,
    onShowAddDialog: (Boolean) -> Unit,
    onAddPopularMcpServer: (PopularMcpServer) -> Unit,
) {
    val manager: McpServerManager = koinInject()
    val oauthEvent by manager.oauthEvents.collectAsState()
    LaunchedEffect(oauthEvent) {
        oauthEvent?.takeIf { it.connected }?.let { onRefreshMcpServer(it.serverId) }
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(Res.string.settings_mcp_servers),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(Res.string.settings_mcp_servers_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(12.dp))

        for (server in mcpServers) {
            McpServerCard(
                server = server,
                onToggle = { enabled -> onToggleMcpServer(server.id, enabled) },
                onRemove = { onRemoveMcpServer(server.id) },
                onRefresh = { onRefreshMcpServer(server.id) },
                onToggleTool = onToggleTool,
            )
            Spacer(Modifier.height(8.dp))
        }

        OutlinedButton(
            onClick = { onShowAddDialog(true) },
            modifier = Modifier.align(Alignment.CenterHorizontally).handCursor(),
        ) {
            Text(stringResource(Res.string.settings_mcp_add_server))
        }
    }

    if (showAddDialog) {
        AddMcpServerDialog(
            onDismiss = { onShowAddDialog(false) },
            onAdd = onAddMcpServer,
            onAddPopular = onAddPopularMcpServer,
            onServerAvailable = onRefreshMcpServer,
        )
    }
}

@Composable
private fun McpServerCard(
    server: McpServerUiState,
    onToggle: (Boolean) -> Unit,
    onRemove: () -> Unit,
    onRefresh: () -> Unit,
    onToggleTool: (String, Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        onClick = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth().handCursor(),
        colors = kaiAdaptiveCardColors(),
        border = kaiAdaptiveCardBorder(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Status dot
                val statusColor = when (server.connectionStatus) {
                    McpConnectionStatus.Connected -> StatusColorConnected
                    McpConnectionStatus.Connecting -> StatusColorChecking
                    McpConnectionStatus.Error -> StatusColorError
                    McpConnectionStatus.Unknown -> StatusColorUnknown
                }
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(statusColor),
                )
                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = server.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = server.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Switch(
                    checked = server.isEnabled,
                    onCheckedChange = onToggle,
                )

                Spacer(Modifier.width(8.dp))

                Icon(
                    imageVector = vectorResource(Res.drawable.ic_arrow_drop_down),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (currentPlatform is Platform.Mobile.Android) McpOAuthCardControls(server.id, server.url, server.isEnabled)

            if (expanded) {
                Spacer(Modifier.height(12.dp))

                // Status text
                val statusText = when (server.connectionStatus) {
                    McpConnectionStatus.Connected -> stringResource(Res.string.settings_mcp_status_connected)
                    McpConnectionStatus.Connecting -> stringResource(Res.string.settings_mcp_status_connecting)
                    McpConnectionStatus.Error -> stringResource(Res.string.settings_mcp_status_error)
                    McpConnectionStatus.Unknown -> ""
                }
                if (statusText.isNotEmpty()) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = when (server.connectionStatus) {
                            McpConnectionStatus.Error -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                }

                // Tools list
                if (server.tools.isNotEmpty()) {
                    for (tool in server.tools) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = tool.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onBackground,
                                )
                                if (tool.description.isNotEmpty()) {
                                    Text(
                                        text = tool.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Switch(
                                checked = tool.isEnabled,
                                onCheckedChange = { enabled -> onToggleTool(tool.id, enabled) },
                            )
                        }
                    }
                } else if (server.connectionStatus == McpConnectionStatus.Connected) {
                    Text(
                        text = stringResource(Res.string.settings_mcp_no_tools),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(8.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onRefresh, modifier = Modifier.handCursor()) {
                        Text(stringResource(Res.string.settings_mcp_refresh))
                    }
                    TextButton(onClick = onRemove, modifier = Modifier.handCursor()) {
                        Text(
                            text = stringResource(Res.string.settings_mcp_remove),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun McpOAuthCardControls(serverId: String, url: String, enabled: Boolean) {
    val manager: McpServerManager = koinInject()
    val event by manager.oauthEvents.collectAsState()
    val uriHandler = LocalUriHandler.current
    val coroutine = rememberCoroutineScope()
    var show by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var clientId by remember { mutableStateOf("") }
    var scopes by remember { mutableStateOf("") }
    var note by remember(event) { mutableStateOf("") }
    val hasSession = manager.hasOAuth(serverId)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(enabled = enabled && !busy, onClick = { show = !show }) {
            Text(if (show) "Hide OAuth settings" else if (hasSession) "Reconnect OAuth" else "Sign in with OAuth")
        }
        if (hasSession) TextButton(onClick = { manager.forgetOAuth(serverId) }) { Text("Sign out") }
    }
    if (show) {
        OAuthFields(clientId, { clientId = it }, scopes, { scopes = it }, url)
        Button(enabled = !busy, onClick = {
            busy = true
            note = "Discovering OAuth settings…"
            coroutine.launch {
                try {
                    uriHandler.openUri(manager.beginOAuth(serverId, clientId, scopes))
                    note = "Complete sign-in in your browser and return to Kai."
                } catch (e: CancellationException) {
                    throw e
                } catch (e: McpException) {
                    note = e.message ?: "Could not start OAuth sign-in."
                } catch (_: Exception) {
                    note = "Could not open OAuth sign-in."
                } finally {
                    busy = false
                }
            }
        }) { Text("Open browser to sign in") }
    }
    val status = note.ifBlank { event?.takeIf { it.serverId == serverId }?.message.orEmpty() }
    if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun OAuthFields(clientId: String, onClientId: (String) -> Unit, scopes: String, onScopes: (String) -> Unit, url: String) {
    if (url.trim().trimEnd('/').equals(REVISE_MCP_URL, ignoreCase = true)) {
        Text("Revise uses browser sign-in and returns through an on-device callback. No client ID, API key, or custom headers are needed.")
        return
    }
    Text("Uses OAuth discovery and PKCE. Leave Client ID blank if the provider supports automatic registration. Otherwise use a public client registered with this callback:")
    SelectionContainer { Text(MCP_OAUTH_REDIRECT, style = MaterialTheme.typography.bodySmall) }
    KaiOutlinedTextField(clientId, onClientId, modifier = Modifier.fillMaxWidth(),
        label = { Text("Public client ID or client metadata URL") }, singleLine = true)
    KaiOutlinedTextField(scopes, onScopes, modifier = Modifier.fillMaxWidth(),
        label = { Text("Scopes (optional, space-separated)") }, singleLine = true)
}

private data class HeaderEntry(val key: String = "Authorization", val value: String = "")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddMcpServerDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String, Map<String, String>) -> Unit,
    onAddPopular: (PopularMcpServer) -> Unit,
    onServerAvailable: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    val headers = remember { mutableStateListOf(HeaderEntry()) }
    // When a popular server needs auth (e.g. Jina), prefill the form and show an API key field.
    var requiresAuth by remember { mutableStateOf(false) }
    var apiKey by remember { mutableStateOf("") }
    val mcpScrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val manager: McpServerManager = koinInject()
    var oauthMode by remember { mutableStateOf(false) }
    var oauthClientId by remember { mutableStateOf("") }
    var oauthScopes by remember { mutableStateOf("") }
    var oauthBusy by remember { mutableStateOf(false) }
    var oauthMessage by remember { mutableStateOf("") }
    var oauthServerId by remember { mutableStateOf<String?>(null) }

    fun prefillPopularWithAuth(server: PopularMcpServer) {
        name = server.name
        url = server.url
        requiresAuth = true
        oauthMode = false
        apiKey = ""
        headers.clear()
        headers.add(HeaderEntry(key = "Authorization", value = ""))
        scope.launch { mcpScrollState.animateScrollTo(0) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Box {
            Column(
                modifier = Modifier
                    .verticalScroll(mcpScrollState)
                    .padding(16.dp),
            ) {
                Text(
                    text = stringResource(Res.string.settings_mcp_add_server),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(16.dp))

                KaiOutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(Res.string.settings_mcp_server_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                KaiOutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(Res.string.settings_mcp_server_url)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))

                if (currentPlatform is Platform.Mobile.Android) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !oauthMode, onClick = { oauthMode = false }, label = { Text("Headers or no auth") })
                        FilterChip(selected = oauthMode, onClick = { oauthMode = true }, label = { Text("OAuth") })
                    }
                }
                if (oauthMode) {
                    OAuthFields(oauthClientId, { oauthClientId = it }, oauthScopes, { oauthScopes = it }, url)
                    if (oauthMessage.isNotBlank()) Text(oauthMessage, style = MaterialTheme.typography.bodySmall)
                } else if (requiresAuth) {
                    KaiOutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text(stringResource(Res.string.settings_mcp_api_key)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(Res.string.settings_mcp_api_key_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                } else {
                    headers.forEachIndexed { index, entry ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // The floating labels must stay on one line: at large font
                            // scales a wrapped label grows upward out of the field and
                            // lands on top of the URL field above it.
                            KaiOutlinedTextField(
                                value = entry.key,
                                onValueChange = { headers[index] = entry.copy(key = it) },
                                label = {
                                    Text(
                                        text = stringResource(Res.string.settings_mcp_header_key),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                singleLine = true,
                                modifier = Modifier.weight(0.5f),
                            )
                            Spacer(Modifier.width(8.dp))
                            KaiOutlinedTextField(
                                value = entry.value,
                                onValueChange = { headers[index] = entry.copy(value = it) },
                                label = {
                                    Text(
                                        text = stringResource(Res.string.settings_mcp_header_value),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                singleLine = true,
                                modifier = Modifier.weight(0.5f),
                            )
                            IconButton(
                                onClick = { headers.removeAt(index) },
                                modifier = Modifier.handCursor(),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = stringResource(Res.string.settings_mcp_remove),
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }

                    TextButton(
                        onClick = { headers.add(HeaderEntry(key = "", value = "")) },
                        modifier = Modifier.handCursor(),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(Res.string.settings_mcp_add_header))
                    }
                }

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = {
                            val headerMap = buildMap {
                                headers
                                    .filter { it.key.isNotBlank() && it.value.isNotBlank() }
                                    .forEach { put(it.key.trim(), it.value.trim()) }
                                if (requiresAuth && apiKey.isNotBlank()) {
                                    // Replace any Authorization row with the dedicated API key field.
                                    keys.filter { it.equals("Authorization", ignoreCase = true) }
                                        .toList()
                                        .forEach { remove(it) }
                                    put("Authorization", authorizationHeaderValue(apiKey))
                                }
                            }
                            if (oauthMode) {
                                val endpoint = url.trim()
                                if (!endpoint.startsWith("https://", ignoreCase = true)) {
                                    oauthMessage = "Enter an HTTPS MCP endpoint."
                                    return@TextButton
                                }
                                oauthBusy = true
                                oauthMessage = "Discovering OAuth settings…"
                                scope.launch {
                                    try {
                                        val prior = manager.getServers().firstOrNull { it.id == oauthServerId && it.url == endpoint }
                                        val entry = prior ?: manager.addServer(name.trim(), endpoint, emptyMap())
                                        oauthServerId = entry.id
                                        uriHandler.openUri(manager.beginOAuth(entry.id, oauthClientId, oauthScopes))
                                        onServerAvailable(entry.id)
                                        onDismiss()
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: McpException) {
                                        oauthMessage = e.message ?: "Could not start OAuth sign-in."
                                        oauthServerId?.let(onServerAvailable)
                                    } catch (_: Exception) {
                                        oauthMessage = "Could not open OAuth sign-in."
                                        oauthServerId?.let(onServerAvailable)
                                    } finally {
                                        oauthBusy = false
                                    }
                                }
                            } else onAdd(name, url, headerMap)
                        },
                        // Auth-required popular servers (e.g. Jina) can still be added without a
                        // key — some tools work unauthenticated; search needs a key later.
                        enabled = name.isNotBlank() && url.isNotBlank() && !oauthBusy,
                        modifier = Modifier.handCursor(),
                    ) {
                        Text(if (oauthMode) "Add and sign in" else stringResource(Res.string.settings_mcp_add))
                    }
                }

                if (popularMcpServers.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(Res.string.settings_mcp_popular_servers),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(8.dp))
                    for (server in popularMcpServers) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(CardDefaults.shape)
                                .clickable {
                                    if (server.requiresAuth) {
                                        prefillPopularWithAuth(server)
                                    } else {
                                        onAddPopular(server)
                                    }
                                }
                                .handCursor(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            ),
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = server.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = server.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }

                Spacer(Modifier.height(16.dp))
            }
            VerticalScrollbarForScroll(
                scrollState = mcpScrollState,
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            )
        }
    }
}
