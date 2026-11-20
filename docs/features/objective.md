# Objective Mode

**Last verified:** 2026-11-20

Objective mode lets the user state one final goal and have agents work toward it across multiple unattended runs — continuing or restarting after failures until the agent reports the objective complete.

## Concepts

### ObjectiveState

Persisted per objective conversation in app settings (JSON map keyed by conversation id, via `ObjectiveStore`). Fields:

| Field | Meaning |
|---|---|
| `goal` | The user's final objective, verbatim |
| `status` | `running`, `paused`, `done`, or `failed` |
| `summary` | Latest agent self-report (progress / blockers) |
| `lastRunAtMs` | When the last continuation run started (drives backoff + min interval) |
| `attempts` | Number of continuation runs performed |

Statuses `running` and `failed` are **active**: the scheduler keeps running them. `paused` and `done` are terminal for scheduling until the user resumes them.

### Conversation binding

Setting an objective on the current conversation marks it with type `objective` (title defaults to the goal). Continuation runs execute with that conversation id bound via `ConversationIdElement`, so shell sessions, memories, and the `objective_set_status` tool all land in the right conversation. Loading an objective conversation restores objective mode; starting a new chat clears the ambient objective flags.

### Headless runs

`ObjectiveManager.runContinuation()` sends the continuation prompt through `DataRepository.askWithTools(prompt, conversationIdOverride = <objective conversation id>)` — a fresh single-message history with the objective's system prompt (goal, status, attempts, last summary plus the instruction to advance the work and self-report via `objective_set_status`). The assistant's answer is also appended to the objective conversation so the user can read the run report in chat.

## Scheduling behaviour

The scheduler loop (`TaskScheduler`) calls `objectiveManager?.runDueObjectives { isLoadingCheck() }` every tick, alongside heartbeats:

- Runs are serialized with a mutex and skipped entirely while the user is actively chatting (`isLoadingCheck`)
- `running` objectives wait `MIN_INTERVAL_MS` (5 min) between runs
- `failed` objectives retry after `FAILED_RETRY_DELAY_MS` (10 min)
- Each run is capped at `RUN_TIMEOUT_MS` (20 min); timeout or exception marks the objective `failed` and bumps `attempts`, so the next tick retries after backoff

This keeps working while the app is backgrounded as long as the scheduler is alive — same model as heartbeats, not a foreground service.

## UI

- The composer chip row has an **Objective** chip (alongside Chat/Plan/Build); tapping opens the Set Objective dialog
- While objective mode is active on the current conversation, a banner below the heartbeat banner shows the status dot (pulsing when `running`/`failed`), the goal, the latest summary, and pause/resume/remove
- Objective conversations appear in the history sheet with an **Objective** badge and the goal as title
- The Remove action clears the goal entirely and un-marks the conversation type

## Tool

| Tool | Description |
|---|---|
| `objective_set_status` | The agent calls this during an objective run to report lifecycle (`running` / `done` / `failed`) plus a summary. The store entry is keyed to the bound conversation id |

## Key Files

| File | Purpose |
|---|---|
| `composeApp/src/commonMain/.../data/ObjectiveStore.kt` | `ObjectiveState`, `ObjectiveStatus`, settings-JSON persistence |
| `composeApp/src/commonMain/.../data/ObjectiveManager.kt` | Due-run evaluation, continuation prompts, headless `askWithTools` runs, backoff/timeouts |
| `composeApp/src/commonMain/.../tools/ObjectiveTools.kt` | `objective_set_status` tool |
| `composeApp/src/commonMain/.../data/ChatSystemPromptBuilder.kt` | `ActiveObjectiveContext` + `## Objective` prompt section |
| `composeApp/src/commonMain/.../data/TaskScheduler.kt` | Scheduler loop integration |
| `composeApp/src/commonMain/.../ui/chat/composables/ObjectiveBanner.kt` | Objective banner + Set Objective dialog |
