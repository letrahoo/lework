/**
 * lework · App ⇄ Hub protocol (v1, M0 subset).
 *
 * Source of truth for the wire contract. Kotlin models in `app/shared` mirror
 * these definitions and are verified against `fixtures/*.json` on both sides.
 *
 * Transport: JSON-RPC 2.0 over a single WebSocket (`/v1/ws`).
 * Server→client push uses JSON-RPC notifications (no `id`).
 */
import { z } from 'zod'

export const PROTOCOL_VERSION = 1

// ---------------------------------------------------------------------------
// JSON-RPC envelope
// ---------------------------------------------------------------------------

export const RpcId = z.union([z.string().max(128), z.number().int()])
export type RpcId = z.infer<typeof RpcId>

export const RpcRequest = z.object({
  jsonrpc: z.literal('2.0'),
  id: RpcId,
  method: z.string().min(1).max(64),
  params: z.unknown().optional(),
})
export type RpcRequest = z.infer<typeof RpcRequest>

export const RpcErrorObject = z.object({
  code: z.number().int(),
  message: z.string(),
  data: z.unknown().optional(),
})
export type RpcErrorObject = z.infer<typeof RpcErrorObject>

export const RpcResponse = z.object({
  jsonrpc: z.literal('2.0'),
  id: RpcId.nullable(),
  result: z.unknown().optional(),
  error: RpcErrorObject.optional(),
})
export type RpcResponse = z.infer<typeof RpcResponse>

export const RpcNotification = z.object({
  jsonrpc: z.literal('2.0'),
  method: z.string(),
  params: z.unknown().optional(),
})
export type RpcNotification = z.infer<typeof RpcNotification>

/** Standard JSON-RPC codes plus application range (-32000..-32099). */
export const ErrorCode = {
  ParseError: -32700,
  InvalidRequest: -32600,
  MethodNotFound: -32601,
  InvalidParams: -32602,
  Internal: -32603,
  Unauthorized: -32001,
  NotFound: -32002,
  Conflict: -32003,
  EngineUnavailable: -32010,
  EngineError: -32011,
  PathNotAllowed: -32020,
  VersionMismatch: -32030,
} as const

// ---------------------------------------------------------------------------
// Domain models
// ---------------------------------------------------------------------------

export const HubRole = z.enum(['master', 'slave', 'standalone'])
export type HubRole = z.infer<typeof HubRole>

export const HubInfo = z.object({
  id: z.string(),
  name: z.string(),
  role: HubRole,
  platform: z.enum(['darwin', 'linux', 'win32', 'other']),
  version: z.string(),
})
export type HubInfo = z.infer<typeof HubInfo>

export const SlaveInfo = z.object({
  id: z.string(),
  name: z.string(),
  platform: z.enum(['darwin', 'linux', 'win32', 'other']),
  online: z.boolean(),
  engines: z.array(z.string()),
  lastSeenAt: z.number().int(),
})
export type SlaveInfo = z.infer<typeof SlaveInfo>

export const ProviderType = z.enum(['subscription', 'endpoint'])
export const ProviderAdapterKind = z.enum(['cpa', 'generic'])
export type ProviderAdapterKind = z.infer<typeof ProviderAdapterKind>
export const WireApi = z.enum(['openai-chat', 'openai-responses', 'anthropic-messages', 'gemini'])
export type WireApi = z.infer<typeof WireApi>
export const ProviderAuthStatus = z.enum(['authenticated', 'required', 'unknown'])

export const ProviderInfo = z.object({
  id: z.string(),
  name: z.string(),
  type: ProviderType,
  adapter: ProviderAdapterKind.optional(),
  baseUrl: z.string().optional(),
  wireApi: WireApi.optional(),
  wireApis: z.array(WireApi).optional(),
  enabledEngines: z.array(z.string()).optional(),
  enabled: z.boolean().optional(),
  builtIn: z.boolean().optional(),
  hasApiKey: z.boolean().optional(),
  apiKeyEnv: z.string().optional(),
  manualModels: z.array(z.string()).optional(),
  selectedModels: z.array(z.string()).optional(),
  healthy: z.boolean(),
  authStatus: ProviderAuthStatus.optional(),
  latencyMs: z.number().int().nonnegative().optional(),
  error: z.string().optional(),
  models: z.array(z.string()),
})
export type ProviderInfo = z.infer<typeof ProviderInfo>

export const ProviderConfigInput = z.object({
  id: z.string().min(1).max(64).optional(),
  name: z.string().min(1).max(48),
  adapter: ProviderAdapterKind.optional(),
  baseUrl: z.string().min(1).max(512),
  apiKey: z.string().max(512).optional(),
  clearApiKey: z.boolean().optional(),
  apiKeyEnv: z.string().max(128).optional(),
  wireApis: z.array(WireApi).min(1).max(4).optional(),
  enabledEngines: z.array(z.string().min(1).max(32)).max(16).optional(),
  enabled: z.boolean().optional(),
  manualModels: z.array(z.string().min(1).max(160)).max(80).optional(),
  selectedModels: z.array(z.string().min(1).max(160)).max(120).optional(),
})
export type ProviderConfigInput = z.infer<typeof ProviderConfigInput>

export const SessionMode = z.enum([
  'default',
  'acceptEdits',
  'plan',
  'auto',
  'bypassPermissions',
])
export type SessionMode = z.infer<typeof SessionMode>

export const ReasoningEffort = z.enum(['low', 'medium', 'high', 'xhigh', 'max'])
export type ReasoningEffort = z.infer<typeof ReasoningEffort>

export const EngineModelSpec = z.object({
  id: z.string(),
  name: z.string(),
  providerId: z.string().optional(),
  providerName: z.string().optional(),
  efforts: z.array(ReasoningEffort).optional(),
  defaultEffort: ReasoningEffort.optional(),
})
export type EngineModelSpec = z.infer<typeof EngineModelSpec>

export const ProfileInfo = z.object({
  id: z.string(),
  name: z.string(),
  slaveId: z.string().optional(),
  engineId: z.string(),
  providerId: z.string().optional(),
  modelId: z.string().optional(),
  mode: SessionMode,
  defaultCwd: z.string().optional(),
})
export type ProfileInfo = z.infer<typeof ProfileInfo>

export const EngineKind = z.enum(['cli', 'service', 'server', 'embedded'])

export const EngineStatus = z.object({
  installed: z.boolean(),
  ready: z.boolean(),
  authStatus: z.enum(['authenticated', 'required', 'unknown']).optional(),
  version: z.string().optional(),
  detail: z.string().optional(),
})
export type EngineStatus = z.infer<typeof EngineStatus>

export const EngineCapabilities = z.object({
  permissions: z.boolean(),
  images: z.boolean(),
  modelSwitch: z.boolean(),
  modes: z.boolean(),
  history: z.boolean(),
  cancel: z.boolean(),
})
export type EngineCapabilities = z.infer<typeof EngineCapabilities>

export const EngineInfo = z.object({
  id: z.string(),
  displayName: z.string(),
  kind: EngineKind,
  status: EngineStatus,
  capabilities: EngineCapabilities,
  models: z.array(EngineModelSpec).optional(),
  defaultModel: z.string().optional(),
  defaultMode: SessionMode.optional(),
  defaultEffort: ReasoningEffort.optional(),
  supportedModes: z.array(SessionMode).optional(),
})
export type EngineInfo = z.infer<typeof EngineInfo>

export const Workspace = z.object({
  id: z.string(),
  name: z.string(),
  path: z.string(),
})
export type Workspace = z.infer<typeof Workspace>

export const FsEntry = z.object({
  name: z.string(),
  path: z.string(),
  isDirectory: z.boolean(),
  size: z.number().int(),
})
export type FsEntry = z.infer<typeof FsEntry>

export const CheckpointInfo = z.object({
  id: z.string(),
  sessionId: z.string(),
  label: z.string(),
  commitSha: z.string(),
  createdAt: z.number().int(),
})
export type CheckpointInfo = z.infer<typeof CheckpointInfo>

export const SessionState = z.enum(['idle', 'running', 'waiting_permission', 'error', 'closed'])
export type SessionState = z.infer<typeof SessionState>

export const ModelOption = z.object({ id: z.string(), name: z.string() })
export type ModelOption = z.infer<typeof ModelOption>

export const SessionWorktreeInfo = z.object({
  baseRepoPath: z.string(),
  worktreePath: z.string(),
  branch: z.string(),
  baseBranch: z.string().optional(),
  baseCommitSha: z.string().optional(),
  cleanedUp: z.boolean().optional(),
})
export type SessionWorktreeInfo = z.infer<typeof SessionWorktreeInfo>

export const SubagentSummary = z.object({
  id: z.string(),
  title: z.string(),
  role: z.string().optional(),
  state: SessionState,
  statusText: z.string().optional(),
  createdAt: z.number().int().optional(),
  updatedAt: z.number().int(),
  workspace: z.string().optional(),
  parentId: z.string().optional(),
  depth: z.number().int().nonnegative().optional(),
  stepsDone: z.number().int().nonnegative().optional(),
  stepsTotal: z.number().int().nonnegative().optional(),
})
export type SubagentSummary = z.infer<typeof SubagentSummary>

export const SessionMeta = z.object({
  id: z.string(),
  engineId: z.string(),
  cwd: z.string(),
  workspaces: z.array(z.string()).optional(),
  worktree: SessionWorktreeInfo.optional(),
  title: z.string(),
  customTitle: z.string().optional(),
  archived: z.boolean().optional(),
  archivedAt: z.number().int().optional(),
  state: SessionState,
  createdAt: z.number().int(),
  updatedAt: z.number().int(),
  lastSeq: z.number().int(),
  providerId: z.string().optional(),
  model: z.string().optional(),
  reasoningEffort: ReasoningEffort.optional(),
  mode: SessionMode.optional(),
  source: z.enum(['hub', 'host']).optional(),
  liveStatusText: z.string().optional(),
  turnStartedAt: z.number().int().optional(),
  queuedPrompts: z.array(z.string()).optional(),
  models: z.array(ModelOption).optional(),
  subagents: z.array(SubagentSummary).optional(),
  artifactCount: z.number().int().nonnegative().optional(),
})
export type SessionMeta = z.infer<typeof SessionMeta>

export const ArtifactKind = z.enum([
  'plan',
  'task_list',
  'walkthrough',
  'html',
  'code',
  'image',
  'other',
])
export type ArtifactKind = z.infer<typeof ArtifactKind>

export const ArtifactSource = z.enum([
  'agy_brain',
  'claude_plan',
  'claude_artifact',
  'codex_plan',
  'session_plan',
  'other',
])
export type ArtifactSource = z.infer<typeof ArtifactSource>

export const ArtifactInfo = z.object({
  id: z.string(),
  sessionId: z.string(),
  kind: ArtifactKind,
  title: z.string(),
  summary: z.string().optional(),
  fileName: z.string().optional(),
  mimeType: z.string(),
  sizeBytes: z.number().int().nonnegative().optional(),
  updatedAt: z.number().int(),
  source: ArtifactSource,
  actionable: z.boolean().optional(),
})
export type ArtifactInfo = z.infer<typeof ArtifactInfo>

export const ArtifactContentResult = z.object({
  artifact: ArtifactInfo,
  content: z.string(),
  isBase64: z.boolean().optional(),
  truncated: z.boolean(),
})
export type ArtifactContentResult = z.infer<typeof ArtifactContentResult>

export const PermissionOptionKind = z.enum(['allow_once', 'allow_always', 'reject_once', 'reject_always'])
export const PermissionOption = z.object({
  id: z.string(),
  name: z.string(),
  kind: PermissionOptionKind,
})
export type PermissionOption = z.infer<typeof PermissionOption>

export const ToolKind = z.enum(['read', 'edit', 'delete', 'move', 'search', 'execute', 'think', 'fetch', 'other'])
export const ToolStatus = z.enum(['pending', 'in_progress', 'completed', 'failed'])

export const PlanEntryStatus = z.enum(['pending', 'in_progress', 'completed', 'skipped', 'failed'])
export type PlanEntryStatus = z.infer<typeof PlanEntryStatus>

export const PlanEntry = z.object({
  content: z.string(),
  status: PlanEntryStatus,
})
export type PlanEntry = z.infer<typeof PlanEntry>

// ---------------------------------------------------------------------------
// Usage, context window & subscription quota models
// ---------------------------------------------------------------------------

export const UsageTotals = z.object({
  inputTokens: z.number().int().nonnegative(),
  outputTokens: z.number().int().nonnegative(),
  cacheReadTokens: z.number().int().nonnegative(),
  cacheWriteTokens: z.number().int().nonnegative(),
  totalTokens: z.number().int().nonnegative(),
  requests: z.number().int().nonnegative(),
  estimatedCostUsd: z.number().nonnegative().optional(),
})
export type UsageTotals = z.infer<typeof UsageTotals>

export const DailyUsageEntry = z.object({
  date: z.string(),
  inputTokens: z.number().int().nonnegative(),
  outputTokens: z.number().int().nonnegative(),
  cacheReadTokens: z.number().int().nonnegative(),
  cacheWriteTokens: z.number().int().nonnegative(),
  totalTokens: z.number().int().nonnegative(),
  requests: z.number().int().nonnegative(),
  estimatedCostUsd: z.number().nonnegative().optional(),
})
export type DailyUsageEntry = z.infer<typeof DailyUsageEntry>

export const EngineUsageEntry = z.object({
  engineId: z.string(),
  supported: z.boolean(),
  inputTokens: z.number().int().nonnegative(),
  outputTokens: z.number().int().nonnegative(),
  cacheReadTokens: z.number().int().nonnegative(),
  cacheWriteTokens: z.number().int().nonnegative(),
  totalTokens: z.number().int().nonnegative(),
  requests: z.number().int().nonnegative(),
  estimatedCostUsd: z.number().nonnegative().optional(),
  note: z.string().optional(),
})
export type EngineUsageEntry = z.infer<typeof EngineUsageEntry>

export const ModelUsageEntry = z.object({
  model: z.string(),
  engineId: z.string(),
  inputTokens: z.number().int().nonnegative(),
  outputTokens: z.number().int().nonnegative(),
  cacheReadTokens: z.number().int().nonnegative(),
  cacheWriteTokens: z.number().int().nonnegative(),
  totalTokens: z.number().int().nonnegative(),
  requests: z.number().int().nonnegative(),
  estimatedCostUsd: z.number().nonnegative().optional(),
})
export type ModelUsageEntry = z.infer<typeof ModelUsageEntry>

export const UsageSummaryResult = z.object({
  days: z.number().int().min(1).max(90),
  generatedAt: z.number().int(),
  totals: UsageTotals,
  daily: z.array(DailyUsageEntry),
  byEngine: z.array(EngineUsageEntry),
  byModel: z.array(ModelUsageEntry),
})
export type UsageSummaryResult = z.infer<typeof UsageSummaryResult>

export const QuotaWindowKind = z.enum(['five_hour', 'weekly'])
export type QuotaWindowKind = z.infer<typeof QuotaWindowKind>

export const QuotaSource = z.enum(['reported', 'estimated'])
export type QuotaSource = z.infer<typeof QuotaSource>

export const QuotaWindowInfo = z.object({
  id: z.string(),
  engineId: z.string(),
  windowKind: QuotaWindowKind,
  label: z.string(),
  source: QuotaSource,
  active: z.boolean(),
  usedPercent: z.number().min(0).max(100).optional(),
  usedTokens: z.number().int().nonnegative(),
  estimatedCostUsd: z.number().nonnegative().optional(),
  burnRateTokensPerMin: z.number().nonnegative().optional(),
  projectedTokens: z.number().int().nonnegative().optional(),
  windowMinutes: z.number().int().positive(),
  startedAt: z.number().int().optional(),
  resetsAt: z.number().int().optional(),
})
export type QuotaWindowInfo = z.infer<typeof QuotaWindowInfo>

export const UsageQuotaResult = z.object({
  generatedAt: z.number().int(),
  windows: z.array(QuotaWindowInfo),
})
export type UsageQuotaResult = z.infer<typeof UsageQuotaResult>

export const SessionUsageResult = z.object({
  sessionId: z.string(),
  engineId: z.string(),
  model: z.string().optional(),
  supported: z.boolean(),
  contextTokens: z.number().int().nonnegative(),
  contextWindow: z.number().int().nonnegative(),
  contextPercent: z.number().min(0).max(100),
  cacheHitPercent: z.number().min(0).max(100).optional(),
  lastInputTokens: z.number().int().nonnegative(),
  lastOutputTokens: z.number().int().nonnegative(),
  totals: UsageTotals,
  note: z.string().optional(),
})
export type SessionUsageResult = z.infer<typeof SessionUsageResult>

export const ProxyModelUsageEntry = z.object({
  model: z.string(),
  requests: z.number().int().nonnegative(),
  totalTokens: z.number().int().nonnegative(),
})
export type ProxyModelUsageEntry = z.infer<typeof ProxyModelUsageEntry>

export const ProxyUsageResult = z.object({
  endpoint: z.string(),
  reachable: z.boolean(),
  exposedModels: z.number().int().nonnegative(),
  managementEnabled: z.boolean(),
  totalRequests: z.number().int().nonnegative().optional(),
  successCount: z.number().int().nonnegative().optional(),
  failureCount: z.number().int().nonnegative().optional(),
  totalTokens: z.number().int().nonnegative().optional(),
  byModel: z.array(ProxyModelUsageEntry),
  note: z.string().optional(),
})
export type ProxyUsageResult = z.infer<typeof ProxyUsageResult>

// ---------------------------------------------------------------------------
// Agent events (normalized across engines)
// ---------------------------------------------------------------------------

const base = { seq: z.number().int().nonnegative(), sessionId: z.string(), ts: z.number().int() }

export const AgentEvent = z.discriminatedUnion('type', [
  z.object({ ...base, type: z.literal('session.state'), state: SessionState, detail: z.string().optional() }),
  z.object({
    ...base,
    type: z.literal('message.user'),
    messageId: z.string(),
    text: z.string(),
    imageCount: z.number().int().nonnegative().optional(),
    imagePreviews: z.array(z.string().min(4).max(300_000)).max(4).optional(),
  }),
  z.object({ ...base, type: z.literal('message.assistant.delta'), messageId: z.string(), text: z.string() }),
  z.object({ ...base, type: z.literal('reasoning.delta'), messageId: z.string(), text: z.string() }),
  z.object({
    ...base, type: z.literal('tool.call'),
    toolCallId: z.string(), title: z.string(), kind: ToolKind, status: ToolStatus,
    input: z.string().optional(), output: z.string().optional(), locations: z.array(z.string()).optional(),
  }),
  z.object({
    ...base, type: z.literal('tool.update'),
    toolCallId: z.string(), status: ToolStatus.optional(), title: z.string().optional(), output: z.string().optional(),
  }),
  z.object({
    ...base, type: z.literal('permission.requested'),
    requestId: z.string(), toolCallId: z.string().optional(), title: z.string(), options: z.array(PermissionOption),
  }),
  z.object({
    ...base, type: z.literal('permission.resolved'),
    requestId: z.string(), optionId: z.string().nullable(), outcome: z.enum(['selected', 'cancelled']),
  }),
  z.object({ ...base, type: z.literal('plan.updated'), entries: z.array(PlanEntry) }),
  z.object({
    ...base, type: z.literal('usage'),
    inputTokens: z.number().int().optional(), outputTokens: z.number().int().optional(),
  }),
  z.object({
    ...base, type: z.literal('turn.done'), turnId: z.string(),
    stopReason: z.enum(['end_turn', 'cancelled', 'max_tokens', 'refusal', 'error']), error: z.string().optional(),
  }),
  z.object({ ...base, type: z.literal('error'), message: z.string() }),
])
export type AgentEvent = z.infer<typeof AgentEvent>
export type AgentEventType = AgentEvent['type']

/** Event body without envelope fields; the Hub assigns seq/sessionId/ts. */
export type AgentEventBody = AgentEvent extends infer E
  ? E extends AgentEvent ? Omit<E, 'seq' | 'sessionId' | 'ts'> : never
  : never

// ---------------------------------------------------------------------------
// Methods (params / result)
// ---------------------------------------------------------------------------

export const ClientInfo = z.object({
  name: z.string().max(64),
  version: z.string().max(32),
  platform: z.string().max(32),
})

export const PromptImageAttachment = z.object({
  mimeType: z.enum(['image/png', 'image/jpeg', 'image/webp', 'image/gif']),
  dataBase64: z.string().min(4).max(1_000_000),
  thumbnailBase64: z.string().min(4).max(260_000).optional(),
  fileName: z.string().max(120).optional(),
})
export type PromptImageAttachment = z.infer<typeof PromptImageAttachment>

export const WorkspaceFilesResult = z.object({
  path: z.string(),
  files: z.array(z.string()),
})
export type WorkspaceFilesResult = z.infer<typeof WorkspaceFilesResult>

export const GitChangedFileEntry = z.object({
  path: z.string(),
  status: z.enum(['modified', 'added', 'deleted', 'renamed', 'untracked']),
  additions: z.number().int().nonnegative(),
  deletions: z.number().int().nonnegative(),
})
export type GitChangedFileEntry = z.infer<typeof GitChangedFileEntry>

export const GitStatusResult = z.object({
  path: z.string(),
  isGitRepo: z.boolean(),
  branch: z.string().optional(),
  headSha: z.string().optional(),
  totalAdditions: z.number().int().nonnegative(),
  totalDeletions: z.number().int().nonnegative(),
  files: z.array(GitChangedFileEntry),
})
export type GitStatusResult = z.infer<typeof GitStatusResult>

export const GitDiffResult = z.object({
  path: z.string(),
  filePath: z.string().optional(),
  diff: z.string(),
  truncated: z.boolean(),
})
export type GitDiffResult = z.infer<typeof GitDiffResult>

export const SessionCancelQueuedParamsSchema = z.object({
  sessionId: z.string().min(1),
  index: z.number().int().min(0).optional(),
  all: z.boolean().optional(),
})
export type SessionCancelQueuedParams = z.infer<typeof SessionCancelQueuedParamsSchema>

export const SessionCancelQueuedResultSchema = z.object({
  ok: z.boolean(),
  queuedPrompts: z.array(z.string()),
  removedCount: z.number().int().min(0),
  notice: z.string().optional(),
})
export type SessionCancelQueuedResult = z.infer<typeof SessionCancelQueuedResultSchema>

// ---------------------------------------------------------------------------
// Cross-engine full-text conversation search models
// ---------------------------------------------------------------------------

export const SearchRoleFilter = z.enum(['all', 'user', 'assistant'])
export type SearchRoleFilter = z.infer<typeof SearchRoleFilter>

export const SessionSearchMatch = z.object({
  seq: z.number().int().nonnegative(),
  role: z.enum(['user', 'assistant']),
  ts: z.number().int(),
  snippet: z.string(),
  matchStart: z.number().int().nonnegative(),
  matchEnd: z.number().int().nonnegative(),
})
export type SessionSearchMatch = z.infer<typeof SessionSearchMatch>

export const SessionSearchHit = z.object({
  sessionId: z.string(),
  engineId: z.string(),
  title: z.string(),
  cwd: z.string(),
  updatedAt: z.number().int(),
  archived: z.boolean().optional(),
  matchCount: z.number().int().nonnegative(),
  matches: z.array(SessionSearchMatch),
})
export type SessionSearchHit = z.infer<typeof SessionSearchHit>

export const SessionSearchParamsSchema = z.object({
  query: z.string().max(300),
  sessionId: z.string().min(1).max(256).optional(),
  engineId: z.string().min(1).max(32).optional(),
  role: SearchRoleFilter.optional(),
  cwd: z.string().min(1).max(4096).optional(),
  includeArchived: z.boolean().optional(),
  limit: z.number().int().min(1).max(100).optional(),
  matchesPerSession: z.number().int().min(1).max(50).optional(),
})
export type SessionSearchParams = z.infer<typeof SessionSearchParamsSchema>

export const SessionSearchResult = z.object({
  query: z.string(),
  hits: z.array(SessionSearchHit),
  indexedSessions: z.number().int().nonnegative(),
  totalSessions: z.number().int().nonnegative(),
  indexing: z.boolean(),
})
export type SessionSearchResult = z.infer<typeof SessionSearchResult>

export const Methods = {
  'hub.hello': {
    params: z.object({ protocolVersion: z.number().int(), token: z.string().min(8).max(1024), client: ClientInfo }),
    result: z.object({
      protocolVersion: z.number().int(),
      hub: HubInfo,
      capabilities: z.array(z.string()),
      /** Alternate addresses of this Hub (relays); only revealed after authentication. */
      relayUrls: z.array(z.string()).optional(),
      /** All connectable HTTPS endpoints (LAN, mDNS, Tailscale IP, Relays) for multi-path roaming. */
      endpoints: z.array(z.string()).optional(),
    }),
  },
  'hub.status': {
    params: z.object({}).optional(),
    result: z.object({
      hub: HubInfo,
      slaves: z.array(SlaveInfo),
      engines: z.array(EngineInfo),
      providers: z.array(ProviderInfo),
      profiles: z.array(ProfileInfo),
    }),
  },
  'slave.list': {
    params: z.object({}).optional(),
    result: z.object({ slaves: z.array(SlaveInfo) }),
  },
  'profile.list': {
    params: z.object({}).optional(),
    result: z.object({ profiles: z.array(ProfileInfo) }),
  },
  'profile.upsert': {
    params: z.object({ profile: ProfileInfo }),
    result: z.object({ profiles: z.array(ProfileInfo) }),
  },
  'provider.list': {
    params: z.object({}).optional(),
    result: z.object({ providers: z.array(ProviderInfo), engines: z.array(EngineInfo) }),
  },
  'provider.upsert': {
    params: z.object({ provider: ProviderConfigInput }),
    result: z.object({
      provider: ProviderInfo,
      providers: z.array(ProviderInfo),
      engines: z.array(EngineInfo),
    }),
  },
  'provider.remove': {
    params: z.object({ providerId: z.string().min(1).max(64) }),
    result: z.object({ providers: z.array(ProviderInfo), engines: z.array(EngineInfo) }),
  },
  'provider.probe': {
    params: z
      .object({
        providerId: z.string().min(1).max(64).optional(),
        provider: ProviderConfigInput.optional(),
      })
      .optional(),
    result: z.object({
      provider: ProviderInfo.optional(),
      providers: z.array(ProviderInfo),
      engines: z.array(EngineInfo),
    }),
  },
  'engine.list': {
    params: z.object({}).optional(),
    result: z.object({ engines: z.array(EngineInfo) }),
  },
  'workspace.list': {
    params: z.object({}).optional(),
    result: z.object({ workspaces: z.array(Workspace) }),
  },
  'workspace.files': {
    params: z.object({
      path: z.string().min(1).max(4096),
      query: z.string().max(200).optional(),
      limit: z.number().int().min(1).max(200).optional(),
    }),
    result: WorkspaceFilesResult,
  },
  'fs.list': {
    params: z.object({ path: z.string().min(1).max(4096) }),
    result: z.object({ path: z.string(), entries: z.array(FsEntry) }),
  },
  'fs.read': {
    params: z.object({ path: z.string().min(1).max(4096) }),
    result: z.object({ path: z.string(), content: z.string(), truncated: z.boolean() }),
  },
  'session.list': {
    params: z.object({ engineId: z.string().optional() }).optional(),
    result: z.object({ sessions: z.array(SessionMeta) }),
  },
  'session.search': {
    params: SessionSearchParamsSchema,
    result: SessionSearchResult,
  },
  'session.create': {
    params: z.object({
      engineId: z.string(),
      cwd: z.string().min(1).max(4096),
      workspaces: z.array(z.string().min(1).max(4096)).max(12).optional(),
      useWorktree: z.boolean().optional(),
      worktreeBranch: z.string().max(80).optional(),
      title: z.string().max(200).optional(),
      providerId: z.string().optional(),
      modelId: z.string().optional(),
      reasoningEffort: ReasoningEffort.optional(),
      mode: SessionMode.optional(),
    }),
    result: z.object({ session: SessionMeta }),
  },
  'session.prompt': {
    params: z.object({
      sessionId: z.string(),
      text: z.string().max(200_000),
      images: z.array(PromptImageAttachment).max(4).optional(),
      providerId: z.string().optional(),
      modelId: z.string().optional(),
      reasoningEffort: ReasoningEffort.optional(),
      mode: SessionMode.optional(),
    }),
    result: z.object({ turnId: z.string() }),
  },
  'session.cancel': {
    params: z.object({ sessionId: z.string() }),
    result: z.object({ ok: z.literal(true) }),
  },
  'session.cancelQueued': {
    params: SessionCancelQueuedParamsSchema,
    result: SessionCancelQueuedResultSchema,
  },
  'session.setModel': {
    params: z.object({
      sessionId: z.string(),
      providerId: z.string().optional(),
      modelId: z.string(),
      reasoningEffort: ReasoningEffort.optional(),
      mode: SessionMode.optional(),
    }),
    result: z.object({ session: SessionMeta }),
  },
  'session.rename': {
    params: z.object({ sessionId: z.string().min(1).max(256), title: z.string().max(160) }),
    result: z.object({ session: SessionMeta }),
  },
  'session.archive': {
    params: z.object({ sessionId: z.string().min(1).max(256), archived: z.boolean() }),
    result: z.object({ session: SessionMeta }),
  },
  'session.close': {
    params: z.object({ sessionId: z.string() }),
    result: z.object({ ok: z.literal(true) }),
  },
  'artifact.list': {
    params: z.object({ sessionId: z.string().min(1).max(256) }),
    result: z.object({ sessionId: z.string(), artifacts: z.array(ArtifactInfo) }),
  },
  'artifact.read': {
    params: z.object({
      sessionId: z.string().min(1).max(256),
      artifactId: z.string().min(1).max(512),
    }),
    result: ArtifactContentResult,
  },
  'stream.subscribe': {
    params: z.object({
      sessionId: z.string(),
      afterSeq: z.number().int().min(-1),
      beforeSeq: z.number().int().optional(),
      aroundSeq: z.number().int().min(0).optional(),
      limit: z.number().int().min(1).max(2000).optional(),
    }),
    result: z.object({
      session: SessionMeta,
      events: z.array(AgentEvent),
      truncated: z.boolean(),
      earliestSeq: z.number().int().optional(),
      hasMoreBefore: z.boolean().optional(),
      hasMoreAfter: z.boolean().optional(),
      latestPlan: z.array(PlanEntry).optional(),
    }),
  },
  'stream.unsubscribe': {
    params: z.object({ sessionId: z.string() }),
    result: z.object({ ok: z.literal(true) }),
  },
  'approval.respond': {
    params: z.object({ sessionId: z.string(), requestId: z.string(), optionId: z.string().nullish() }),
    result: z.object({ ok: z.literal(true) }),
  },
  'checkpoint.list': {
    params: z.object({ sessionId: z.string() }),
    result: z.object({ checkpoints: z.array(CheckpointInfo) }),
  },
  'checkpoint.create': {
    params: z.object({ sessionId: z.string(), label: z.string().max(120).optional() }),
    result: z.object({ checkpoint: CheckpointInfo }),
  },
  'checkpoint.restore': {
    params: z.object({ sessionId: z.string(), checkpointId: z.string() }),
    result: z.object({ ok: z.literal(true) }),
  },
  'usage.summary': {
    params: z
      .object({
        days: z.number().int().min(1).max(90).optional(),
        engineId: z.string().optional(),
      })
      .optional(),
    result: UsageSummaryResult,
  },
  'usage.quota': {
    params: z.object({}).optional(),
    result: UsageQuotaResult,
  },
  'usage.session': {
    params: z.object({ sessionId: z.string().min(1) }),
    result: SessionUsageResult,
  },
  'usage.proxy': {
    params: z.object({}).optional(),
    result: ProxyUsageResult,
  },
  'git.status': {
    params: z.object({ path: z.string().min(1) }),
    result: GitStatusResult,
  },
  'git.diff': {
    params: z.object({
      path: z.string().min(1),
      filePath: z.string().optional(),
      baseCommitSha: z.string().max(40).optional(),
    }),
    result: GitDiffResult,
  },
} as const

export type MethodName = keyof typeof Methods
export type MethodParams<M extends MethodName> = z.infer<(typeof Methods)[M]['params']>
export type MethodResult<M extends MethodName> = z.infer<(typeof Methods)[M]['result']>

// ---------------------------------------------------------------------------
// Notifications (Hub → App)
// ---------------------------------------------------------------------------

export const Notifications = {
  /** A new event for a subscribed session. */
  'stream.event': z.object({ event: AgentEvent }),
  /** Session metadata changed (title/state/model). Sent to all authenticated clients. */
  'session.updated': z.object({ session: SessionMeta }),
} as const

export type NotificationName = keyof typeof Notifications
export type NotificationParams<N extends NotificationName> = z.infer<(typeof Notifications)[N]>
