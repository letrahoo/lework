import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { z } from 'zod'
import {
  PROTOCOL_VERSION,
  AgentEvent,
  HubInfo,
  SlaveInfo,
  EngineInfo,
  ProviderInfo,
  ProviderConfigInput,
  ProfileInfo,
  Workspace,
  SessionWorktreeInfo,
  SubagentSummary,
  SessionMeta,
  ArtifactKind,
  ArtifactSource,
  ArtifactInfo,
  ArtifactContentResult,
  SearchRoleFilter,
  SessionSearchMatch,
  SessionSearchHit,
  SessionSearchResult,
  UsageTotals,
  UsageSummaryResult,
  UsageQuotaResult,
  SessionUsageResult,
  ProxyUsageResult,
  RpcRequest,
  RpcResponse,
  RpcNotification,
  Methods,
  Notifications,
} from '../src/index.ts'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const pkgRoot = path.resolve(__dirname, '..')
const repoRoot = path.resolve(pkgRoot, '..')

const schemaDir = path.join(pkgRoot, 'schema')
const fixturesDir = path.join(pkgRoot, 'fixtures')
fs.mkdirSync(schemaDir, { recursive: true })
fs.mkdirSync(fixturesDir, { recursive: true })

// 1. JSON Schema bundle
const bundle = {
  $schema: 'https://json-schema.org/draft/2020-12/schema',
  protocolVersion: PROTOCOL_VERSION,
  definitions: {
    HubInfo: z.toJSONSchema(HubInfo),
    SlaveInfo: z.toJSONSchema(SlaveInfo),
    EngineInfo: z.toJSONSchema(EngineInfo),
    ProviderInfo: z.toJSONSchema(ProviderInfo),
    ProviderConfigInput: z.toJSONSchema(ProviderConfigInput),
    ProfileInfo: z.toJSONSchema(ProfileInfo),
    Workspace: z.toJSONSchema(Workspace),
    SessionWorktreeInfo: z.toJSONSchema(SessionWorktreeInfo),
    SubagentSummary: z.toJSONSchema(SubagentSummary),
    SessionMeta: z.toJSONSchema(SessionMeta),
    ArtifactKind: z.toJSONSchema(ArtifactKind),
    ArtifactSource: z.toJSONSchema(ArtifactSource),
    ArtifactInfo: z.toJSONSchema(ArtifactInfo),
    ArtifactContentResult: z.toJSONSchema(ArtifactContentResult),
    SearchRoleFilter: z.toJSONSchema(SearchRoleFilter),
    SessionSearchMatch: z.toJSONSchema(SessionSearchMatch),
    SessionSearchHit: z.toJSONSchema(SessionSearchHit),
    SessionSearchResult: z.toJSONSchema(SessionSearchResult),
    UsageTotals: z.toJSONSchema(UsageTotals),
    UsageSummaryResult: z.toJSONSchema(UsageSummaryResult),
    UsageQuotaResult: z.toJSONSchema(UsageQuotaResult),
    SessionUsageResult: z.toJSONSchema(SessionUsageResult),
    ProxyUsageResult: z.toJSONSchema(ProxyUsageResult),
    AgentEvent: z.toJSONSchema(AgentEvent),
    RpcRequest: z.toJSONSchema(RpcRequest),
    RpcResponse: z.toJSONSchema(RpcResponse),
    RpcNotification: z.toJSONSchema(RpcNotification),
    methods: Object.fromEntries(
      Object.entries(Methods).map(([k, v]) => [
        k,
        {
          params: z.toJSONSchema(v.params),
          result: z.toJSONSchema(v.result),
        },
      ]),
    ),
    notifications: Object.fromEntries(
      Object.entries(Notifications).map(([k, v]) => [k, z.toJSONSchema(v)]),
    ),
  },
}

const schemaPath = path.join(schemaDir, 'protocol.schema.json')
fs.writeFileSync(schemaPath, JSON.stringify(bundle, null, 2) + '\n', 'utf8')

// 2. Conformance fixtures (shared by TS & Kotlin tests)
const sampleEvents: z.infer<typeof AgentEvent>[] = [
  { seq: 0, sessionId: 's_1', ts: 1700000000000, type: 'session.state', state: 'running' },
  { seq: 1, sessionId: 's_1', ts: 1700000000100, type: 'message.user', messageId: 'm_u1', text: 'Run git status' },
  { seq: 2, sessionId: 's_1', ts: 1700000000200, type: 'reasoning.delta', messageId: 'm_r1', text: 'Checking repo state...' },
  { seq: 3, sessionId: 's_1', ts: 1700000000300, type: 'tool.call', toolCallId: 'tc_1', title: 'Bash: git status', kind: 'execute', status: 'pending', input: 'git status' },
  {
    seq: 4,
    sessionId: 's_1',
    ts: 1700000000400,
    type: 'permission.requested',
    requestId: 'perm_1',
    toolCallId: 'tc_1',
    title: 'Allow running `git status`?',
    options: [
      { id: 'allow', name: 'Allow once', kind: 'allow_once' },
      { id: 'reject', name: 'Reject', kind: 'reject_once' },
    ],
  },
  {
    seq: 5,
    sessionId: 's_1',
    ts: 1700000000500,
    type: 'permission.resolved',
    requestId: 'perm_1',
    optionId: 'allow',
    outcome: 'selected',
  },
  {
    seq: 6,
    sessionId: 's_1',
    ts: 1700000000600,
    type: 'tool.update',
    toolCallId: 'tc_1',
    status: 'completed',
    output: 'On branch main\nnothing to commit',
  },
  { seq: 7, sessionId: 's_1', ts: 1700000000700, type: 'message.assistant.delta', messageId: 'm_a1', text: 'Working tree is clean.' },
  { seq: 8, sessionId: 's_1', ts: 1700000000800, type: 'usage', inputTokens: 120, outputTokens: 24 },
  { seq: 9, sessionId: 's_1', ts: 1700000000900, type: 'turn.done', turnId: 't_1', stopReason: 'end_turn' },
]

for (const ev of sampleEvents) {
  AgentEvent.parse(ev)
}

const fixturesPath = path.join(fixturesDir, 'events.json')
fs.writeFileSync(fixturesPath, JSON.stringify(sampleEvents, null, 2) + '\n', 'utf8')

// 3. Generate Kotlin @Serializable protocol models
const ktDir = path.join(repoRoot, 'app/shared/src/commonMain/kotlin/dev/lework/protocol')
fs.mkdirSync(ktDir, { recursive: true })

const kotlinCode = `// AUTO-GENERATED by protocol-schema/scripts/gen-json-schema.ts — DO NOT EDIT MANUALLY.
package dev.lework.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

const val PROTOCOL_VERSION: Int = ${PROTOCOL_VERSION}

object ErrorCode {
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL = -32603
    const val UNAUTHORIZED = -32001
    const val NOT_FOUND = -32002
    const val CONFLICT = -32003
    const val ENGINE_UNAVAILABLE = -32010
    const val ENGINE_ERROR = -32011
    const val PATH_NOT_ALLOWED = -32020
    const val VERSION_MISMATCH = -32030
}

@Serializable
data class RpcErrorObject(
    val code: Int,
    val message: String,
    val data: JsonElement? = null,
)

@Serializable
enum class HubRole {
    @SerialName("master") MASTER,
    @SerialName("slave") SLAVE,
    @SerialName("standalone") STANDALONE,
}

@Serializable
enum class PlatformType {
    @SerialName("darwin") DARWIN,
    @SerialName("linux") LINUX,
    @SerialName("win32") WIN32,
    @SerialName("other") OTHER,
}

@Serializable
data class HubInfo(
    val id: String,
    val name: String,
    val role: HubRole,
    val platform: PlatformType,
    val version: String,
)

@Serializable
data class SlaveInfo(
    val id: String,
    val name: String,
    val platform: PlatformType,
    val online: Boolean,
    val engines: List<String>,
    val lastSeenAt: Long,
)

@Serializable
enum class ProviderType {
    @SerialName("subscription") SUBSCRIPTION,
    @SerialName("endpoint") ENDPOINT,
}

@Serializable
enum class ProviderAdapterKind {
    @SerialName("cpa") CPA,
    @SerialName("generic") GENERIC,
}

@Serializable
enum class WireApi {
    @SerialName("openai-chat") OPENAI_CHAT,
    @SerialName("openai-responses") OPENAI_RESPONSES,
    @SerialName("anthropic-messages") ANTHROPIC_MESSAGES,
    @SerialName("gemini") GEMINI,
}

@Serializable
data class ProviderInfo(
    val id: String,
    val name: String,
    val type: ProviderType,
    val adapter: ProviderAdapterKind? = null,
    val baseUrl: String? = null,
    val wireApi: WireApi? = null,
    val wireApis: List<WireApi> = emptyList(),
    val enabledEngines: List<String> = emptyList(),
    val enabled: Boolean = true,
    val builtIn: Boolean = false,
    val hasApiKey: Boolean = false,
    val apiKeyEnv: String? = null,
    val manualModels: List<String> = emptyList(),
    val selectedModels: List<String> = emptyList(),
    val healthy: Boolean,
    val authStatus: String? = null,
    val latencyMs: Int? = null,
    val error: String? = null,
    val models: List<String> = emptyList(),
)

@Serializable
data class ProviderConfigInput(
    val id: String? = null,
    val name: String,
    val adapter: ProviderAdapterKind? = null,
    val baseUrl: String,
    val apiKey: String? = null,
    val clearApiKey: Boolean? = null,
    val apiKeyEnv: String? = null,
    val wireApis: List<WireApi>? = null,
    val enabledEngines: List<String>? = null,
    val enabled: Boolean? = null,
    val manualModels: List<String>? = null,
    val selectedModels: List<String>? = null,
)

@Serializable
data class ProviderListResult(
    val providers: List<ProviderInfo> = emptyList(),
    val engines: List<EngineInfo> = emptyList(),
)

@Serializable
data class ProviderUpsertParams(
    val provider: ProviderConfigInput,
)

@Serializable
data class ProviderUpsertResult(
    val provider: ProviderInfo,
    val providers: List<ProviderInfo> = emptyList(),
    val engines: List<EngineInfo> = emptyList(),
)

@Serializable
data class ProviderRemoveParams(
    val providerId: String,
)

@Serializable
data class ProviderProbeParams(
    val providerId: String? = null,
    val provider: ProviderConfigInput? = null,
)

@Serializable
data class ProviderProbeResult(
    val provider: ProviderInfo? = null,
    val providers: List<ProviderInfo> = emptyList(),
    val engines: List<EngineInfo> = emptyList(),
)

@Serializable
enum class SessionMode {
    @SerialName("default") DEFAULT,
    @SerialName("acceptEdits") ACCEPT_EDITS,
    @SerialName("plan") PLAN,
    @SerialName("auto") AUTO,
    @SerialName("bypassPermissions") BYPASS_PERMISSIONS,
}

@Serializable
enum class ReasoningEffort {
    @SerialName("low") LOW,
    @SerialName("medium") MEDIUM,
    @SerialName("high") HIGH,
    @SerialName("xhigh") XHIGH,
    @SerialName("max") MAX,
}

@Serializable
data class EngineModelSpec(
    val id: String,
    val name: String,
    val providerId: String? = null,
    val providerName: String? = null,
    val efforts: List<ReasoningEffort> = emptyList(),
    val defaultEffort: ReasoningEffort? = null,
)

@Serializable
enum class SessionSource {
    @SerialName("hub") HUB,
    @SerialName("host") HOST,
}

@Serializable
data class ProfileInfo(
    val id: String,
    val name: String,
    val slaveId: String? = null,
    val engineId: String,
    val providerId: String? = null,
    val modelId: String? = null,
    val mode: SessionMode,
    val defaultCwd: String? = null,
)

@Serializable
enum class EngineKind {
    @SerialName("cli") CLI,
    @SerialName("service") SERVICE,
    @SerialName("server") SERVER,
    @SerialName("embedded") EMBEDDED,
}

@Serializable
data class EngineStatus(
    val installed: Boolean,
    val ready: Boolean,
    val version: String? = null,
    val detail: String? = null,
    val authStatus: String? = null,
)

@Serializable
data class EngineCapabilities(
    val permissions: Boolean,
    val images: Boolean,
    val modelSwitch: Boolean,
    val modes: Boolean,
    val history: Boolean,
    val cancel: Boolean,
)

@Serializable
data class EngineInfo(
    val id: String,
    val displayName: String,
    val kind: EngineKind,
    val status: EngineStatus,
    val capabilities: EngineCapabilities,
    val models: List<EngineModelSpec> = emptyList(),
    val defaultModel: String? = null,
    val defaultMode: SessionMode? = null,
    val defaultEffort: ReasoningEffort? = null,
    val supportedModes: List<SessionMode> = emptyList(),
)

@Serializable
data class Workspace(
    val id: String,
    val name: String,
    val path: String,
)

@Serializable
data class FsEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
)

@Serializable
data class CheckpointInfo(
    val id: String,
    val sessionId: String,
    val label: String,
    val commitSha: String,
    val createdAt: Long,
)

@Serializable
enum class SessionState {
    @SerialName("idle") IDLE,
    @SerialName("running") RUNNING,
    @SerialName("waiting_permission") WAITING_PERMISSION,
    @SerialName("error") ERROR,
    @SerialName("closed") CLOSED,
}

@Serializable
data class ModelOption(
    val id: String,
    val name: String,
)

@Serializable
data class SessionWorktreeInfo(
    val baseRepoPath: String,
    val worktreePath: String,
    val branch: String,
    val baseBranch: String? = null,
    val baseCommitSha: String? = null,
    val cleanedUp: Boolean? = null,
)

@Serializable
data class SubagentSummary(
    val id: String,
    val title: String,
    val role: String? = null,
    val state: SessionState,
    val statusText: String? = null,
    val createdAt: Long? = null,
    val updatedAt: Long,
    val workspace: String? = null,
    val parentId: String? = null,
    val depth: Int? = null,
    val stepsDone: Int? = null,
    val stepsTotal: Int? = null,
)

@Serializable
data class SessionMeta(
    val id: String,
    val engineId: String,
    val cwd: String,
    val workspaces: List<String>? = null,
    val worktree: SessionWorktreeInfo? = null,
    val title: String,
    val customTitle: String? = null,
    val archived: Boolean = false,
    val archivedAt: Long? = null,
    val state: SessionState,
    val createdAt: Long,
    val updatedAt: Long,
    val lastSeq: Int,
    val providerId: String? = null,
    val model: String? = null,
    val reasoningEffort: ReasoningEffort? = null,
    val mode: SessionMode? = null,
    val source: SessionSource? = null,
    val liveStatusText: String? = null,
    val turnStartedAt: Long? = null,
    val queuedPrompts: List<String>? = null,
    val models: List<ModelOption>? = null,
    val subagents: List<SubagentSummary>? = null,
    val artifactCount: Int? = null,
)

@Serializable
enum class ArtifactKind {
    @SerialName("plan") PLAN,
    @SerialName("task_list") TASK_LIST,
    @SerialName("walkthrough") WALKTHROUGH,
    @SerialName("html") HTML,
    @SerialName("code") CODE,
    @SerialName("image") IMAGE,
    @SerialName("other") OTHER,
}

@Serializable
enum class ArtifactSource {
    @SerialName("agy_brain") AGY_BRAIN,
    @SerialName("claude_plan") CLAUDE_PLAN,
    @SerialName("claude_artifact") CLAUDE_ARTIFACT,
    @SerialName("codex_plan") CODEX_PLAN,
    @SerialName("session_plan") SESSION_PLAN,
    @SerialName("other") OTHER,
}

@Serializable
data class ArtifactInfo(
    val id: String,
    val sessionId: String,
    val kind: ArtifactKind,
    val title: String,
    val summary: String? = null,
    val fileName: String? = null,
    val mimeType: String,
    val sizeBytes: Long? = null,
    val updatedAt: Long,
    val source: ArtifactSource,
    val actionable: Boolean = false,
)

@Serializable
data class ArtifactContentResult(
    val artifact: ArtifactInfo,
    val content: String,
    val isBase64: Boolean = false,
    val truncated: Boolean = false,
)

@Serializable
enum class PermissionOptionKind {
    @SerialName("allow_once") ALLOW_ONCE,
    @SerialName("allow_always") ALLOW_ALWAYS,
    @SerialName("reject_once") REJECT_ONCE,
    @SerialName("reject_always") REJECT_ALWAYS,
}

@Serializable
data class PermissionOption(
    val id: String,
    val name: String,
    val kind: PermissionOptionKind,
)

@Serializable
enum class ToolKind {
    @SerialName("read") READ,
    @SerialName("edit") EDIT,
    @SerialName("delete") DELETE,
    @SerialName("move") MOVE,
    @SerialName("search") SEARCH,
    @SerialName("execute") EXECUTE,
    @SerialName("think") THINK,
    @SerialName("fetch") FETCH,
    @SerialName("other") OTHER,
}

@Serializable
enum class ToolStatus {
    @SerialName("pending") PENDING,
    @SerialName("in_progress") IN_PROGRESS,
    @SerialName("completed") COMPLETED,
    @SerialName("failed") FAILED,
}

@Serializable
data class PlanEntry(
    val content: String,
    val status: String,
)

@Serializable
sealed interface AgentEvent {
    val seq: Int
    val sessionId: String
    val ts: Long

    @Serializable
    @SerialName("session.state")
    data class SessionStateChanged(
        override val seq: Int,
        override val sessionId: String,
        override val ts: Long,
        val state: SessionState,
        val detail: String? = null,
    ) : AgentEvent

    @Serializable
    @SerialName("message.user")
    data class UserMessage(
        override val seq: Int,
        override val sessionId: String,
        override val ts: Long,
        val messageId: String,
        val text: String,
        val imageCount: Int? = null,
        val imagePreviews: List<String>? = null,
    ) : AgentEvent

    @Serializable
    @SerialName("message.assistant.delta")
    data class AssistantDelta(
        override val seq: Int,
        override val sessionId: String,
        override val ts: Long,
        val messageId: String,
        val text: String,
    ) : AgentEvent

    @Serializable
    @SerialName("reasoning.delta")
    data class ReasoningDelta(
        override val seq: Int,
        override val sessionId: String,
        override val ts: Long,
        val messageId: String,
        val text: String,
    ) : AgentEvent

    @Serializable
    @SerialName("tool.call")
    data class ToolCall(
        override val seq: Int,
        override val sessionId: String,
        override val ts: Long,
        val toolCallId: String,
        val title: String,
        val kind: ToolKind,
        val status: ToolStatus,
        val input: String? = null,
        val output: String? = null,
        val locations: List<String>? = null,
    ) : AgentEvent

    @Serializable
    @SerialName("tool.update")
    data class ToolUpdate(
        override val seq: Int,
        override val sessionId: String,
        override val ts: Long,
        val toolCallId: String,
        val status: ToolStatus? = null,
        val title: String? = null,
        val output: String? = null,
    ) : AgentEvent

    @Serializable
    @SerialName("permission.requested")
    data class PermissionRequested(
        override val seq: Int,
        override val sessionId: String,
        override val ts: Long,
        val requestId: String,
        val toolCallId: String? = null,
        val title: String,
        val options: List<PermissionOption>,
    ) : AgentEvent

    @Serializable
    @SerialName("permission.resolved")
    data class PermissionResolved(
        override val seq: Int,
        override val sessionId: String,
        override val ts: Long,
        val requestId: String,
        val optionId: String? = null,
        val outcome: String,
    ) : AgentEvent

    @Serializable
    @SerialName("plan.updated")
    data class PlanUpdated(
        override val seq: Int,
        override val sessionId: String,
        override val ts: Long,
        val entries: List<PlanEntry>,
    ) : AgentEvent

    @Serializable
    @SerialName("usage")
    data class Usage(
        override val seq: Int,
        override val sessionId: String,
        override val ts: Long,
        val inputTokens: Int? = null,
        val outputTokens: Int? = null,
    ) : AgentEvent

    @Serializable
    @SerialName("turn.done")
    data class TurnDone(
        override val seq: Int,
        override val sessionId: String,
        override val ts: Long,
        val turnId: String,
        val stopReason: String,
        val error: String? = null,
    ) : AgentEvent

    @Serializable
    @SerialName("error")
    data class ErrorEvent(
        override val seq: Int,
        override val sessionId: String,
        override val ts: Long,
        val message: String,
    ) : AgentEvent
}

// RPC DTOs
@Serializable
data class ClientInfo(
    val name: String,
    val version: String,
    val platform: String,
)

@Serializable
data class HubHelloParams(
    val protocolVersion: Int = PROTOCOL_VERSION,
    val token: String,
    val client: ClientInfo,
)

@Serializable
data class HubHelloResult(
    val protocolVersion: Int,
    val hub: HubInfo,
    val capabilities: List<String>,
    val relayUrls: List<String> = emptyList(),
    val endpoints: List<String> = emptyList(),
)

@Serializable
data class HubStatusResult(
    val hub: HubInfo,
    val slaves: List<SlaveInfo>,
    val engines: List<EngineInfo>,
    val providers: List<ProviderInfo>,
    val profiles: List<ProfileInfo>,
)

@Serializable
data class EngineListResult(
    val engines: List<EngineInfo>,
)

@Serializable
data class WorkspaceListResult(
    val workspaces: List<Workspace>,
)

@Serializable
data class WorkspaceFilesParams(
    val path: String,
    val query: String? = null,
    val limit: Int? = null,
)

@Serializable
data class WorkspaceFilesResult(
    val path: String,
    val files: List<String> = emptyList(),
)

@Serializable
data class PromptImageAttachment(
    val mimeType: String,
    val dataBase64: String,
    val thumbnailBase64: String? = null,
    val fileName: String? = null,
)

@Serializable
data class FsListParams(
    val path: String,
)

@Serializable
data class FsListResult(
    val path: String,
    val entries: List<FsEntry>,
)

@Serializable
data class FsReadParams(
    val path: String,
)

@Serializable
data class FsReadResult(
    val path: String,
    val content: String,
    val truncated: Boolean,
)

@Serializable
data class ProfileUpsertParams(
    val profile: ProfileInfo,
)

@Serializable
data class ProfileListResult(
    val profiles: List<ProfileInfo>,
)

@Serializable
data class SessionListParams(
    val engineId: String? = null,
)

@Serializable
data class SessionListResult(
    val sessions: List<SessionMeta>,
)

@Serializable
data class SessionCreateParams(
    val engineId: String,
    val cwd: String,
    val workspaces: List<String>? = null,
    val useWorktree: Boolean? = null,
    val worktreeBranch: String? = null,
    val title: String? = null,
    val providerId: String? = null,
    val modelId: String? = null,
    val reasoningEffort: ReasoningEffort? = null,
    val mode: SessionMode? = null,
)

@Serializable
data class SessionCreateResult(
    val session: SessionMeta,
)

@Serializable
data class SessionPromptParams(
    val sessionId: String,
    val text: String,
    val images: List<PromptImageAttachment>? = null,
    val providerId: String? = null,
    val modelId: String? = null,
    val reasoningEffort: ReasoningEffort? = null,
    val mode: SessionMode? = null,
)

@Serializable
data class SessionPromptResult(
    val turnId: String,
)

@Serializable
data class SessionCancelParams(
    val sessionId: String,
)

@Serializable
data class SessionCancelQueuedParams(
    val sessionId: String,
    val index: Int? = null,
    val all: Boolean? = null,
)

@Serializable
data class SessionCancelQueuedResult(
    val ok: Boolean,
    val queuedPrompts: List<String> = emptyList(),
    val removedCount: Int = 0,
    val notice: String? = null,
)

@Serializable
data class SessionSetModelParams(
    val sessionId: String,
    val providerId: String? = null,
    val modelId: String,
    val reasoningEffort: ReasoningEffort? = null,
    val mode: SessionMode? = null,
)

@Serializable
data class SessionRenameParams(
    val sessionId: String,
    val title: String,
)

@Serializable
data class SessionArchiveParams(
    val sessionId: String,
    val archived: Boolean,
)

@Serializable
data class ArtifactListParams(
    val sessionId: String,
)

@Serializable
data class ArtifactListResult(
    val sessionId: String,
    val artifacts: List<ArtifactInfo> = emptyList(),
)

@Serializable
data class ArtifactReadParams(
    val sessionId: String,
    val artifactId: String,
)

@Serializable
enum class SearchRoleFilter {
    @SerialName("all") ALL,
    @SerialName("user") USER,
    @SerialName("assistant") ASSISTANT,
}

@Serializable
enum class SearchMatchRole {
    @SerialName("user") USER,
    @SerialName("assistant") ASSISTANT,
}

@Serializable
data class SessionSearchMatch(
    val seq: Int,
    val role: SearchMatchRole,
    val ts: Long,
    val snippet: String,
    val matchStart: Int = 0,
    val matchEnd: Int = 0,
)

@Serializable
data class SessionSearchHit(
    val sessionId: String,
    val engineId: String,
    val title: String,
    val cwd: String,
    val updatedAt: Long,
    val archived: Boolean? = null,
    val matchCount: Int = 0,
    val matches: List<SessionSearchMatch> = emptyList(),
)

@Serializable
data class SessionSearchParams(
    val query: String,
    val sessionId: String? = null,
    val engineId: String? = null,
    val role: SearchRoleFilter? = null,
    val cwd: String? = null,
    val includeArchived: Boolean? = null,
    val limit: Int? = null,
    val matchesPerSession: Int? = null,
)

@Serializable
data class SessionSearchResult(
    val query: String,
    val hits: List<SessionSearchHit> = emptyList(),
    val indexedSessions: Int = 0,
    val totalSessions: Int = 0,
    val indexing: Boolean = false,
)

@Serializable
data class StreamSubscribeParams(
    val sessionId: String,
    val afterSeq: Int,
    val beforeSeq: Int? = null,
    val aroundSeq: Int? = null,
    val limit: Int? = null,
)

@Serializable
data class StreamSubscribeResult(
    val session: SessionMeta,
    val events: List<AgentEvent>,
    val truncated: Boolean,
    val earliestSeq: Int? = null,
    val hasMoreBefore: Boolean? = null,
    val hasMoreAfter: Boolean? = null,
    val latestPlan: List<PlanEntry>? = null,
)

@Serializable
data class ApprovalRespondParams(
    val sessionId: String,
    val requestId: String,
    val optionId: String? = null,
)

@Serializable
data class CheckpointListParams(
    val sessionId: String,
)

@Serializable
data class CheckpointListResult(
    val checkpoints: List<CheckpointInfo>,
)

@Serializable
data class CheckpointCreateParams(
    val sessionId: String,
    val label: String? = null,
)

@Serializable
data class CheckpointCreateResult(
    val checkpoint: CheckpointInfo,
)

@Serializable
data class CheckpointRestoreParams(
    val sessionId: String,
    val checkpointId: String,
)

@Serializable
data class OkResult(
    val ok: Boolean,
)

@Serializable
data class UsageTotals(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
    val totalTokens: Long = 0,
    val requests: Int = 0,
    val estimatedCostUsd: Double? = null,
)

@Serializable
data class DailyUsageEntry(
    val date: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long,
    val cacheWriteTokens: Long,
    val totalTokens: Long,
    val requests: Int,
    val estimatedCostUsd: Double? = null,
)

@Serializable
data class EngineUsageEntry(
    val engineId: String,
    val supported: Boolean,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long,
    val cacheWriteTokens: Long,
    val totalTokens: Long,
    val requests: Int,
    val estimatedCostUsd: Double? = null,
    val note: String? = null,
)

@Serializable
data class ModelUsageEntry(
    val model: String,
    val engineId: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long,
    val cacheWriteTokens: Long,
    val totalTokens: Long,
    val requests: Int,
    val estimatedCostUsd: Double? = null,
)

@Serializable
data class UsageSummaryParams(
    val days: Int? = null,
    val engineId: String? = null,
)

@Serializable
data class UsageSummaryResult(
    val days: Int,
    val generatedAt: Long,
    val totals: UsageTotals,
    val daily: List<DailyUsageEntry> = emptyList(),
    val byEngine: List<EngineUsageEntry> = emptyList(),
    val byModel: List<ModelUsageEntry> = emptyList(),
)

@Serializable
data class QuotaWindowInfo(
    val id: String,
    val engineId: String,
    val windowKind: String,
    val label: String,
    val source: String,
    val active: Boolean,
    val usedPercent: Double? = null,
    val usedTokens: Long,
    val estimatedCostUsd: Double? = null,
    val burnRateTokensPerMin: Double? = null,
    val projectedTokens: Long? = null,
    val windowMinutes: Int,
    val startedAt: Long? = null,
    val resetsAt: Long? = null,
)

@Serializable
data class UsageQuotaResult(
    val generatedAt: Long,
    val windows: List<QuotaWindowInfo> = emptyList(),
)

@Serializable
data class UsageSessionParams(
    val sessionId: String,
)

typealias SessionUsageParams = UsageSessionParams

@Serializable
data class SessionUsageResult(
    val sessionId: String,
    val engineId: String,
    val model: String? = null,
    val supported: Boolean,
    val contextTokens: Long,
    val contextWindow: Long,
    val contextPercent: Double,
    val cacheHitPercent: Double? = null,
    val lastInputTokens: Long,
    val lastOutputTokens: Long,
    val totals: UsageTotals,
    val note: String? = null,
)

@Serializable
data class ProxyModelUsageEntry(
    val model: String,
    val requests: Long,
    val totalTokens: Long,
)

@Serializable
data class ProxyUsageResult(
    val endpoint: String,
    val reachable: Boolean,
    val exposedModels: Int,
    val managementEnabled: Boolean,
    val totalRequests: Long? = null,
    val successCount: Long? = null,
    val failureCount: Long? = null,
    val totalTokens: Long? = null,
    val byModel: List<ProxyModelUsageEntry> = emptyList(),
    val note: String? = null,
)

@Serializable
data class GitStatusParams(
    val path: String,
)

@Serializable
data class GitChangedFileEntry(
    val path: String,
    val status: String,
    val additions: Int,
    val deletions: Int,
)

@Serializable
data class GitStatusResult(
    val path: String,
    val isGitRepo: Boolean,
    val branch: String? = null,
    val headSha: String? = null,
    val totalAdditions: Int = 0,
    val totalDeletions: Int = 0,
    val files: List<GitChangedFileEntry> = emptyList(),
)

@Serializable
data class GitDiffParams(
    val path: String,
    val filePath: String? = null,
    val baseCommitSha: String? = null,
)

@Serializable
data class GitDiffResult(
    val path: String,
    val filePath: String? = null,
    val diff: String,
    val truncated: Boolean = false,
)

@Serializable
data class StreamEventNotification(
    val event: AgentEvent,
)

@Serializable
data class SessionUpdatedNotification(
    val session: SessionMeta,
)
`

const ktPath = path.join(ktDir, 'ProtocolModels.kt')
fs.writeFileSync(ktPath, kotlinCode, 'utf8')

console.log(`Generated:\n  - ${schemaPath}\n  - ${fixturesPath}\n  - ${ktPath}`)
