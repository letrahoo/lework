package dev.lework.store

import dev.lework.platform.MobileTsnetStatus
import dev.lework.platform.PairedHost
import dev.lework.platform.isMobileTsnetAvailable
import dev.lework.platform.loadActiveHostId
import dev.lework.platform.loadPairedHosts
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

data class PendingDeepLinkPairing(
    val rawUri: String,
    val hostName: String,
    val primaryUrl: String,
    val shortFingerprint: String?,
)

sealed interface ChatTimelineItem {
    val id: String

    data class User(
        override val id: String,
        val text: String,
        val imageCount: Int = 0,
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
    val loadingSessionId: String? = null,
    val transcripts: Map<String, SessionTranscript> = emptyMap(),
    val checkpoints: List<CheckpointInfo> = emptyList(),
    val currentBrowsePath: String = "",
    val dirEntries: List<FsEntry> = emptyList(),
    val openedFilePath: String? = null,
    val openedFileContent: String? = null,
    // Usage & Subscription Quota Telemetry
    val usageSelectedDays: Int = 14,
    val usageLoading: Boolean = false,
    val usageSummary: UsageSummaryResult? = null,
    val usageQuota: UsageQuotaResult? = null,
    val activeSessionUsage: SessionUsageResult? = null,
    val proxyUsage: ProxyUsageResult? = null,
    // Git Diff & Snapshot Checkpoints
    val currentDiffWorkspacePath: String = "",
    val gitLoading: Boolean = false,
    val gitStatus: GitStatusResult? = null,
    val openedDiffTitle: String? = null,
    val openedDiffContent: String? = null,
    val openedDiffTruncated: Boolean = false,
    val checkpointNotice: String? = null,
    val submittingApprovalRequestId: String? = null,
    val approvalErrorText: String? = null,
    val promptErrorText: String? = null,
    val unsentDraftToRestore: String? = null,
    // Embedded Tailscale `tsnet` mesh & multi-host asset book
    val tsnetStatus: MobileTsnetStatus = MobileTsnetStatus(available = isMobileTsnetAvailable()),
    val tsnetLoggingIn: Boolean = false,
    val pairedHosts: List<PairedHost> = loadPairedHosts(),
    val activeHostId: String? = loadActiveHostId(),
    // Composer `@file` autocomplete, image attachments & signing expiry reminder
    val composerAttachedImages: List<PromptImageAttachment> = emptyList(),
    val mentionQuery: String? = null,
    val workspaceFileSuggestions: List<String> = emptyList(),
    val signingExpiryDismissed: Boolean = false,
    val pendingDeepLinkPairing: PendingDeepLinkPairing? = null,
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
        get() {
            val target = selectedModelId?.trim().orEmpty()
            if (target.isNotEmpty()) {
                val normTarget = target.lowercase().replace('.', '-')
                availableModelsForEngine.firstOrNull {
                    it.id.equals(target, ignoreCase = true) ||
                        it.name.removeSuffix(" (Proxy)").equals(target, ignoreCase = true) ||
                        it.id.lowercase().replace('.', '-') == normTarget
                }?.let { return it }
                return EngineModelSpec(id = target, name = target)
            }
            return availableModelsForEngine.firstOrNull()
        }

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
        // A live terminal pane can only display one active `tty_perm_*` prompt at a time: replace any
        // older `tty_perm_*` entry in-place so `.firstOrNull` / `.lastOrNull` never holds a stale hash!
        val filtered = if (event.requestId.startsWith("tty_perm_")) {
            current.pendingPermissions.filterNot {
                it.requestId == event.requestId || it.requestId.startsWith("tty_perm_")
            }
        } else {
            current.pendingPermissions.filterNot { it.requestId == event.requestId }
        }
        val perms = filtered + event
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
            items.add(
                ChatTimelineItem.User(
                    id = event.messageId,
                    text = event.text,
                    imageCount = event.imageCount ?: 0,
                ),
            )
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

/**
 * Single-pass O(N) batch reducer for initial session snapshots and reverse-pagination pages.
 * Avoids O(N^2) list copies (`items.toMutableList()`) and linear `indexOfLast` scans on mobile UI threads.
 */
fun reduceTranscriptBatch(initial: SessionTranscript, events: List<AgentEvent>): SessionTranscript {
    if (events.isEmpty()) return initial

    val items = ArrayList<ChatTimelineItem>(initial.items.size + minOf(events.size, 256))
    items.addAll(initial.items)

    val reasoningIdxById = HashMap<String, Int>(64)
    val assistantIdxById = HashMap<String, Int>(64)
    val toolIdxById = HashMap<String, Int>(128)
    for (i in items.indices) {
        when (val it = items[i]) {
            is ChatTimelineItem.Reasoning -> reasoningIdxById[it.id] = i
            is ChatTimelineItem.Assistant -> assistantIdxById[it.id] = i
            is ChatTimelineItem.Tool -> toolIdxById[it.id] = i
            else -> Unit
        }
    }

    val perms = initial.pendingPermissions.toMutableList()
    var lastSeq = initial.lastSeq
    var plans = initial.planEntries
    var inTok = initial.inputTokens
    var outTok = initial.outputTokens

    for (event in events) {
        if (event is AgentEvent.PermissionRequested) {
            if (event.requestId.startsWith("tty_perm_")) {
                perms.removeAll { it.requestId == event.requestId || it.requestId.startsWith("tty_perm_") }
            } else {
                perms.removeAll { it.requestId == event.requestId }
            }
            perms.add(event)
            if (!event.requestId.startsWith("tty_perm_")) {
                lastSeq = maxOf(lastSeq, event.seq)
            }
            continue
        }
        if (event is AgentEvent.PermissionResolved) {
            perms.removeAll { it.requestId == event.requestId }
            if (!event.requestId.startsWith("tty_perm_")) {
                lastSeq = maxOf(lastSeq, event.seq)
            }
            continue
        }

        if (event.seq <= lastSeq) continue
        lastSeq = event.seq

        when (event) {
            is AgentEvent.UserMessage -> {
                items.add(
                    ChatTimelineItem.User(
                        id = event.messageId,
                        text = event.text,
                        imageCount = event.imageCount ?: 0,
                    ),
                )
            }
            is AgentEvent.ReasoningDelta -> {
                val idx = reasoningIdxById[event.messageId]
                if (idx != null) {
                    val prev = items[idx] as ChatTimelineItem.Reasoning
                    items[idx] = prev.copy(text = prev.text + event.text)
                } else {
                    reasoningIdxById[event.messageId] = items.size
                    items.add(ChatTimelineItem.Reasoning(id = event.messageId, text = event.text))
                }
            }
            is AgentEvent.AssistantDelta -> {
                val idx = assistantIdxById[event.messageId]
                if (idx != null) {
                    val prev = items[idx] as ChatTimelineItem.Assistant
                    items[idx] = prev.copy(text = prev.text + event.text)
                } else {
                    assistantIdxById[event.messageId] = items.size
                    items.add(ChatTimelineItem.Assistant(id = event.messageId, text = event.text))
                }
            }
            is AgentEvent.ToolCall -> {
                val toolItem = ChatTimelineItem.Tool(
                    id = event.toolCallId,
                    title = event.title,
                    kind = event.kind,
                    status = event.status,
                    input = event.input,
                    output = event.output,
                )
                val idx = toolIdxById[event.toolCallId]
                if (idx != null) {
                    items[idx] = toolItem
                } else {
                    toolIdxById[event.toolCallId] = items.size
                    items.add(toolItem)
                }
            }
            is AgentEvent.ToolUpdate -> {
                val idx = toolIdxById[event.toolCallId]
                if (idx != null) {
                    val prev = items[idx] as ChatTimelineItem.Tool
                    items[idx] = prev.copy(
                        title = event.title ?: prev.title,
                        status = event.status ?: prev.status,
                        output = event.output ?: prev.output,
                    )
                }
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
            is AgentEvent.PermissionRequested,
            is AgentEvent.PermissionResolved,
            is AgentEvent.SessionStateChanged,
            is AgentEvent.TurnDone -> Unit
        }
    }

    return initial.copy(
        lastSeq = lastSeq,
        items = items,
        pendingPermissions = perms,
        planEntries = plans,
        inputTokens = inTok,
        outputTokens = outTok,
    )
}

