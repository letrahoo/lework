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
export const WireApi = z.enum(['openai-chat', 'openai-responses', 'anthropic-messages', 'gemini'])

export const ProviderInfo = z.object({
  id: z.string(),
  name: z.string(),
  type: ProviderType,
  baseUrl: z.string().optional(),
  wireApi: WireApi.optional(),
  healthy: z.boolean(),
  models: z.array(z.string()),
})
export type ProviderInfo = z.infer<typeof ProviderInfo>

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

export const SessionMeta = z.object({
  id: z.string(),
  engineId: z.string(),
  cwd: z.string(),
  workspaces: z.array(z.string()).optional(),
  title: z.string(),
  state: SessionState,
  createdAt: z.number().int(),
  updatedAt: z.number().int(),
  lastSeq: z.number().int(),
  model: z.string().optional(),
  reasoningEffort: ReasoningEffort.optional(),
  mode: SessionMode.optional(),
  source: z.enum(['hub', 'host']).optional(),
  liveStatusText: z.string().optional(),
  turnStartedAt: z.number().int().optional(),
  queuedPrompts: z.array(z.string()).optional(),
  models: z.array(ModelOption).optional(),
})
export type SessionMeta = z.infer<typeof SessionMeta>

export const PermissionOptionKind = z.enum(['allow_once', 'allow_always', 'reject_once', 'reject_always'])
export const PermissionOption = z.object({
  id: z.string(),
  name: z.string(),
  kind: PermissionOptionKind,
})
export type PermissionOption = z.infer<typeof PermissionOption>

export const ToolKind = z.enum(['read', 'edit', 'delete', 'move', 'search', 'execute', 'think', 'fetch', 'other'])
export const ToolStatus = z.enum(['pending', 'in_progress', 'completed', 'failed'])

export const PlanEntry = z.object({
  content: z.string(),
  status: z.enum(['pending', 'in_progress', 'completed']),
})

// ---------------------------------------------------------------------------
// Agent events (normalized across engines)
// ---------------------------------------------------------------------------

const base = { seq: z.number().int().nonnegative(), sessionId: z.string(), ts: z.number().int() }

export const AgentEvent = z.discriminatedUnion('type', [
  z.object({ ...base, type: z.literal('session.state'), state: SessionState, detail: z.string().optional() }),
  z.object({ ...base, type: z.literal('message.user'), messageId: z.string(), text: z.string() }),
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

export const Methods = {
  'hub.hello': {
    params: z.object({ protocolVersion: z.number().int(), token: z.string().min(8).max(1024), client: ClientInfo }),
    result: z.object({
      protocolVersion: z.number().int(),
      hub: HubInfo,
      capabilities: z.array(z.string()),
      /** Alternate addresses of this Hub (relays); only revealed after authentication. */
      relayUrls: z.array(z.string()).optional(),
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
  'engine.list': {
    params: z.object({}).optional(),
    result: z.object({ engines: z.array(EngineInfo) }),
  },
  'workspace.list': {
    params: z.object({}).optional(),
    result: z.object({ workspaces: z.array(Workspace) }),
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
  'session.create': {
    params: z.object({
      engineId: z.string(),
      cwd: z.string().min(1).max(4096),
      workspaces: z.array(z.string().min(1).max(4096)).max(12).optional(),
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
      text: z.string().min(1).max(200_000),
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
  'session.setModel': {
    params: z.object({ sessionId: z.string(), modelId: z.string() }),
    result: z.object({ session: SessionMeta }),
  },
  'session.close': {
    params: z.object({ sessionId: z.string() }),
    result: z.object({ ok: z.literal(true) }),
  },
  'stream.subscribe': {
    params: z.object({
      sessionId: z.string(),
      afterSeq: z.number().int().min(-1),
      beforeSeq: z.number().int().optional(),
      limit: z.number().int().min(1).max(2000).optional(),
    }),
    result: z.object({
      session: SessionMeta,
      events: z.array(AgentEvent),
      truncated: z.boolean(),
      earliestSeq: z.number().int().optional(),
      hasMoreBefore: z.boolean().optional(),
    }),
  },
  'stream.unsubscribe': {
    params: z.object({ sessionId: z.string() }),
    result: z.object({ ok: z.literal(true) }),
  },
  'approval.respond': {
    params: z.object({ sessionId: z.string(), requestId: z.string(), optionId: z.string().nullable() }),
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
