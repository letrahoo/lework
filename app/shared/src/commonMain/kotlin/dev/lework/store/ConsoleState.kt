package dev.lework.store

import dev.lework.platform.loadSavedAuthUserName
import dev.lework.protocol.*

enum class ConnectionStatus {
    DISCOVERING,
    NEEDS_AUTH,
    CONNECTING,
    RECONNECTING,
    CONNECTED,
    ERROR,
}

sealed interface ChatTimelineItem {
    val id: String

    data class User(
        override val id: String,
        val text: String,
    ) : ChatTimelineItem

    data class Reasoning(
        override val id: String,
        val text: String,
    ) : ChatTimelineItem

    data class Assistant(
        override val id: String,
        val text: String,
    ) : ChatTimelineItem

    data class Tool(
        override val id: String,
        val title: String,
        val kind: ToolKind,
        val status: ToolStatus,
        val input: String? = null,
        val output: String? = null,
    ) : ChatTimelineItem

    data class Notice(
        override val id: String,
        val text: String,
        val isError: Boolean = false,
    ) : ChatTimelineItem
}

data class SessionTranscript(
    val earliestSeq: Int? = null,
    val lastSeq: Int = -1,
    val hasMoreBefore: Boolean = false,
    val loadingOlder: Boolean = false,
    val items: List<ChatTimelineItem> = emptyList(),
    val pendingPermissions: List<AgentEvent.PermissionRequested> = emptyList(),
    val planEntries: List<PlanEntry> = emptyList(),
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
)

internal fun parseEffort(raw: String): ReasoningEffort = when (raw.lowercase()) {
    "low" -> ReasoningEffort.LOW
    "high" -> ReasoningEffort.HIGH
    "xhigh" -> ReasoningEffort.XHIGH
    "max" -> ReasoningEffort.MAX
    else -> ReasoningEffort.MEDIUM
}

internal fun effortToWire(e: ReasoningEffort): String = when (e) {
    ReasoningEffort.LOW -> "low"
    ReasoningEffort.MEDIUM -> "medium"
    ReasoningEffort.HIGH -> "high"
    ReasoningEffort.XHIGH -> "xhigh"
    ReasoningEffort.MAX -> "max"
}

internal fun parseMode(raw: String): SessionMode = when (raw.lowercase()) {
    "auto" -> SessionMode.AUTO
    "acceptedits", "auto-edit", "auto_edit" -> SessionMode.ACCEPT_EDITS
    "plan" -> SessionMode.PLAN
    "bypasspermissions", "yolo" -> SessionMode.BYPASS_PERMISSIONS
    "default", "manual" -> SessionMode.DEFAULT
    // Legacy `"ask"` was written by older builds as hardcoded default; upgrade to computer default (`AUTO`)
    else -> SessionMode.AUTO
}

internal fun modeToWire(m: SessionMode): String = when (m) {
    SessionMode.DEFAULT -> "default"
    SessionMode.ACCEPT_EDITS -> "acceptEdits"
    SessionMode.PLAN -> "plan"
    SessionMode.AUTO -> "auto"
    SessionMode.BYPASS_PERMISSIONS -> "bypassPermissions"
}

data class ConsoleUiState(
    val hasEnteredWorkspace: Boolean = false,
    val discoveredBaseUrl: String = "",
    val discoveredWsUrl: String = "",
    val oauthEnabled: Boolean = false,
    val oauthProvider: String? = null,
    val oauthDisplayName: String? = null,
    val authUserName: String? = loadSavedAuthUserName(),
    val oauthChallenge: OAuthStartResult? = null,
    val authStatusText: String = "正在发现可用主机节点…",
    val connectionStatus: ConnectionStatus = ConnectionStatus.DISCOVERING,
    /** Short SHA-256 of the pinned Hub certificate, for out-of-band comparison with `hub pair`. */
    val hubFingerprint: String? = null,
    val connectionError: String? = null,
    val hub: HubInfo? = null,
    val slaves: List<SlaveInfo> = emptyList(),
    val engines: List<EngineInfo> = emptyList(),
    val providers: List<ProviderInfo> = emptyList(),
    val workspaces: List<Workspace> = emptyList(),
    val selectedEngineId: String = "claude",
    val selectedProviderId: String? = null,
    val selectedModelId: String? = null,
    val selectedEffort: ReasoningEffort = ReasoningEffort.MEDIUM,
    val selectedMode: SessionMode = SessionMode.AUTO,
    val selectedWorkspacePath: String = "",
    val selectedExtraWorkspaces: List<String> = emptyList(),
    val drawerEngineFilter: String? = null,
    val sessions: List<SessionMeta> = emptyList(),
    val activeSessionId: String? = null,
    val transcripts: Map<String, SessionTranscript> = emptyMap(),
    val checkpoints: List<CheckpointInfo> = emptyList(),
    val currentBrowsePath: String = "",
    val dirEntries: List<FsEntry> = emptyList(),
    val openedFilePath: String? = null,
    val openedFileContent: String? = null,
) {
    val activeSession: SessionMeta?
        get() = sessions.firstOrNull { it.id == activeSessionId }

    val activeTranscript: SessionTranscript
        get() = activeSessionId?.let { transcripts[it] } ?: SessionTranscript()

    val selectedEngine: EngineInfo?
        get() = engines.firstOrNull { it.id == selectedEngineId }

    val availableModelsForEngine: List<EngineModelSpec>
        get() = selectedEngine?.models.orEmpty()

    val selectedModelSpec: EngineModelSpec?
        get() = availableModelsForEngine.firstOrNull { it.id == selectedModelId }
            ?: availableModelsForEngine.firstOrNull()

    val availableEfforts: List<ReasoningEffort>
        get() = selectedModelSpec?.efforts?.ifEmpty { null }
            ?: listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH)

    val availableModes: List<SessionMode>
        get() = selectedEngine?.supportedModes?.ifEmpty { null }
            ?: listOf(SessionMode.AUTO, SessionMode.ACCEPT_EDITS, SessionMode.PLAN, SessionMode.DEFAULT)

    val activeWorkspaces: List<String>
        get() {
            val sess = activeSession
            return if (sess != null) {
                val fromMeta = sess.workspaces?.filter { it.isNotBlank() }.orEmpty()
                if (fromMeta.isNotEmpty()) fromMeta.distinct()
                else listOfNotNull(sess.cwd.takeIf { it.isNotBlank() })
            } else {
                (listOf(selectedWorkspacePath) + selectedExtraWorkspaces)
                    .filter { it.isNotBlank() }
                    .distinct()
            }
        }

    val workspaceDisplayName: String
        get() {
            val list = activeWorkspaces
            val primary = list.firstOrNull().orEmpty()
            if (primary.isBlank()) return "选择工作区"
            val shortName = primary.trimEnd('/').substringAfterLast('/').ifBlank { primary }
            return if (list.size > 1) "$shortName +${list.size - 1}" else shortName
        }

    val runningSessions: List<SessionMeta>
        get() = sessions
            .filter {
                it.state == SessionState.RUNNING || it.state == SessionState.WAITING_PERMISSION
            }
            .sortedBy { if (it.state == SessionState.WAITING_PERMISSION) 0 else 1 }

    val filteredHistorySessions: List<SessionMeta>
        get() {
            val base = if (drawerEngineFilter == null) sessions
            else sessions.filter { it.engineId == drawerEngineFilter }
            return base.filterNot {
                it.state == SessionState.RUNNING || it.state == SessionState.WAITING_PERMISSION
            }
        }
}

fun reduceTranscript(current: SessionTranscript, event: AgentEvent): SessionTranscript {
    if (event is AgentEvent.PermissionRequested) {
        val perms = if (current.pendingPermissions.none { it.requestId == event.requestId }) {
            current.pendingPermissions + event
        } else {
            current.pendingPermissions
        }
        val nextSeq = if (event.requestId.startsWith("tty_perm_")) {
            current.lastSeq
        } else {
            maxOf(current.lastSeq, event.seq)
        }
        return current.copy(lastSeq = nextSeq, pendingPermissions = perms)
    }
    if (event is AgentEvent.PermissionResolved) {
        val perms = current.pendingPermissions.filterNot { it.requestId == event.requestId }
        val nextSeq = if (event.requestId.startsWith("tty_perm_")) {
            current.lastSeq
        } else {
            maxOf(current.lastSeq, event.seq)
        }
        return current.copy(lastSeq = nextSeq, pendingPermissions = perms)
    }

    if (event.seq <= current.lastSeq) return current

    val items = current.items.toMutableList()
    val perms = current.pendingPermissions.toMutableList()
    var plans = current.planEntries
    var inTok = current.inputTokens
    var outTok = current.outputTokens

    when (event) {
        is AgentEvent.UserMessage -> {
            items.add(ChatTimelineItem.User(id = event.messageId, text = event.text))
        }
        is AgentEvent.ReasoningDelta -> {
            val idx = items.indexOfLast { it is ChatTimelineItem.Reasoning && it.id == event.messageId }
            if (idx >= 0) {
                val prev = items[idx] as ChatTimelineItem.Reasoning
                items[idx] = prev.copy(text = prev.text + event.text)
            } else {
                items.add(ChatTimelineItem.Reasoning(id = event.messageId, text = event.text))
            }
        }
        is AgentEvent.AssistantDelta -> {
            val idx = items.indexOfLast { it is ChatTimelineItem.Assistant && it.id == event.messageId }
            if (idx >= 0) {
                val prev = items[idx] as ChatTimelineItem.Assistant
                items[idx] = prev.copy(text = prev.text + event.text)
            } else {
                items.add(ChatTimelineItem.Assistant(id = event.messageId, text = event.text))
            }
        }
        is AgentEvent.ToolCall -> {
            val idx = items.indexOfLast { it is ChatTimelineItem.Tool && it.id == event.toolCallId }
            val toolItem = ChatTimelineItem.Tool(
                id = event.toolCallId,
                title = event.title,
                kind = event.kind,
                status = event.status,
                input = event.input,
                output = event.output,
            )
            if (idx >= 0) items[idx] = toolItem else items.add(toolItem)
        }
        is AgentEvent.ToolUpdate -> {
            val idx = items.indexOfLast { it is ChatTimelineItem.Tool && it.id == event.toolCallId }
            if (idx >= 0) {
                val prev = items[idx] as ChatTimelineItem.Tool
                items[idx] = prev.copy(
                    title = event.title ?: prev.title,
                    status = event.status ?: prev.status,
                    output = event.output ?: prev.output,
                )
            }
        }
        is AgentEvent.PermissionRequested -> {
            if (perms.none { it.requestId == event.requestId }) {
                perms.add(event)
            }
        }
        is AgentEvent.PermissionResolved -> {
            perms.removeAll { it.requestId == event.requestId }
        }
        is AgentEvent.PlanUpdated -> {
            plans = event.entries
        }
        is AgentEvent.Usage -> {
            inTok += event.inputTokens ?: 0
            outTok += event.outputTokens ?: 0
        }
        is AgentEvent.ErrorEvent -> {
            items.add(
                ChatTimelineItem.Notice(
                    id = "err_${event.seq}",
                    text = event.message,
                    isError = true,
                ),
            )
        }
        is AgentEvent.SessionStateChanged,
        is AgentEvent.TurnDone -> Unit
    }

    return current.copy(
        lastSeq = event.seq,
        items = items,
        pendingPermissions = perms,
        planEntries = plans,
        inputTokens = inTok,
        outputTokens = outTok,
    )
}

