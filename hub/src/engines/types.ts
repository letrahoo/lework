import type {
  AgentEventBody,
  EngineCapabilities,
  EngineInfo,
  EngineStatus,
  ModelOption,
  PromptFileAttachment,
  PromptImageAttachment,
  ReasoningEffort,
  SessionMode,
  SlashCommandInfo,
} from '@lework/protocol'

export type EmitAgentEvent = (body: AgentEventBody) => void

export interface StagedPromptImage extends PromptImageAttachment {
  stagedPath: string
  previewBase64?: string
}

export interface StagedPromptFile extends PromptFileAttachment {
  stagedPath: string
  sizeBytes: number
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
  onSlashCommandsChanged?: (commands: SlashCommandInfo[]) => void
  onModeChanged?: (mode: SessionMode) => void
  onSubagentsHint?: () => void
}

export interface EngineSession {
  readonly sessionId: string
  readonly cwd: string
  prompt(
    turnId: string,
    text: string,
    images?: StagedPromptImage[],
    files?: StagedPromptFile[],
  ): Promise<void>
  cancel(): Promise<void>
  respondPermission(requestId: string, optionId: string | null, customInput?: string): Promise<void>
  setModel?(modelId: string): Promise<void>
  setEffort?(effort: ReasoningEffort): Promise<void>
  setMode?(mode: SessionMode): Promise<void>
  isAlive?(): boolean
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
