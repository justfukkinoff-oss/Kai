# Memories

**Last verified:** 2026-11-22 (source and test review; Android runtime not yet verified)

Kai has two kinds of memory: automatic continuity scoped to one chat, and user-manageable memories that can be shared across chats. Shared memories can be reinforced or promoted into the soul/system prompt. The heartbeat surfaces candidates for promotion.

## Automatic chat continuity

The app saves excerpts of user requests, assistant replies, and completed tool returns to local app settings for the current conversation. The original request and recent entries are retained within bounded limits. When a request can't use the whole transcript, relevant saved excerpts omitted from the request are replayed with the prompt. This does not require a separate model call, a successful model tool call, or the shared-memory toggle. Deleting the conversation deletes its continuity. Entries are excerpts, not an exact transcript or a guarantee of provider compliance.

Scheduled Objective recovery uses its own per-task checkpoint instead of reading another chat or injecting the foreground conversation's continuity.

## Concepts

### Memory

A persistent key-value entry containing a descriptive key, content, category, hit count, and optional source. Memories survive app restarts and are shared across all conversations.

### Category

Each memory belongs to one of four categories:

- **General** — user preferences, facts, and important information
- **Learning** — successful approaches and patterns that worked well
- **Error** — error resolutions and known issues
- **Preference** — user corrections and explicit preferences

### Reinforcement

A mechanism for tracking how often a memory proves useful. Each reinforcement increments the hit count and updates the timestamp. Memories with 5 or more hits become promotion candidates.

### Promotion

The process of graduating a well-reinforced memory into the permanent soul/system prompt. The promoted text is appended to the soul, and the memory is removed from the memory store. Promotion is not limited to heartbeat runs — the AI can promote a learning from a normal chat as well. See the [heartbeat spec](heartbeat.md) for details on how promotion candidates are surfaced.

## Storage

- Memories are serialized as JSON in app settings
- Thread-safe access via mutex for concurrent operations
- If the stored JSON can no longer be decoded (e.g. after a schema change), the raw blob is backed up under a separate settings key before the next write, so the only copy is never overwritten. The backup is not part of settings export
- Memory feature is enabled by default
- The toggle controls automatic injection of shared memories. Stored memories and callable memory tools are retained.

## Memory Lifecycle

1. AI stores a memory using `memory_store` (general) or `memory_learn` (categorized)
2. If a memory with the same key exists, it is updated
3. On subsequent conversations, AI can reinforce memories that prove useful via `memory_reinforce`
4. AI can remove outdated memories via `memory_forget`
5. Memories with a hit count of 5 or more (a new memory starts at 1) become promotion candidates, surfaced during heartbeat checks

## System Prompt Injection

When memory is enabled, all memories are retrieved and grouped by category in the system prompt:

- General memories listed under "Your Memories"
- Preference memories under "User Preferences"
- Learning memories under "Learnings" with reinforcement count shown
- Error memories under "Known Issues & Resolutions"

For on-device (local) chat, the injected memory section is budgeted to a maximum of 2,000 characters. Entries are appended in category order until the budget is reached; if no entries fit, the header is dropped entirely. Remote chat has no such cap and includes all memories.

## Default Instructions

Built-in memory instructions (included for every chat variant when memory is enabled) guide the AI to:

- Proactively store important information (names, preferences, projects, goals) with `memory_store`
- Use `memory_forget` for outdated or incorrect information
- Avoid storing trivial or transient information

For remote models, an additional "Structured Learning" section explains `memory_learn` and reinforcement. A compatible on-device model can also call the registered memory tools.

## Settings UI

The memories section lives in Settings → Agent and contains:

- **Toggle** — enables or disables the memory feature with a switch
- **Description** — explains that memories are included in every message for context
- **Memory list** — shown only while memory is enabled; the five most recently updated memories are shown inline; each entry displays the key (bold) and content (max 3 lines, truncated with ellipsis)
- **Show all button** — appears when more than five memories exist; opens a modal bottom sheet listing every memory
- **Edit memory** — tapping any memory row (inline or inside the bottom sheet) opens an edit bottom sheet that lets the user modify the memory content. The key is shown but not editable. Saving updates the memory's content and timestamp
- **Delete button** — per-memory trash icon to remove individual memories; deletion is deferred with a snackbar "Undo" option (~4 seconds) before the memory is permanently removed

## AI Tools

| Tool | Purpose |
|---|---|
| `memory_store` | Store general key-value memories (General category) |
| `memory_learn` | Store categorized memories with optional source tracking (Learning, Error, or Preference). Rejects the General category at runtime and suggests using `memory_store` instead |
| `memory_forget` | Remove a memory by key |
| `memory_reinforce` | Increment a memory's hit count |
| `promote_learning` | Graduate a memory into the soul/system prompt and remove it from the store. Rides the scheduling switch (not the memory switch) and is callable from any conversation, not only heartbeat runs |

Tool-capable on-device models can receive the full registered tool set, including memory tools. Tool availability depends on the platform, installed/configured services, and permissions.

## Key Files

| File | Purpose |
|---|---|
| `composeApp/src/commonMain/.../data/MemoryStore.kt` | Core storage, retrieval, reinforcement, deletion, categorization |
| `composeApp/src/commonMain/.../data/SettingsJson.kt` | Shared settings-backed JSON persistence: decode-or-default, encode-and-write, locked read-modify-write |
| `composeApp/src/commonMain/.../data/AppSettings.kt` | Persistence layer for memories JSON and enable flag |
| `composeApp/src/commonMain/.../data/ConversationContinuity.kt` | Always-on, conversation-scoped recall |
| `composeApp/src/commonMain/.../data/RemoteDataRepository.kt` | Checkpoints live tool results and replays saved context when the request history is trimmed |
| `composeApp/src/commonMain/.../data/ChatSystemPromptBuilder.kt` | Category-grouped memory sections, local 2,000-char budget, Structured Learning section |
| `composeApp/src/commonMain/.../data/HeartbeatManager.kt` | Surfaces promotion candidates (5+ hits) in the heartbeat prompt |
| `composeApp/src/commonMain/.../tools/AgentToolSet.kt` | Registers memory tools; `promote_learning` depends on scheduling |
| `composeApp/src/commonMain/.../tools/CommonTools.kt` | AI tool definitions for memory_store, memory_learn, memory_forget, memory_reinforce |
| `composeApp/src/commonMain/.../tools/HeartbeatTools.kt` | promote_learning tool |
| `composeApp/src/commonMain/.../ui/settings/AgentSettings.kt` | Memory management UI (list, all-memories sheet, edit sheet) |
| `composeApp/src/commonMain/.../ui/settings/SettingsViewModel.kt` | Memory state management and user actions |
