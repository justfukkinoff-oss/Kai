@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.inspiredandroid.kai.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import com.inspiredandroid.kai.tools.LegalLibrary
import com.inspiredandroid.kai.tools.LibraryRoute
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.size
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.uuid.Uuid

private fun JsonObject.field(key: String) = (get(key) as? JsonPrimitive)?.content.orEmpty()
private fun JsonObject.rows(key: String) = (get(key) as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()

@Composable
internal fun LegalLibrarySection(onManageSandbox: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Legal Library", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
        LegalLibraryControls(onManageSandbox)
    }
}

@Composable
private fun LegalLibraryControls(onManageSandbox: () -> Unit) {
    val coroutine = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    var active by remember { mutableStateOf<Job?>(null) }
    var running by remember { mutableStateOf(false) }
    val sandboxState by LegalLibrary.sandboxStatus.collectAsState()
    var message by remember { mutableStateOf("") }
    var matter by remember { mutableStateOf(LegalLibrary.currentMatter().orEmpty()) }
    var collection by remember { mutableStateOf("matter") }
    val target = if (collection == "matter") matter else collection
    var matterName by remember { mutableStateOf("") }
    var matters by remember { mutableStateOf(emptyList<JsonObject>()) }
    var documents by remember { mutableStateOf(emptyList<JsonObject>()) }
    var hits by remember { mutableStateOf(emptyList<JsonObject>()) }
    var pageText by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var documentType by remember { mutableStateOf("") }
    var court by remember { mutableStateOf("") }
    var docket by remember { mutableStateOf("") }
    var date by remember { mutableStateOf("") }
    var metadata by remember { mutableStateOf(false) }
    var photo by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf("Files") }
    var chooseMatter by remember { mutableStateOf(false) }
    var createMatter by remember { mutableStateOf(false) }
    var forceOcr by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf(LegalLibrary.courtListenerToken()) }
    var exports by remember { mutableStateOf(emptyList<JsonObject>()) }

    fun work(label: String, block: suspend () -> Unit) {
        if (running) return
        message = label
        running = true
        active = coroutine.launch {
            try {
                block()
            } catch (e: CancellationException) {
                message = "Cancelled."
                throw e
            } catch (_: Exception) {
                message = "The operation could not finish."
            } finally {
                running = false
            }
        }
    }
    suspend fun refreshDocuments(scope: String) {
        val response = LegalLibrary.call("list", scope)
        documents = response.rows("documents")
        message = response.field("error").ifEmpty { "${documents.size} indexed documents." }
    }
    suspend fun refreshMatters() {
        val response = LegalLibrary.call("matters")
        matters = response.rows("matters")
        message = response.field("error").ifEmpty { "${matters.size} matters." }
    }
    val picker = rememberFilePickerLauncher(
        mode = FileKitMode.Multiple(maxItems = 10),
        type = FileKitType.File(extensions = listOf(
            "pdf", "docx", "pptx", "epub", "txt", "md", "csv", "json", "xml", "kt", "java", "py", "js", "ts", "rs", "go", "jpg", "jpeg", "png", "webp", "tif", "tiff",
        )),
    ) { files ->
        if (!files.isNullOrEmpty() && target.isNotBlank()) work("Preparing ${files.size} files…") {
            val results = mutableListOf<String>()
            for ((index, file) in files.withIndex()) {
                val label = "${index + 1}/${files.size} ${file.name}"
                message = "$label: copying to the sandbox…"
                if (file.size() > 80_000_000) {
                    results.add("${file.name}: exceeds the 80 MB limit.")
                } else {
                    val result = LegalLibrary.call("ingest", target,
                        mapOf("doc_type" to JsonPrimitive(documentType.trim()), "court" to JsonPrimitive(court.trim()),
                            "docket" to JsonPrimitive(docket.trim()), "date" to JsonPrimitive(date.trim()), "photo" to JsonPrimitive(photo),
                            "force_ocr" to JsonPrimitive(forceOcr)),
                        upload = file, onProgress = { message = "$label: $it" })
                    val error = result.field("error")
                    val missing = (result["needs_ocr"] as? JsonArray)?.size ?: 0
                    results.add(if (error.isNotBlank()) "${file.name}: $error" else
                        "${file.name}: ${result.field("status")}${if (missing > 0) " ($missing pages need OCR)" else ""}.")
                }
            }
            refreshDocuments(target)
            message = results.joinToString("\n")
        }
    }

    LaunchedEffect(sandboxState.ready) {
        if (sandboxState.ready) work("Opening library…") { refreshMatters(); if (target.isNotBlank()) refreshDocuments(target) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (name in listOf("Files", "Models", "Setup")) {
            FilterChip(selected = panel == name, onClick = { panel = name }, label = { Text(name) })
        }
    }
    if (message.isNotBlank() || running) SettingsCard(Modifier.fillMaxWidth()) {
        if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        if (running) TextButton(onClick = { active?.cancel() }) { Text("Cancel") }
    }
    if (panel == "Models") {
        SettingsCard(Modifier.fillMaxWidth()) {
            Text("Models used by the library", style = MaterialTheme.typography.titleMedium)
            Text("Independent of chat. Remote routes receive the document text or page images required for their task. Leave OCR blank for local Tesseract.")
        }
        for ((role, title) in LegalLibrary.roles) SettingsCard(Modifier.fillMaxWidth()) { LibraryRouteEditor(role, title) }
        return
    }
    if (panel == "Setup") {
        SettingsCard(Modifier.fillMaxWidth()) {
            Text("Set up the library", style = MaterialTheme.typography.titleMedium)
            Text("Start Linux Sandbox, then install the library tools below. Next, choose a matter in Files and tap Add files. You can select up to 10 files at a time. No provider is needed for local OCR or keyword search.")
            TextButton(onClick = onManageSandbox) { Text("Open Linux Sandbox settings") }
            Button(enabled = sandboxState.ready && !running, onClick = {
                work("Installing sandbox packages…") {
                    val setup = LegalLibrary.setup { message = it }
                    refreshMatters()
                    if (target.isNotBlank()) refreshDocuments(target)
                    message = setup
                }
            }) { Text("Install PDF, OCR and citation tools") }
        }
        SettingsCard(Modifier.fillMaxWidth()) {
            Text("How scanned PDFs are handled", style = MaterialTheme.typography.titleMedium)
            Text("Kai checks each PDF page for embedded text. Image-only pages are rendered to individual PNGs and processed one at a time. Local Tesseract is used unless a vision OCR route is configured. Hybrid PDFs can use both methods in one file.")
            Text("If a scan has a poor text layer, choose OCR every PDF page in Files → Import options. The original PDF stays intact. Open a result’s source page text and compare it with the PDF before relying on OCR wording.")
        }
        SettingsCard(Modifier.fillMaxWidth()) {
            Text("Citation lookup", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), label = { Text("CourtListener API token (fallback)") },
                singleLine = true, visualTransformation = PasswordVisualTransformation())
            TextButton(onClick = { LegalLibrary.saveCourtListenerToken(token); message = "CourtListener settings saved." }) { Text("Save CourtListener token") }
        }
        SettingsCard(Modifier.fillMaxWidth()) {
            TextButton(enabled = !running, onClick = {
                work("Loading exports…") {
                    val result = LegalLibrary.call("exports")
                    exports = result.rows("files")
                    message = result.field("error").ifEmpty { "${exports.size} local exports." }
                }
            }) { Text("Show filing exports") }
            for (file in exports) TextButton(enabled = !running, onClick = {
                work("Opening export…") { message = LegalLibrary.open(file.field("path")) }
            }) { Text(file.field("name")) }
        }
        return
    }
    SettingsCard(Modifier.fillMaxWidth()) {
    Text("Collection", style = MaterialTheme.typography.titleMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((key, title) in listOf("matter" to "Matter", "law" to "Public law", "code" to "Code")) {
            FilterChip(selected = collection == key, enabled = !running, onClick = {
                collection = key
                documents = emptyList()
                hits = emptyList()
                pageText = ""
                val selected = if (key == "matter") matter else key
                if (selected.isNotBlank()) work("Loading…") { refreshDocuments(selected) }
            }, label = { Text(title) })
        }
    }
    if (collection == "matter") {
        val name = matters.firstOrNull { it.field("id") == matter }?.field("name")
        Text(if (matter.isBlank()) "Choose a matter to search its records in this chat." else "Current chat matter: ${name ?: "Selected matter"}")
        Row {
            Box {
                TextButton(enabled = !running && matters.isNotEmpty(), onClick = { chooseMatter = true }) { Text(if (matter.isBlank()) "Choose matter" else "Change matter") }
                DropdownMenu(expanded = chooseMatter, onDismissRequest = { chooseMatter = false }) {
                    for (item in matters) DropdownMenuItem(text = { Text(item.field("name")) }, onClick = {
                        val id = item.field("id")
                        chooseMatter = false
                        LegalLibrary.bind(id)
                        matter = id
                        hits = emptyList()
                        pageText = ""
                        work("Loading ${item.field("name")}…") { refreshDocuments(id) }
                    })
                    if (matter.isNotBlank()) DropdownMenuItem(text = { Text("Unbind this chat") }, onClick = {
                        chooseMatter = false
                        LegalLibrary.bind("")
                        matter = ""; documents = emptyList(); hits = emptyList(); pageText = ""
                    })
                }
            }
            TextButton(enabled = sandboxState.ready && !running, onClick = { createMatter = !createMatter }) { Text("New matter") }
        }
        if (createMatter) {
        OutlinedTextField(matterName, { matterName = it }, Modifier.fillMaxWidth(), label = { Text("New matter name") }, singleLine = true)
        Button(enabled = matterName.isNotBlank() && !running, onClick = {
            work("Creating matter…") {
                val id = Uuid.random().toString()
                val response = LegalLibrary.call("matter_create", id, mapOf("name" to JsonPrimitive(matterName.trim())))
                if (response.field("error").isNotBlank()) message = response.field("error")
                else {
                    matterName = ""
                    createMatter = false
                    refreshMatters()
                    LegalLibrary.bind(id)
                    matter = id
                    hits = emptyList()
                    pageText = ""
                    refreshDocuments(id)
                }
            }
        }) { Text("Create matter") }
        }
    }
    Row {
        Button(enabled = sandboxState.ready && !running, onClick = {
            if (target.isNotBlank()) picker.launch()
            else if (matters.isNotEmpty()) chooseMatter = true
            else { createMatter = true; message = "Create a matter, then tap Add files." }
        }) { Text("Add files") }
        TextButton(enabled = sandboxState.ready && !running, onClick = {
            work("Refreshing…") { refreshMatters(); if (target.isNotBlank()) refreshDocuments(target) }
        }) { Text("Refresh") }
    }
    if (!sandboxState.ready) TextButton(onClick = onManageSandbox) { Text("Start Linux Sandbox to add files") }
    if (target.isNotBlank()) {
        TextButton(onClick = { metadata = !metadata }) { Text("Import options") }
        if (metadata) {
            OutlinedTextField(documentType, { documentType = it }, Modifier.fillMaxWidth(), label = { Text("Document type, e.g. transcript") }, singleLine = true)
            OutlinedTextField(court, { court = it }, Modifier.fillMaxWidth(), label = { Text("Court") }, singleLine = true)
            OutlinedTextField(docket, { docket = it }, Modifier.fillMaxWidth(), label = { Text("Docket") }, singleLine = true)
            OutlinedTextField(date, { date = it }, Modifier.fillMaxWidth(), label = { Text("Date (YYYY-MM-DD)") }, singleLine = true)
            Row { Checkbox(photo, { photo = it }); Text("Describe photographs instead of transcribing them") }
            Row { Checkbox(forceOcr, { forceOcr = it }); Text("OCR every PDF page, even when it has embedded text") }
        }
    }
    }
    if (target.isNotBlank()) SettingsCard(Modifier.fillMaxWidth()) {
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Search selected collection") })
        Button(enabled = !running && query.isNotBlank(), onClick = {
            work("Searching…") {
                val result = LegalLibrary.call("search", target, mapOf("query" to JsonPrimitive(query.trim())))
                hits = result.rows("hits")
                pageText = ""
                val notes = (result["notes"] as? JsonArray)?.joinToString("; ") { (it as? JsonPrimitive)?.content.orEmpty() }.orEmpty()
                message = result.field("error").ifEmpty { "${hits.size} results. $notes" }
            }
        }) { Text("Search") }
        if (pageText.isNotBlank()) Text(pageText)
        for (hit in hits) {
            HorizontalDivider()
            Text("${hit.field("title")} · page ${hit.field("page")} · ${hit.field("method")}")
            Text(listOf("bates", "ecf", "exhibit", "line_start", "line_end").mapNotNull { key ->
                hit.field(key).takeIf { it.isNotBlank() && it != "null" }?.let { "$key $it" }
            }.joinToString(" · "))
            Text(hit.field("text").take(600))
            Row {
                TextButton(enabled = !running, onClick = { work("Reading page…") {
                    val result = LegalLibrary.call("read", target, mapOf("document" to JsonPrimitive(hit.field("doc")), "page" to JsonPrimitive(hit.field("page"))))
                    pageText = result.field("text")
                    message = result.field("error").ifEmpty { "Source page ${hit.field("page")}." }
                } }) { Text("Read page text") }
                val source = hit.field("source_url")
                if (source.matches(Regex("https://www\\.courtlistener\\.com/opinion/\\d+/"))) {
                    TextButton(onClick = {
                        runCatching { uriHandler.openUri(source) }.onFailure { message = "Could not open CourtListener." }
                    }) { Text("CourtListener") }
                } else {
                    TextButton(enabled = !running, onClick = { work("Opening original…") { message = LegalLibrary.open(hit.field("origin")) } }) { Text("Open original") }
                }
            }
        }
        for (doc in documents) {
            HorizontalDivider()
            Text("${doc.field("title")} · ${doc.field("passages")} passages · ${doc.field("embedded")} embedded · ${doc.field("needs_ocr")} pages need OCR")
            Row {
                TextButton(enabled = !running, onClick = { work("Opening original…") { message = LegalLibrary.open(doc.field("origin")) } }) { Text("Open") }
                for ((operation, title) in listOf("reindex" to "Reindex", "reembed" to "Re-embed")) {
                    TextButton(enabled = !running, onClick = {
                        work("$title…") {
                            val result = LegalLibrary.call(operation, target, mapOf("document" to JsonPrimitive(doc.field("id")), "force_ocr" to JsonPrimitive(forceOcr)), onProgress = { message = it })
                            refreshDocuments(target)
                            message = result.field("error").ifEmpty { result.field("status") }
                        }
                    }) { Text(title) }
                }
            }
        }
    }
}

@Composable
private fun LibraryRouteEditor(role: String, title: String) {
    var route by remember(role) { mutableStateOf(LegalLibrary.route(role)) }
    var saved by remember(role) { mutableStateOf("") }
    var editing by remember(role) { mutableStateOf(false) }
    var advanced by remember(role) { mutableStateOf(false) }
    var revealHeaders by remember(role) { mutableStateOf(false) }
    fun edit(value: LibraryRoute) { route = value; saved = "" }
    Text(title, style = MaterialTheme.typography.titleMedium)
    if (!editing) {
        val current = LegalLibrary.route(role)
        Text(if (current.configured) current.model.ifBlank { "Custom endpoint" } else if (role == "ocr") "Local Tesseract" else "Not configured")
        TextButton(onClick = { editing = true }) { Text("Edit $title") }
        return
    }
    OutlinedTextField(route.url, { edit(route.copy(url = it)) }, Modifier.fillMaxWidth(), label = { Text(if (route.exactUrl) "Complete API URL" else "Base URL, e.g. https://host/v1") }, singleLine = true)
    OutlinedTextField(route.model, { edit(route.copy(model = it)) }, Modifier.fillMaxWidth(), label = { Text("Model ID") }, singleLine = true)
    OutlinedTextField(route.key, { edit(route.copy(key = it)) }, Modifier.fillMaxWidth(), label = { Text("API key (optional for local)") },
        singleLine = true, visualTransformation = PasswordVisualTransformation())
    if (role == "embedding" || role == "rerank") Row {
        TextButton(onClick = { edit(route.copy(format = "")) }) { Text(if (route.format.isEmpty()) "[Compatible]" else "Compatible") }
        TextButton(onClick = { edit(route.copy(format = "nvidia")) }) { Text(if (route.format == "nvidia") "[NVIDIA]" else "NVIDIA") }
    }
    TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide request settings" else "Headers and request settings") }
    if (advanced) {
        Row { Checkbox(route.exactUrl, { edit(route.copy(exactUrl = it)) }); Text("Use exact URL. You can put {{model}} in the URL.") }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (method in listOf("POST", "PUT", "PATCH")) FilterChip(
                selected = route.method == method, onClick = { edit(route.copy(method = method)) }, label = { Text(method) },
            )
        }
        OutlinedTextField(route.headers, { edit(route.copy(headers = it)) }, Modifier.fillMaxWidth(),
            label = { Text("Headers: one Name: value per line") }, minLines = 2, maxLines = 5,
            visualTransformation = if (revealHeaders) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation())
        TextButton(onClick = { revealHeaders = !revealHeaders }) { Text(if (revealHeaders) "Hide header values" else "Show header values") }
        Text("Use {{key}} in a header value to insert the API key. A custom Authorization header replaces the default Bearer header.")
        OutlinedTextField(route.template, { edit(route.copy(template = it)) }, Modifier.fillMaxWidth(),
            label = { Text("Custom JSON body (optional)") }, minLines = 3, maxLines = 8)
        val variables = when (role) {
            "embedding" -> "{{input}}, {{input_type}}"
            "rerank" -> "{{query}}, {{passages}}, {{top_n}}"
            "cleanup" -> "{{text}}, {{prompt}}"
            else -> "{{prompt}}, {{image_base64}}, {{mime_type}}, {{image_url}}"
        }
        Text("Put variables in JSON strings: {{model}}, {{body}} (default body), $variables. Array and object values are inserted as JSON.")
        OutlinedTextField(route.responsePath, { edit(route.copy(responsePath = it)) }, Modifier.fillMaxWidth(),
            label = { Text("Response path (e.g. candidates.0.content.parts.0.text)") }, singleLine = true)
        Text(when (role) {
            "embedding" -> "Point to vectors or a list of items with an embedding field."
            "rerank" -> "Point to numeric scores in input order, or items with index and score fields."
            else -> "Point to the response string that contains the resulting text."
        })
    }
    Row {
        Button(onClick = {
            val candidate = route.copy(url = route.url.trim(), model = route.model.trim(), key = route.key.trim())
            saved = when {
                !candidate.configured -> "Enter a URL and model ID, or select Use exact URL."
                candidate.template.isNotBlank() && runCatching { Json.parseToJsonElement(candidate.template) }.isFailure -> "The request body must be valid JSON."
                candidate.headers.lineSequence().any { it.isNotBlank() && !it.substringBefore(':').trim().matches(Regex("[A-Za-z0-9!#%&'*+.^_`|~-]+")) || it.isNotBlank() && ':' !in it } -> "Use Name: value on each header line."
                else -> { LegalLibrary.saveRoute(role, candidate); "Saved." }
            }
        }) { Text("Save") }
        TextButton(onClick = { LegalLibrary.saveRoute(role, LibraryRoute()); route = LibraryRoute(); saved = "Cleared." }) { Text("Clear route") }
        TextButton(onClick = { route = LegalLibrary.route(role); editing = false }) { Text("Done") }
    }
    if (saved.isNotBlank()) Text(saved)
}
