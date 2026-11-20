package com.inspiredandroid.kai.data

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
)

/**
 * Persistence for Objective mode. Each objective conversation carries its state keyed by
 * conversation id in app settings as a JSON object. Pure JSON plumbing — no DI, no suspend,
 * so the prompt builder and the UI can both use it cheaply.
 */
class ObjectiveStore(private val appSettings: AppSettings) {

    fun getAll(): Map<String, ObjectiveState> = parse(appSettings.getObjectivesJson())

    fun get(conversationId: String): ObjectiveState? = getAll()[conversationId]

    fun upsert(conversationId: String, state: ObjectiveState) {
        val all = getAll()
        val updated = all + (conversationId to state)
        appSettings.setObjectivesJson(JSON.encodeToString(MapSerializer, updated))
    }

    fun remove(conversationId: String) {
        val updated = getAll() - conversationId
        if (updated.isEmpty()) {
            appSettings.setObjectivesJson("")
        } else {
            appSettings.setObjectivesJson(JSON.encodeToString(MapSerializer, updated))
        }
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
