@file:OptIn(ExperimentalEncodingApi::class, ExperimentalTime::class, ExperimentalUuidApi::class)

package com.inspiredandroid.kai.data

import com.inspiredandroid.kai.SandboxController
import com.inspiredandroid.kai.compressImageBytes
import com.inspiredandroid.kai.currentPlatform
import com.inspiredandroid.kai.getBackgroundDispatcher
import com.inspiredandroid.kai.data.providers.buildAnthropicMessages
import com.inspiredandroid.kai.data.providers.buildOpenAIMessages
import com.inspiredandroid.kai.data.providers.toResponsesInput
import com.inspiredandroid.kai.email.EmailPoller
import com.inspiredandroid.kai.formatFileSize
import com.inspiredandroid.kai.getAvailableTools
import com.inspiredandroid.kai.getPlatformToolDefinitions
import com.inspiredandroid.kai.inference.DownloadError
import com.inspiredandroid.kai.inference.DownloadedModel
import com.inspiredandroid.kai.inference.EngineState
import com.inspiredandroid.kai.inference.InferenceMessage
import com.inspiredandroid.kai.inference.LocalInferenceEngine
import com.inspiredandroid.kai.inference.LocalModel
import com.inspiredandroid.kai.inference.LocalTool
import com.inspiredandroid.kai.inference.ModelImportError
import com.inspiredandroid.kai.inference.ModelImportResult
import com.inspiredandroid.kai.inference.NoModelDownloadedException
import com.inspiredandroid.kai.inference.getTotalMemoryBytes
import com.inspiredandroid.kai.linux.LinuxDistro
import com.inspiredandroid.kai.mcp.McpServerConfig
import com.inspiredandroid.kai.mcp.McpServerManager
import com.inspiredandroid.kai.network.AllServicesFailedException
import com.inspiredandroid.kai.network.AnthropicInsufficientCreditsException
import com.inspiredandroid.kai.network.AnthropicInvalidApiKeyException
import com.inspiredandroid.kai.network.ContextWindowExceededException
import com.inspiredandroid.kai.network.FileTooLargeException
import com.inspiredandroid.kai.network.GeminiInvalidApiKeyException
import com.inspiredandroid.kai.network.OpenAICompatibleBadRequestException
import com.inspiredandroid.kai.network.OpenAICompatibleContentModerationException
import com.inspiredandroid.kai.network.OpenAICompatibleEmptyResponseException
import com.inspiredandroid.kai.network.OpenAICompatibleGenericException
import com.inspiredandroid.kai.network.OpenAICompatibleInvalidApiKeyException
import com.inspiredandroid.kai.network.OpenAICompatibleModelNotFoundException
import com.inspiredandroid.kai.network.OpenAICompatibleQuotaExhaustedException
import com.inspiredandroid.kai.network.OpenAICompatibleRequestTooLargeException
import com.inspiredandroid.kai.network.Requests
import com.inspiredandroid.kai.network.ServiceCredentials
import com.inspiredandroid.kai.network.UnsupportedFileTypeException
import com.inspiredandroid.kai.network.dtos.anthropic.extractText
import com.inspiredandroid.kai.network.dtos.gemini.extractText
import com.inspiredandroid.kai.network.dtos.openaicompatible.extractInlineToolCalls
import com.inspiredandroid.kai.network.dtos.openairesponses.OpenAIResponsesResponseDto
import com.inspiredandroid.kai.network.toUiError
import com.inspiredandroid.kai.network.tools.Tool
import com.inspiredandroid.kai.network.tools.ToolInfo
import com.inspiredandroid.kai.skills.RegistrySkillEntry
import com.inspiredandroid.kai.skills.SkillManager
import com.inspiredandroid.kai.skills.SkillManifest
import com.inspiredandroid.kai.sms.SmsPoller
import com.inspiredandroid.kai.sms.SmsReader
import com.inspiredandroid.kai.sms.SmsSendResult
import com.inspiredandroid.kai.sms.SmsSender
import com.inspiredandroid.kai.tools.NotificationListenerController
import com.inspiredandroid.kai.tools.CitationCheckTool
import com.inspiredandroid.kai.tools.PermissionController
import com.inspiredandroid.kai.ui.chat.History
import com.inspiredandroid.kai.ui.chat.ToolCallInfo
import com.inspiredandroid.kai.ui.chat.toGeminiMessageDto
import com.inspiredandroid.kai.ui.settings.SettingsModel
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.mimeType
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import io.github.vinceglb.filekit.size
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private const val MAX_TOOL_ITERATIONS = 15
private const val MIN_TOOL_DISPLAY_MS = 2000L
private const val MAX_REPEATED_TOOL_CALLS = 3
private const val MAX_API_RETRIES = 2
private const val MAX_HEARTBEAT_MESSAGES = 50
private const val BINARY_UPLOAD_DIR = "/root/chat-uploads/"
private const val ESTIMATED_CHARS_PER_TOKEN = 4

/**
 * The Responses API reports a failed turn inside a 200 body (`status: "failed"`), which would
 * otherwise read as an empty answer. Surface OpenAI's message instead of a generic empty-response
 * error.
 */
private fun OpenAIResponsesResponseDto.throwIfFailed(service: Service) {
    val message = error?.message ?: return
    throw OpenAICompatibleGenericException("${service.displayName}: $message")
}

private data class LoopChatResult(
    val textContent: String,
    val reasoningContent: String? = null,
    val isThinkingContent: Boolean = false,
    val toolCalls: List<ToolCallInfo>,
)

/** Final answer from a single assistant turn — text and (optionally) the reasoning trace
 * that produced it. Returned from [askWithService] so the caller can persist both. */
private data class AssistantTurn(
    val content: String,
    val reasoningContent: String? = null,
)

private enum class BailoutReason { LIMIT_REACHED, REPEATING }

private fun bailoutPrompt(reason: BailoutReason): String = when (reason) {
    BailoutReason.LIMIT_REACHED -> "You have reached the tool call limit. Please respond with the best answer you have so far based on the information gathered."
    BailoutReason.REPEATING -> "You are repeating the same tool calls. Please respond with the best answer you have so far."
}

private interface ToolLoopStrategy {
    suspend fun chat(history: List<History>, systemPrompt: String?): LoopChatResult
    suspend fun bailout(history: List<History>, systemPrompt: String?, reason: BailoutReason): String

    /**
     * Context budget used to trim the request copy of the history each tool round. Providers that
     * send the history as-is (Gemini, Anthropic) declare their window here; the OpenAI-compatible
     * strategy trims the built message list inside [chat] instead and leaves this null.
     */
    val historyContextWindowTokens: Int? get() = null
}

class RemoteDataRepository(
    private val requests: Requests,
    private val appSettings: AppSettings,
    private val conversationStorage: ConversationStorage,
    private val toolExecutor: ToolExecutor,
    private val memoryStore: MemoryStore,
    private val taskStore: TaskStore,
    private val heartbeatManager: HeartbeatManager,
    private val emailStore: EmailStore,
    private val emailPoller: EmailPoller,
    private val smsStore: SmsStore,
    private val smsPoller: SmsPoller,
    private val smsReader: SmsReader,
    private val smsPermissionController: PermissionController,
    private val smsSendPermissionController: PermissionController,
    private val smsSender: SmsSender,
    private val smsDraftStore: SmsDraftStore,
    private val notificationStore: NotificationStore,
    private val notificationListenerController: NotificationListenerController,
    private val mcpServerManager: McpServerManager,
    private val skillManager: SkillManager,
    private val sandboxController: SandboxController,
    private val objectiveStore: ObjectiveStore,
    private val localInferenceEngine: LocalInferenceEngine? = null,
) : DataRepository {

    private val prettyJson = Json { prettyPrint = true }
    private val continuity = ConversationContinuity(appSettings)
    private val backgroundScope = CoroutineScope(SupervisorJob() + getBackgroundDispatcher())

    /**
     * Whether the on-device model file for [modelId] carries a tool section in its chat
     * template. A model that doesn't isn't merely worse at tools — it answers *instead* of
     * calling them, inventing whatever the tool would have returned, which is how Qwen3
     * 0.6B reports a fictional time rather than calling `get_local_time`. Withholding the
     * tools makes it a plain chat model, which is what it actually is.
     *
     * The engine reports null when it cannot read the declaration (iOS, or a bundle whose
     * metadata predates it). That is *unknown*, not *no*: keep offering the allowlist there
     * so existing setups behave exactly as before.
     */
    private suspend fun localModelDeclaresTools(modelId: String): Boolean = localInferenceEngine?.modelCapabilities(modelId)?.supportsFunctionCalling != false

    // Per-instance model storage: instanceId -> models flow
    private val modelsByInstance: MutableMap<String, MutableStateFlow<List<SettingsModel>>> = mutableMapOf()

    /** Build credentials from per-instance settings */
    private fun instanceCredentials(instanceId: String, service: Service): ServiceCredentials = ServiceCredentials(
        apiKey = appSettings.getInstanceApiKey(instanceId),
        modelId = if (service == Service.Free) {
            appSettings.getFreeMode().modelId
        } else {
            appSettings.getInstanceEffectiveModelId(instanceId).ifEmpty { appSettings.getSelectedModelId(service) }
        },
        baseUrl = getInstanceBaseUrl(instanceId, service),
    )

    override val chatHistory: MutableStateFlow<List<History>> = MutableStateFlow(emptyList())

    private val _currentConversationId = MutableStateFlow<String?>(null)
    override val currentConversationId: StateFlow<String?> = _currentConversationId

    private val _fallbackStatus = MutableStateFlow<FallbackStatus?>(null)
    override val fallbackStatus: StateFlow<FallbackStatus?> = _fallbackStatus

    override val savedConversations: StateFlow<List<Conversation>> = conversationStorage.conversations

    override fun getConfiguredServiceInstances(): List<ServiceInstance> = appSettings.getConfiguredServiceInstances().filter { Service.fromId(it.serviceId) != Service.Free }

    override fun addConfiguredService(serviceId: String): ServiceInstance {
        val instanceId = appSettings.generateInstanceId(serviceId)
        val instance = ServiceInstance(instanceId = instanceId, serviceId = serviceId)
        val current = appSettings.getConfiguredServiceInstances().toMutableList()
        current.add(instance)
        appSettings.setConfiguredServiceInstances(current)
        appSettings.setFreeServicePrimary(false)
        return instance
    }

    override fun removeConfiguredService(instanceId: String) {
        val current = appSettings.getConfiguredServiceInstances().toMutableList()
        current.removeAll { it.instanceId == instanceId }
        appSettings.setConfiguredServiceInstances(current)
        appSettings.removeInstanceSettings(instanceId)
        modelsByInstance.remove(instanceId)
    }

    override fun reorderConfiguredServices(orderedInstanceIds: List<String>) {
        val current = appSettings.getConfiguredServiceInstances()
        val byId = current.associateBy { it.instanceId }
        val reordered = orderedInstanceIds.mapNotNull { byId[it] }
        appSettings.setConfiguredServiceInstances(reordered)
    }

    override fun getServiceEntries(): List<ServiceEntry> = getConfiguredServiceInstances().map { instance ->
        val service = Service.fromId(instance.serviceId)
        val modelId = appSettings.getInstanceEffectiveModelId(instance.instanceId).ifEmpty {
            appSettings.getSelectedModelId(service)
        }
        ServiceEntry(
            instanceId = instance.instanceId,
            serviceId = service.id,
            serviceName = service.displayName,
            modelId = modelId,
            icon = service.icon,
        )
    }

    override fun isFreeFallbackEnabled(): Boolean = appSettings.isFreeFallbackEnabled()

    override fun setFreeFallbackEnabled(enabled: Boolean) {
        appSettings.setFreeFallbackEnabled(enabled)
    }

    override fun getFreeMode(): FreeMode = appSettings.getFreeMode()

    override fun setFreeMode(mode: FreeMode) {
        appSettings.setFreeMode(mode)
    }

    override fun isFreeServicePrimary(): Boolean = appSettings.isFreeServicePrimary()

    override fun setFreeServicePrimary(primary: Boolean) {
        appSettings.setFreeServicePrimary(primary)
    }

    // Per-instance settings
    override fun getInstanceApiKey(instanceId: String): String = appSettings.getInstanceApiKey(instanceId)

    override fun updateInstanceApiKey(instanceId: String, apiKey: String) {
        appSettings.setInstanceApiKey(instanceId, apiKey)
    }

    override fun getInstanceBaseUrl(instanceId: String, service: Service): String {
        val url = appSettings.getInstanceBaseUrl(instanceId)
        return url.ifBlank { if (service is Service.OpenAICompatible) Service.DEFAULT_OPENAI_COMPATIBLE_BASE_URL else "" }
    }

    override fun updateInstanceBaseUrl(instanceId: String, baseUrl: String) {
        appSettings.setInstanceBaseUrl(instanceId, baseUrl)
    }

    override fun getInstanceModels(instanceId: String, service: Service): StateFlow<List<SettingsModel>> = modelsByInstance.getOrPut(instanceId) {
        val selectedModelId = appSettings.getInstanceModelId(instanceId)
        val defaultSettingsModels = service.defaultModels.map {
            SettingsModel(
                id = it.id,
                subtitle = it.subtitle,
                descriptionRes = it.descriptionRes,
                isSelected = it.id == selectedModelId,
            )
        }
        val models = if (selectedModelId.isNotEmpty() && defaultSettingsModels.none { it.id == selectedModelId }) {
            listOf(
                SettingsModel(
                    id = selectedModelId,
                    subtitle = "",
                    isSelected = true,
                    isManualEntry = true,
                ),
            ) + defaultSettingsModels
        } else {
            defaultSettingsModels
        }
        MutableStateFlow(models)
    }

    override fun updateInstanceSelectedModel(instanceId: String, service: Service, modelId: String) {
        appSettings.setInstanceModelId(instanceId, modelId)
        modelsByInstance[instanceId]?.update { models ->
            ensureSelectedModelPresent(models, modelId)
        }
        // Free the previously-loaded on-device model as soon as the user picks a new one.
        // Deferring until the next chat would briefly hold both models' GPU buffers resident
        // and the driver's lazy reclaim can push us past LMK thresholds on mid-range devices.
        if (service.isOnDevice && localInferenceEngine?.currentModelId?.let { it != modelId } == true) {
            localInferenceEngine.releaseInBackground()
        }
    }

    override fun getInstanceUseCustomModel(instanceId: String): Boolean = appSettings.getInstanceUseCustomModel(instanceId)

    override fun updateInstanceUseCustomModel(instanceId: String, useCustom: Boolean) {
        if (useCustom && appSettings.getInstanceCustomModelId(instanceId).isBlank()) {
            // Prefill from list selection so the field is not empty when the checkbox is first enabled.
            val listModelId = appSettings.getInstanceModelId(instanceId)
            if (listModelId.isNotBlank()) {
                appSettings.setInstanceCustomModelId(instanceId, listModelId)
            }
        }
        appSettings.setInstanceUseCustomModel(instanceId, useCustom)
    }

    override fun getInstanceCustomModelId(instanceId: String): String = appSettings.getInstanceCustomModelId(instanceId)

    override fun updateInstanceCustomModelId(instanceId: String, modelId: String) {
        appSettings.setInstanceCustomModelId(instanceId, modelId)
    }

    override fun clearInstanceModels(instanceId: String, service: Service) {
        modelsByInstance[instanceId]?.update { emptyList() }
    }

    override suspend fun validateConnection(service: Service, instanceId: String) {
        if (service.isOnDevice) {
            fetchInstanceModels(service, instanceId)
            return
        }
        val creds = instanceCredentials(instanceId, service)
        when (service) {
            Service.Free -> { /* Always valid */ }

            Service.OpenRouter -> {
                requests.validateOpenRouterApiKey(creds).getOrThrow()
                fetchInstanceModels(service, instanceId)
            }

            Service.Requesty -> {
                requests.validateRequestyApiKey(creds).getOrThrow()
                fetchInstanceModels(service, instanceId)
            }

            Service.Perplexity -> {
                // No Sonar `/models` list — validate the key, then load the curated defaults.
                requests.validatePerplexityApiKey(creds).getOrThrow()
                fetchInstanceModels(service, instanceId)
            }

            else -> fetchInstanceModels(service, instanceId)
        }
    }

    private suspend fun fetchInstanceModels(service: Service, instanceId: String) {
        when (service) {
            Service.Gemini -> fetchModelsForInstance(service, instanceId) { creds, selectedModelId ->
                mapGeminiModels(requests.getGeminiModels(creds).getOrThrow().models, selectedModelId)
            }

            Service.Anthropic -> fetchModelsForInstance(service, instanceId) { creds, selectedModelId ->
                mapAnthropicModels(requests.getAnthropicModels(creds).getOrThrow().data, selectedModelId)
            }

            Service.Free -> { /* No model listing */ }

            Service.LiteRT -> {
                val engine = localInferenceEngine ?: return
                val selectedModelId = appSettings.getInstanceModelId(instanceId)
                val downloaded = engine.getDownloadedModels()
                val models = downloaded.map {
                    SettingsModel(
                        id = it.id,
                        subtitle = "${it.displayName} (${formatFileSize(it.sizeBytes)})",
                        isSelected = it.id == selectedModelId,
                    )
                }
                updateModelsForInstance(instanceId, models, service)
            }

            else -> {
                if (service.modelsUrl != null) {
                    fetchModelsForInstance(service, instanceId) { creds, selectedModelId ->
                        mapOpenAICompatibleModels(
                            requests.getOpenAICompatibleModels(service, creds).getOrThrow().data,
                            service,
                            selectedModelId,
                        )
                    }
                } else if (service.defaultModels.isNotEmpty()) {
                    val selectedModelId = appSettings.getInstanceModelId(instanceId)
                    val models = service.defaultModels.map {
                        SettingsModel(
                            id = it.id,
                            subtitle = it.subtitle,
                            descriptionRes = it.descriptionRes,
                            isSelected = it.id == selectedModelId,
                        )
                    }
                    updateModelsForInstance(instanceId, models, service)
                }
            }
        }
    }

    /**
     * Fetches [service]'s model list for [instanceId] and publishes it. [fetchAndMap] is the only
     * per-provider part: it issues the request and maps the response to [SettingsModel]s, given
     * the instance credentials and the currently selected model id.
     */
    private suspend fun fetchModelsForInstance(
        service: Service,
        instanceId: String,
        fetchAndMap: suspend (ServiceCredentials, String) -> List<SettingsModel>,
    ) {
        val creds = instanceCredentials(instanceId, service)
        val selectedModelId = appSettings.getInstanceModelId(instanceId)
        updateModelsForInstance(instanceId, fetchAndMap(creds, selectedModelId))
    }

    private fun updateModelsForInstance(instanceId: String, models: List<SettingsModel>, service: Service? = null) {
        val selectedModelId = appSettings.getInstanceModelId(instanceId)
        val withSelected = ensureSelectedModelPresent(models, selectedModelId)
        val flow = modelsByInstance.getOrPut(instanceId) { MutableStateFlow(emptyList()) }
        flow.update { withSelected }
        // Only auto-pick when nothing is stored yet — never overwrite a custom / unlisted model id.
        if (withSelected.isNotEmpty() && withSelected.none { it.isSelected }) {
            val default = pickDefaultModel(withSelected, service)
            if (default != null) {
                appSettings.setInstanceModelId(instanceId, default.id)
                flow.update { m -> m.map { it.copy(isSelected = it.id == default.id) } }
            }
        }
    }

    private fun pickDefaultModel(models: List<SettingsModel>, service: Service? = null): SettingsModel? {
        val defaultModel = service?.defaultModel
        if (defaultModel != null) {
            models.firstOrNull { it.id == defaultModel }?.let { return it }
        }
        return models.firstOrNull { it.id.contains("kimi-k2.5", ignoreCase = true) }
            ?: models.firstOrNull()
    }

    private suspend fun askWithLocalEngine(
        messages: List<History>,
        systemPrompt: String?,
        instanceId: String,
        history: MutableStateFlow<List<History>> = chatHistory,
    ): String {
        val engine = localInferenceEngine
            ?: throw IllegalStateException("On-device inference not available on this platform")

        val modelId = appSettings.getInstanceModelId(instanceId)
        val downloadedModels = engine.getDownloadedModels()
        val model = downloadedModels.find { it.id == modelId }
            ?: downloadedModels.firstOrNull()
            ?: throw NoModelDownloadedException()

        val catalogModel = engine.getAvailableModels().find { it.id == model.id }
        val storedContext = appSettings.getModelContextTokens(model.id)
        val contextTokens = if (storedContext > 0) storedContext else catalogModel?.defaultContextTokens ?: 0

        val needsInit = engine.engineState.value != EngineState.READY || engine.currentModelId != model.id
        if (needsInit) {
            val statusEntry = History(
                role = History.Role.TOOL_EXECUTING,
                content = "",
                toolName = "Initializing ${model.displayName}",
                isStatusMessage = true,
            )
            history.update { it + statusEntry }
            try {
                engine.initialize(model, contextTokens)
            } finally {
                history.update { h -> h.filter { it.id != statusEntry.id } }
            }
        } else {
            engine.initialize(model, contextTokens)
        }

        // Callers pass either a CHAT_LOCAL system prompt (chat + silent paths) or null
        // (Splinterlands via `askSilentlyWithInstance`, where the caller owns the full
        // prompt shape). We hand whichever one through to the engine unchanged.
        // Native litert-lm `automaticToolCalling` owns the tool loop — our allowlisted
        // tools are passed once via [localToolDescriptionJson] and the engine drives them.
        val objectiveRecovery = currentObjectiveRun() != null
        val localTools: List<LocalTool> = if (localModelDeclaresTools(model.id)) {
            getAvailableTools().map { tool ->
                LocalTool(
                    name = tool.schema.name,
                    descriptionJsonString = localToolDescriptionJson(tool),
                    execute = { jsonArgs -> runLocalToolWithUiFeedback(tool.schema.name, jsonArgs, history) },
                )
            }
        } else {
            emptyList()
        }
        check(!objectiveRecovery || localTools.any { it.name == "objective_set_status" }) {
            "The Objective model does not have the checkpoint tool"
        }

        val inferenceMessages = messages.mapNotNull { msg ->
            when (msg.role) {
                History.Role.USER -> InferenceMessage(role = "user", content = msg.content)
                History.Role.ASSISTANT -> InferenceMessage(role = "assistant", content = msg.content)
                else -> null
            }
        }

        return try {
            engine.chat(messages = inferenceMessages, systemPrompt = systemPrompt, tools = localTools)
        } catch (e: RuntimeException) {
            if (e is kotlinx.coroutines.CancellationException || objectiveRecovery) throw e
            // litert-lm's strict ANTLR function-call parser sometimes rejects malformed
            // tool-call output from small Gemma models, throwing INVALID_ARGUMENT from JNI.
            // Retry once without tools so the user gets *some* answer rather than a hard
            // error in the UI. With an empty tool list, LiteRTInferenceEngine sets
            // automaticToolCalling = false, so the parser is bypassed entirely on the retry.
            println("LiteRT: tool-call parser failed (${e.message?.take(200)}). Falling back to plain chat.")
            engine.chat(messages = inferenceMessages, systemPrompt = systemPrompt, tools = emptyList())
        }
    }

    /**
     * Cached OpenAPI/OpenAI-style JSON descriptions for local tools, keyed by tool name.
     * Schemas are static for allowlisted tools, so serializing them once per tool avoids
     * re-running the JSON builder on every message.
     */
    private val localToolDescriptionJsonCache = mutableMapOf<String, String>()

    /**
     * Returns the cached OpenAPI/OpenAI-style JSON description for [tool], building it on
     * first request. Shape mirrors `Tool.toRequestTool()` in `Requests.kt` without the
     * OpenAI `{type: "function", function: {…}}` wrapper, so litert-lm's `OpenApiTool`
     * adapter can forward it straight to the model. If a parameter has a `rawSchema`,
     * it's passed through verbatim — that preserves array/enum/nested-object shapes the
     * simple `{type, description}` form would lose.
     */
    private fun localToolDescriptionJson(tool: Tool): String = localToolDescriptionJsonCache.getOrPut(tool.schema.name) {
        buildJsonObject {
            put("name", tool.schema.name)
            put("description", tool.schema.description)
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    for ((paramName, param) in tool.schema.parameters) {
                        val raw = param.rawSchema
                        if (raw != null) {
                            put(paramName, raw)
                        } else {
                            putJsonObject(paramName) {
                                put("type", param.type)
                                put("description", param.description)
                            }
                        }
                    }
                }
                putJsonArray("required") {
                    tool.schema.parameters.filter { it.value.required }.keys.forEach { add(it) }
                }
            }
        }.toString()
    }

    /**
     * Runs a single tool invocation requested by the on-device engine, mirroring the UI
     * flow used by [executeToolCallsInParallel]: write the assistant tool-call row, show a
     * TOOL_EXECUTING indicator (with a 2 s minimum so it's visible), execute the tool, then
     * replace the indicator with a TOOL result row. Returns the raw result string for the
     * engine to feed back to the model.
     */
    private suspend fun runLocalToolWithUiFeedback(
        name: String,
        arguments: String,
        history: MutableStateFlow<List<History>>,
    ): String {
        val callId = "local-${Uuid.random()}"
        val executingId = Uuid.random().toString()
        val displayName = toolExecutor.getToolDisplayName(name)
        // Append the assistant tool-call row and the executing indicator in a single
        // StateFlow update so the UI doesn't flash twice before the tool even starts.
        history.update {
            it.toMutableList().apply {
                add(
                    History(
                        role = History.Role.ASSISTANT,
                        content = "",
                        toolCalls = persistentListOf(
                            ToolCallInfo(id = callId, name = name, arguments = arguments),
                        ),
                    ),
                )
                add(
                    History(
                        id = executingId,
                        role = History.Role.TOOL_EXECUTING,
                        content = name,
                        toolName = displayName,
                    ),
                )
            }
        }
        val startTime = Clock.System.now().toEpochMilliseconds()
        // Background runs must not leak shell commands into whatever chat is open.
        val conversationIdForTool = activeConversationId()
        try {
            val result = try {
                toolExecutor.executeTool(name, arguments, conversationIdForTool)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                toolErrorJson(e.message ?: "Tool execution failed")
            }
            recordToolMemory(conversationIdForTool, callId, name, result)
            val elapsed = Clock.System.now().toEpochMilliseconds() - startTime
            if (elapsed < MIN_TOOL_DISPLAY_MS) {
                delay(MIN_TOOL_DISPLAY_MS.milliseconds - elapsed.milliseconds)
            }
            history.update { h ->
                buildList(h.size) {
                    for (entry in h) {
                        if (entry.id != executingId) add(entry)
                    }
                    add(
                        History(
                            role = History.Role.TOOL,
                            content = result,
                            toolCallId = callId,
                            toolName = name,
                        ),
                    )
                }
            }
            return result
        } finally {
            // On cancellation the fused update above never ran — drop the stranded
            // indicator. On the success path this is a no-op (id already removed).
            history.update { h ->
                if (h.none { it.id == executingId }) h else h.filter { it.id != executingId }
            }
        }
    }

    private suspend fun askWithService(
        service: Service,
        messages: List<History>,
        systemPrompt: String?,
        instanceId: String,
        history: MutableStateFlow<List<History>> = chatHistory,
    ): AssistantTurn {
        if (service.isOnDevice) {
            // No retry: local-inference failures are deterministic, and this path mutates
            // chat history through tool execution, so a replay could re-run tools.
            // Re-fetch the system prompt with the CHAT_LOCAL variant — the caller
            // (`ask()`/`askWithTools()`) pre-fetched a CHAT_REMOTE prompt, but on-device
            // needs the trimmed variant.
            val localPrompt = if (currentObjectiveRun() != null) systemPrompt else {
                withContinuity(getActiveSystemPrompt(SystemPromptVariant.CHAT_LOCAL), messages)
            }
            return AssistantTurn(askWithLocalEngine(messages, localPrompt, instanceId, history))
        }

        val creds = instanceCredentials(instanceId, service)
        val tools = if (supportsTools(creds.modelId)) getAvailableTools() else emptyList()
        check(currentObjectiveRun() == null || tools.any { it.schema.name == "objective_set_status" }) {
            "The Objective model does not have the checkpoint tool"
        }

        if (tools.isEmpty()) {
            return plainChat(service, creds, messages, systemPrompt, strictEmptyResponse = true)
        }

        return when (service) {
            Service.Gemini -> handleGeminiChatWithTools(creds, messages, tools, systemPrompt, history)
            Service.Anthropic -> handleAnthropicChatWithTools(creds, messages, tools, systemPrompt, history)
            else -> handleOpenAICompatibleChatWithTools(service, creds, messages, tools, systemPrompt, history)
        }
    }

    /**
     * The conversation a request belongs to: an explicit coroutine-context id (set by
     * askWithTools for heartbeat / scheduled runs) wins over the globally active chat id, so
     * background runs aren't attributed to whatever chat the user is viewing. Null before any
     * conversation exists. Used as the upstream session id for providers that require one.
     */
    private suspend fun activeConversationId(): String? = currentConversationIdOrNull() ?: _currentConversationId.value

    private suspend fun providerSessionId(): String? = currentObjectiveRun()?.providerSessionId ?: activeConversationId()

    /**
     * One assistant turn with no tools declared, dispatched to whichever API [service] speaks.
     * The three providers differ only in message shape and text extraction, so every caller
     * needing a plain completion — the no-tools path above, the silent asks, and the
     * Gemini/Anthropic tool-loop bailouts — funnels through here.
     *
     * [retry] wraps the call in [retryApiCall]; silent callers skip it because they run on a
     * caller-supplied deadline. [strictEmptyResponse] turns a missing OpenAI-compatible message
     * or content into [OpenAICompatibleEmptyResponseException] — the visible chat path wants that
     * error surfaced, silent callers prefer empty text.
     */
    private suspend fun plainChat(
        service: Service,
        credentials: ServiceCredentials,
        messages: List<History>,
        systemPrompt: String?,
        requestTimeoutMs: Long? = null,
        retry: Boolean = true,
        strictEmptyResponse: Boolean = false,
    ): AssistantTurn {
        suspend fun <T> call(block: suspend () -> T): T = if (retry) retryApiCall(block) else block()

        return when (service) {
            Service.Gemini -> {
                val response = call {
                    requests.geminiChat(
                        credentials = credentials,
                        messages = messages.map { it.toGeminiMessageDto() },
                        systemInstruction = systemPrompt,
                        requestTimeoutMs = requestTimeoutMs,
                    ).getOrThrow()
                }
                AssistantTurn(response.extractText())
            }

            Service.Anthropic -> {
                val response = call {
                    requests.anthropicChat(
                        credentials = credentials,
                        messages = buildAnthropicMessages(messages),
                        systemInstruction = systemPrompt,
                        requestTimeoutMs = requestTimeoutMs,
                    ).getOrThrow()
                }
                AssistantTurn(response.extractText())
            }

            else -> {
                // No tools on this request — strip any historic tool_calls so Groq's strict
                // validator doesn't see calls to tools we no longer declare.
                val openAIMessages = buildOpenAIMessages(service, messages, systemPrompt, credentials.modelId, declaredToolNames = emptySet())
                if (requiresResponsesApi(service, credentials.modelId, credentials.baseUrl)) {
                    val response = call {
                        requests.openAIResponses(service, credentials, toResponsesInput(openAIMessages), requestTimeoutMs = requestTimeoutMs).getOrThrow()
                    }
                    response.throwIfFailed(service)
                    val content = response.outputText
                    if (content == null && strictEmptyResponse) throw OpenAICompatibleEmptyResponseException()
                    return AssistantTurn(content.orEmpty(), response.reasoningSummary)
                }
                val sessionId = providerSessionId()
                val response = call {
                    requests.openAICompatibleChat(service, credentials, openAIMessages, sessionId = sessionId, requestTimeoutMs = requestTimeoutMs).getOrThrow()
                }
                val message = response.choices.firstOrNull()?.message
                val content = message?.effectiveContent
                if (content == null && strictEmptyResponse) throw OpenAICompatibleEmptyResponseException()
                AssistantTurn(content.orEmpty(), message?.reasoningTraceFor(content))
            }
        }
    }

    private fun hasValidInstanceApiKey(instanceId: String, service: Service): Boolean {
        if (service == Service.Free) return true
        if (service.isOnDevice) return true
        if (!service.requiresApiKey && !service.supportsOptionalApiKey) return true
        if (service.requiresApiKey) return appSettings.getInstanceApiKey(instanceId).isNotBlank()
        return true // Optional API key services are always valid
    }

    private data class FallbackEntry(val instanceId: String, val service: Service)

    private fun getOrderedFallbackEntries(): List<FallbackEntry> {
        val instances = getConfiguredServiceInstances()
        val entries = instances.map { FallbackEntry(instanceId = it.instanceId, service = Service.fromId(it.serviceId)) }
            .filter { it.service != Service.Free }
            .filter { !it.service.isOnDevice || localInferenceEngine != null }
        val freeEntry = FallbackEntry(instanceId = "free", service = Service.Free)
        val ordered = if (entries.isEmpty()) {
            listOf(freeEntry)
        } else if (appSettings.isFreeServicePrimary()) {
            listOf(freeEntry) + entries
        } else if (appSettings.isFreeFallbackEnabled()) {
            entries + freeEntry
        } else {
            entries
        }
        // On-device models are only tried as the primary service, never as a fallback
        // target — falling into one would silently start a heavy model load the user
        // didn't ask for (mirrors the guard that keeps on-device errors from silently
        // falling back to cloud services).
        return ordered.filterIndexed { index, entry -> index == 0 || !entry.service.isOnDevice }
    }

    override suspend fun ask(question: String?, files: List<PlatformFile>, uiSubmission: UiSubmission?, activeSkillId: String?) {
        // The active skill (if any) is consumed for this single turn only — stored in a
        // field rather than a parameter on getActiveSystemPrompt so the existing internal
        // callers (heartbeat, askWithTools, etc.) don't all need a new parameter. The
        // skill's files already live in the sandbox at ~/skills/<id>/, so nothing is
        // materialized here.
        val resolvedSkillId = activeSkillId?.takeIf { skillManager.getSkill(it) != null }
        pendingActiveSkillId = resolvedSkillId
        try {
            askInternal(question, files, uiSubmission)
        } finally {
            pendingActiveSkillId = null
            // The create-skill flow writes a new /root/skills/<id>/SKILL.md via
            // execute_shell_command; rescan so it shows up in the slash menu and Settings
            // without a restart. Cheap (one listDirectory) and only runs on those turns.
            if (resolvedSkillId == createSkillId) {
                skillManager.load()
            }
        }
    }

    private var pendingActiveSkillId: String? = null

    /** Built-in skill id; matches the bundled SKILL.md under composeResources. */
    private val createSkillId = "create-skill"

    private suspend fun askInternal(question: String?, files: List<PlatformFile>, uiSubmission: UiSubmission?) {
        // Allocate a conversation id immediately for fresh chats. Without this,
        // the very first tool call lands here with _currentConversationId.value
        // still null, so per-conversation routing (e.g. the sandbox shell)
        // falls through to a shared default — which both makes the new chat
        // invisible in the Terminal session picker and lets unrelated callers
        // collide on the same shell mutex. Persistence is deferred to the
        // existing saveCurrentConversation() flow that runs after the response.
        if (_currentConversationId.value == null) {
            setCurrentConversationId(Uuid.random().toString())
        }
        // Process every attached file: classify, compress/encode, and build an Attachment.
        // readBytes() is suspend, so this happens before the StateFlow.update block.
        val attachmentCandidates = files.map { file ->
            val fileMimeType = file.mimeType()?.toString()
            val fileName = file.name

            val category = classifyFile(fileMimeType, fileName)
            if (category == FileCategory.UNSUPPORTED) throw UnsupportedFileTypeException()

            // Reject oversized files by stat size before readBytes(), which would otherwise
            // allocate a ByteArray large enough to OOM the process on multi-GB inputs.
            val rawSizeLimit = when (category) {
                FileCategory.TEXT -> MAX_TEXT_FILE_BYTES.toLong()
                FileCategory.PDF -> MAX_PDF_BYTES.toLong()
                FileCategory.IMAGE -> MAX_RAW_IMAGE_BYTES.toLong()
                FileCategory.BINARY -> MAX_BINARY_FILE_BYTES.toLong()
                FileCategory.UNSUPPORTED -> 0L
            }
            if (file.size() > rawSizeLimit) throw FileTooLargeException()

            val rawBytes = file.readBytes()

            when (category) {
                FileCategory.IMAGE -> {
                    val compressed = compressImageBytes(rawBytes, fileMimeType ?: "image/jpeg")
                    // compressImageBytes can fall back to the original bytes on failure or on
                    // platforms without compression — guard against Base64 OOM for oversized input.
                    if (compressed.size > MAX_IMAGE_BYTES) throw FileTooLargeException()
                    Attachment(
                        data = Base64.encode(compressed),
                        mimeType = "image/jpeg",
                        fileName = null,
                    )
                }

                FileCategory.TEXT -> Attachment(
                    data = Base64.encode(rawBytes),
                    mimeType = fileMimeType ?: "text/plain",
                    fileName = fileName,
                )

                FileCategory.PDF -> Attachment(
                    data = Base64.encode(rawBytes),
                    mimeType = "application/pdf",
                    fileName = fileName,
                )

                FileCategory.BINARY -> null

                FileCategory.UNSUPPORTED -> throw UnsupportedFileTypeException()
            }
        }

        // Binary uploads can't be shown to the model; they go into the shared Linux
        // workspace instead and the message tells the agent where each one landed.
        val workspaceNotes = files.mapIndexedNotNull { index, file ->
            if (classifyFile(file.mimeType()?.toString(), file.name) != FileCategory.BINARY) {
                return@mapIndexedNotNull null
            }
            val guestPath = BINARY_UPLOAD_DIR + (file.name.ifBlank { "upload-$index.bin" })
            val copied = sandboxController.writeBinaryFile(guestPath, file.readBytes())
            if (copied) {
                "Attached file \"" + file.name + "\" has been placed in the Linux workspace at " +
                    guestPath + ". Inspect it with workspace tools and work on it there."
            } else {
                "Attached file \"" + file.name + "\" could NOT be copied into the Linux workspace " +
                    "(sandbox not installed?). Tell the user it needs the sandbox to work with it."
            }
        }

        val attachments = attachmentCandidates.filterNotNull().toImmutableList()
        val notes = workspaceNotes.joinToString("\n\n")
        val contentWithNotes = when {
            notes.isEmpty() -> question
            question.isNullOrBlank() -> notes
            else -> "$question\n\n$notes"
        }

        if (contentWithNotes != null) {
            chatHistory.update {
                it.toMutableList().apply {
                    add(
                        History(
                            role = History.Role.USER,
                            content = contentWithNotes,
                            attachments = attachments,
                            uiSubmission = uiSubmission,
                        ),
                    )
                }
            }
            val objectiveId = _currentConversationId.value
            if (objectiveId != null && objectiveModeFlag) {
                objectiveStore.update(objectiveId) { existing ->
                    if (existing == null) null
                    else {
                        val extra = listOfNotNull(
                            question?.takeIf { it != existing.goal && it.isNotBlank() },
                            notes.takeIf { it.isNotBlank() },
                        ).joinToString("\n")
                        if (extra.isBlank()) existing else existing.copy(
                            userDetails = listOf(existing.userDetails, extra).filter { it.isNotBlank() }.joinToString("\n").takeLast(12_000),
                        )
                    }
                }
            }
            saveCurrentConversation()
        }

        val historySnapshot = chatHistory.value
        val draftMode = getChatMode() == ChatMode.DRAFT
        val baseSystemPrompt = getActiveSystemPrompt()

        val fallbackEntries = getOrderedFallbackEntries().filter { hasValidInstanceApiKey(it.instanceId, it.service) }

        var lastException: Exception? = null
        var fallbackServiceName: String? = null

        try {
            for ((index, entry) in fallbackEntries.withIndex()) {
                val modelId = instanceCredentials(entry.instanceId, entry.service).modelId
                val contextWindow = ModelCatalog.estimateContextWindow(modelId)
                val reserve = (baseSystemPrompt?.length ?: 0) + 6_000
                val messages = if (entry.service.isOnDevice) {
                    trimToRecentExchanges(historySnapshot, 8)
                } else {
                    trimHistoryForContext(historySnapshot, reserve, contextWindow).ifEmpty {
                        trimToRecentExchanges(historySnapshot, 1)
                    }
                }
                val systemPrompt = withContinuity(baseSystemPrompt, messages)
                // Skip fallback services whose context window is too small for the current history
                // On-device models handle their own context limits, so skip this check for them
                if (!entry.service.isOnDevice) {
                    val entryWindowChars = contextWindow * ESTIMATED_CHARS_PER_TOKEN
                    if (messages.sumOf { it.content.length } + (systemPrompt?.length ?: 0) > entryWindowChars) {
                        lastException = ContextWindowExceededException()
                        _fallbackStatus.value = FallbackStatus(
                            serviceName = entry.service.displayName,
                            errorReason = ContextWindowExceededException().toUiError(),
                            nextServiceName = fallbackEntries.getOrNull(index + 1)?.service?.displayName,
                        )
                        continue
                    }
                }

                // No retry wrapper here: each network call retries inside askWithService.
                // Retrying the whole call would re-enter the tool loop against a chat
                // history already mutated by the failed attempt.
                val turn = try {
                    askWithService(entry.service, messages, systemPrompt, entry.instanceId)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    // On-device services should not silently fall back — surface the error
                    if (entry.service.isOnDevice) throw e
                    lastException = e
                    _fallbackStatus.value = FallbackStatus(
                        serviceName = entry.service.displayName,
                        errorReason = e.toUiError(),
                        nextServiceName = fallbackEntries.getOrNull(index + 1)?.service?.displayName,
                    )
                    continue
                }
                if (index > 0) {
                    fallbackServiceName = entry.service.displayName
                }
                chatHistory.update {
                    it.toMutableList().apply {
                        add(
                            History(
                                role = History.Role.ASSISTANT,
                                content = turn.content,
                                reasoningContent = turn.reasoningContent,
                                fallbackServiceName = fallbackServiceName,
                            ),
                        )
                    }
                }
                saveCurrentConversation()
                if (draftMode && turn.content.isNotBlank()) {
                    val draftConversation = _currentConversationId.value
                    if (draftConversation != null) backgroundScope.launch {
                        val review = CitationCheckTool.review(turn.content, sandboxController)
                        if (savedConversations.value.any { it.id == draftConversation }) {
                            addAssistantMessageTo(draftConversation, review.message())
                        }
                    }
                }
                return
            }

            throw if (fallbackEntries.size > 1 && lastException != null) {
                AllServicesFailedException()
            } else {
                lastException ?: OpenAICompatibleEmptyResponseException()
            }
        } finally {
            _fallbackStatus.value = null
        }
    }

    private suspend fun handleOpenAICompatibleChatWithTools(
        service: Service,
        credentials: ServiceCredentials,
        @Suppress("UNUSED_PARAMETER") messages: List<History>,
        tools: List<Tool>,
        systemPrompt: String? = null,
        history: MutableStateFlow<List<History>> = chatHistory,
    ): AssistantTurn {
        val contextWindowTokens = ModelCatalog.estimateContextWindow(credentials.modelId)
        val declaredToolNames = tools.map { it.schema.name }.toSet()
        // GPT-5.6 and friends reject function tools on chat completions; the same messages are
        // translated to Responses API items instead. Everything before the wire call — prompt
        // assembly, tool-call pairing, context trimming — is shared.
        val useResponsesApi = requiresResponsesApi(service, credentials.modelId, credentials.baseUrl)
        val strategy = object : ToolLoopStrategy {
            override suspend fun chat(history: List<History>, systemPrompt: String?): LoopChatResult {
                val msgs = trimMessagesForContext(buildOpenAIMessages(service, history, systemPrompt, credentials.modelId, declaredToolNames), contextWindowTokens)
                if (useResponsesApi) {
                    val response = retryApiCall {
                        requests.openAIResponses(service, credentials, toResponsesInput(msgs), tools).getOrThrow()
                    }
                    response.throwIfFailed(service)
                    val text = response.outputText
                    val calls = response.functionCalls.map { fc ->
                        ToolCallInfo(
                            id = fc.callId ?: Uuid.random().toString(),
                            name = fc.name.orEmpty(),
                            arguments = fc.arguments ?: "{}",
                        )
                    }
                    if (text == null && calls.isEmpty()) throw OpenAICompatibleEmptyResponseException()
                    return LoopChatResult(
                        textContent = text.orEmpty(),
                        reasoningContent = response.reasoningSummary,
                        toolCalls = calls,
                    )
                }
                val sessionId = providerSessionId()
                val response = retryApiCall {
                    requests.openAICompatibleChat(service, credentials, msgs, tools, sessionId = sessionId).getOrThrow()
                }
                val message = response.choices.firstOrNull()?.message ?: throw OpenAICompatibleEmptyResponseException()
                var calls = message.toolCalls.orEmpty().map { tc ->
                    ToolCallInfo(id = tc.id, name = tc.function.name, arguments = tc.function.arguments)
                }
                var textContent = message.effectiveContent ?: ""
                if (calls.isEmpty() && textContent.contains("<tool_call>")) {
                    val extracted = extractInlineToolCalls(textContent, tools)
                    if (extracted.calls.isNotEmpty()) {
                        textContent = extracted.cleanedText
                        calls = extracted.calls.map {
                            ToolCallInfo(
                                id = "inline-${Uuid.random()}",
                                name = it.name,
                                arguments = it.arguments,
                            )
                        }
                    }
                }
                return LoopChatResult(
                    textContent = textContent,
                    reasoningContent = message.reasoningTraceFor(textContent),
                    isThinkingContent = message.isContentFromReasoning,
                    toolCalls = calls,
                )
            }

            override suspend fun bailout(history: List<History>, systemPrompt: String?, reason: BailoutReason): String {
                // Bailout sends no tools — strip historic tool_calls to satisfy strict validators.
                val msgs = trimMessagesForContext(buildOpenAIMessages(service, history, systemPrompt, credentials.modelId, declaredToolNames = emptySet()), contextWindowTokens)
                return makeFinalCallWithoutTools(service, credentials, msgs, reason, useResponsesApi)
            }
        }
        return runToolLoop(strategy, systemPrompt, history)
    }

    private suspend fun handleGeminiChatWithTools(
        credentials: ServiceCredentials,
        @Suppress("UNUSED_PARAMETER") messages: List<History>,
        tools: List<Tool>,
        systemPrompt: String? = null,
        history: MutableStateFlow<List<History>> = chatHistory,
    ): AssistantTurn {
        val contextWindowTokens = ModelCatalog.estimateContextWindow(credentials.modelId)
        val strategy = object : ToolLoopStrategy {
            override suspend fun chat(history: List<History>, systemPrompt: String?): LoopChatResult {
                val geminiMessages = history.map { it.toGeminiMessageDto() }
                val response = retryApiCall {
                    requests.geminiChat(
                        credentials = credentials,
                        messages = geminiMessages,
                        tools = tools,
                        systemInstruction = systemPrompt,
                    ).getOrThrow()
                }
                val parts = response.candidates.firstOrNull()?.content?.parts.orEmpty()
                val partsWithFunctionCalls = parts.filter { it.functionCall != null }
                val toolCallInfos = partsWithFunctionCalls.map { part ->
                    val fc = part.functionCall!!
                    val argsJson = fc.args?.let { JsonObject(it).toString() } ?: "{}"
                    ToolCallInfo(
                        id = "gemini-${Uuid.random()}",
                        name = fc.name,
                        arguments = argsJson,
                        thoughtSignature = part.thoughtSignature,
                    )
                }
                val textContent = parts.filterNot { it.isThought }.mapNotNull { it.text }.joinToString("\n")
                val reasoning = parts.filter { it.isThought }.mapNotNull { it.text }
                    .joinToString("\n").ifBlank { null }
                return LoopChatResult(textContent = textContent, toolCalls = toolCallInfos, reasoningContent = reasoning)
            }

            override suspend fun bailout(history: List<History>, systemPrompt: String?, reason: BailoutReason): String = plainChat(Service.Gemini, credentials, history, "${bailoutPrompt(reason)} $systemPrompt").content

            override val historyContextWindowTokens = contextWindowTokens
        }
        return runToolLoop(strategy, systemPrompt, history)
    }

    private suspend fun handleAnthropicChatWithTools(
        credentials: ServiceCredentials,
        @Suppress("UNUSED_PARAMETER") messages: List<History>,
        tools: List<Tool>,
        systemPrompt: String? = null,
        history: MutableStateFlow<List<History>> = chatHistory,
    ): AssistantTurn {
        val contextWindowTokens = ModelCatalog.estimateContextWindow(credentials.modelId)
        val strategy = object : ToolLoopStrategy {
            override suspend fun chat(history: List<History>, systemPrompt: String?): LoopChatResult {
                val msgs = buildAnthropicMessages(history)
                val response = retryApiCall {
                    requests.anthropicChat(
                        credentials = credentials,
                        messages = msgs,
                        tools = tools,
                        systemInstruction = systemPrompt,
                    ).getOrThrow()
                }
                val toolUseBlocks = response.content.filter { it.type == "tool_use" }
                val toolCallInfos = toolUseBlocks.map { block ->
                    val argsJson = block.input?.toString() ?: "{}"
                    ToolCallInfo(
                        id = block.id ?: "anthropic-${Uuid.random()}",
                        name = block.name ?: "unknown",
                        arguments = argsJson,
                    )
                }
                val textContent = response.content.filter { it.type == "text" }.mapNotNull { it.text }.joinToString("\n")
                return LoopChatResult(textContent = textContent, toolCalls = toolCallInfos)
            }

            override suspend fun bailout(history: List<History>, systemPrompt: String?, reason: BailoutReason): String = plainChat(Service.Anthropic, credentials, history, "${bailoutPrompt(reason)} $systemPrompt").content

            override val historyContextWindowTokens = contextWindowTokens
        }
        return runToolLoop(strategy, systemPrompt, history)
    }

    private suspend fun runToolLoop(
        strategy: ToolLoopStrategy,
        systemPrompt: String?,
        history: MutableStateFlow<List<History>>,
    ): AssistantTurn {
        var iteration = 0
        val recentSignatures = mutableListOf<String>()
        while (true) {
            iteration++
            val visible = history.value.filter { it.role != History.Role.TOOL_EXECUTING }.let { full ->
                // Trim a request copy only — the history flow is the visible, persisted conversation.
                strategy.historyContextWindowTokens
                    ?.let { trimHistoryForContext(full, systemPrompt?.length ?: 0, it).ifEmpty { full } }
                    ?: full
            }
            if (iteration > MAX_TOOL_ITERATIONS) {
                return AssistantTurn(strategy.bailout(visible, systemPrompt, BailoutReason.LIMIT_REACHED))
            }
            val result = strategy.chat(visible, systemPrompt)
            if (result.toolCalls.isEmpty()) {
                // For thinking-only turns, the reasoning text already became the content via
                // `isContentFromReasoning`, so don't surface it again as a reasoning trace.
                val reasoning = result.reasoningContent?.takeIf { !result.isThinkingContent }
                return AssistantTurn(result.textContent, reasoning)
            }

            val signatures = result.toolCalls.map { "${it.name}:${it.arguments.hashCode()}" }
            if (isRepeatingToolCalls(recentSignatures, signatures)) {
                return AssistantTurn(strategy.bailout(visible, systemPrompt, BailoutReason.REPEATING))
            }
            recentSignatures.addAll(signatures)

            history.update {
                it.toMutableList().apply {
                    add(
                        History(
                            role = History.Role.ASSISTANT,
                            content = result.textContent,
                            isThinking = result.isThinkingContent,
                            toolCalls = result.toolCalls.toImmutableList(),
                            reasoningContent = result.reasoningContent,
                        ),
                    )
                }
            }

            val toolResults = executeToolCallsInParallel(
                result.toolCalls.map { Triple(it.id, it.name, it.arguments) },
                history,
            )

            history.update { h ->
                buildList(h.size + toolResults.size) {
                    for (entry in h) {
                        if (entry.role != History.Role.TOOL_EXECUTING) add(entry)
                    }
                    for ((callId, name, content) in toolResults) {
                        add(
                            History(
                                role = History.Role.TOOL,
                                content = content,
                                toolCallId = callId,
                                toolName = name,
                            ),
                        )
                    }
                }
            }
        }
    }

    /**
     * Detects if the current batch of tool calls is repeating a recent pattern.
     */
    private fun isRepeatingToolCalls(recentSignatures: List<String>, currentSignatures: List<String>): Boolean {
        if (currentSignatures.isEmpty()) return false
        // Count how many consecutive times the same signature set appeared at the tail
        val batchSize = currentSignatures.size
        var consecutiveCount = 0
        var i = recentSignatures.size - batchSize
        while (i >= 0) {
            val slice = recentSignatures.subList(i, i + batchSize)
            if (slice == currentSignatures) {
                consecutiveCount++
                i -= batchSize
            } else {
                break
            }
        }
        // +1 for the current batch that's about to be executed
        return consecutiveCount + 1 >= MAX_REPEATED_TOOL_CALLS
    }

    /**
     * Makes a final OpenAI-compatible API call without tools, asking the model to summarize.
     */
    private suspend fun makeFinalCallWithoutTools(
        service: Service,
        credentials: ServiceCredentials,
        messages: List<com.inspiredandroid.kai.network.dtos.openaicompatible.OpenAICompatibleChatRequestDto.Message>,
        reason: BailoutReason,
        useResponsesApi: Boolean = false,
    ): String {
        val bailoutMessages = messages.toMutableList().apply {
            add(
                com.inspiredandroid.kai.network.dtos.openaicompatible.OpenAICompatibleChatRequestDto.Message(
                    role = "user",
                    content = JsonPrimitive(bailoutPrompt(reason)),
                ),
            )
        }
        if (useResponsesApi) {
            val response = retryApiCall {
                requests.openAIResponses(service, credentials, toResponsesInput(bailoutMessages)).getOrThrow()
            }
            response.throwIfFailed(service)
            return response.outputText.orEmpty()
        }
        val sessionId = providerSessionId()
        val response = retryApiCall {
            requests.openAICompatibleChat(service, credentials, bailoutMessages, sessionId = sessionId).getOrThrow()
        }
        return response.choices.firstOrNull()?.message?.effectiveContent ?: ""
    }

    /**
     * Executes tool calls in parallel, showing TOOL_EXECUTING indicators in [history] — the run's
     * own flow, so background runs (heartbeat, scheduled tasks) don't leak rows into the open chat.
     * Returns a list of (callId, toolName, result).
     */
    private suspend fun executeToolCallsInParallel(
        toolCalls: List<Triple<String, String, String>>,
        history: MutableStateFlow<List<History>>,
    ): List<Triple<String, String, String>> {
        // Add all TOOL_EXECUTING indicators first
        val executingIds = toolCalls.map { Uuid.random().toString() }
        for ((index, toolCall) in toolCalls.withIndex()) {
            val (_, name, _) = toolCall
            val toolDisplayName = toolExecutor.getToolDisplayName(name)
            history.update {
                it.toMutableList().apply {
                    add(
                        History(
                            id = executingIds[index],
                            role = History.Role.TOOL_EXECUTING,
                            content = name,
                            toolName = toolDisplayName,
                        ),
                    )
                }
            }
        }

        // Execute all tools concurrently, ensuring indicators show for at least 2 seconds.
        // Snapshot the conversation id once so all parallel tool calls in this batch
        // see a stable value even if the user switches conversations mid-flight.
        val conversationIdSnapshot = activeConversationId()
        val startTime = Clock.System.now().toEpochMilliseconds()
        try {
            val results = coroutineScope {
                toolCalls.map { (callId, name, arguments) ->
                    async {
                        val result = toolExecutor.executeTool(name, arguments, conversationIdSnapshot)
                        recordToolMemory(conversationIdSnapshot, callId, name, result)
                        Triple(callId, name, result)
                    }
                }.awaitAll()
            }
            val elapsed = Clock.System.now().toEpochMilliseconds() - startTime
            if (elapsed < MIN_TOOL_DISPLAY_MS) {
                delay((MIN_TOOL_DISPLAY_MS - elapsed).milliseconds)
            }
            return results
        } finally {
            // Remove all TOOL_EXECUTING indicators — also on cancellation, so stopping a
            // run doesn't strand spinner rows in the chat. Non-suspending, safe in finally.
            history.update { h ->
                h.filter { it.id !in executingIds }
            }
        }
    }

    /**
     * Failures that a retry a second later cannot fix: exhausted credit, rejected credentials, and
     * requests the provider refused as malformed, too large or disallowed. Retrying these only
     * delays the fallback chain (~3 s per service). Unknown/generic errors stay retryable.
     */
    private fun isNonRetryableException(e: Exception): Boolean = when (e) {
        is AnthropicInsufficientCreditsException,
        is OpenAICompatibleQuotaExhaustedException,
        is AnthropicInvalidApiKeyException,
        is GeminiInvalidApiKeyException,
        is OpenAICompatibleInvalidApiKeyException,
        is OpenAICompatibleModelNotFoundException,
        is OpenAICompatibleRequestTooLargeException,
        is OpenAICompatibleContentModerationException,
        is OpenAICompatibleBadRequestException,
        is ContextWindowExceededException,
        is UnsupportedFileTypeException,
        is FileTooLargeException,
        -> true

        else -> false
    }

    /**
     * Retries an API call with simple exponential backoff.
     */
    private suspend fun <T> retryApiCall(block: suspend () -> T): T {
        var lastException: Exception? = null
        for (attempt in 0..MAX_API_RETRIES) {
            try {
                return block()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (isNonRetryableException(e)) throw e
                lastException = e
                if (attempt < MAX_API_RETRIES) {
                    delay((attempt + 1).seconds)
                }
            }
        }
        throw lastException!!
    }

    private fun estimateMessageChars(msg: com.inspiredandroid.kai.network.dtos.openaicompatible.OpenAICompatibleChatRequestDto.Message): Int {
        val contentChars = when (val content = msg.content) {
            is JsonArray -> {
                // Vision messages: only count text parts, not base64 image data
                content.sumOf { element ->
                    val obj = element as? JsonObject
                    val type = (obj?.get("type") as? JsonPrimitive)?.content
                    if (type == "text") {
                        (obj["text"] as? JsonPrimitive)?.content?.length ?: 0
                    } else {
                        100 // Fixed small cost for image references
                    }
                }
            }

            is JsonPrimitive -> content.content.length

            else -> content?.toString()?.length ?: 0
        }
        return contentChars + msg.role.length
    }

    /**
     * Trims messages to fit within the estimated context window by dropping oldest messages
     * (keeping the system prompt and most recent messages).
     */
    private fun trimMessagesForContext(
        messages: List<com.inspiredandroid.kai.network.dtos.openaicompatible.OpenAICompatibleChatRequestDto.Message>,
        contextWindowTokens: Int = ModelCatalog.DEFAULT_CONTEXT_WINDOW_TOKENS,
    ): List<com.inspiredandroid.kai.network.dtos.openaicompatible.OpenAICompatibleChatRequestDto.Message> {
        val maxChars = contextWindowTokens * ESTIMATED_CHARS_PER_TOKEN
        val totalChars = messages.sumOf { estimateMessageChars(it) }
        if (totalChars <= maxChars) return messages

        // Keep system prompt (first message if role is "system") and trim from oldest non-system
        val systemMessages = messages.takeWhile { it.role == "system" }
        val nonSystemMessages = messages.drop(systemMessages.size)

        val systemChars = systemMessages.sumOf { estimateMessageChars(it) }
        val availableChars = maxChars - systemChars

        // Group each assistant tool-call turn together with the tool responses that follow it so
        // trimming never strands one without the other. Strict OpenAI-compatible providers (e.g.
        // DeepSeek via OpenCode Zen) reject an assistant `tool_calls` message that isn't followed
        // by its tool responses, and a `tool` message without a preceding `tool_calls`.
        val groups = mutableListOf<List<com.inspiredandroid.kai.network.dtos.openaicompatible.OpenAICompatibleChatRequestDto.Message>>()
        var index = 0
        while (index < nonSystemMessages.size) {
            val msg = nonSystemMessages[index]
            if (msg.role == "assistant" && !msg.tool_calls.isNullOrEmpty()) {
                var end = index + 1
                while (end < nonSystemMessages.size && nonSystemMessages[end].role == "tool") {
                    end++
                }
                groups.add(nonSystemMessages.subList(index, end).toList())
                index = end
            } else {
                groups.add(listOf(msg))
                index++
            }
        }

        // Keep whole groups from the end until we exceed the budget.
        val kept = mutableListOf<com.inspiredandroid.kai.network.dtos.openaicompatible.OpenAICompatibleChatRequestDto.Message>()
        var usedChars = 0
        for (group in groups.asReversed()) {
            val groupChars = group.sumOf { estimateMessageChars(it) }
            if (usedChars + groupChars > availableChars) break
            kept.addAll(0, group)
            usedChars += groupChars
        }

        return systemMessages + kept
    }

    /**
     * Trims History entries to fit within the estimated context window by dropping oldest messages
     * (keeping the most recent). Used by Gemini and Anthropic tool loops where the system prompt
     * is sent separately (not as a message).
     */
    private fun trimHistoryForContext(
        history: List<History>,
        systemPromptChars: Int = 0,
        contextWindowTokens: Int = ModelCatalog.DEFAULT_CONTEXT_WINDOW_TOKENS,
    ): List<History> {
        val maxChars = contextWindowTokens * ESTIMATED_CHARS_PER_TOKEN
        val totalChars = history.sumOf { it.content.length } + systemPromptChars
        if (totalChars <= maxChars) return history

        val availableChars = maxChars - systemPromptChars

        // Keep messages from the end until we exceed the budget
        val kept = mutableListOf<History>()
        var usedChars = 0
        for (msg in history.reversed()) {
            val msgChars = msg.content.length
            if (usedChars + msgChars > availableChars) break
            kept.add(0, msg)
            usedChars += msgChars
        }

        // Dropping from the front can cut a tool round in half; a tool result whose assistant
        // tool call was trimmed away is an orphan that strict providers reject.
        return kept.dropWhile { it.role == History.Role.TOOL }
    }

    private fun trimToRecentExchanges(history: List<History>, maxExchanges: Int): List<History> {
        val userIndices = history.mapIndexedNotNull { index, h ->
            if (h.role == History.Role.USER) index else null
        }
        if (userIndices.size <= maxExchanges) return history
        val cutoffIndex = userIndices[userIndices.size - maxExchanges]
        return history.subList(cutoffIndex, history.size)
    }

    private suspend fun withContinuity(systemPrompt: String?, messages: List<History>): String? {
        val id = activeConversationId() ?: return systemPrompt
        return (systemPrompt.orEmpty() + continuity.context(id, messages)).ifEmpty { null }
    }

    private suspend fun saveCurrentConversation() {
        val history = trimToRecentExchanges(chatHistory.value, 20)
        if (history.isEmpty()) return

        val now = Clock.System.now().toEpochMilliseconds()
        val conversationId = _currentConversationId.value ?: Uuid.random().toString().also {
            setCurrentConversationId(it)
        }
        continuity.write(conversationId, history.mapNotNull { item ->
            val source = when {
                item.role == History.Role.USER -> "User"
                item.role == History.Role.ASSISTANT && !item.isThinking && item.toolCalls == null -> "Assistant"
                else -> return@mapNotNull null
            }
            if (item.content.isBlank()) null else ContinuityEntry(item.id, source, continuityExcerpt(item.content))
        })

        val existingConversation = savedConversations.value.find { it.id == conversationId }

        val title = existingConversation?.title?.ifEmpty { null }
            ?: deriveTitle(history)
        val conversation = Conversation(
            id = conversationId,
            messages = history
                .filter { it.role != History.Role.TOOL_EXECUTING }
                .map { h ->
                    Conversation.Message(
                        id = h.id,
                        role = when (h.role) {
                            History.Role.USER -> "user"
                            History.Role.ASSISTANT -> "assistant"
                            History.Role.TOOL -> "tool"
                            History.Role.TOOL_EXECUTING -> "tool" // Should not happen due to filter
                        },
                        content = h.content,
                        attachments = h.attachments,
                        uiSubmission = h.uiSubmission,
                        isThinking = h.isThinking,
                        reasoningContent = h.reasoningContent,
                    )
                },
            createdAt = existingConversation?.createdAt ?: now,
            updatedAt = now,
            title = title,
            type = existingConversation?.type
                ?: when {
                    interactiveModeFlag -> Conversation.TYPE_INTERACTIVE
                    objectiveModeFlag && appSettings.getCurrentObjectiveGoal().isNotBlank() ->
                        Conversation.TYPE_OBJECTIVE
                    else -> Conversation.TYPE_CHAT
                },
        )

        conversationStorage.saveConversation(conversation)
    }

    override fun clearHistory() {
        chatHistory.update {
            emptyList()
        }
    }

    override fun isUsingSharedKey(): Boolean = currentService() == Service.Free

    override fun supportedFileExtensions(): List<String> {
        val service = currentService()
        if (service.isOnDevice) return emptyList()
        // Binary uploads don't go to the model directly — they land in the Linux
        // sandbox workspace, so they're offered only where the sandbox exists.
        val imagesSupported = service.supportsImages && modelSupportsImages(currentModelId())
        val base = (if (imagesSupported) supportedFileExtensions else supportedFileExtensions - imageExtensions)
        return if (service.supportsPdf) base + "pdf" else base
    }

    override fun currentService(): Service {
        if (appSettings.isFreeServicePrimary()) return Service.Free
        val instances = getConfiguredServiceInstances()
        return instances.firstOrNull()?.let { Service.fromId(it.serviceId) } ?: Service.Free
    }

    /** Model id of the active (first configured, non-Free) instance, or "" when none. */
    private fun currentModelId(): String {
        val instance = getConfiguredServiceInstances().firstOrNull() ?: return ""
        val service = Service.fromId(instance.serviceId)
        return appSettings.getInstanceEffectiveModelId(instance.instanceId).ifEmpty { appSettings.getSelectedModelId(service) }
    }

    private fun setCurrentConversationId(id: String?) {
        _currentConversationId.value = id
        appSettings.setCurrentConversationId(id)
    }

    // Conversation management
    override fun loadConversations() {
        conversationStorage.loadConversations()
    }

    override fun loadConversation(id: String) {
        val conversation = savedConversations.value.find { it.id == id } ?: return

        setCurrentConversationId(id)
        // Restore the objective mode flags alongside the conversation, same way the
        // interactive mode is restored when a conversation is loaded.
        objectiveModeFlag = conversation.type == Conversation.TYPE_OBJECTIVE
        appSettings.setCurrentObjectiveMode(objectiveModeFlag)
        appSettings.setCurrentObjectiveGoal(
            if (objectiveModeFlag) objectiveStore.get(id)?.goal.orEmpty() else "",
        )
        chatHistory.value = conversation.messages.map { m ->
            // Prefer the modern `attachments` field. Fall back to the legacy single-file
            // fields for conversations saved before multi-attachment support.
            val attachments = when {
                m.attachments.isNotEmpty() -> m.attachments.toImmutableList()

                m.data != null && m.mimeType != null ->
                    persistentListOf(Attachment(data = m.data, mimeType = m.mimeType, fileName = m.fileName))

                else -> persistentListOf()
            }
            History(
                id = m.id,
                role = when (m.role) {
                    "user" -> History.Role.USER
                    "tool" -> History.Role.TOOL
                    else -> History.Role.ASSISTANT
                },
                content = m.content,
                attachments = attachments,
                uiSubmission = m.uiSubmission,
                isThinking = m.isThinking,
                reasoningContent = m.reasoningContent,
            )
        }
    }

    override suspend fun deleteConversation(id: String) {
        if (_currentConversationId.value == id) {
            setCurrentConversationId(null)
            chatHistory.value = emptyList()
        }
        conversationStorage.deleteConversation(id)
        objectiveStore.update(id) { null }
        continuity.delete(id)
        // Drop the per-conversation shell session so a future conversation reusing
        // this id (very unlikely — random uuids) doesn't inherit stale state, and
        // memory is freed.
        sandboxController.closeSession(id)
    }

    override fun regenerate() {
        chatHistory.update { history ->
            val lastUserIndex = history.indexOfLast { it.role == History.Role.USER }
            if (lastUserIndex >= 0) {
                history.subList(0, lastUserIndex + 1)
            } else {
                history
            }
        }
    }

    override fun startNewChat() {
        setCurrentConversationId(null)
        chatHistory.value = emptyList()
        objectiveModeFlag = false
        appSettings.setCurrentObjectiveMode(false)
        appSettings.setCurrentObjectiveGoal("")
    }

    override fun popLastExchange() {
        chatHistory.update { history ->
            val lastUserIndex = history.indexOfLast { it.role == History.Role.USER }
            if (lastUserIndex >= 0) history.take(lastUserIndex) else history
        }
    }

    override fun truncateFrom(messageId: String) {
        chatHistory.update { history ->
            val index = history.indexOfFirst { it.id == messageId }
            if (index >= 0) history.take(index) else history
        }
    }

    override fun restoreCurrentConversation() {
        // One-time migration for existing users: pin the latest conversation as the new
        // "current" pointer so the upgrade is non-disruptive.
        if (!appSettings.isCurrentConversationMigrated()) {
            val latest = savedConversations.value.maxByOrNull { it.updatedAt }
            if (latest != null) {
                loadConversation(latest.id)
            }
            appSettings.markCurrentConversationMigrated()
            return
        }

        // Already-loaded guard (covers re-entry from refreshSettings)
        val currentId = _currentConversationId.value
        if (currentId != null && chatHistory.value.isNotEmpty() &&
            savedConversations.value.any { it.id == currentId }
        ) {
            return
        }

        val persistedId = appSettings.getCurrentConversationId()
        if (persistedId != null && savedConversations.value.any { it.id == persistedId }) {
            loadConversation(persistedId)
        }
        // else: null id or stale id → leave history empty (this is the new-empty-chat state)
    }

    // Tool management
    override fun getToolDefinitions(): List<ToolInfo> = getPlatformToolDefinitions()
        .filter { it.userToggleable }
        .map { it.copy(isEnabled = appSettings.isToolEnabled(it.id, defaultEnabled = it.isEnabled)) }

    override fun setToolEnabled(toolId: String, enabled: Boolean) {
        appSettings.setToolEnabled(toolId, enabled)
    }

    // MCP servers
    override fun getMcpServers(): List<McpServerConfig> = mcpServerManager.getServers()

    override suspend fun addMcpServer(name: String, url: String, headers: Map<String, String>): McpServerConfig = mcpServerManager.addServer(name, url, headers)

    override fun removeMcpServer(serverId: String) {
        mcpServerManager.removeServer(serverId)
    }

    override fun setMcpServerEnabled(serverId: String, enabled: Boolean) {
        mcpServerManager.setServerEnabled(serverId, enabled)
    }

    override suspend fun connectMcpServer(serverId: String): Result<List<ToolInfo>> {
        val result = mcpServerManager.connectAndDiscoverTools(serverId)
        return result.map { mcpServerManager.getToolsForServer(serverId) }
    }

    override fun getMcpToolsForServer(serverId: String): List<ToolInfo> = mcpServerManager.getToolsForServer(serverId)

    override fun isMcpServerConnected(serverId: String): Boolean = mcpServerManager.isConnected(serverId)

    override suspend fun connectEnabledMcpServers() {
        mcpServerManager.connectEnabledServers()
    }

    // Skills
    override fun getInstalledSkills(): List<SkillManifest> = skillManager.getInstalled()

    override suspend fun uninstallSkill(id: String) {
        skillManager.uninstall(id)
    }

    override suspend fun browseSkillMarketplaces(): Result<List<RegistrySkillEntry>> = skillManager.browseMarketplaces()

    override suspend fun installBrowsedSkill(entry: RegistrySkillEntry): Result<SkillManifest> = skillManager.installFromRegistryEntry(entry)

    override suspend fun installGitHubSkill(owner: String, repo: String, ref: String, path: String): Result<SkillManifest> = skillManager.installFromGitHub(owner, repo, ref, path)

    // Soul (system prompt)
    override fun getSoulText(): String = appSettings.getSoulText()

    override fun setSoulText(text: String) {
        appSettings.setSoulText(text)
    }

    override suspend fun getActiveSystemPrompt(variant: SystemPromptVariant): String? {
        // Hard-coded app prompt: Re-Kai is a specific engineering agent, and the shipped
        // default prompt documents its fixed surfaces (workspace, uploads, Actions
        // pipeline, APK rework). A user-customized Soul still wins over it.
        val soul = appSettings.getSoulText().ifEmpty { HARDCODED_REKAI_PROMPT }
        val memoryEnabled = appSettings.isMemoryEnabled()
        val schedulingEnabled = appSettings.isSchedulingEnabled()

        val memoryInstructions = if (memoryEnabled) {
            appSettings.getMemoryInstructions().ifEmpty { null }
        } else {
            null
        }

        val memories = if (memoryEnabled) memoryStore.getAllMemories() else emptyList()
        val byCategory = memories.groupBy { it.category }

        val tasksSplit = if (schedulingEnabled) taskStore.getPendingTasksPartitioned() else PendingTaskPartition(emptyList(), emptyList())
        val pendingTasks = tasksSplit.scheduled
        val heartbeatAdditions = tasksSplit.heartbeatAdditions

        // Surface connected email accounts so the AI knows they exist in regular chat,
        // not just during heartbeats. Only the remote variant uses this — email tools
        // aren't in the local allowlist. Gated on the email toggle: if the user has email
        // off, the AI shouldn't reference the accounts.
        val emailAccounts = if (variant == SystemPromptVariant.CHAT_REMOTE && appSettings.isEmailEnabled()) {
            emailStore.getAccounts().map { account ->
                val state = emailStore.getSyncState(account.id)
                EmailAccountSummary(
                    email = account.email,
                    unreadCount = state.unreadCount,
                    lastSyncEpochMs = state.lastSyncEpochMs,
                    lastError = state.lastError,
                )
            }
        } else {
            emptyList()
        }

        val service = currentService()
        // On-device services store the active model ID per-instance, not globally, so
        // `getSelectedModelId` comes back blank for LiteRT. Fall back to the first
        // configured on-device instance's model ID in that case.
        val modelId = appSettings.getSelectedModelId(service).ifBlank {
            if (service.isOnDevice) {
                getConfiguredServiceInstances()
                    .firstOrNull { Service.fromId(it.serviceId).isOnDevice }
                    ?.let { appSettings.getInstanceModelId(it.instanceId) }
                    .orEmpty()
            } else {
                ""
            }
        }
        val now = Clock.System.now()
        val timeZone = TimeZone.currentSystemDefault()
        val localDateTime = now.toLocalDateTime(timeZone)
        val offset = timeZone.offsetAt(now)
        val runtime = ChatPromptRuntimeContext(
            nowLocalIsoWithOffset = "$localDateTime$offset",
            timeZoneId = timeZone.id,
            nowUtcIsoString = now.toString(),
            platform = currentPlatform.displayName,
            modelId = modelId,
            providerName = service.displayName,
        )

        val isLimited = !supportsTools(modelId)
        val uiMode = when {
            interactiveModeFlag -> ChatPromptUiMode.INTERACTIVE_UI
            appSettings.isDynamicUiEnabled() && !isLimited -> ChatPromptUiMode.DYNAMIC_UI
            else -> ChatPromptUiMode.NONE
        }

        // Tool-use guidance is only worth sending when the model is actually given tools.
        // Mirror the tool list the request will carry: remote uses the full set (when the
        // model supports tools), local uses the allowlist-filtered set.
        val hasTools = when (variant) {
            SystemPromptVariant.CHAT_REMOTE -> !isLimited && getAvailableTools().isNotEmpty()
            SystemPromptVariant.CHAT_LOCAL -> getAvailableTools().isNotEmpty() && localModelDeclaresTools(modelId)
        }

        val activeSkill = pendingActiveSkillId?.let { skillManager.getSkill(it) }

        val currentConversationId = _currentConversationId.value
        val activeObjective = currentConversationId?.let { objectiveStore.get(it) }

        return buildChatSystemPrompt(
            variant = variant,
            soul = soul,
            mode = ChatMode.fromName(appSettings.getChatMode()),
            activeObjective = activeObjective?.let {
                ActiveObjectiveContext(
                    goal = it.goal,
                    status = it.status,
                    summary = it.summary,
                    attempts = it.attempts,
                )
            },
            hasTools = hasTools,
            memoryEnabled = memoryEnabled,
            schedulingEnabled = schedulingEnabled,
            memoryInstructions = memoryInstructions,
            generalMemories = byCategory[MemoryCategory.GENERAL].orEmpty(),
            preferenceMemories = byCategory[MemoryCategory.PREFERENCE].orEmpty(),
            learningMemories = byCategory[MemoryCategory.LEARNING].orEmpty(),
            errorMemories = byCategory[MemoryCategory.ERROR].orEmpty(),
            pendingTasks = pendingTasks,
            heartbeatAdditions = heartbeatAdditions,
            emailAccounts = emailAccounts,
            runtime = runtime,
            uiMode = uiMode,
            activeSkill = activeSkill,
        ).ifEmpty { null }
    }

    override fun isDynamicUiEnabled(): Boolean = appSettings.isDynamicUiEnabled()

    override fun setDynamicUiEnabled(enabled: Boolean) {
        appSettings.setDynamicUiEnabled(enabled)
    }

    override fun getThemeMode(): ThemeMode = appSettings.getThemeMode()

    override fun setThemeMode(mode: ThemeMode) {
        appSettings.setThemeMode(mode)
    }

    private var interactiveModeFlag = appSettings.getCurrentInteractiveMode()

    override fun setInteractiveMode(enabled: Boolean) {
        interactiveModeFlag = enabled
        appSettings.setCurrentInteractiveMode(enabled)
    }

    override fun isInteractiveModeActive(): Boolean = interactiveModeFlag

    // --- Objective mode ---

    private var objectiveModeFlag = appSettings.getCurrentObjectiveMode()

    override fun isObjectiveModeActive(): Boolean = objectiveModeFlag

    override fun getChatMode(): ChatMode = ChatMode.fromName(appSettings.getChatMode())

    override fun setChatMode(mode: ChatMode) {
        appSettings.setChatMode(mode.name)
    }

    override suspend fun setObjective(goal: String) {
        if (_currentConversationId.value == null) {
            setCurrentConversationId(Uuid.random().toString())
        }
        val conversationId = _currentConversationId.value ?: return
        objectiveModeFlag = goal.isNotBlank()
        appSettings.setCurrentObjectiveMode(objectiveModeFlag)
        appSettings.setCurrentObjectiveGoal(goal)
        objectiveStore.update(conversationId) { existing ->
            when {
                goal.isBlank() -> null
                goal == existing?.goal -> existing
                else -> ObjectiveState(goal = goal)
            }
        }
        val snapshot = savedConversations.value
        val existingConversation = snapshot.find { it.id == conversationId }
        if (existingConversation == null) {
            conversationStorage.saveConversation(
                Conversation(
                    id = conversationId,
                    messages = emptyList(),
                    createdAt = Clock.System.now().toEpochMilliseconds(),
                    updatedAt = Clock.System.now().toEpochMilliseconds(),
                    title = goal,
                    type = Conversation.TYPE_OBJECTIVE,
                ),
            )
        } else {
            conversationStorage.saveConversation(
                existingConversation.copy(
                    title = if (goal.isBlank() && existingConversation.type != Conversation.TYPE_OBJECTIVE) {
                        existingConversation.title
                    } else if (existingConversation.title.isBlank()) {
                        goal
                    } else {
                        existingConversation.title
                    },
                    type = if (goal.isBlank()) {
                        if (existingConversation.type == Conversation.TYPE_OBJECTIVE) Conversation.TYPE_CHAT else existingConversation.type
                    } else {
                        Conversation.TYPE_OBJECTIVE
                    },
                ),
            )
        }
    }

    override suspend fun setObjectiveStatus(status: String) {
        val conversationId = _currentConversationId.value ?: return
        objectiveStore.update(conversationId) {
            it?.copy(status = status, lastRunAtMs = if (status == ObjectiveStatus.RUNNING) 0L else it.lastRunAtMs)
        }
    }

    override fun getActiveObjective(): ObjectiveState? {
        val conversationId = _currentConversationId.value ?: return null
        return objectiveStore.get(conversationId)
    }


    override fun isMemoryEnabled(): Boolean = appSettings.isMemoryEnabled()

    override fun setMemoryEnabled(enabled: Boolean) {
        appSettings.setMemoryEnabled(enabled)
    }

    override fun getMemories(): List<MemoryEntry> = memoryStore.getAllMemories()

    override suspend fun deleteMemory(key: String) {
        memoryStore.forget(key)
    }

    override suspend fun updateMemoryContent(key: String, content: String) {
        memoryStore.updateContent(key, content)
    }

    override fun isSchedulingEnabled(): Boolean = appSettings.isSchedulingEnabled()

    override fun setSchedulingEnabled(enabled: Boolean) {
        appSettings.setSchedulingEnabled(enabled)
    }

    override fun getScheduledTasks(): List<ScheduledTask> = taskStore.getAllTasks()

    override suspend fun cancelScheduledTask(id: String) {
        taskStore.removeTask(id)
    }

    override fun isDaemonEnabled(): Boolean = appSettings.isDaemonEnabled()

    override fun setDaemonEnabled(enabled: Boolean) {
        appSettings.setDaemonEnabled(enabled)
    }

    override fun isSandboxEnabled(): Boolean = appSettings.isSandboxEnabled()

    override fun setSandboxEnabled(enabled: Boolean) {
        appSettings.setSandboxEnabled(enabled)
    }

    override fun getSandboxDistro(): LinuxDistro = appSettings.getSandboxDistro()

    override fun setSandboxDistro(distro: LinuxDistro) {
        appSettings.setSandboxDistro(distro)
    }

    override fun getKaiBuildLaunchAgent(): String? = appSettings.getKaiBuildLaunchAgent()

    override fun setKaiBuildLaunchAgent(agentId: String?) {
        appSettings.setKaiBuildLaunchAgent(agentId)
    }

    override fun getHeartbeatConfig(): HeartbeatConfig = heartbeatManager.getConfig()

    override fun setHeartbeatEnabled(enabled: Boolean) {
        val config = heartbeatManager.getConfig()
        heartbeatManager.saveConfig(config.copy(enabled = enabled))
    }

    override fun setHeartbeatIntervalMinutes(minutes: Int) {
        val config = heartbeatManager.getConfig()
        heartbeatManager.saveConfig(config.copy(intervalMinutes = minutes))
    }

    override fun setHeartbeatActiveHours(start: Int, end: Int) {
        val config = heartbeatManager.getConfig()
        heartbeatManager.saveConfig(config.copy(activeHoursStart = start, activeHoursEnd = end))
    }

    override fun getHeartbeatPrompt(): String = appSettings.getHeartbeatPrompt()

    override fun setHeartbeatPrompt(text: String) {
        appSettings.setHeartbeatPrompt(text)
    }

    override fun getHeartbeatLog(): List<HeartbeatLogEntry> = heartbeatManager.getHeartbeatLog()

    override fun getHeartbeatInstanceId(): String? = heartbeatManager.getConfig().heartbeatInstanceId

    override fun setHeartbeatInstanceId(instanceId: String?) {
        val config = heartbeatManager.getConfig()
        heartbeatManager.saveConfig(config.copy(heartbeatInstanceId = instanceId))
    }

    override fun isEmailEnabled(): Boolean = appSettings.isEmailEnabled()

    override fun setEmailEnabled(enabled: Boolean) {
        appSettings.setEmailEnabled(enabled)
    }

    override fun getEmailAccounts(): List<EmailAccount> = emailStore.getAccounts()

    override suspend fun removeEmailAccount(id: String) {
        emailStore.removeAccount(id)
    }

    override fun getEmailPollIntervalMinutes(): Int = appSettings.getEmailPollIntervalMinutes()

    override fun getPendingEmailCount(): Int = emailStore.getPending().size

    override fun getEmailSyncStates(): Map<String, EmailSyncState> = emailStore.getAllSyncStates()

    override suspend fun pollEmailAccount(accountId: String) {
        val account = emailStore.getAccount(accountId) ?: return
        emailPoller.poll(account)
    }

    override fun setEmailPollIntervalMinutes(minutes: Int) {
        appSettings.setEmailPollIntervalMinutes(minutes)
    }

    override fun isSmsEnabled(): Boolean = appSettings.isSmsEnabled()

    override fun setSmsEnabled(enabled: Boolean) {
        appSettings.setSmsEnabled(enabled)
    }

    override fun getSmsPollIntervalMinutes(): Int = appSettings.getSmsPollIntervalMinutes()

    override fun setSmsPollIntervalMinutes(minutes: Int) {
        appSettings.setSmsPollIntervalMinutes(minutes)
    }

    override fun getPendingSmsCount(): Int = smsStore.getPending().size

    override fun getSmsSyncState(): SmsSyncState = smsStore.getSyncState()

    override fun hasSmsPermission(): Boolean = smsReader.hasPermission()

    override suspend fun requestSmsPermission(): Boolean = smsPermissionController.requestPermission()

    override suspend fun pollSms() {
        smsPoller.poll()
    }

    override fun isSmsSendEnabled(): Boolean = appSettings.isSmsSendEnabled()

    override fun setSmsSendEnabled(enabled: Boolean) {
        appSettings.setSmsSendEnabled(enabled)
    }

    override fun hasSmsSendPermission(): Boolean = smsSender.hasPermission()

    override suspend fun requestSmsSendPermission(): Boolean = smsSendPermissionController.requestPermission()

    override val smsDrafts: StateFlow<List<SmsDraft>> = smsDraftStore.drafts

    // Flips the draft to SENDING, delegates to SmsSender, then updates to SENT/FAILED.
    // Explicit user-triggered (never AI-triggered) — the banner is the gate.
    override suspend fun sendSmsDraft(draftId: String): Boolean {
        val draft = smsDraftStore.getDraft(draftId) ?: return false
        if (draft.status != SmsDraftStatus.PENDING) return false
        smsDraftStore.updateStatus(draftId, SmsDraftStatus.SENDING)
        return when (val result = smsSender.send(draft.address, draft.body)) {
            is SmsSendResult.Success -> {
                smsDraftStore.updateStatus(draftId, SmsDraftStatus.SENT)
                true
            }

            is SmsSendResult.Failure -> {
                smsDraftStore.updateStatus(draftId, SmsDraftStatus.FAILED, result.message)
                false
            }
        }
    }

    override suspend fun discardSmsDraft(draftId: String) {
        smsDraftStore.removeDraft(draftId)
    }

    override fun isNotificationsEnabled(): Boolean = appSettings.isNotificationsEnabled()

    override fun setNotificationsEnabled(enabled: Boolean) {
        appSettings.setNotificationsEnabled(enabled)
    }

    override fun isNotificationListenerAccessGranted(): Boolean = notificationListenerController.isAccessGranted()

    override fun openNotificationListenerSettings() {
        notificationListenerController.openAccessSettings()
    }

    override fun getPendingNotificationCount(): Int = notificationStore.getPending().size

    override fun getNotificationSyncState(): NotificationSyncState = notificationStore.getSyncState()

    override suspend fun clearPendingNotifications() {
        notificationStore.clearPending()
    }

    override fun getUiScale(): Float = appSettings.getUiScale()

    override fun setUiScale(scale: Float) {
        appSettings.setUiScale(scale)
    }

    override fun exportSettingsToJson(sections: Set<ImportSection>): String {
        val toolIds = getPlatformToolDefinitions().map { it.id }
        val jsonObject = appSettings.exportToJson(toolIds, sections, conversationStorage.conversations.value)
        return prettyJson.encodeToString(JsonObject.serializer(), jsonObject)
    }

    override fun getExportPreview(): Map<ImportSection, String?> {
        val toolIds = getPlatformToolDefinitions().map { it.id }
        val jsonObject = appSettings.exportToJson(toolIds, conversations = conversationStorage.conversations.value)
        return detectExportableSections(jsonObject)
    }

    override fun importSettingsFromJson(json: String, sections: Set<ImportSection>, replace: Boolean): Int {
        val jsonObject = SharedJson.parseToJsonElement(json).jsonObject
        val toolIds = getPlatformToolDefinitions().map { it.id }
        return appSettings.importFromJson(jsonObject, toolIds, sections, replace)
    }

    override suspend fun askWithTools(
        prompt: String,
        instanceId: String?,
        conversationIdOverride: String?,
        objectiveRunAtMs: Long?,
        onActivity: ((List<History>) -> Unit)?,
    ): String {
        // Selection: explicit instance > first remote > first on-device. The simple-tool
        // allowlist works at any context size, so on-device is always eligible for fallback.
        val instances = getConfiguredServiceInstances()
        val targetInstance = instanceId?.let { id -> instances.find { it.instanceId == id } }
            ?: if (objectiveRunAtMs != null && instanceId != null) {
                error("The configured Objective model is no longer available")
            } else {
                null
            }
        val selectedInstance = targetInstance
            ?: instances.firstOrNull { !Service.fromId(it.serviceId).isOnDevice }
            ?: instances.firstOrNull { Service.fromId(it.serviceId).isOnDevice }
            ?: if (objectiveRunAtMs != null) error("Configure a model for Objective recovery") else return ""
        val service = Service.fromId(selectedInstance.serviceId)
        val messages = listOf(History(role = History.Role.USER, content = prompt))
        val localHistory = MutableStateFlow(messages)
        if (objectiveRunAtMs != null) {
            val id = requireNotNull(conversationIdOverride) { "Objective recovery needs a bound conversation" }
            val state = objectiveStore.get(id)
            if (state == null || state.lastRunAtMs != objectiveRunAtMs || state.status !in ObjectiveStatus.ACTIVE) return ""
            return withContext(ConversationIdElement(id) + ObjectiveRunElement(objectiveRunAtMs, Uuid.random().toString())) {
                coroutineScope {
                    val observer = onActivity?.let { notify ->
                        launch { localHistory.collect { notify(it) } }
                    }
                    try {
                        val turn = askWithService(service, messages, OBJECTIVE_RECOVERY_SYSTEM_PROMPT, selectedInstance.instanceId, localHistory)
                        onActivity?.invoke(localHistory.value + History(
                            role = History.Role.ASSISTANT,
                            content = "",
                            reasoningContent = turn.reasoningContent,
                        ))
                        objectiveStore.update(id) {
                            it?.takeIf { current -> current.lastRunAtMs == objectiveRunAtMs }
                                ?.recordActivity("Run report: ${turn.content}") ?: it
                        }
                        turn.content
                    } finally {
                        observer?.cancel()
                    }
                }
            }
        }
        val systemPrompt = getActiveSystemPrompt()
        // Use a local history to avoid polluting the current conversation's chatHistory
        // When a conversation override is set (heartbeat / scheduled tasks), bind any
        // tool calls in this run to that conversation's sandbox session via the
        // coroutine context — otherwise tool dispatch would inherit `_currentConversationId`
        // (the chat the user is viewing), routing the heartbeat's shell commands into
        // that chat's persistent bash session.
        return if (conversationIdOverride != null) {
            withContext(ConversationIdElement(conversationIdOverride)) {
                askWithService(service, messages, systemPrompt, selectedInstance.instanceId, localHistory).content
            }
        } else {
            askWithService(service, messages, systemPrompt, selectedInstance.instanceId, localHistory).content
        }
    }

    override suspend fun askSilently(question: String): String {
        val service = currentService()
        val firstInstance = getConfiguredServiceInstances().firstOrNull() ?: return ""
        val messages = listOf(History(role = History.Role.USER, content = question))

        if (service.isOnDevice) {
            // Throwaway history — we don't want tool-execution rows leaking into the
            // visible chatHistory for a "silent" call. LOCAL variant of the system
            // prompt so small on-device models get the right section set.
            val localPrompt = getActiveSystemPrompt(SystemPromptVariant.CHAT_LOCAL)
            return askWithLocalEngine(messages, localPrompt, firstInstance.instanceId, MutableStateFlow(messages))
        }

        val systemPrompt = getActiveSystemPrompt()
        val creds = instanceCredentials(firstInstance.instanceId, service)

        return plainChat(service, creds, messages, systemPrompt, retry = false).content
    }

    override suspend fun askSilentlyWithInstance(instanceId: String, prompt: String, timeoutMs: Long): String {
        val instance = getConfiguredServiceInstances().find { it.instanceId == instanceId }
            ?: return askSilently(prompt)
        val service = Service.fromId(instance.serviceId)
        val messages = listOf(History(role = History.Role.USER, content = prompt))

        if (service.isOnDevice) {
            return askWithLocalEngine(messages, null, instanceId, MutableStateFlow(messages))
        }

        val creds = instanceCredentials(instanceId, service)

        return plainChat(
            service = service,
            credentials = creds,
            messages = messages,
            systemPrompt = null,
            requestTimeoutMs = timeoutMs.takeIf { it > 0 },
            retry = false,
        ).content
    }

    private val _hasUnreadHeartbeat = MutableStateFlow(false)
    override val hasUnreadHeartbeat: StateFlow<Boolean> = _hasUnreadHeartbeat

    override fun clearUnreadHeartbeat() {
        _hasUnreadHeartbeat.value = false
    }

    private val _openHeartbeatRequested = MutableStateFlow(false)
    override val openHeartbeatRequested: StateFlow<Boolean> = _openHeartbeatRequested

    override fun requestOpenHeartbeat() {
        _openHeartbeatRequested.value = true
    }

    override fun consumeOpenHeartbeatRequest() {
        _openHeartbeatRequested.value = false
    }

    private val _openAssistRequested = MutableStateFlow(false)
    override val openAssistRequested: StateFlow<Boolean> = _openAssistRequested

    override fun requestOpenAssist() {
        _openAssistRequested.value = true
    }

    override fun consumeOpenAssistRequest() {
        _openAssistRequested.value = false
    }

    private val _pendingShareText = MutableStateFlow<String?>(null)
    override val pendingShareText: StateFlow<String?> = _pendingShareText

    override fun requestOpenShare(text: String) {
        _pendingShareText.value = text
    }

    override fun consumeOpenShareRequest() {
        _pendingShareText.value = null
    }

    override suspend fun addAssistantMessage(content: String) {
        val now = Clock.System.now().toEpochMilliseconds()

        val existing = savedConversations.value.find { it.type == Conversation.TYPE_HEARTBEAT }
        val heartbeatId = existing?.id ?: getOrCreateHeartbeatConversationId()

        val newMessage = Conversation.Message(
            id = Uuid.random().toString(),
            role = "assistant",
            content = content,
        )

        val messages = ((existing?.messages ?: emptyList()) + newMessage).takeLast(MAX_HEARTBEAT_MESSAGES)

        val conversation = Conversation(
            id = heartbeatId,
            messages = messages,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            type = Conversation.TYPE_HEARTBEAT,
        )

        _hasUnreadHeartbeat.value = true
        conversationStorage.saveConversation(conversation)
    }

    override suspend fun addAssistantMessageTo(conversationId: String, content: String, reasoningContent: String?) {
        val now = Clock.System.now().toEpochMilliseconds()

        val existing = savedConversations.value.find { it.id == conversationId }

        val newMessage = Conversation.Message(
            id = Uuid.random().toString(),
            role = "assistant",
            content = content,
            reasoningContent = reasoningContent,
        )

        // Objective continuations can run long — use the heartbeat-sized cap.
        val cap = MAX_HEARTBEAT_MESSAGES
        val messages = ((existing?.messages ?: emptyList()) + newMessage).takeLast(cap)

        val conversation = Conversation(
            id = conversationId,
            messages = messages,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            type = existing?.type ?: Conversation.TYPE_OBJECTIVE,
        )

        conversationStorage.saveConversation(conversation)
        if (_currentConversationId.value == conversationId) {
            chatHistory.update { it + History(id = newMessage.id, role = History.Role.ASSISTANT, content = content, reasoningContent = reasoningContent) }
        }
    }

    private suspend fun recordToolMemory(conversationId: String?, callId: String, name: String, result: String) {
        if (conversationId == null) return
        val run = currentObjectiveRun()
        continuity.write(conversationId, listOf(ContinuityEntry(callId, "Tool $name", continuityExcerpt(result))))
        objectiveStore.update(conversationId) {
            if (it != null && (run == null || it.lastRunAtMs == run.startedAtMs)) it.recordActivity("$name: $result") else it
        }
    }

    override suspend fun getOrCreateHeartbeatConversationId(): String {
        val existing = savedConversations.value.find { it.type == Conversation.TYPE_HEARTBEAT }
        if (existing != null) return existing.id
        val now = Clock.System.now().toEpochMilliseconds()
        val id = Uuid.random().toString()
        conversationStorage.saveConversation(
            Conversation(
                id = id,
                messages = emptyList(),
                createdAt = now,
                updatedAt = now,
                type = Conversation.TYPE_HEARTBEAT,
            ),
        )
        return id
    }

    private fun deriveTitle(history: List<History>): String {
        val firstUserMessage = history.firstOrNull { it.role == History.Role.USER }?.content ?: return ""
        return if (firstUserMessage.length <= 50) {
            firstUserMessage
        } else {
            val truncated = firstUserMessage.take(50)
            val lastSpace = truncated.lastIndexOf(' ')
            if (lastSpace > 20) truncated.substring(0, lastSpace) + "..." else truncated + "..."
        }
    }

    // On-device inference (LiteRT)

    override fun isLocalInferenceAvailable(): Boolean = localInferenceEngine != null

    override fun getLocalEngineState(): StateFlow<EngineState>? = localInferenceEngine?.engineState

    override fun getLocalDownloadingModelId(): StateFlow<String?>? = localInferenceEngine?.downloadingModelId

    override fun getLocalDownloadProgress(): StateFlow<Float?>? = localInferenceEngine?.downloadProgress

    override fun getLocalDownloadError(): StateFlow<DownloadError?>? = localInferenceEngine?.downloadError

    override fun getLocalDownloadedModels(): List<DownloadedModel> = localInferenceEngine?.getDownloadedModels() ?: emptyList()

    override fun getLocalAvailableModels(): List<LocalModel> = localInferenceEngine?.getAvailableModels() ?: emptyList()

    override fun getLocalImportedModels(): List<LocalModel> = localInferenceEngine?.getImportedLocalModels() ?: emptyList()

    override fun getLocalFreeSpaceBytes(): Long = localInferenceEngine?.getFreeSpaceBytes() ?: 0L

    override fun getTotalDeviceMemoryBytes(): Long = getTotalMemoryBytes()

    override fun getModelContextTokens(modelId: String): Int = appSettings.getModelContextTokens(modelId)

    override fun setModelContextTokens(modelId: String, contextTokens: Int) {
        appSettings.setModelContextTokens(modelId, contextTokens)
    }

    override suspend fun releaseLocalEngine() {
        localInferenceEngine?.release()
    }

    override fun getLocalImportingFileName(): StateFlow<String?>? = localInferenceEngine?.importingFileName

    override fun getLocalImportProgress(): StateFlow<Float?>? = localInferenceEngine?.importProgress

    override fun getLocalImportError(): StateFlow<ModelImportError?>? = localInferenceEngine?.importError

    override fun startLocalModelDownload(model: LocalModel) {
        localInferenceEngine?.startDownload(model)
    }

    override fun cancelLocalModelDownload() {
        localInferenceEngine?.cancelDownload()
    }

    override suspend fun importLocalModel(source: PlatformFile): ModelImportResult {
        val engine = localInferenceEngine
            ?: return ModelImportResult.Failure(ModelImportError.COPY_FAILED, "On-device inference not available")
        return engine.importModel(source)
    }

    override fun cancelLocalModelImport() {
        localInferenceEngine?.cancelImport()
    }

    override suspend fun deleteLocalModel(modelId: String) {
        localInferenceEngine?.deleteModel(modelId)
    }
}
