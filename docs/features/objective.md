# Objective Mode

**Last verified:** 2026-11-22

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
| Progress sections | Completed steps, current blocker, next action, and key findings/citations |

Statuses `running` and `failed` are **active**: the scheduler keeps running them. `paused` and `done` are terminal for scheduling until the user resumes them.

### Conversation binding

Setting an objective on the current conversation marks it with type `objective` (title defaults to the goal). Continuation runs execute with that conversation id bound via `ConversationIdElement`, so shell sessions, memories, and the `objective_set_status` tool all land in the right conversation. Loading an objective conversation restores objective mode; starting a new chat clears the ambient objective flags.

### Headless runs

Each scheduled run starts a fresh model context with a dedicated system prompt, the user-supplied objective spec, and the structured checkpoint. It does not include chat history, ambient memories, active skills, or the open chat's mode. The run's report is stored in the objective conversation for the user, but is not replayed as chat history.

On Android and desktop the app also writes a per-conversation `objectives/<conversation-id>/OBJECTIVE_STATE.md` under its app files directory, outside the project checkout. The file mirrors the same checkpoint used for recovery; app settings remain authoritative if the mirror cannot be written. The file is plaintext app data, not a secure vault. Keep confidential exhibits in the matter library rather than pasting them into the checkpoint.

## Scheduling behaviour

The scheduler loop (`TaskScheduler`) calls `objectiveManager?.runDueObjectives { isLoadingCheck() }` every tick, alongside heartbeats:

- Runs are serialized with a mutex and skipped entirely while the user is actively chatting (`isLoadingCheck`)
- Active objectives use the configured Heartbeat interval. Failed runs wait at least ten minutes. The first run of a new objective is eligible on the next scheduler tick.
- Objectives use the configured Heartbeat model when selected. If that model was removed, the run fails instead of switching providers silently. If no model was set, Kai prefers the first configured remote entry, then the first on-device entry. The model must support the objective checkpoint tool.
- Runs are capped at twenty minutes. A timeout or exception leaves the prior progress intact and marks an active run for retry; a late report from an obsolete run cannot overwrite a replacement objective.

This keeps working while the app is backgrounded as long as the scheduler is alive — same model as heartbeats, not a foreground service.

## UI

- Tap **Objective** in the composer, type the goal in the normal message field, and send it as a chat message. There is no separate goal dialog.
- While objective mode is active on the current conversation, a banner below the heartbeat banner shows the status dot (pulsing when `running`/`failed`), the goal, the latest summary, and pause/resume/remove
- The transcript shows running tool names and returned tool activity. Reasoning is shown when supplied by the provider; it is not fabricated for providers that return none. Scheduled runs save their report, returned tool names, and available reasoning in the same conversation.
- Objective conversations appear in the history sheet with an **Objective** badge and the goal as title
- Pause and Remove cancel an in-flight scheduled continuation. Remove also clears the goal and un-marks the conversation type.

## Tool

| Tool | Description |
|---|---|
| `objective_set_status` | Reports lifecycle (`running` / `done` / `failed`), a summary, and optional structured progress fields. Updates land on the bound conversation. A model cannot use this tool to resume a paused objective. |

## Key Files

| File | Purpose |
|---|---|
| `composeApp/src/commonMain/.../data/ObjectiveStore.kt` | `ObjectiveState`, `ObjectiveStatus`, settings-JSON persistence |
| `composeApp/src/jvmShared/.../data/ObjectiveStateFile.kt` | Atomic checkpoint mirror on Android and desktop |
| `composeApp/src/commonMain/.../data/ObjectiveManager.kt` | Due-run evaluation, continuation prompts, headless `askWithTools` runs, backoff/timeouts |
| `composeApp/src/commonMain/.../tools/ObjectiveTools.kt` | `objective_set_status` tool |
| `composeApp/src/commonMain/.../data/ChatSystemPromptBuilder.kt` | `ActiveObjectiveContext` + `## Objective` prompt section |
| `composeApp/src/commonMain/.../data/TaskScheduler.kt` | Scheduler loop integration |
| `composeApp/src/commonMain/.../ui/chat/composables/ObjectiveBanner.kt` | Objective status and lifecycle controls |
| `composeApp/src/commonMain/.../ui/chat/ChatViewModel.kt` | Starts objectives through the composer and merges live activity into the current transcript |
| `composeApp/src/commonMain/.../ui/chat/composables/ToolMessage.kt` | Waiting and currently executing tool labels |
| `composeApp/src/commonTest/.../data/ObjectiveRecoveryTest.kt` | Regression tests for checkpoint isolation, retry cadence, and the Draft label |
