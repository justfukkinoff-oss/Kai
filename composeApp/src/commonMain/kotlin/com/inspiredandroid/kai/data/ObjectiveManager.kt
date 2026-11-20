package com.inspiredandroid.kai.data

import com.inspiredandroid.kai.getBackgroundDispatcher
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.CoroutineContext

/**
 * Timing knobs for Objective mode continuation runs.
 */
object ObjectiveTiming {
    /** Skip a conversation whose run failed fewer than N ms ago (restart backoff). */
    const val FAILED_RETRY_DELAY_MS = 10 * 60_000L

    /** Re-visit any active objective at most this often. */
    const val MIN_INTERVAL_MS = 5 * 60_000L

    /** Upper bound for one headless continuation run. */
    const val RUN_TIMEOUT_MS = 20 * 60_000L
}

/**
 * Pure prompt for one Objective-mode continuation run. Only the harness (system prompt,
 * tools, sandbox) differs between runs — this message carries the goal, the tracked
 * status, and the previous report so every continuation can resume.
 */
fun buildObjectiveContinuationPrompt(state: ObjectiveState): String {
    val report = if (state.summary.isNotBlank()) {
        "Report from the previous run:\n${state.summary.trim()}\n\n"
    } else {
        ""
    }
    return "Continue executing the objective autonomously until it is fully achieved.\n\n" +
        "Objective: ${state.goal.trim()}\n" +
        "Tracked status: ${state.status} (attempt ${state.attempts + 1})\n\n" +
        report +
        "Work in the shared Linux workspace and use your tools. When you finish a piece of " +
        "progress, call objective_set_status with a short summary of what was achieved or what " +
        "still blocks. Call objective_set_status with status \"done\" only when the objective is " +
        "fully achieved, or \"failed\" when it is blocked and cannot proceed."
}

/**
 * Drives Objective-mode conversations: periodically picks every conversation whose
 * objective is neither paused nor done and runs one headless continuation through
 * `askWithTools`, bound to that conversation (tools route to its sandbox session and
 * results land in its history).
 *
 * Runs are serialized (one at a time), skipped while the user's foreground request is
 * in flight, and failed runs are restarted on later sweeps — [ObjectiveStatus.FAILED]
 * is an active status. Paused and done objectives are never executed.
 */
class ObjectiveManager(
    private val dataRepository: DataRepository,
    private val objectiveStore: ObjectiveStore,
    private val appSettings: AppSettings,
    private val enabled: Boolean = true,
    backgroundDispatcher: CoroutineContext = getBackgroundDispatcher(),
) {
    private val runMutex = Mutex()

    /** Set true while a continuation is executing so the UI can show it. */
    private val _runningConversationId = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val runningConversationId: kotlinx.coroutines.flow.StateFlow<String?> = _runningConversationId

    private val scope = CoroutineScope(SupervisorJob() + backgroundDispatcher + CoroutineName("ObjectiveManager"))

    @OptIn(ExperimentalTime::class)
    fun runDueObjectives(isBusy: () -> Boolean) {
        if (!enabled || !appSettings.isSchedulingEnabled()) return
        scope.launch {
            // Serialized: no concurrent API calls from objective sweeps.
            runMutex.withLock {
                val now = Clock.System.now().toEpochMilliseconds()
                val due = objectiveStore.getAll().filter { (_, s) ->
                    canRun(s, now)
                }
                for ((conversationId, state) in due) {
                    if (isBusy()) break
                    runContinuation(conversationId, state)
                }
            }
        }
    }

    @OptIn(ExperimentalTime::class)
    private fun canRun(state: ObjectiveState, nowMs: Long): Boolean =
        when (state.status) {
            ObjectiveStatus.RUNNING -> nowMs - state.lastRunAtMs >= ObjectiveTiming.MIN_INTERVAL_MS
            ObjectiveStatus.FAILED -> nowMs - state.lastRunAtMs >= ObjectiveTiming.FAILED_RETRY_DELAY_MS
            else -> false
        }

    @OptIn(ExperimentalTime::class)
    private suspend fun runContinuation(conversationId: String, state: ObjectiveState) {
        _runningConversationId.value = conversationId
        try {
            objectiveStore.upsert(
                conversationId,
                state.copy(attempts = state.attempts + 1, lastRunAtMs = Clock.System.now().toEpochMilliseconds()),
            )
            val prompt = buildObjectiveContinuationPrompt(
                objectiveStore.get(conversationId) ?: state,
            )
            withTimeout(ObjectiveTiming.RUN_TIMEOUT_MS) {
                val response = dataRepository.askWithTools(
                    prompt,
                    conversationIdOverride = conversationId,
                )
                if (response.isNotBlank()) {
                    dataRepository.addAssistantMessageTo(conversationId, response)
                }
            }
            // No status flip here: the agent reports through the objective_set_status tool.
            // A run that neither completes nor fails is still active and re-visited.
        } catch (e: TimeoutCancellationException) {
            markFailed(conversationId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            markFailed(conversationId)
        } finally {
            _runningConversationId.value = null
        }
    }

    private suspend fun markFailed(conversationId: String) {
        // Restart on the next sweep (with backoff).
        objectiveStore.get(conversationId)?.let {
            objectiveStore.upsert(conversationId, it.copy(status = ObjectiveStatus.FAILED))
        }
        dataRepository.addAssistantMessageTo(
            conversationId,
            "**Objective run failed** — I'll retry automatically.",
        )
    }
}
