@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.inspiredandroid.kai.tools

import com.inspiredandroid.kai.SandboxController
import com.inspiredandroid.kai.network.tools.ParameterSchema
import com.inspiredandroid.kai.network.tools.Tool
import com.inspiredandroid.kai.network.tools.ToolInfo
import com.inspiredandroid.kai.network.tools.ToolSchema
import kai.composeapp.generated.resources.Res
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

@Serializable
internal data class CitationReview(
    val issues: List<String>,
    val sources: Map<String, String?> = emptyMap(),
    val citations: Int = 0,
) {
    fun message(): String = if (issues.isEmpty()) {
        if (citations == 0) "Citation check: no recognizable citations or attributed quotes found."
        else "Citation check: no unresolved items detected in the checks performed."
    } else {
        val visible = issues.take(30).joinToString("\n") { issue ->
            // Cite display text is untrusted: keep it text and only add vetted source URLs.
            val source = sources.entries.find { issue.startsWith("${it.key}:") }?.value
                ?.takeIf { it.matches(Regex("https://www\\.courtlistener\\.com/opinion/[0-9]+/(?:[A-Za-z0-9_-]+/)?")) }
            val shown = issue.replace("[", "\\[").replace("]", "\\]").replace('\n', ' ').take(300)
            "- $shown" + if (source == null) "" else " ([opinion]($source))"
        }
        "Citation check: items to review\n$visible" +
            if (issues.size > 30) "\n- ${issues.size - 30} additional items in the check result." else ""
    }
}

object CitationCheckTool : Tool, KoinComponent {
    private val sandbox: SandboxController by inject()
    private val json = Json { ignoreUnknownKeys = true }
    const val ID = "cite_check"
    val toolInfo = ToolInfo(id = ID, name = "Check citations", description = "Report unresolved citations, quotes and treatment")

    override val schema = ToolSchema(
        name = ID,
        description = "Check a legal draft with local eyecite and public-case lookups. Returns notes only; never restricts the draft.",
        parameters = mapOf("draft" to ParameterSchema("string", "Legal draft text to check", true)),
    )
    override val timeout = 55.seconds

    override suspend fun execute(args: Map<String, Any>): Any {
        val text = args["draft"] as? String ?: return mapOf("error" to "draft is required")
        val result = review(text, sandbox)
        return mapOf("issues" to result.issues, "sources" to result.sources, "citations" to result.citations)
    }

    internal suspend fun review(draft: String, controller: SandboxController): CitationReview {
        fun issue(text: String) = CitationReview(listOf(text))
        if (draft.isBlank()) return issue("No draft text to review.")
        if (!controller.status.value.ready) return issue("Citation check unavailable: Linux sandbox is not ready.")
        val text = draft.encodeToByteArray()
        if (text.size > 512_000) return issue("Citation check did not run: draft exceeds 512 KB.")
        val id = Uuid.random().toString()
        val directory = "/root/.kai/cite-check/$id"
        val session = "cite-check-$id"
        return try {
            withTimeoutOrNull(40.seconds) {
                val script = Res.readBytes("files/legal/cite_check.py")
                if (!controller.writeBinaryFile("$directory/check.py", script) ||
                    !controller.writeBinaryFile("$directory/draft.txt", text)
                ) return@withTimeoutOrNull issue("Citation check unavailable: could not stage the draft locally.")
                val output = controller.executeCommand(
                    "python3 '$directory/check.py' '$directory/draft.txt'",
                    session,
                )
                val line = output.lineSequence().lastOrNull { it.startsWith("KAI_CITE_CHECK_RESULT ") }
                    ?: return@withTimeoutOrNull issue("Citation check unavailable: sandbox checker did not return a result.")
                json.decodeFromString<CitationReview>(line.substringAfter(' '))
            } ?: issue("Citation check timed out; the draft was not verified.")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            issue("Citation check unavailable; the draft was not verified.")
        } finally {
            withContext(NonCancellable) {
                try {
                    withTimeoutOrNull(3.seconds) {
                        controller.executeCommand("rm -f '$directory/draft.txt' '$directory/check.py'; rmdir '$directory'", session)
                    }
                } finally {
                    controller.closeSession(session)
                }
            }
        }
    }
}
