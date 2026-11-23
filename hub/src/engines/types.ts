import type {
  AgentEventBody,
  EngineCapabilities,
  EngineInfo,
  EngineStatus,
  ModelOption,
  PromptImageAttachment,
  ReasoningEffort,
  SessionMode,
} from '@lework/protocol'

export type EmitAgentEvent = (body: AgentEventBody) => void

export interface StagedPromptImage extends PromptImageAttachment {
  stagedPath: string
  previewBase64?: string
}

export interface EngineSessionInit {
  sessionId: string
  cwd: string
  providerId?: string
  modelId?: string
  reasoningEffort?: ReasoningEffort
  mode?: SessionMode
  resumeNativeId?: string
  env?: NodeJS.ProcessEnv
  emit: EmitAgentEvent
  onModelsChanged?: (currentModel: string | undefined, models: ModelOption[]) => void
  onNativeSessionLinked?: (nativeId: string) => void
}

export interface EngineSession {
  readonly sessionId: string
  readonly cwd: string
  prompt(turnId: string, text: string, images?: StagedPromptImage[]): Promise<void>
  cancel(): Promise<void>
  respondPermission(requestId: string, optionId: string | null): Promise<void>
  setModel?(modelId: string): Promise<void>
  setEffort?(effort: ReasoningEffort): Promise<void>
  setMode?(mode: SessionMode): Promise<void>
  close(): Promise<void>
}

export interface EngineAdapter {
  readonly id: string
  readonly displayName: string
  readonly kind: EngineInfo['kind']
  probe(): Promise<EngineStatus>
  capabilities(): EngineCapabilities
  openSession(init: EngineSessionInit): Promise<EngineSession>
}
