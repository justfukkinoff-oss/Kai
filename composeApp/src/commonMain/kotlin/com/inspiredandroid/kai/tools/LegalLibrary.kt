@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.inspiredandroid.kai.tools

import com.inspiredandroid.kai.CommandHandle
import com.inspiredandroid.kai.SandboxController
import com.inspiredandroid.kai.data.AppSettings
import com.inspiredandroid.kai.data.currentConversationIdOrNull
import com.inspiredandroid.kai.mcp.McpServerManager
import com.inspiredandroid.kai.network.tools.ParameterSchema
import com.inspiredandroid.kai.network.tools.Tool
import com.inspiredandroid.kai.network.tools.ToolSchema
import kai.composeapp.generated.resources.Res
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

@Serializable
internal data class LibraryRoute(val url: String = "", val model: String = "", val key: String = "", val format: String = "")

internal object LegalLibrary : KoinComponent {
    private val sandbox: SandboxController by inject()
    private val settings: AppSettings by inject()
    val roles = linkedMapOf("ocr" to "OCR vision", "cleanup" to "OCR cleanup", "embedding" to "Embeddings",
        "rerank" to "Cross-encoder rerank", "photo" to "Photo description")

    fun route(role: String) = runCatching {
        Json.decodeFromString<LibraryRoute>(settings.libraryValue("route.$role"))
    }.getOrDefault(LibraryRoute())

    fun saveRoute(role: String, value: LibraryRoute) {
        settings.setLibraryValue("route.$role", if (value.url.isBlank() || value.model.isBlank()) "" else Json.encodeToString(LibraryRoute.serializer(), value))
    }

    fun currentMatter() = settings.getCurrentConversationId()?.let(::matterFor)
    fun matterFor(chat: String) = settings.libraryValue("matter.$chat").takeIf { it.isNotBlank() }
    fun courtListenerToken() = settings.libraryValue("courtListenerToken")
    fun saveCourtListenerToken(value: String) = settings.setLibraryValue("courtListenerToken", value.trim())
    fun bind(matter: String): Boolean {
        val chat = settings.getCurrentConversationId() ?: return false
        settings.setLibraryValue("matter.$chat", matter)
        return true
    }

    private fun issue(message: String) = buildJsonObject { put("error", message) }

    suspend fun call(
        action: String,
        scope: String = "",
        args: Map<String, JsonElement> = emptyMap(),
        uploadTitle: String? = null,
        upload: ByteArray? = null,
        onProgress: (String) -> Unit = {},
    ): JsonObject {
        if (!sandbox.status.value.ready) return issue("Start the existing Linux sandbox first.")
        val id = Uuid.random().toString()
        val folder = "/root/.kai/library/requests/$id"
        val session = "library-$id"
        var handle: CommandHandle? = null
        val payload = buildJsonObject {
            args.forEach { (key, value) -> put(key, value) }
            put("action", action)
            put("scope", scope)
            put("routes", buildJsonObject {
                val selected = when (action) {
                    "ingest", "reindex" -> roles.keys
                    "search" -> setOf("embedding", "rerank")
                    "list", "reembed" -> setOf("embedding")
                    else -> emptySet()
                }
                for (role in selected) {
                    val route = route(role)
                    if (route.url.isNotBlank() && route.model.isNotBlank()) {
                        put(role, Json.encodeToJsonElement(LibraryRoute.serializer(), if (action == "list") route.copy(key = "") else route))
                    }
                }
            })
            if (upload != null && uploadTitle != null) {
                val ext = uploadTitle.substringAfterLast('.', "").lowercase().takeIf { it.matches(Regex("[a-z0-9]{1,8}")) } ?: "txt"
                put("title", uploadTitle)
                put("path", "$folder/upload.$ext")
            }
        }
        try {
            if (!sandbox.writeBinaryFile("$folder/library.py", Res.readBytes("files/legal/library.py"))) return issue("Could not stage library worker.")
            if (action == "export" && !sandbox.writeBinaryFile("$folder/filing.py", Res.readBytes("files/legal/filing.py"))) return issue("Could not stage filing formatter.")
            if (upload != null) {
                val path = (payload["path"] as? JsonPrimitive)?.content ?: return issue("Missing upload name.")
                if (!sandbox.writeBinaryFile(path, upload)) return issue("Could not copy the upload.")
            }
            if (!sandbox.writeBinaryFile("$folder/request.json", payload.toString().encodeToByteArray())) return issue("Could not stage library request.")
            val stdout = MutableStateFlow("")
            val stderr = MutableStateFlow("")
            handle = sandbox.executeCommandStreaming(
                "chmod 700 '$folder'; chmod 600 '$folder/request.json'; " +
                    "py=python3; if [ -x /root/.kai/legal-venv/bin/python ]; then py=/root/.kai/legal-venv/bin/python; fi; " +
                    "\"\$py\" -u '$folder/library.py' '$folder/request.json'",
                onStdout = { value ->
                    stdout.update { (it + value + "\n").takeLast(400_000) }
                    value.lineSequence().filter { it.startsWith("KAI_PROGRESS ") }.forEach { onProgress(it.substringAfter(' ').trim()) }
                },
                onStderr = { value -> stderr.update { (it + value).takeLast(2000) } },
                sessionId = session,
            )
            handle.awaitExit()
            val line = stdout.value.substringAfterLast("KAI_LIBRARY ", "").lineSequence().first().trim()
            if (line.isEmpty()) return issue(if (stderr.value.contains("python3")) "Python is unavailable; run Set up sandbox." else "The library worker did not return a result.")
            return Json.parseToJsonElement(line) as? JsonObject ?: issue("Invalid library response.")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return issue("The library request could not be completed.")
        } finally {
            withContext(NonCancellable) {
                handle?.cancel()
                sandbox.closeSession(session)
                withTimeoutOrNull(5000) { sandbox.deleteEntry(folder, recursive = true) }
            }
        }
    }

    suspend fun setup(onProgress: (String) -> Unit): String {
        if (!sandbox.status.value.ready) return "Start the existing Linux sandbox first."
        val session = "library-setup-${Uuid.random()}"
        var handle: CommandHandle? = null
        try {
            handle = sandbox.executeCommandStreaming(
                "if command -v apk >/dev/null; then apk add python3 py3-pip poppler-utils tesseract-ocr tesseract-ocr-data-eng; " +
                    "elif command -v apt-get >/dev/null; then apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y python3 python3-venv poppler-utils tesseract-ocr; else exit 2; fi && " +
                    "python3 -m venv /root/.kai/legal-venv && /root/.kai/legal-venv/bin/python -m pip install eyecite",
                onStdout = { onProgress(it.trim()) }, onStderr = { onProgress(it.trim()) }, sessionId = session,
            )
            return if (handle.awaitExit() == 0) "PDF extraction, local English OCR, and citation parsing are ready." else "Sandbox setup did not finish. See the terminal for package errors."
        } finally {
            handle?.cancel()
            sandbox.closeSession(session)
        }
    }

    suspend fun open(path: String) = sandbox.openFile(path).fold({ "Opened original" }, { "Could not open original" })
}

internal object FilingExportTool : Tool, KoinComponent {
    private val sandbox: SandboxController by inject()
    private val mcp: McpServerManager by inject()
    override val timeout = 6.minutes
    override val schema = ToolSchema("filing_export", "Export a filing with Times New Roman 13 pt, double spacing, one-inch margins, caption, TOC, TOA, and service certificate. Citation review is advisory. Use heading lines starting with # and provide actual service details; never invent them.", mapOf(
        "draft" to ParameterSchema("string", "Main filing body", true),
        "caption" to ParameterSchema("string", "Complete case caption", true),
        "title" to ParameterSchema("string", "Filing title", true),
        "service" to ParameterSchema("string", "User-supplied certificate of service; omit when unknown", false),
        "authorities" to ParameterSchema("string", "Optional newline-separated authority entries", false),
        "format" to ParameterSchema("string", "docx, pdf, or both; default docx", false),
    ))

    override suspend fun execute(args: Map<String, Any>): Any {
        val draft = args["draft"] as? String ?: return "Draft required."
        if (draft.length > 500_000) return "Export request exceeds 500,000 characters."
        val inputs = args.filterKeys { it in schema.parameters }.filterValues { it is String }
        var output: Any? = null
        for (provider in listOf("revise", "doxai")) {
            val tool = mcp.compatibleTool(provider, listOf("format_document", "export_document", "format_filing")) { candidate ->
                candidate.schema.parameters.keys.containsAll(listOf("draft", "caption", "title", "service", "authorities", "format")) &&
                    candidate.schema.parameters.none { (key, value) -> value.required && key !in inputs }
            } ?: continue
            val response = withTimeoutOrNull(25_000) { tool.execute(inputs) } as? Map<*, *>
            val result = response?.get("result") as? JsonElement
            if (response?.get("success") == true && result != null && result !is JsonArray) {
                output = mapOf("provider" to provider, "result" to result.toString())
                break
            }
        }
        val localArgs = inputs.mapValues { JsonPrimitive(it.value.toString()) }
        val exported = output ?: LegalLibrary.call("export", args = localArgs).toString()
        val review = CitationCheckTool.review(draft, sandbox)
        return mapOf("export" to exported, "citation_review" to review.message(), "citation_sources" to review.sources)
    }
}

internal object LegalLibraryTools {
    private fun text(description: String, required: Boolean = false) = ParameterSchema("string", description, required)
    private fun number(description: String) = ParameterSchema("integer", description, false)
    private val filters = mapOf(
        "query" to text("Search query; wrap an exact phrase in quotes", true),
        "mode" to text("hybrid (default), exact, keyword, semantic, or citation"),
        "document" to text("Document ID"), "doc_type" to text("Document type"), "court" to text("Court"),
        "docket" to text("Docket number"), "bates" to text("Exact Bates stamp"), "ecf" to text("ECF document or attachment number"),
        "exhibit" to text("Exhibit label"), "date_from" to text("Earliest ISO date"), "date_to" to text("Latest ISO date"),
        "page_from" to number("First PDF page"), "page_to" to number("Last PDF page"),
        "line_from" to number("First transcript line"), "line_to" to number("Last transcript line"), "limit" to number("Maximum hits"),
    )
    private fun operation(name: String, description: String, action: String, scope: String?, parameters: Map<String, ParameterSchema>): Tool =
        object : Tool {
            override val schema = ToolSchema(name, description, parameters)
            override val timeout = 12.minutes
            override suspend fun execute(args: Map<String, Any>): Any {
                val actual = when (val collection = scope ?: (args["collection"] as? String ?: "matter")) {
                    "law", "code" -> collection
                    "matter" -> currentConversationIdOrNull()?.let(LegalLibrary::matterFor)
                        ?: return "Bind this chat to a matter in Settings → Agent → Legal Library."
                    else -> return "Unknown collection."
                }
                val values = args.filterKeys { it in parameters && it != "collection" }.mapValues { (_, value) ->
                    when (value) {
                        is Boolean -> JsonPrimitive(value)
                        is Number -> JsonPrimitive(value)
                        else -> JsonPrimitive(value.toString())
                    }
                }
                return LegalLibrary.call(action, actual, values).toString()
            }
        }
    private val collection = mapOf("collection" to text("matter (default), law, or code"))
    private val location = collection + mapOf("document" to text("Document ID", true), "page" to number("Physical page, starting at 1"))
    val tools = listOf(
        operation("matter_search", "Search the private record for the matter bound to this chat. Use library_read for source evidence.", "search", "matter", filters),
        operation("law_search", "Search the separate local public law collection with citation and hybrid retrieval.", "search", "law", filters),
        operation("code_search", "Search the separate local code collection, including identifiers.", "search", "code", filters),
        operation("library_read", "Read raw text and provenance for a particular page.", "read", null, location + ("offset" to number("Advance using next_offset"))),
        operation("library_quote", "Check an exact attributed quote on a page, preserving OCR provenance.", "quote", null, location + ("query" to text("Exact quote", true))),
        operation("library_list", "List indexed documents in a collection.", "list", null, collection),
        operation("library_ingest", "Index a file already in the Linux sandbox.", "ingest", null, collection + mapOf(
            "path" to text("Existing absolute sandbox path", true), "title" to text("Original filename"),
            "doc_type" to text("Document type"), "court" to text("Court"), "date" to text("ISO date"),
            "docket" to text("Docket number"), "photo" to ParameterSchema("boolean", "Describe a photo instead of transcribing a scan", false),
        )),
    )
}
