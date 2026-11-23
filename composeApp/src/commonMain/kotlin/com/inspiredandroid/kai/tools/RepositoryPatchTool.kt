@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.inspiredandroid.kai.tools

import com.inspiredandroid.kai.SandboxController
import com.inspiredandroid.kai.network.tools.ParameterSchema
import com.inspiredandroid.kai.network.tools.Tool
import com.inspiredandroid.kai.network.tools.ToolSchema
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

internal object RepositoryPatchTool : Tool, KoinComponent {
    private val sandbox: SandboxController by inject()
    private val edits = Mutex()
    override val timeout = 80.seconds
    override val schema = ToolSchema(
        "repository_patch",
        "Apply a unified diff to an existing Git fork in the Linux sandbox. Automatically checkpoints the pre-edit tree and checks the patch before applying it. Does not push or compile.",
        mapOf("repository" to ParameterSchema("string", "Absolute path to the Git worktree root", true),
            "diff" to ParameterSchema("string", "A Git-compatible unified diff, with newlines preserved", true)),
    )

    override suspend fun execute(args: Map<String, Any>): Any = edits.withLock {
        val repo = args["repository"] as? String ?: return@withLock "Repository path required."
        val diff = args["diff"] as? String ?: return@withLock "Unified diff required."
        if (!repo.startsWith("/") || repo.any { it.isISOControl() }) return@withLock "Use an absolute repository path."
        if (!(diff.startsWith("diff --git ") || diff.startsWith("--- ")) ||
            "\n@@ " !in diff || diff.length > 500_000) return@withLock "Provide a textual unified diff of up to 500,000 characters."
        if (!sandbox.status.value.ready) return@withLock "Start the existing Linux sandbox."
        val id = Uuid.random().toString()
        val patch = "/root/.kai/patches/$id.diff"
        val session = "repo-patch-$id"
        val git = "git -C " + quote(repo)
        try {
            val checkpoint = sandbox.executeCommand(
                "$git rev-parse --is-inside-work-tree >/dev/null 2>&1 && " +
                    "n=\$($git log -1 --format=%s --grep='^wip: objective step [0-9][0-9]*$' | sed 's/.* //'); " +
                    "case \"\$n\" in ''|*[!0-9]*) n=0 ;; esac; " +
                    "$git add -A && $git -c core.hooksPath=/dev/null commit --allow-empty " +
                    "-m \"wip: objective step \$((n+1))\" && printf '\\nKAI_PATCH_CHECKPOINT ' && $git rev-parse HEAD",
                session,
            )
            val marker = checkpoint.lineSequence().lastOrNull { it.startsWith("KAI_PATCH_CHECKPOINT ") }
                ?: return@withLock "Checkpoint failed; no patch applied.\n${checkpoint.takeLast(1600)}"
            if (!sandbox.writeBinaryFile(patch, diff.trimEnd('\r', '\n').plus("\n").encodeToByteArray())) {
                return@withLock "Checkpoint created but the patch could not be staged."
            }
            val output = sandbox.executeCommand(
                "$git apply --check ${quote(patch)} && $git apply ${quote(patch)} && printf '\\nKAI_PATCH_APPLIED\\n' && $git diff --stat",
                session,
            )
            if (output.lineSequence().any { it.trim() == "KAI_PATCH_APPLIED" }) "$marker\n$output"
            else "$marker\nPatch did not apply.\n${output.takeLast(2800)}"
        } finally {
            withContext(NonCancellable) {
                sandbox.closeSession(session)
                withTimeoutOrNull(3000) { sandbox.deleteEntry(patch, recursive = false) }
            }
        }
    }

    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
}
