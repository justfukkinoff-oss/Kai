package com.inspiredandroid.kai.tools

import com.inspiredandroid.kai.data.currentConversationIdOrNull
import com.inspiredandroid.kai.data.ObjectiveStatus
import com.inspiredandroid.kai.data.ObjectiveStore
import com.inspiredandroid.kai.network.tools.ParameterSchema
import com.inspiredandroid.kai.network.tools.Tool
import com.inspiredandroid.kai.network.tools.ToolInfo
import com.inspiredandroid.kai.network.tools.ToolSchema
import kai.composeapp.generated.resources.Res
import kai.composeapp.generated.resources.tool_objective_set_status_description
import kai.composeapp.generated.resources.tool_objective_set_status_name
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Objective-mode progress reporting. The tool is bound to the conversation it runs in:
 * during a headless continuation sweep the conversation id resolves from the coroutine
 * context (same mechanism `execute_shell_command` uses for session routing), so the
 * agent's status updates land on the right objective even when the user is viewing a
 * different chat.
 */
object ObjectiveStatusTool : Tool, KoinComponent {
    private val objectiveStore: ObjectiveStore by inject(ObjectiveStore::class.java)

    override val schema = ToolSchema(
        name = "objective_set_status",
        description = "Report progress on the active objective (Objective mode). Update this after " +
            "completing meaningful work, and at the end of each run. status is one of: running " +
            "(work continues), done (objective fully achieved), failed (blocked, cannot proceed). " +
            "Summary should be a short factual report of what was achieved or what blocks.",
        parameters = mapOf(
            "status" to ParameterSchema("string", "running | done | failed", true),
            "summary" to ParameterSchema("string", "Short factual progress report or blocker", true),
        ),
    )

    override suspend fun execute(args: Map<String, Any>): Any {
        val status = args["status"]?.toString()?.trim()?.lowercase()
            ?: return mapOf("success" to false, "error" to "status is required")
        if (status !in ObjectiveStatus.ACTIVE && status != ObjectiveStatus.DONE) {
            return mapOf(
                "success" to false,
                "error" to "Unknown status '$status'. Use running, done, or failed.",
            )
        }
        val summary = args["summary"]?.toString() ?: ""

        val conversationId = currentConversationIdOrNull()
            ?: return mapOf("success" to false, "error" to "No objective conversation is bound to this run")

        val existing = objectiveStore.get(conversationId)
            ?: return mapOf("success" to false, "error" to "This conversation has no objective set")

        objectiveStore.upsert(conversationId, existing.copy(status = status, summary = summary))

        return mapOf(
            "success" to true,
            "status" to status,
            "conversation_id" to conversationId,
        )
    }
}

object ObjectiveTools {
    fun getObjectiveTools(): List<Tool> = listOf(ObjectiveStatusTool)

    fun getToolInfos(): List<ToolInfo> = listOf(
        ToolInfo(
            id = "objective_set_status",
            nameRes = Res.string.tool_objective_set_status_name,
            descriptionRes = Res.string.tool_objective_set_status_description,
            userToggleable = false,
        ),
    )
}
