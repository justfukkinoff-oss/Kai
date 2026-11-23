package com.inspiredandroid.kai.tools

import com.inspiredandroid.kai.data.currentConversationIdOrNull
import com.inspiredandroid.kai.data.currentObjectiveRun
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
    private val objectiveStore: ObjectiveStore by inject()

    override val schema = ToolSchema(
        name = "objective_set_status",
        description = "Report progress on the active objective (Objective mode). Update this after " +
            "completing meaningful work, and at the end of each run. status is one of: running " +
            "(work continues), done (objective fully achieved), failed (blocked, cannot proceed). " +
            "Summary should be a short factual report of what was achieved or what blocks.",
        parameters = mapOf(
            "status" to ParameterSchema("string", "running | done | failed", true),
            "summary" to ParameterSchema("string", "Short factual progress report or blocker", true),
            "completed_steps" to ParameterSchema("string", "Cumulative completed steps, compact", false),
            "current_blocker" to ParameterSchema("string", "Current blocker; empty if resolved", false),
            "next_action" to ParameterSchema("string", "Next concrete action; empty when done", false),
            "key_findings" to ParameterSchema("string", "Cumulative findings or source citations", false),
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
        val summary = (args["summary"] as? String)?.trim()?.take(2_000)
            ?.takeIf { it.isNotBlank() } ?: return mapOf("success" to false, "error" to "summary is required")

        val conversationId = currentConversationIdOrNull()
            ?: return mapOf("success" to false, "error" to "No objective conversation is bound to this run")

        val run = currentObjectiveRun()
        var accepted = false
        objectiveStore.update(conversationId) { existing ->
            if (existing == null || existing.status !in ObjectiveStatus.ACTIVE ||
                (run != null && run.startedAtMs != existing.lastRunAtMs)
            ) {
                existing
            } else {
                accepted = true
                fun field(name: String, fallback: String) = (args[name] as? String)?.trim()?.take(2_000) ?: fallback
                existing.copy(
                    status = status,
                    summary = summary,
                    completedSteps = field("completed_steps", existing.completedSteps),
                    currentBlocker = field("current_blocker", if (status == ObjectiveStatus.FAILED) summary else ""),
                    nextAction = field("next_action", if (status == ObjectiveStatus.DONE) "" else existing.nextAction),
                    keyFindings = field("key_findings", existing.keyFindings),
                )
            }
        }
        if (!accepted) return mapOf("success" to false, "error" to "No running objective is bound to this run")

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
            name = "Objective status",
            description = "Report objective-mode progress to Re-Kai",
            nameRes = Res.string.tool_objective_set_status_name,
            descriptionRes = Res.string.tool_objective_set_status_description,
            userToggleable = false,
        ),
    )
}
