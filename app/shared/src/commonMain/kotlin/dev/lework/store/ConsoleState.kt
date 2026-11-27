package dev.lework.store

import dev.lework.platform.MobileTsnetStatus
import dev.lework.platform.PairedHost
import dev.lework.platform.isMobileTsnetAvailable
import dev.lework.platform.listSshConfigHosts
import dev.lework.platform.loadActiveHostId
import dev.lework.platform.loadPairedHosts
import dev.lework.platform.loadSavedAuthUserName
import dev.lework.protocol.*

internal fun isExternalProviderId(providerId: String?): Boolean {
    val clean = providerId?.trim()
    return !clean.isNullOrEmpty() && !clean.startsWith("sub_")
}

internal fun EngineModelSpec.isExternalProvider(): Boolean = isExternalProviderId(providerId)

internal fun normalizeModelKey(modelId: String): String =
    modelId.trim().lowercase().replace('.', '-')

internal fun favoriteModelKey(engineId: String, providerId: String?, modelId: String): String =
    "${engineId.trim()}:${providerId?.trim().orEmpty()}:${modelId.trim()}"

internal fun isFavoriteModel(
    favSet: Set<String>,
    engineId: String,
    providerId: String?,
    modelId: String,
): Boolean {
    val exact = favoriteModelKey(engineId, providerId, modelId)
    if (exact in favSet) return true
    val legacy = "${engineId.trim()}:${modelId.trim()}"
    // Legacy 2-part favorite keys only match native entries so native & external same-name models don't collide.
    return !isExternalProviderId(providerId) && legacy in favSet
}

internal fun EngineModelSpec.externalProviderLabel(providers: List<ProviderInfo> = emptyList()): String? {
    if (!isExternalProvider()) return null
    val explicit = providerName?.trim()?.takeIf { it.isNotEmpty() }
    if (explicit != null) return explicit
    val fromList = providers.firstOrNull { it.id == providerId }?.name?.trim()?.takeIf { it.isNotEmpty() }
    if (fromList != null) return fromList
    return if (providerId == "cpa_local") "CPA" else "代理"
}

/**
 * Source badge policy:
 * - External providers ALWAYS display their service short label (e.g. `CPA`, `OpenRouter`).
 * - Native subscription models display `官方` ONLY when another provider in the same engine exposes a same-name model.
 */
internal fun EngineModelSpec.sourceBadgeLabel(
    allEngineModels: List<EngineModelSpec>,
    providers: List<ProviderInfo> = emptyList(),
): String? {
    if (isExternalProvider()) {
        return externalProviderLabel(providers)
    }
    val myNorm = normalizeModelKey(id)
    val hasSameNameCollision = allEngineModels.any { other ->
        other.providerId != providerId && normalizeModelKey(other.id) == myNorm
    }
    return if (hasSameNameCollision) "官方" else null
}

internal val EngineInfo.canStartTask: Boolean
    get() = id != "mock" && status.installed && (status.ready || status.authStatus == "unknown")

internal val EngineInfo.readinessLabel: String
    get() = when {
        status.authStatus == "required" && status.ready -> "外部模型服务可用 · 主机 CLI 需授权"
        status.authStatus == "unknown" && status.ready -> "外部模型服务可用 · CLI 登录状态未知"
        status.ready -> "可用"
        status.authStatus == "unknown" -> "登录状态未知"
        status.authStatus == "required" -> "需在主机上授权"
        else -> "当前不可用"
    }

/** Do not silently replace native CLI config with an external proxy model just because a proxy is online. */
internal fun EngineInfo.draftDefaultModel(): EngineModelSpec? =
    models.firstOrNull { it.id == defaultModel && !isExternalProviderId(it.providerId) }
        ?: models.firstOrNull { !isExternalProviderId(it.providerId) }
        ?: models.firstOrNull()?.takeIf { id == "pi" || status.authStatus == "required" }

internal fun List<EngineInfo>.draftEngine(preferred: String): EngineInfo? =
    firstOrNull { it.id == preferred && it.canStartTask } ?: firstOrNull { it.canStartTask && it.status.ready }
        ?: firstOrNull { it.canStartTask }

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

data class DiscoveredTsnetHub(
    val hub: HubInfo,
    val baseUrl: String,
    val wsUrl: String,
    val hostName: String,
    val tailscaleIp: String,
    val os: String? = null,
    val tailscaleSameUser: Boolean,
    val tailscaleUser: String? = null,
    val oauthEnabled: Boolean = false,
    val oauthProvider: String? = null,
    val oauthDisplayName: String? = null,
    val alreadyPaired: Boolean = false,
    val isCurrentActive: Boolean = false,
)

sealed interface ChatTimelineItem {
    val id: String
    val ts: Long
    val seq: Int

    data class User(
        override val id: String,
        val text: String,
        val imageCount: Int = 0,
        val imagePreviews: List<String> = emptyList(),
        override val ts: Long = 0L,
        override val seq: Int = -1,
    ) : ChatTimelineItem

    data class Reasoning(
        override val id: String,
        val text: String,
        override val ts: Long = 0L,
        override val seq: Int = -1,
    ) : ChatTimelineItem

    data class Assistant(
        override val id: String,
        val text: String,
        override val ts: Long = 0L,
        override val seq: Int = -1,
    ) : ChatTimelineItem

    data class Tool(
        override val id: String,
        val title: String,
        val kind: ToolKind,
        val status: ToolStatus,
        val input: String? = null,
        val output: String? = null,
        override val ts: Long = 0L,
        override val seq: Int = -1,
    ) : ChatTimelineItem

    data class Notice(
        override val id: String,
        val text: String,
        val isError: Boolean = false,
        val repeatCount: Int = 1,
        override val ts: Long = 0L,
        override val seq: Int = -1,
    ) : ChatTimelineItem
}

data class TurnRecoveryBannerState(
    val sessionId: String?,
    val isNetworkError: Boolean,
    val errorCount: Int = 1,
    val headline: String,
    val detail: String,
    val failedPromptText: String? = null,
    val failedImages: List<PromptImageAttachment> = emptyList(),
    val canResumePreviousTurn: Boolean = true,
    val lastErrorAtMs: Long = 0L,
)

private val NETWORK_ERROR_PATTERNS = listOf(
    Regex("\\bECONNRESET\\b", RegexOption.IGNORE_CASE),
    Regex("\\bECONNREFUSED\\b", RegexOption.IGNORE_CASE),
    Regex("\\bECONNABORTED\\b", RegexOption.IGNORE_CASE),
    Regex("\\bENOTFOUND\\b", RegexOption.IGNORE_CASE),
    Regex("\\bETIMEDOUT\\b", RegexOption.IGNORE_CASE),
    Regex("\\bEAI_AGAIN\\b", RegexOption.IGNORE_CASE),
    Regex("\\bEHOSTUNREACH\\b", RegexOption.IGNORE_CASE),
    Regex("\\bENETUNREACH\\b", RegexOption.IGNORE_CASE),
    Regex("\\bENETDOWN\\b", RegexOption.IGNORE_CASE),
    Regex("\\bENOTCONN\\b", RegexOption.IGNORE_CASE),
    Regex("\\bEPIPE\\b", RegexOption.IGNORE_CASE),
    Regex("\\bUND_ERR_[A-Z0-9_]+\\b", RegexOption.IGNORE_CASE),
    Regex("\\bERR_SOCKET_[A-Z0-9_]+\\b", RegexOption.IGNORE_CASE),
    Regex("\\bERR_NETWORK\\b", RegexOption.IGNORE_CASE),
    Regex("\\bERR_TLS_[A-Z0-9_]+\\b", RegexOption.IGNORE_CASE),
    Regex("fetch failed", RegexOption.IGNORE_CASE),
    Regex("socket hang up", RegexOption.IGNORE_CASE),
    Regex("connection\\s*(refused|reset|closed|aborted|lost|dropped|timed out|failed|error)", RegexOption.IGNORE_CASE),
    Regex("network\\s*(error|is unreachable|unreachable|changed|offline|down|timeout|failed)", RegexOption.IGNORE_CASE),
    Regex("api\\s*connection\\s*error|apiconnectionerror", RegexOption.IGNORE_CASE),
    Regex("tls\\s*handshake", RegexOption.IGNORE_CASE),
    Regex("timed\\s*out|\\btimeout\\b", RegexOption.IGNORE_CASE),
    Regex("\\boffline\\b", RegexOption.IGNORE_CASE),
    Regex("\\bdns\\b", RegexOption.IGNORE_CASE),
    Regex("\\b(502|503|504)\\b"),
    Regex("bad gateway|service unavailable|gateway timeout", RegexOption.IGNORE_CASE),
    Regex("unable to resolve host|getaddrinfo", RegexOption.IGNORE_CASE),
    Regex("no route to host", RegexOption.IGNORE_CASE),
    Regex("\\beof\\b", RegexOption.IGNORE_CASE),
    Regex("broken pipe", RegexOption.IGNORE_CASE),
    Regex("premature close", RegexOption.IGNORE_CASE),
    Regex("unresolvedaddress|connecttimeout|sockettimeout|closedchannel|websocket.*(closed|disconnected|not connected|failed)|connection abort|nsurlerrordomain", RegexOption.IGNORE_CASE),
    Regex("断网|网络|连接断开|连接超时|连接中断|超时|不可达|无法连接"),
)

internal fun isLikelyNetworkError(message: String?): Boolean {
    val text = message?.trim().orEmpty()
    if (text.isEmpty()) return false
    return NETWORK_ERROR_PATTERNS.any { it.containsMatchIn(text) }
}

data class SessionTranscript(
    val earliestSeq: Int? = null,
    val lastSeq: Int = -1,
    val hasMoreBefore: Boolean = false,
    val hasMoreAfter: Boolean = false,
    val loadingOlder: Boolean = false,
    val items: List<ChatTimelineItem> = emptyList(),
    val pendingPermissions: List<AgentEvent.PermissionRequested> = emptyList(),
    val planEntries: List<PlanEntry> = emptyList(),
    val planUpdatedAt: Long = 0L,
    val planTurnEnded: Boolean = false,
    val planSeenPostUpdateUserTurn: Boolean = false,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
)

internal fun PlanEntry.isTerminalStatus(): Boolean =
    status == "completed" || status == "skipped" || status == "failed"

internal fun SessionState.isInactiveTurnState(): Boolean =
    this == SessionState.IDLE || this == SessionState.ERROR || this == SessionState.CLOSED

internal fun SessionTranscript.syncPlanLifecycleWithSessionState(
    sessionState: SessionState,
): SessionTranscript {
    if (!sessionState.isInactiveTurnState() || planEntries.isEmpty()) return this
    if (planSeenPostUpdateUserTurn) {
        return copy(
            planEntries = emptyList(),
            planUpdatedAt = 0L,
            planTurnEnded = false,
            planSeenPostUpdateUserTurn = false,
        )
    }
    return if (planTurnEnded) this else copy(planTurnEnded = true)
}

internal fun SessionTranscript.clearStalePlanBeforeNewPrompt(): SessionTranscript {
    if (planEntries.isEmpty()) return this
    val shouldClear = planEntries.all { it.isTerminalStatus() } || planTurnEnded || planSeenPostUpdateUserTurn
    if (!shouldClear) return this
    return copy(
        planEntries = emptyList(),
        planUpdatedAt = 0L,
        planTurnEnded = false,
        planSeenPostUpdateUserTurn = false,
    )
}

internal fun SessionTranscript.withFallbackLatestPlan(
    latestPlan: List<PlanEntry>?,
    fallbackTs: Long = 0L,
    sessionState: SessionState? = null,
): SessionTranscript {
    val base = if (planEntries.isEmpty() && !latestPlan.isNullOrEmpty()) {
        copy(
            planEntries = latestPlan,
            planUpdatedAt = fallbackTs,
            planTurnEnded = sessionState?.isInactiveTurnState() == true,
            planSeenPostUpdateUserTurn = false,
        )
    } else {
        this
    }
    return if (sessionState != null) base.syncPlanLifecycleWithSessionState(sessionState) else base
}

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

internal fun reasoningEffortOrder(effort: ReasoningEffort): Int = when (effort) {
    ReasoningEffort.LOW -> 0
    ReasoningEffort.MEDIUM -> 1
    ReasoningEffort.HIGH -> 2
    ReasoningEffort.XHIGH -> 3
    ReasoningEffort.MAX -> 4
}

internal fun sortedReasoningEfforts(efforts: List<ReasoningEffort>): List<ReasoningEffort> =
    efforts.distinct().sortedBy(::reasoningEffortOrder)

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
    val providersBusy: Boolean = false,
    val providerProbingId: String? = null,
    val providerDraftProbeResult: ProviderInfo? = null,
    val providerActionError: String? = null,
    val providerActionNotice: String? = null,
    val workspaces: List<Workspace> = emptyList(),
    val selectedEngineId: String = "claude",
    val selectedProviderId: String? = null,
    val selectedModelId: String? = null,
    val selectedEffort: ReasoningEffort = ReasoningEffort.MEDIUM,
    val selectedMode: SessionMode = SessionMode.AUTO,
    val favoriteModelKeys: List<String> = emptyList(),
    val pinnedSessionIds: List<String> = emptyList(),
    val selectedWorkspacePath: String = "",
    val selectedExtraWorkspaces: List<String> = emptyList(),
    val draftUseWorktree: Boolean = false,
    val draftWorktreeBranch: String = "",
    val drawerEngineFilter: String? = null,
    val drawerShowArchived: Boolean = false,
    val drawerSearchQuery: String = "",
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
    val promptNoticeText: String? = null,
    val turnRecoveryBanner: TurnRecoveryBannerState? = null,
    val retryingTurn: Boolean = false,
    val unsentDraftToRestore: String? = null,
    // Embedded Tailscale `tsnet` mesh & multi-host asset book
    val tsnetStatus: MobileTsnetStatus = MobileTsnetStatus(available = isMobileTsnetAvailable()),
    val tsnetLoggingIn: Boolean = false,
    val discoveredTsnetHubs: List<DiscoveredTsnetHub> = emptyList(),
    val connectingTsnetHubId: String? = null,
    val pairedHosts: List<PairedHost> = loadPairedHosts(),
    val activeHostId: String? = loadActiveHostId(),
    // Composer `@file` autocomplete, image attachments & signing expiry reminder
    val composerAttachedImages: List<PromptImageAttachment> = emptyList(),
    val mentionQuery: String? = null,
    val workspaceFileSuggestions: List<String> = emptyList(),
    val signingExpiryDismissed: Boolean = false,
    val pendingDeepLinkPairing: PendingDeepLinkPairing? = null,
    val sshPairingInProgress: Boolean = false,
    val sshConfigHosts: List<String> = listSshConfigHosts(),
    // Live SubAgents Drill-Down Inspector
    val inspectedSubagent: SubagentSummary? = null,
    val inspectedSubagentTranscript: SessionTranscript = SessionTranscript(),
    val inspectedSubagentLoading: Boolean = false,
    val inspectedSubagentError: String? = null,
    // Unified Per-Engine Artifacts & Proposed Plan Closure
    val activeSessionArtifacts: List<ArtifactInfo> = emptyList(),
    val artifactsLoading: Boolean = false,
    val openedArtifact: ArtifactContentResult? = null,
    val artifactError: String? = null,
    // Desktop Global Full-Text Search (⇧⌘F) & In-Session Find (⌘F)
    val searchQuery: String = "",
    val searchEngineFilter: String? = null,
    val searchRoleFilter: SearchRoleFilter = SearchRoleFilter.ALL,
    val searchScopeCurrentWorkspace: Boolean = false,
    val searchIncludeArchived: Boolean = false,
    val searchLoading: Boolean = false,
    val searchError: String? = null,
    val searchHits: List<SessionSearchHit> = emptyList(),
    val searchIndexedSessions: Int = 0,
    val searchTotalSessions: Int = 0,
    val searchIndexing: Boolean = false,
    val pendingJumpSeq: Int? = null,
    val pendingJumpNonce: Long = 0L,
    val highlightedSeq: Int? = null,
    val highlightedQuery: String? = null,
    val inSessionFindOpen: Boolean = false,
    val inSessionFindQuery: String = "",
    val inSessionFindMatches: List<SessionSearchMatch> = emptyList(),
    val inSessionFindActiveIndex: Int = 0,
    val inSessionFindLoading: Boolean = false,
) {
    val activeHostDisplayName: String
        get() = pairedHosts.firstOrNull { it.hubId == hub?.id }?.displayName
            ?: hub?.name
            ?: "本机工作站"

    val activeSession: SessionMeta?
        get() = sessions.firstOrNull { it.id == activeSessionId }

    val activeSubagents: List<SubagentSummary>
        get() = activeSession?.subagents.orEmpty()

    val inspectedSubagentEvents: List<ChatTimelineItem>
        get() = inspectedSubagentTranscript.items

    val activeTranscript: SessionTranscript
        get() = activeSessionId?.let { transcripts[it] } ?: SessionTranscript()

    val taskEngines: List<EngineInfo>
        get() = if (connectionStatus == ConnectionStatus.CONNECTED) engines.filter { it.id != "mock" && it.status.installed } else emptyList()

    val externalProviders: List<ProviderInfo>
        get() = providers.filter { it.type == ProviderType.ENDPOINT }

    val selectedEngine: EngineInfo?
        get() = engines.firstOrNull { it.id == selectedEngineId }

    val availableModelsForEngine: List<EngineModelSpec>
        get() = selectedEngine?.models.orEmpty()

    val selectedModelSpec: EngineModelSpec?
        get() {
            val target = selectedModelId?.trim().orEmpty()
            val targetProvider = selectedProviderId?.trim()?.takeIf { it.isNotEmpty() }
            if (target.isNotEmpty()) {
                val normTarget = normalizeModelKey(target)
                fun matchesId(spec: EngineModelSpec): Boolean =
                    spec.id.equals(target, ignoreCase = true) ||
                        spec.name.removeSuffix(" (Proxy)").equals(target, ignoreCase = true) ||
                        normalizeModelKey(spec.id) == normTarget

                if (targetProvider != null) {
                    availableModelsForEngine.firstOrNull {
                        it.providerId == targetProvider && matchesId(it)
                    }?.let { return it }
                }
                // Prefer native model over external proxy if providerId wasn't specified explicitly
                availableModelsForEngine.firstOrNull {
                    !isExternalProviderId(it.providerId) && matchesId(it)
                }?.let { return it }
                availableModelsForEngine.firstOrNull { matchesId(it) }?.let { return it }
                val fallbackProviderName = targetProvider?.let { pid ->
                    providers.firstOrNull { it.id == pid }?.name
                }
                return EngineModelSpec(
                    id = target,
                    name = target,
                    providerId = targetProvider,
                    providerName = fallbackProviderName,
                )
            }
            return selectedEngine?.draftDefaultModel()
        }

    val availableEfforts: List<ReasoningEffort>
        get() = selectedModelSpec?.efforts
            ?.let(::sortedReasoningEfforts)
            ?.ifEmpty { null }
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
            val activeWt = activeSession?.worktree
            if (activeWt != null) {
                val repoName = activeWt.baseRepoPath.trimEnd('/').substringAfterLast('/').ifBlank { "repo" }
                val shortBranch = activeWt.branch.removePrefix("lework/")
                return "$repoName · $shortBranch"
            }
            val list = activeWorkspaces
            val primary = list.firstOrNull().orEmpty()
            if (primary.isBlank()) return "选择工作区"
            val shortName = primary.trimEnd('/').substringAfterLast('/').ifBlank { primary }
            val multiLabel = if (list.size > 1) "$shortName +${list.size - 1}" else shortName
            return if (activeSession == null && draftUseWorktree) {
                val customBranch = draftWorktreeBranch.trim().removePrefix("lework/")
                if (customBranch.isNotEmpty()) "$multiLabel · $customBranch" else "$multiLabel · 独立分支"
            } else {
                multiLabel
            }
        }

    private fun SessionMeta.matchesDrawerQuery(rawQuery: String): Boolean {
        val q = rawQuery.trim().lowercase()
        if (q.isEmpty()) return true
        if (title.lowercase().contains(q)) return true
        if (customTitle?.lowercase()?.contains(q) == true) return true
        if (cwd.lowercase().contains(q)) return true
        if (engineId.lowercase().contains(q)) return true
        if (worktree?.branch?.lowercase()?.contains(q) == true) return true
        if (worktree?.baseRepoPath?.lowercase()?.contains(q) == true) return true
        if (workspaces?.any { it.lowercase().contains(q) } == true) return true
        return false
    }

    val runningSessions: List<SessionMeta>
        get() = sessions
            .filter {
                !it.archived &&
                    (it.state == SessionState.RUNNING || it.state == SessionState.WAITING_PERMISSION)
            }
            .sortedBy { if (it.state == SessionState.WAITING_PERMISSION) 0 else 1 }

    val filteredRunningSessions: List<SessionMeta>
        get() = runningSessions.filter {
            (drawerEngineFilter == null || it.engineId == drawerEngineFilter) &&
                it.matchesDrawerQuery(drawerSearchQuery)
        }

    val filteredHistorySessions: List<SessionMeta>
        get() {
            val base = if (drawerEngineFilter == null) sessions
            else sessions.filter { it.engineId == drawerEngineFilter }
            return base.filter {
                !it.archived &&
                    it.state != SessionState.RUNNING &&
                    it.state != SessionState.WAITING_PERMISSION &&
                    it.matchesDrawerQuery(drawerSearchQuery)
            }
        }

    val archivedSessionsCount: Int
        get() = sessions.count {
            it.archived && (drawerEngineFilter == null || it.engineId == drawerEngineFilter)
        }

    val filteredArchivedSessions: List<SessionMeta>
        get() = sessions
            .filter {
                it.archived &&
                    (drawerEngineFilter == null || it.engineId == drawerEngineFilter) &&
                    it.matchesDrawerQuery(drawerSearchQuery)
            }
            .sortedByDescending { it.archivedAt ?: it.updatedAt }
}

private class PlanHygieneMutableState(
    var entries: List<PlanEntry>,
    var updatedAt: Long,
    var turnEnded: Boolean,
    var seenPostUpdateUserTurn: Boolean,
) {
    constructor(transcript: SessionTranscript) : this(
        entries = transcript.planEntries,
        updatedAt = transcript.planUpdatedAt,
        turnEnded = transcript.planTurnEnded,
        seenPostUpdateUserTurn = transcript.planSeenPostUpdateUserTurn,
    )

    fun onUserMessage() {
        if (entries.isEmpty()) return
        val shouldClear = entries.all { it.isTerminalStatus() } || turnEnded || seenPostUpdateUserTurn
        if (shouldClear) {
            clear()
        } else {
            seenPostUpdateUserTurn = true
        }
    }

    fun onPlanUpdated(nextEntries: List<PlanEntry>, ts: Long) {
        entries = nextEntries
        updatedAt = if (nextEntries.isNotEmpty()) ts else 0L
        turnEnded = false
        seenPostUpdateUserTurn = false
    }

    fun onTurnEnded() {
        if (entries.isEmpty()) {
            turnEnded = false
            seenPostUpdateUserTurn = false
            return
        }
        if (seenPostUpdateUserTurn) {
            clear()
        } else {
            turnEnded = true
        }
    }

    private fun clear() {
        entries = emptyList()
        updatedAt = 0L
        turnEnded = false
        seenPostUpdateUserTurn = false
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
    val planHygiene = PlanHygieneMutableState(current)
    var inTok = current.inputTokens
    var outTok = current.outputTokens

    when (event) {
        is AgentEvent.UserMessage -> {
            planHygiene.onUserMessage()
            items.add(
                ChatTimelineItem.User(
                    id = event.messageId,
                    text = event.text,
                    imageCount = event.imageCount ?: event.imagePreviews?.size ?: 0,
                    imagePreviews = event.imagePreviews.orEmpty(),
                    ts = event.ts,
                    seq = event.seq,
                ),
            )
        }
        is AgentEvent.ReasoningDelta -> {
            val idx = items.indexOfLast { it is ChatTimelineItem.Reasoning && it.id == event.messageId }
            if (idx >= 0) {
                val prev = items[idx] as ChatTimelineItem.Reasoning
                items[idx] = prev.copy(
                    text = prev.text + event.text,
                    ts = if (event.ts > 0L) maxOf(prev.ts, event.ts) else prev.ts,
                )
            } else {
                items.add(
                    ChatTimelineItem.Reasoning(
                        id = event.messageId,
                        text = event.text,
                        ts = event.ts,
                        seq = event.seq,
                    ),
                )
            }
        }
        is AgentEvent.AssistantDelta -> {
            val idx = items.indexOfLast { it is ChatTimelineItem.Assistant && it.id == event.messageId }
            if (idx >= 0) {
                val prev = items[idx] as ChatTimelineItem.Assistant
                items[idx] = prev.copy(
                    text = prev.text + event.text,
                    ts = if (event.ts > 0L) maxOf(prev.ts, event.ts) else prev.ts,
                )
            } else {
                items.add(
                    ChatTimelineItem.Assistant(
                        id = event.messageId,
                        text = event.text,
                        ts = event.ts,
                        seq = event.seq,
                    ),
                )
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
                ts = event.ts,
                seq = event.seq,
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
                    ts = if (event.ts > 0L) maxOf(prev.ts, event.ts) else prev.ts,
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
            planHygiene.onPlanUpdated(event.entries, event.ts)
        }
        is AgentEvent.Usage -> {
            inTok += event.inputTokens ?: 0
            outTok += event.outputTokens ?: 0
        }
        is AgentEvent.ErrorEvent -> {
            appendOrCollapseErrorNotice(items, event)
        }
        is AgentEvent.SessionStateChanged -> {
            if (event.state.isInactiveTurnState()) {
                planHygiene.onTurnEnded()
            }
        }
        is AgentEvent.TurnDone -> {
            planHygiene.onTurnEnded()
        }
    }

    return current.copy(
        lastSeq = event.seq,
        items = items,
        pendingPermissions = perms,
        planEntries = planHygiene.entries,
        planUpdatedAt = planHygiene.updatedAt,
        planTurnEnded = planHygiene.turnEnded,
        planSeenPostUpdateUserTurn = planHygiene.seenPostUpdateUserTurn,
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
    val planHygiene = PlanHygieneMutableState(initial)
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
                planHygiene.onUserMessage()
                items.add(
                    ChatTimelineItem.User(
                        id = event.messageId,
                        text = event.text,
                        imageCount = event.imageCount ?: event.imagePreviews?.size ?: 0,
                        imagePreviews = event.imagePreviews.orEmpty(),
                        ts = event.ts,
                        seq = event.seq,
                    ),
                )
            }
            is AgentEvent.ReasoningDelta -> {
                val idx = reasoningIdxById[event.messageId]
                if (idx != null) {
                    val prev = items[idx] as ChatTimelineItem.Reasoning
                    items[idx] = prev.copy(
                        text = prev.text + event.text,
                        ts = if (event.ts > 0L) maxOf(prev.ts, event.ts) else prev.ts,
                    )
                } else {
                    reasoningIdxById[event.messageId] = items.size
                    items.add(
                        ChatTimelineItem.Reasoning(
                            id = event.messageId,
                            text = event.text,
                            ts = event.ts,
                            seq = event.seq,
                        ),
                    )
                }
            }
            is AgentEvent.AssistantDelta -> {
                val idx = assistantIdxById[event.messageId]
                if (idx != null) {
                    val prev = items[idx] as ChatTimelineItem.Assistant
                    items[idx] = prev.copy(
                        text = prev.text + event.text,
                        ts = if (event.ts > 0L) maxOf(prev.ts, event.ts) else prev.ts,
                    )
                } else {
                    assistantIdxById[event.messageId] = items.size
                    items.add(
                        ChatTimelineItem.Assistant(
                            id = event.messageId,
                            text = event.text,
                            ts = event.ts,
                            seq = event.seq,
                        ),
                    )
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
                    ts = event.ts,
                    seq = event.seq,
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
                        ts = if (event.ts > 0L) maxOf(prev.ts, event.ts) else prev.ts,
                    )
                }
            }
            is AgentEvent.PlanUpdated -> {
                planHygiene.onPlanUpdated(event.entries, event.ts)
            }
            is AgentEvent.Usage -> {
                inTok += event.inputTokens ?: 0
                outTok += event.outputTokens ?: 0
            }
            is AgentEvent.ErrorEvent -> {
                appendOrCollapseErrorNotice(items, event)
            }
            is AgentEvent.SessionStateChanged -> {
                if (event.state.isInactiveTurnState()) {
                    planHygiene.onTurnEnded()
                }
            }
            is AgentEvent.TurnDone -> {
                planHygiene.onTurnEnded()
            }
            is AgentEvent.PermissionRequested,
            is AgentEvent.PermissionResolved -> Unit
        }
    }

    return initial.copy(
        lastSeq = lastSeq,
        items = items,
        pendingPermissions = perms,
        planEntries = planHygiene.entries,
        planUpdatedAt = planHygiene.updatedAt,
        planTurnEnded = planHygiene.turnEnded,
        planSeenPostUpdateUserTurn = planHygiene.seenPostUpdateUserTurn,
        inputTokens = inTok,
        outputTokens = outTok,
    )
}

/**
 * Merges a reverse-paginated older transcript slice (`olderItems`, all strictly older than `currentItems`)
 * in front of `currentItems`.
 *
 * Unlike naive ID-based deduplication (which discards the entire prefix of a streaming `Assistant` or
 * `Reasoning` message whenever a pagination boundary cuts mid-message), split items sharing the same ID
 * with `older.seq < existing.seq` are stitched back together (`older.text + existing.text`) in their
 * earliest chronological slot, matching full-stream `reduceTranscriptBatch` output.
 */
fun mergePrependedOlderItems(
    olderItems: List<ChatTimelineItem>,
    currentItems: List<ChatTimelineItem>,
): List<ChatTimelineItem> {
    if (olderItems.isEmpty()) return currentItems
    if (currentItems.isEmpty()) return olderItems

    val currentByKey = HashMap<String, Pair<Int, ChatTimelineItem>>(currentItems.size)
    currentItems.forEachIndexed { idx, item ->
        currentByKey["${item.id}_${item::class.simpleName}"] = idx to item
    }

    val consumedCurrentIndices = HashSet<Int>()
    val mergedOlder = ArrayList<ChatTimelineItem>(olderItems.size)

    for (older in olderItems) {
        val key = "${older.id}_${older::class.simpleName}"
        val match = currentByKey[key]
        if (match == null) {
            mergedOlder.add(older)
            continue
        }
        val (curIdx, existing) = match
        when {
            older is ChatTimelineItem.Assistant && existing is ChatTimelineItem.Assistant -> {
                consumedCurrentIndices.add(curIdx)
                if (older.seq < existing.seq) {
                    mergedOlder.add(
                        older.copy(
                            text = older.text + existing.text,
                            ts = maxOf(older.ts, existing.ts),
                        ),
                    )
                } else {
                    mergedOlder.add(existing)
                }
            }
            older is ChatTimelineItem.Reasoning && existing is ChatTimelineItem.Reasoning -> {
                consumedCurrentIndices.add(curIdx)
                if (older.seq < existing.seq) {
                    mergedOlder.add(
                        older.copy(
                            text = older.text + existing.text,
                            ts = maxOf(older.ts, existing.ts),
                        ),
                    )
                } else {
                    mergedOlder.add(existing)
                }
            }
            older is ChatTimelineItem.Tool && existing is ChatTimelineItem.Tool -> {
                consumedCurrentIndices.add(curIdx)
                mergedOlder.add(
                    older.copy(
                        title = existing.title.ifBlank { older.title },
                        status = existing.status,
                        input = older.input ?: existing.input,
                        output = existing.output ?: older.output,
                        ts = maxOf(older.ts, existing.ts),
                        seq = minOf(older.seq, existing.seq),
                    ),
                )
            }
            else -> {
                // User / Notice with identical ID: keep the existing item in its current slot.
            }
        }
    }

    val remainingCurrent = currentItems.filterIndexed { idx, _ -> idx !in consumedCurrentIndices }
    return mergedOlder + remainingCurrent
}

private val REPEAT_SUFFIX_REGEX = Regex("\\s*\\(×\\d+\\)$")
private val ERROR_VOLATILE_TOKEN_REGEX = Regex("\\b([0-9a-f]{8,}|\\d+)\\b", RegexOption.IGNORE_CASE)

private fun normalizeErrorNoticeSignature(raw: String): String =
    raw.replace(REPEAT_SUFFIX_REGEX, "")
        .lowercase()
        .replace(ERROR_VOLATILE_TOKEN_REGEX, "#")
        .replace(Regex("\\s+"), " ")
        .trim()

private fun appendOrCollapseErrorNotice(
    items: MutableList<ChatTimelineItem>,
    event: AgentEvent.ErrorEvent,
) {
    val cleanMsg = event.message.trim().ifEmpty { "执行异常中断" }
    val prev = items.lastOrNull() as? ChatTimelineItem.Notice
    if (prev != null && prev.isError) {
        val prevBase = prev.text.replace(REPEAT_SUFFIX_REGEX, "").trim()
        val sameSignature = normalizeErrorNoticeSignature(prevBase) == normalizeErrorNoticeSignature(cleanMsg)
        val bothNetwork = isLikelyNetworkError(prevBase) && isLikelyNetworkError(cleanMsg)
        if (sameSignature || bothNetwork) {
            val nextCount = prev.repeatCount + 1
            items[items.lastIndex] = prev.copy(
                text = "$cleanMsg (×$nextCount)",
                repeatCount = nextCount,
                ts = if (event.ts > 0L) maxOf(prev.ts, event.ts) else prev.ts,
            )
            return
        }
    }
    items.add(
        ChatTimelineItem.Notice(
            id = "err_${event.seq}",
            text = cleanMsg,
            isError = true,
            repeatCount = 1,
            ts = event.ts,
            seq = event.seq,
        ),
    )
}

