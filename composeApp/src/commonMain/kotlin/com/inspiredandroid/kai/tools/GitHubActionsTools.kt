package com.inspiredandroid.kai.tools

import com.inspiredandroid.kai.data.AppSettings
import com.inspiredandroid.kai.httpClient
import com.inspiredandroid.kai.mcp.McpServerManager
import com.inspiredandroid.kai.network.tools.ParameterSchema
import com.inspiredandroid.kai.network.tools.Tool
import com.inspiredandroid.kai.network.tools.ToolSchema
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds

/**
 * GitHub Actions build pipeline. Builds are delegated to CI rather than run inside the
 * Linux workspace: these tools let the agent list dispatchable workflows, trigger a
 * dispatch, watch runs/jobs, read failure logs, and pull finished artifacts back into
 * the workspace [sink].
 *
 * Authentication: a dedicated GitHub token in settings, falling back to the
 * `Authorization` header of an enabled GitHub Copilot MCP server — one PAT covers both.
 */
object GitHubActionsTools {

    const val COPILOT_MCP_HOST = "api.githubcopilot.com"

    fun getGitHubActionsTools(
        artifactSink: suspend (fileName: String, bytes: ByteArray) -> Boolean,
    ): List<Tool> = listOf(
        GhListWorkflows,
        GhTriggerWorkflow,
        GhGetRunTool,
        GhGetJobLogsTool,
        GhListArtifactsTool,
        GhDownloadArtifactTool(artifactSink),
    )

    val client: HttpClient = httpClient {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
        }
    }

    val json = Json { ignoreUnknownKeys = true }

    /**
     * Token precedence: dedicated setting first, then an enabled Copilot MCP server's
     * Authorization header (so a single PAT can drive both surfaces).
     */
    fun resolveToken(appSettings: AppSettings, mcpServerManager: McpServerManager): String? {
        appSettings.getGithubToken().takeIf { it.isNotBlank() }?.let { return it }
        val server = mcpServerManager.getServersJson()
            ?.firstOrNull { it.isEnabled && it.url.contains(COPILOT_MCP_HOST) } ?: return null
        val auth = server.headers.entries
            .firstOrNull { it.key.equals(HttpHeaders.Authorization, ignoreCase = true) }
            ?.value ?: return null
        return auth.removePrefix("Bearer ").removePrefix("bearer ").trim().ifBlank { null }
    }

    fun fail(status: Int, body: String): Map<String, Any> = mapOf(
        "success" to false,
        "error" to "GitHub API $status: ${body.take(400)}",
    )

    suspend fun apiRequest(token: String, url: String): HttpResponse =
        client.get("https://api.github.com$url") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.Accept, "application/vnd.github+json")
            header("X-GitHub-Api-Version", "2022-11-28")
        }

    fun requireString(args: Map<String, Any>, key: String): String? =
        args[key]?.toString()?.trim()?.takeIf { it.isNotEmpty() }

    @Serializable
    private data class WorkflowDto(
        val id: Long = 0,
        val name: String = "",
        val path: String = "",
        @SerialName("state") val state: String = "",
    )

    @Serializable
    private data class WorkflowsDto(
        @SerialName("total_count") val totalCount: Int = 0,
        val workflows: List<WorkflowDto> = emptyList(),
    )

    private object GhListWorkflows : Tool {
        override val schema = ToolSchema(
            name = "gha_list_workflows",
            description = "List the GitHub Actions workflows of a repository (id, name, path, state). " +
                "The workflow id (or path) is what a dispatch trigger needs.",
            parameters = mapOf(
                "owner" to ParameterSchema("string", "Repository owner", true),
                "repo" to ParameterSchema("string", "Repository name", true),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            val token = GitHubActionsTools.tokenOrError() ?: return GitHubActionsTools.errorArg()
            val owner = GitHubActionsTools.requireString(args, "owner")
                ?: return mapOf("success" to false, "error" to "owner is required")
            val repo = GitHubActionsTools.requireString(args, "repo")
                ?: return mapOf("success" to false, "error" to "repo is required")
            return try {
                val response = GitHubActionsTools.apiRequest(token, "/repos/$owner/$repo/actions/workflows")
                val body = response.bodyAsText()
                if (!response.status.isSuccess()) return GitHubActionsTools.fail(response.status.value, body)
                val dto = GitHubActionsTools.json.decodeFromString(GitHubActionsTools.WorkflowsDto.serializer(), body)
                mapOf(
                    "success" to true,
                    "workflows" to dto.workflows.map {
                        mapOf("id" to it.id, "name" to it.name, "path" to it.path, "state" to it.state)
                    },
                )
            } catch (e: Exception) {
                mapOf("success" to false, "error" to "Failed to list workflows: ${e.message}")
            }
        }
    }

    private object GhTriggerWorkflow : Tool {
        override val schema = ToolSchema(
            name = "gha_trigger_workflow",
            description = "Trigger a GitHub Actions workflow with workflow_dispatch. Use this for " +
                "builds and packaging instead of compiling inside the workspace. Requires the " +
                "workflow to declare workflow_dispatch and the target ref to exist. Returns 204 on " +
                "success; then check the run via gha_get_run.",
            parameters = mapOf(
                "owner" to ParameterSchema("string", "Repository owner", true),
                "repo" to ParameterSchema("string", "Repository name", true),
                "workflow_id" to ParameterSchema("string", "Workflow id (number) or file path, e.g. release-apk.yml", true),
                "ref" to ParameterSchema("string", "Branch or tag to run against", true),
                "inputs" to ParameterSchema("object", "Optional workflow inputs as a flat object of strings", false),
            ),
        )

        @Suppress("UNCHECKED_CAST")
        override suspend fun execute(args: Map<String, Any>): Any {
            val token = GitHubActionsTools.tokenOrError() ?: return GitHubActionsTools.errorArg()
            val owner = GitHubActionsTools.requireString(args, "owner")
                ?: return mapOf("success" to false, "error" to "owner is required")
            val repo = GitHubActionsTools.requireString(args, "repo")
                ?: return mapOf("success" to false, "error" to "repo is required")
            val workflowId = GitHubActionsTools.requireString(args, "workflow_id")
                ?: return mapOf("success" to false, "error" to "workflow_id is required")
            val ref = GitHubActionsTools.requireString(args, "ref")
                ?: return mapOf("success" to false, "error" to "ref is required")
            val inputs = (args["inputs"] as? Map<String, Any>)?.mapValues { it.value.toString() } ?: emptyMap()
            return try {
                val response = GitHubActionsTools.client.post(
                    "https://api.github.com/repos/$owner/$repo/actions/workflows/$workflowId/dispatches",
                ) {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    header(HttpHeaders.Accept, "application/vnd.github+json")
                    header("X-GitHub-Api-Version", "2022-11-28")
                    setBody(mapOf("ref" to ref, "inputs" to inputs))
                }
                val body = response.bodyAsText()
                if (!response.status.isSuccess()) return GitHubActionsTools.fail(response.status.value, body)
                mapOf(
                    "success" to true,
                    "message" to "Dispatch accepted. Check the newest run for workflow '$workflowId' on ref '$ref' with gha_get_run.",
                )
            } catch (e: Exception) {
                mapOf("success" to false, "error" to "Failed to trigger workflow: ${e.message}")
            }
        }
    }

    @Serializable
    private data class JobDto(
        val id: Long = 0,
        val name: String = "",
        val status: String = "",
        val conclusion: String? = null,
        @SerialName("started_at") val startedAt: String? = null,
        @SerialName("completed_at") val completedAt: String? = null,
    )

    @Serializable
    private data class JobsDto(
        @SerialName("total_count") val totalCount: Int = 0,
        val jobs: List<JobDto> = emptyList(),
    )

    @Serializable
    private data class RunDto(
        val id: Long = 0,
        val name: String? = null,
        @SerialName("run_number") val runNumber: Int = 0,
        val status: String = "",
        val conclusion: String? = null,
        val event: String = "",
        val headBranch: String? = null,
        @SerialName("head_sha") val headSha: String? = null,
        @SerialName("html_url") val htmlUrl: String? = null,
        @SerialName("created_at") val createdAt: String? = null,
        @SerialName("updated_at") val updatedAt: String? = null,
    )

    private object GhGetRunTool : Tool {
        override val schema = ToolSchema(
            name = "gha_get_run",
            description = "Check a GitHub Actions run: status, conclusion, branch, commit, and its jobs " +
                "(id, name, status, conclusion). Poll this while a triggered build runs.",
            parameters = mapOf(
                "owner" to ParameterSchema("string", "Repository owner", true),
                "repo" to ParameterSchema("string", "Repository name", true),
                "run_id" to ParameterSchema("integer", "The workflow run id", true),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            val token = GitHubActionsTools.tokenOrError() ?: return GitHubActionsTools.errorArg()
            val owner = GitHubActionsTools.requireString(args, "owner")
                ?: return mapOf("success" to false, "error" to "owner is required")
            val repo = GitHubActionsTools.requireString(args, "repo")
                ?: return mapOf("success" to false, "error" to "repo is required")
            val runId = (args["run_id"] as? Number)?.toLong()
                ?: GitHubActionsTools.requireString(args, "run_id")?.toLongOrNull()
                ?: return mapOf("success" to false, "error" to "run_id is required")
            return try {
                val runResponse =
                    GitHubActionsTools.apiRequest(token, "/repos/$owner/$repo/actions/runs/$runId")
                val runBody = runResponse.bodyAsText()
                if (!runResponse.status.isSuccess()) return GitHubActionsTools.fail(runResponse.status.value, runBody)
                val run = GitHubActionsTools.json.decodeFromString(RunDto.serializer(), runBody)
                val jobsResponse =
                    GitHubActionsTools.apiRequest(token, "/repos/$owner/$repo/actions/runs/$runId/jobs")
                val jobs = if (jobsResponse.status.isSuccess()) {
                    GitHubActionsTools.json
                        .decodeFromString(JobsDto.serializer(), jobsResponse.bodyAsText())
                        .jobs
                } else {
                    emptyList()
                }
                mapOf(
                    "success" to true,
                    "run" to mapOf(
                        "id" to run.id,
                        "name" to run.name,
                        "run_number" to run.runNumber,
                        "status" to run.status,
                        "conclusion" to run.conclusion,
                        "event" to run.event,
                        "branch" to run.headBranch,
                        "commit" to run.headSha,
                        "url" to run.htmlUrl,
                        "created_at" to run.createdAt,
                        "updated_at" to run.updatedAt,
                    ),
                    "jobs" to jobs.map {
                        mapOf(
                            "id" to it.id,
                            "name" to it.name,
                            "status" to it.status,
                            "conclusion" to it.conclusion,
                        )
                    },
                )
            } catch (e: Exception) {
                mapOf("success" to false, "error" to "Failed to get run: ${e.message}")
            }
        }
    }

    private object GhGetJobLogsTool : Tool {
        override val schema = ToolSchema(
            name = "gha_get_job_logs",
            description = "Read the tail of a GitHub Actions job's logs (use gha_get_run to find job ids). " +
                "Use to analyze build or test failures.",
            parameters = mapOf(
                "owner" to ParameterSchema("string", "Repository owner", true),
                "repo" to ParameterSchema("string", "Repository name", true),
                "job_id" to ParameterSchema("integer", "The job id from gha_get_run", true),
                "tail_lines" to ParameterSchema("integer", "How many trailing lines to return (default 200)", false),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            val token = GitHubActionsTools.tokenOrError() ?: return GitHubActionsTools.errorArg()
            val owner = GitHubActionsTools.requireString(args, "owner")
                ?: return mapOf("success" to false, "error" to "owner is required")
            val repo = GitHubActionsTools.requireString(args, "repo")
                ?: return mapOf("success" to false, "error" to "repo is required")
            val jobId = (args["job_id"] as? Number)?.toLong()
                ?: GitHubActionsTools.requireString(args, "job_id")?.toLongOrNull()
                ?: return mapOf("success" to false, "error" to "job_id is required")
            val tailLines = ((args["tail_lines"] as? Number)?.toInt() ?: 200).coerceIn(10, 2000)
            return try {
                // The jobs/:id/logs endpoint 302s to the signed log archive. ktor follows
                // the redirect and rewrites Authorization, so this works with the PAT.
                val response =
                    GitHubActionsTools.apiRequest(token, "/repos/$owner/$repo/actions/jobs/$jobId/logs")
                if (!response.status.isSuccess()) {
                    return GitHubActionsTools.fail(response.status.value, response.bodyAsText())
                }
                val allLogs = response.bodyAsText().lines()
                val tail = allLogs.takeLast(tailLines)
                mapOf(
                    "success" to true,
                    "total_lines" to allLogs.size,
                    "logs" to tail,
                )
            } catch (e: Exception) {
                mapOf("success" to false, "error" to "Failed to get job logs: ${e.message}")
            }
        }
    }

    @Serializable
    private data class ArtifactDto(
        val id: Long = 0,
        val name: String = "",
        @SerialName("size_in_bytes") val sizeInBytes: Long = 0,
        val expired: Boolean = false,
    )

    @Serializable
    private data class ArtifactsDto(
        @SerialName("total_count") val totalCount: Int = 0,
        val artifacts: List<ArtifactDto> = emptyList(),
    )

    private object GhListArtifactsTool : Tool {
        override val schema = ToolSchema(
            name = "gha_list_artifacts",
            description = "List artifacts produced by a GitHub Actions run (id, name, size). " +
                "Use gha_download_artifact with an id to pull one into the workspace.",
            parameters = mapOf(
                "owner" to ParameterSchema("string", "Repository owner", true),
                "repo" to ParameterSchema("string", "Repository name", true),
                "run_id" to ParameterSchema("integer", "The workflow run id", true),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            val token = GitHubActionsTools.tokenOrError() ?: return GitHubActionsTools.errorArg()
            val owner = GitHubActionsTools.requireString(args, "owner")
                ?: return mapOf("success" to false, "error" to "owner is required")
            val repo = GitHubActionsTools.requireString(args, "repo")
                ?: return mapOf("success" to false, "error" to "repo is required")
            val runId = (args["run_id"] as? Number)?.toLong()
                ?: GitHubActionsTools.requireString(args, "run_id")?.toLongOrNull()
                ?: return mapOf("success" to false, "error" to "run_id is required")
            return try {
                val response =
                    GitHubActionsTools.apiRequest(token, "/repos/$owner/$repo/actions/runs/$runId/artifacts")
                val body = response.bodyAsText()
                if (!response.status.isSuccess()) return GitHubActionsTools.fail(response.status.value, body)
                val dto = GitHubActionsTools.json.decodeFromString(ArtifactsDto.serializer(), body)
                mapOf(
                    "success" to true,
                    "artifacts" to dto.artifacts.map {
                        mapOf(
                            "id" to it.id,
                            "name" to it.name,
                            "size_in_bytes" to it.sizeInBytes,
                            "expired" to it.expired,
                        )
                    },
                )
            } catch (e: Exception) {
                mapOf("success" to false, "error" to "Failed to list artifacts: ${e.message}")
            }
        }
    }

    private class GhDownloadArtifactTool(
        private val sink: suspend (fileName: String, bytes: ByteArray) -> Boolean,
    ) : Tool {
        override val timeout = 120.seconds

        override val schema = ToolSchema(
            name = "gha_download_artifact",
            description = "Download a GitHub Actions artifact (a zip) into the workspace at " +
                "/root/artifacts/<name>.zip where you can unpack and work with it. " +
                "Use gha_list_artifacts to find ids.",
            parameters = mapOf(
                "owner" to ParameterSchema("string", "Repository owner", true),
                "repo" to ParameterSchema("string", "Repository name", true),
                "artifact_id" to ParameterSchema("integer", "The artifact id from gha_list_artifacts", true),
                "artifact_name" to ParameterSchema("string", "Name used for the saved file", false),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            val token = GitHubActionsTools.tokenOrError() ?: return GitHubActionsTools.errorArg()
            val owner = GitHubActionsTools.requireString(args, "owner")
                ?: return mapOf("success" to false, "error" to "owner is required")
            val repo = GitHubActionsTools.requireString(args, "repo")
                ?: return mapOf("success" to false, "error" to "repo is required")
            val artifactId = (args["artifact_id"] as? Number)?.toLong()
                ?: GitHubActionsTools.requireString(args, "artifact_id")?.toLongOrNull()
                ?: return mapOf("success" to false, "error" to "artifact_id is required")
            val name = GitHubActionsTools.requireString(args, "artifact_name")
                ?: "artifact-$artifactId"
            val fileName = "${name.filter { it.isLetterOrDigit() || it in "._-".toSet() }}.zip"
            return try {
                val response = GitHubActionsTools.apiRequest(
                    token,
                    "/repos/$owner/$repo/actions/artifacts/$artifactId/zip",
                )
                if (!response.status.isSuccess()) {
                    return GitHubActionsTools.fail(response.status.value, response.bodyAsText())
                }
                val bytes = response.readBytes()
                val saved = sink(fileName, bytes)
                if (!saved) {
                    return mapOf(
                        "success" to false,
                        "error" to "Could not write into " + GitHubActionsTools.SINK_PATH + " (sandbox not installed?)",
                    )
                }
                mapOf(
                    "success" to true,
                    "path" to GitHubActionsTools.SINK_PATH + fileName,
                    "bytes" to bytes.size,
                )
            } catch (e: Exception) {
                mapOf("success" to false, "error" to "Failed to download artifact: ${e.message}")
            }
        }
    }

    const val SINK_PATH = "/root/artifacts/"

    // Helpers that tools call through the object: token resolution, errors, API wrapper.

    private val appSettings: AppSettings by lazy {
        org.koin.java.KoinJavaComponent.inject<AppSettings>(AppSettings::class.java).value
    }
    private val mcpServerManager: McpServerManager by lazy {
        org.koin.java.KoinJavaComponent.inject<McpServerManager>(McpServerManager::class.java).value
    }

    fun tokenOrError(): String? = resolveToken(appSettings, mcpServerManager)

    fun errorArg(): Map<String, Any> = mapOf(
        "success" to false,
        "error" to "No GitHub token configured. Add one in Settings > " +
            "Integrations > GitHub, or connect the GitHub Copilot MCP server with a PAT.",
    )

    fun McpServerManager.getServersJson() = this.getServers()
}
