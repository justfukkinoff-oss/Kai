@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.inspiredandroid.kai.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.inspiredandroid.kai.tools.LegalLibrary
import com.inspiredandroid.kai.tools.LibraryRoute
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import io.github.vinceglb.filekit.size
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.uuid.Uuid

private fun JsonObject.field(key: String) = (get(key) as? JsonPrimitive)?.content.orEmpty()
private fun JsonObject.rows(key: String) = (get(key) as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()

@Composable
internal fun LegalLibrarySection() {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Legal Library ▾" else "Legal Library ▸") }
        if (expanded) LegalLibraryControls()
    }
}

@Composable
private fun LegalLibraryControls() {
    val coroutine = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    var active by remember { mutableStateOf<Job?>(null) }
    var running by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Start the Linux sandbox, then refresh the library.") }
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
    var routes by remember { mutableStateOf(false) }
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
    val picker = rememberFilePickerLauncher(type = FileKitType.File(extensions = listOf(
        "pdf", "docx", "pptx", "epub", "txt", "md", "csv", "json", "xml", "kt", "java", "py", "js", "ts", "rs", "go", "jpg", "jpeg", "png", "webp", "tif", "tiff",
    ))) { file ->
        if (file != null && target.isNotBlank()) work("Reading ${file.name}…") {
            if (file.size() > 80_000_000) {
                message = "Choose a file of at most 80 MB."
            } else {
                val result = LegalLibrary.call("ingest", target,
                    mapOf("doc_type" to JsonPrimitive(documentType.trim()), "court" to JsonPrimitive(court.trim()),
                        "docket" to JsonPrimitive(docket.trim()), "date" to JsonPrimitive(date.trim()), "photo" to JsonPrimitive(photo)),
                    file.name, file.readBytes(), { message = it })
                refreshDocuments(target)
                message = result.field("error").ifEmpty {
                    "${result.field("title")}: ${result.field("status")}. Pages needing OCR: ${result["needs_ocr"] ?: "not reported"}"
                }
            }
        }
    }

    Text(message, style = MaterialTheme.typography.bodyMedium)
    Row {
        TextButton(enabled = !running, onClick = { work("Installing sandbox packages…") { message = LegalLibrary.setup { message = it } } }) { Text("Set up sandbox") }
        TextButton(enabled = !running, onClick = { work("Refreshing…") { refreshMatters(); if (target.isNotBlank()) refreshDocuments(target) } }) { Text("Refresh") }
        if (running) TextButton(onClick = { active?.cancel() }) { Text("Cancel") }
    }
    Text("Collections", style = MaterialTheme.typography.titleSmall)
    Row {
        for ((key, title) in listOf("matter" to "Matter", "law" to "Public law", "code" to "Code")) {
            TextButton(enabled = !running, onClick = {
                collection = key
                documents = emptyList()
                hits = emptyList()
                pageText = ""
                val selected = if (key == "matter") matter else key
                if (selected.isNotBlank()) work("Loading…") { refreshDocuments(selected) }
            }) { Text(if (collection == key) "[$title]" else title) }
        }
    }
    if (collection == "matter") {
        Text("Bound to this chat: ${matters.firstOrNull { it.field("id") == matter }?.field("name") ?: if (matter.isEmpty()) "None" else matter}")
        for (item in matters) {
            val id = item.field("id")
            TextButton(enabled = !running && id != matter, onClick = {
                if (!LegalLibrary.bind(id)) message = "Open or create a chat first."
                else {
                    matter = id
                    hits = emptyList()
                    pageText = ""
                    work("Loading matter…") { refreshDocuments(id) }
                }
            }) { Text("Bind ${item.field("name")}") }
        }
        if (matter.isNotBlank()) TextButton(enabled = !running, onClick = {
            if (LegalLibrary.bind("")) { matter = ""; documents = emptyList(); hits = emptyList(); pageText = "" }
        }) { Text("Unbind this chat") }
        OutlinedTextField(matterName, { matterName = it }, Modifier.fillMaxWidth(), label = { Text("New matter name") }, singleLine = true)
        Button(enabled = matterName.isNotBlank() && !running, onClick = {
            work("Creating matter…") {
                val id = Uuid.random().toString()
                val response = LegalLibrary.call("matter_create", id, mapOf("name" to JsonPrimitive(matterName.trim())))
                if (response.field("error").isNotBlank()) message = response.field("error")
                else {
                    matterName = ""
                    refreshMatters()
                    if (LegalLibrary.bind(id)) {
                        matter = id
                        hits = emptyList()
                        pageText = ""
                        refreshDocuments(id)
                    } else message = "Matter created. Open a chat to bind it."
                }
            }
        }) { Text("Create matter") }
    }
    if (target.isNotBlank()) {
        TextButton(onClick = { metadata = !metadata }) { Text("Import metadata and OCR options") }
        if (metadata) {
            OutlinedTextField(documentType, { documentType = it }, Modifier.fillMaxWidth(), label = { Text("Document type, e.g. transcript") }, singleLine = true)
            OutlinedTextField(court, { court = it }, Modifier.fillMaxWidth(), label = { Text("Court") }, singleLine = true)
            OutlinedTextField(docket, { docket = it }, Modifier.fillMaxWidth(), label = { Text("Docket") }, singleLine = true)
            OutlinedTextField(date, { date = it }, Modifier.fillMaxWidth(), label = { Text("Date (YYYY-MM-DD)") }, singleLine = true)
            Row { Checkbox(photo, { photo = it }); Text("Describe photographs instead of transcribing them") }
        }
        Button(enabled = !running, onClick = { picker.launch() }) { Text("Import and index file") }
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
                            val result = LegalLibrary.call(operation, target, mapOf("document" to JsonPrimitive(doc.field("id"))), onProgress = { message = it })
                            refreshDocuments(target)
                            message = result.field("error").ifEmpty { result.field("status") }
                        }
                    }) { Text(title) }
                }
            }
        }
    }
    TextButton(onClick = { routes = !routes }) { Text("Independent model routes") }
    if (routes) {
        Text("A configured remote route receives text or images used for that task. Leave it blank to keep that task local or disabled.")
        for ((role, title) in LegalLibrary.roles) LibraryRouteEditor(role, title)
    }
    OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), label = { Text("CourtListener API token (fallback)") },
        singleLine = true, visualTransformation = PasswordVisualTransformation())
    TextButton(onClick = { LegalLibrary.saveCourtListenerToken(token); message = "CourtListener settings saved." }) { Text("Save CourtListener token") }
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

@Composable
private fun LibraryRouteEditor(role: String, title: String) {
    var route by remember(role) { mutableStateOf(LegalLibrary.route(role)) }
    var saved by remember(role) { mutableStateOf("") }
    fun edit(value: LibraryRoute) { route = value; saved = "" }
    Text(title, style = MaterialTheme.typography.titleSmall)
    OutlinedTextField(route.url, { edit(route.copy(url = it)) }, Modifier.fillMaxWidth(), label = { Text("API endpoint") }, singleLine = true)
    OutlinedTextField(route.model, { edit(route.copy(model = it)) }, Modifier.fillMaxWidth(), label = { Text("Model ID") }, singleLine = true)
    OutlinedTextField(route.key, { edit(route.copy(key = it)) }, Modifier.fillMaxWidth(), label = { Text("API key (optional for local)") },
        singleLine = true, visualTransformation = PasswordVisualTransformation())
    if (role == "embedding" || role == "rerank") Row {
        TextButton(onClick = { edit(route.copy(format = "")) }) { Text(if (route.format.isEmpty()) "[Compatible]" else "Compatible") }
        TextButton(onClick = { edit(route.copy(format = "nvidia")) }) { Text(if (route.format == "nvidia") "[NVIDIA]" else "NVIDIA") }
    }
    TextButton(onClick = {
        LegalLibrary.saveRoute(role, route.copy(url = route.url.trim(), model = route.model.trim(), key = route.key.trim()))
        saved = if (route.url.isBlank() || route.model.isBlank()) "Cleared" else "Saved"
    }) { Text("Save $title") }
    if (saved.isNotBlank()) Text(saved)
}
