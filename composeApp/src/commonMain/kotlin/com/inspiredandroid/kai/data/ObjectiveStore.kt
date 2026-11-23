package com.inspiredandroid.kai.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Lifecycle status of an objective conversation. */
object ObjectiveStatus {
    const val RUNNING = "running"
    const val PAUSED = "paused"
    const val DONE = "done"
    const val FAILED = "failed"

    val ACTIVE = setOf(RUNNING, FAILED)
}

@Serializable
data class ObjectiveState(
    val goal: String,
    /** One of [ObjectiveStatus]. String, not enum, so persisted JSON stays migration-proof. */
    val status: String = ObjectiveStatus.RUNNING,
    /** Last agent self-report (what has been achieved / what blocks). */
    val summary: String = "",
    val lastRunAtMs: Long = 0L,
    val attempts: Int = 0,
    val completedSteps: String = "",
    val currentBlocker: String = "",
    val nextAction: String = "",
    val keyFindings: String = "",
    val userDetails: String = "",
    val activityJournal: String = "",
)

internal fun ObjectiveState.toScratchpad(): String = listOf(
    "Goal" to goal,
    "Completed Steps" to completedSteps.ifBlank { if (status == ObjectiveStatus.FAILED) "" else summary },
    "Current Blocker" to currentBlocker.ifBlank { if (status == ObjectiveStatus.FAILED) summary else "" },
    "Next Action" to nextAction,
    "Key Findings/Citations" to listOf(keyFindings, activityJournal).filter { it.isNotBlank() }.joinToString("\n"),
).joinToString("\n\n", postfix = "\n") { (label, text) ->
    "[$label]\n" + text.take(if (label == "Goal") text.length else 2_000)
}

internal fun ObjectiveState.recordActivity(value: String): ObjectiveState = copy(
    activityJournal = listOf(activityJournal, value.take(600)).filter { it.isNotBlank() }
        .joinToString("\n").takeLast(1_000),
)

internal expect fun writeObjectiveStateFile(conversationId: String, markdown: String?)

/**
 * Persistence for Objective mode. Each objective conversation carries its state keyed by
 * conversation id in app settings as a JSON object. Pure JSON plumbing — no DI, no suspend,
 * so the prompt builder and the UI can both use it cheaply.
 */
class ObjectiveStore(
    private val appSettings: AppSettings,
    private val writeStateFile: (String, String?) -> Unit = ::writeObjectiveStateFile,
) {
    private val mutex = Mutex()

    fun getAll(): Map<String, ObjectiveState> = parse(appSettings.getObjectivesJson())

    fun get(conversationId: String): ObjectiveState? = getAll()[conversationId]

    suspend fun update(conversationId: String, transform: (ObjectiveState?) -> ObjectiveState?): ObjectiveState? =
        mutex.withLock {
            val all = getAll()
            val previous = all[conversationId]
            val next = transform(previous)
            if (next != previous) {
                val updated = if (next == null) all - conversationId else all + (conversationId to next)
                appSettings.setObjectivesJson(if (updated.isEmpty()) "" else JSON.encodeToString(MapSerializer, updated))
                // Settings remain the scheduler's source of truth. A filesystem failure
                // must not undo a progress checkpoint or replay a successful tool action.
                writeStateFile(conversationId, next?.toScratchpad())
            }
            next
        }

    private fun parse(raw: String): Map<String, ObjectiveState> {
        if (raw.isBlank()) return emptyMap()
        return try {
            JSON.decodeFromString(MapSerializer, raw)
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private companion object {
        private val JSON = Json { ignoreUnknownKeys = true }
        private val MapSerializer =
            MapSerializer(
                String.serializer(),
                ObjectiveState.serializer(),
            )
    }
}
