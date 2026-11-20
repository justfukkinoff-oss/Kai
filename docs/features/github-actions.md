# GitHub Actions Build Tools

**Last verified:** 2026-11-20

Re-Kai deliberately does **not** build Android artifacts inside the proot workspace. Instead the agent delegates heavy work to GitHub Actions through a set of built-in tools, then pulls results back into `/root/artifacts/`.

## Concepts

### Delegation flow

1. The agent (usually in **Build** mode or via the `apk-rework` skill) prepares sources/patches in the sandbox workspace
2. It commits/pushes them with the GitHub tools from the Copilot MCP server, or a plain `git push`
3. It calls `gha_trigger_workflow` to start the repository's dispatchable build workflow
4. `gha_get_run` / `gha_get_job_logs` follow progress; on failure the agent tails logs and iterates
5. `gha_list_artifacts` + `gha_download_artifact` fetch build outputs (e.g. rebuilt APKs) into the workspace for inspection, patching, and signing

### Authentication

API calls go to `https://api.github.com` with `Authorization: Bearer <token>` plus `X-GitHub-Api-Version`. The token resolves in this order:

1. Settings → Integrations → GitHub (`AppSettings.getGithubToken()`) — a user-provided fine-grained PAT (Actions read/write + Contents read is sufficient)
2. The `Authorization: Bearer …` header of the enabled GitHub Copilot MCP server (`https://api.githubcopilot.com/mcp/x/all`)

When neither is available the tools return a structured error instead of a failed HTTP call.

### Artifact sink

Downloads are written through a platform sink so the feature needs no sandbox on desktop:

- **Android** — files land in the Linux sandbox at `/root/artifacts/<name>.zip` via `SandboxController.writeBinaryFile`
- **Desktop** — files land in the app files directory under `artifacts/`

## Tools

All are registered by the platform layer (`Platform.android.kt` / `Platform.jvm.kt`) when scheduling is enabled, and are not individually toggleable.

| Tool | Parameters | Description |
|---|---|---|
| `gha_list_workflows` | `owner`, `repo` | List the repository's workflows (id, name, dispatchable flag) |
| `gha_trigger_workflow` | `owner`, `repo`, `workflow_id`, `ref`, optional `inputs` map | Create a `workflow_dispatch` event; returns 204 on success |
| `gha_get_run` | `owner`, `repo`, `run_id` | Run status/conclusion plus its jobs (id, name, status, conclusion) |
| `gha_get_job_logs` | `owner`, `repo`, `job_id`, `tail_lines` | Tail of the job's log text for failure analysis |
| `gha_list_artifacts` | `owner`, `repo`, `run_id` | Artifacts of a run (id, name, size, expiry) |
| `gha_download_artifact` | `owner`, `repo`, `artifact_id`, `artifact_name` | Streams the artifact zip to the sink and reports the path |

## Bundled workflow

The repo ships `.github/workflows/debug-apk.yml` — a `workflow_dispatch` build that assembles the FOSS debug APK with the default debug/test key and `-Prekai.arm64Only=true`, so the tools above can trigger and download a single arm64-v8a APK (artifact `Re-Kai-debug-arm64`). Passing the property enables an ABI split on `androidApp` that emits only `arm64-v8a` and no universal APK; local builds without the property are unchanged.

## Key Files

| File | Purpose |
|---|---|
| `composeApp/src/commonMain/.../tools/GitHubActionsTools.kt` | Tool schemas, GitHub REST calls, token resolution, DTOs |
| `composeApp/src/androidMain/kotlin/.../Platform.android.kt` | Android registration with sandbox sink |
| `composeApp/src/desktopMain/kotlin/.../Platform.jvm.kt` | Desktop registration with filesystem sink |
| `composeApp/src/commonMain/kotlin/com/inspiredandroid/kai/data/AppSettings.kt` | `github_token` setting |
