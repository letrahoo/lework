package dev.lework.store

import dev.lework.platform.*
import dev.lework.protocol.*
import dev.lework.security.HubAttestation
import dev.lework.security.HubTrust
import dev.lework.security.hubBaseUrlOfWs
import dev.lework.security.hubWsUrlOf
import dev.lework.security.isTrustedOAuthUrl
import dev.lework.security.normalizeHubBaseUrl
import dev.lework.transport.HubRpcClient
import dev.lework.transport.HubTransport
import dev.lework.transport.RpcCallException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

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

private fun parseEffort(raw: String): ReasoningEffort = when (raw.lowercase()) {
    "low" -> ReasoningEffort.LOW
    "high" -> ReasoningEffort.HIGH
    "xhigh" -> ReasoningEffort.XHIGH
    "max" -> ReasoningEffort.MAX
    else -> ReasoningEffort.MEDIUM
}

private fun effortToWire(e: ReasoningEffort): String = when (e) {
    ReasoningEffort.LOW -> "low"
    ReasoningEffort.MEDIUM -> "medium"
    ReasoningEffort.HIGH -> "high"
    ReasoningEffort.XHIGH -> "xhigh"
    ReasoningEffort.MAX -> "max"
}

private fun parseMode(raw: String): SessionMode = when (raw.lowercase()) {
    "auto" -> SessionMode.AUTO
    "acceptedits", "auto-edit", "auto_edit" -> SessionMode.ACCEPT_EDITS
    "plan" -> SessionMode.PLAN
    "bypasspermissions", "yolo" -> SessionMode.BYPASS_PERMISSIONS
    "default", "manual" -> SessionMode.DEFAULT
    // Legacy `"ask"` was written by older builds as hardcoded default; upgrade to computer default (`AUTO`)
    else -> SessionMode.AUTO
}

private fun modeToWire(m: SessionMode): String = when (m) {
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

class ConsoleStore(
    private val scope: CoroutineScope,
    private val rpc: HubTransport = HubRpcClient(scope),
) {
    private val initialPrefs = loadSessionPrefs()
    private val _state = MutableStateFlow(
        ConsoleUiState(
            selectedEngineId = initialPrefs.engineId,
            selectedProviderId = initialPrefs.providerId,
            selectedModelId = initialPrefs.modelId,
            selectedEffort = parseEffort(initialPrefs.reasoningEffort),
            selectedMode = parseMode(initialPrefs.mode),
            selectedWorkspacePath = initialPrefs.workspacePath,
            currentBrowsePath = initialPrefs.workspacePath,
        ),
    )
    val state: StateFlow<ConsoleUiState> = _state.asStateFlow()
    private var discoverJob: Job? = null
    private var oauthPollJob: Job? = null
    private var resumeJob: Job? = null
    private var subscribedSessionId: String? = null
    private var subscribeSeqToken: Long = 0L
    private var userManuallyPickedMode: Boolean = false

    private fun switchSubscriptionTo(nextSessionId: String?) {
        val prev = subscribedSessionId
        subscribedSessionId = nextSessionId
        subscribeSeqToken++
        if (prev != null && prev != nextSessionId) {
            scope.launch {
                runCatching { rpc.unsubscribeSession(prev) }
            }
        }
    }

    init {
        scope.launch {
            observeBonjourHubUrls().collect { urls ->
                if (urls.isNotEmpty() && !_state.value.hasEnteredWorkspace && _state.value.connectionStatus == ConnectionStatus.ERROR) {
                    bootstrapOrConnect()
                }
            }
        }
        scope.launch {
            var wasBackground = false
            observeAppForeground().collect { isForeground ->
                if (!isForeground) {
                    wasBackground = true
                } else if (wasBackground) {
                    wasBackground = false
                    if (_state.value.hasEnteredWorkspace) {
                        verifyAndResumeForegroundSession()
                    }
                }
            }
        }
        scope.launch {
            rpc.events.collect { ev ->
                applyIncomingEvent(ev)
            }
        }
        scope.launch {
            rpc.sessionUpdates.collect { updated ->
                _state.update { s ->
                    val existing = s.sessions.indexOfFirst { it.id == updated.id }
                    val nextList = if (existing >= 0) {
                        s.sessions.toMutableList().also { it[existing] = updated }
                    } else {
                        listOf(updated) + s.sessions
                    }
                    val syncMode = if (s.activeSessionId == updated.id && updated.mode != null) {
                        updated.mode
                    } else {
                        s.selectedMode
                    }
                    s.copy(sessions = nextList, selectedMode = syncMode)
                }
            }
        }
        scope.launch {
            rpc.disconnected.collect { reason ->
                val cur = _state.value
                if (cur.hasEnteredWorkspace) {
                    if (!observeAppForeground().value) {
                        // iOS background / screen locked: keep workspace & Session untouched until foreground return
                        _state.update {
                            it.copy(
                                connectionStatus = ConnectionStatus.RECONNECTING,
                                connectionError = null,
                            )
                        }
                    } else {
                        verifyAndResumeForegroundSession()
                    }
                } else {
                    _state.update { s ->
                        if (s.connectionStatus == ConnectionStatus.CONNECTED) {
                            s.copy(
                                connectionStatus = ConnectionStatus.ERROR,
                                connectionError = reason,
                            )
                        } else {
                            s
                        }
                    }
                }
            }
        }
    }

    private fun persistCurrentPrefs(st: ConsoleUiState = _state.value) {
        saveSessionPrefs(
            SavedSessionPrefs(
                engineId = st.selectedEngineId,
                providerId = st.selectedProviderId,
                modelId = st.selectedModelId,
                reasoningEffort = effortToWire(st.selectedEffort),
                mode = modeToWire(st.selectedMode),
                workspacePath = st.selectedWorkspacePath,
            ),
        )
    }

    fun bootstrapOrConnect() {
        discoverJob?.cancel()
        oauthPollJob?.cancel()
        startBonjourDiscovery()
        _state.update {
            it.copy(
                connectionStatus = ConnectionStatus.DISCOVERING,
                connectionError = null,
                authStatusText = "正在自动发现同 Wi‑Fi 局域网或中继主机…",
            )
        }
        discoverJob = scope.launch {
            val found = rpc.discoverIntranetHub(
                candidates = candidateHubHttpUrls(),
                dynamicCandidates = observeBonjourHubUrls(),
            )
            if (found == null) {
                _state.update {
                    it.copy(
                        connectionStatus = ConnectionStatus.ERROR,
                        connectionError = "未发现可用主机，请确认处于同一路由器 Wi‑Fi，或在下方手动配置主机/中继地址",
                    )
                }
                return@launch
            }

            // Nothing learned from (unauthenticated) discovery is persisted; addresses are saved
            // only after the Hub has proven its identity and accepted our token (connectWithToken).
            _state.update {
                it.copy(
                    discoveredBaseUrl = found.baseUrl,
                    discoveredWsUrl = found.wsUrl,
                    oauthEnabled = found.discover.oauthEnabled,
                    oauthProvider = found.discover.oauthProvider,
                    oauthDisplayName = found.discover.oauthDisplayName,
                    hub = found.discover.hub,
                )
            }

            val cachedToken = loadSavedAccessToken()
            if (!cachedToken.isNullOrBlank()) {
                val ok = connectWithToken(found.wsUrl, cachedToken)
                if (ok) return@launch
                if (!loadSavedAccessToken().isNullOrBlank()) {
                    // Network/transport failure rather than invalid token: stay on retry screen without wiping token
                    return@launch
                }
            }

            if (found.discover.oauthEnabled) {
                startOAuthLoginFlow(found.baseUrl, found.wsUrl)
            } else {
                _state.update {
                    it.copy(
                        connectionStatus = ConnectionStatus.NEEDS_AUTH,
                        oauthChallenge = null,
                        authStatusText = "已发现主机 ${found.discover.hub.name}，请输入访问令牌连接",
                    )
                }
            }
        }
    }

    fun connectManualEndpoint(rawHostOrUrl: String, rawToken: String) {
        val trimmedHost = rawHostOrUrl.trim().trimEnd('/')
        val baseUrl = if (trimmedHost.isEmpty()) {
            _state.value.discoveredBaseUrl
        } else {
            normalizeHubBaseUrl(trimmedHost)
        }
        val token = rawToken.trim()
        if (token.isNotEmpty()) {
            saveAuthSession(token, "Owner")
            if (baseUrl.isNotBlank()) {
                val wsUrl = hubWsUrlOf(baseUrl)
                _state.update { it.copy(discoveredBaseUrl = baseUrl, discoveredWsUrl = wsUrl) }
                discoverJob?.cancel()
                oauthPollJob?.cancel()
                discoverJob = scope.launch {
                    connectWithToken(wsUrl, token)
                }
                return
            }
        }
        bootstrapOrConnect()
    }

    /**
     * Triggered when returning to foreground on iOS after a lock-screen or background suspension.
     * Silently verifies token permission & Hub reachability:
     * - Pass: resumes the active Session context seamlessly and catches up missed events (`afterSeq`).
     * - Fail: transitions back to the reconnect / auth screen (`hasEnteredWorkspace = false`).
     */
    fun verifyAndResumeForegroundSession() {
        if (resumeJob?.isActive == true) return
        resumeJob = scope.launch {
            val cachedToken = loadSavedAccessToken()
            if (cachedToken.isNullOrBlank()) {
                _state.update {
                    it.copy(
                        hasEnteredWorkspace = false,
                        authUserName = null,
                        connectionStatus = ConnectionStatus.NEEDS_AUTH,
                    )
                }
                startOAuthLoginFlow()
                return@launch
            }

            // Fast-path: if WebSocket remained alive across a brief lock, verify permission & catch up in place
            if (_state.value.connectionStatus == ConnectionStatus.CONNECTED) {
                try {
                    val liveSessions = withTimeout(3_500L) { rpc.listSessions() }
                    _state.update { it.copy(sessions = liveSessions, connectionError = null) }
                    val activeId = _state.value.activeSessionId
                    if (activeId != null) {
                        runCatching { subscribeAndCatchUp(activeId) }
                    }
                    return@launch
                } catch (e: RpcCallException) {
                    if (e.code == ErrorCode.UNAUTHORIZED) {
                        saveAuthSession(null, null)
                        _state.update {
                            it.copy(
                                hasEnteredWorkspace = false,
                                authUserName = null,
                                connectionStatus = ConnectionStatus.NEEDS_AUTH,
                            )
                        }
                        startOAuthLoginFlow()
                        return@launch
                    }
                    // Other RPC / zombie-socket failure falls through to full discovery & reconnect
                } catch (_: Throwable) {
                    // Zombie TCP socket after iOS sleep — fall through to reconnect
                }
            }

            _state.update {
                it.copy(
                    connectionStatus = ConnectionStatus.RECONNECTING,
                    connectionError = null,
                )
            }
            startBonjourDiscovery()
            val found = rpc.discoverIntranetHub(
                candidates = candidateHubHttpUrls(),
                dynamicCandidates = observeBonjourHubUrls(),
                waitWindowMs = 5_500L,
            )
            if (found == null) {
                _state.update {
                    it.copy(
                        hasEnteredWorkspace = false,
                        connectionStatus = ConnectionStatus.ERROR,
                        connectionError = "唤醒校验未通过：未发现同一 Wi‑Fi 主机或中继连接，请检查网络后点击重连",
                    )
                }
                return@launch
            }

            _state.update {
                it.copy(
                    discoveredBaseUrl = found.baseUrl,
                    discoveredWsUrl = found.wsUrl,
                    oauthEnabled = found.discover.oauthEnabled,
                    oauthProvider = found.discover.oauthProvider,
                    oauthDisplayName = found.discover.oauthDisplayName,
                    hub = found.discover.hub,
                )
            }

            connectWithToken(
                wsUrl = found.wsUrl,
                token = cachedToken,
                isForegroundResume = true,
            )
        }
    }

    fun startOAuthLoginFlow(
        baseUrl: String = _state.value.discoveredBaseUrl,
        wsUrl: String = _state.value.discoveredWsUrl,
    ) {
        if (baseUrl.isEmpty()) {
            bootstrapOrConnect()
            return
        }
        oauthPollJob?.cancel()
        val label = _state.value.oauthDisplayName ?: "SSO"
        _state.update {
            it.copy(
                connectionStatus = ConnectionStatus.NEEDS_AUTH,
                connectionError = null,
                authStatusText = "正在获取 $label 授权码…",
            )
        }
        oauthPollJob = scope.launch {
            try {
                val challenge = rpc.startOAuthDeviceFlow(baseUrl)
                _state.update {
                    it.copy(
                        oauthChallenge = challenge,
                        authStatusText = "点击按钮在 $label 中确认后将自动进入",
                    )
                }
                while (isActive) {
                    delay(2_000L)
                    val poll = runCatching { rpc.pollOAuthDeviceFlow(baseUrl, challenge.sid) }.getOrNull()
                        ?: continue
                    when (poll.status) {
                        "ok" -> {
                            val token = poll.accessToken ?: continue
                            val userName = poll.user?.name ?: "Owner"
                            saveAuthSession(token, userName)
                            _state.update {
                                it.copy(
                                    authUserName = userName,
                                    oauthChallenge = null,
                                    authStatusText = "已验证，正在进入工作台…",
                                )
                            }
                            connectWithToken(wsUrl, token)
                            break
                        }
                        "forbidden" -> {
                            _state.update {
                                it.copy(
                                    authStatusText = "当前账号 (${poll.name ?: ""}) 无权访问此主机",
                                )
                            }
                            break
                        }
                        "expired" -> {
                            startOAuthLoginFlow(baseUrl, wsUrl)
                            break
                        }
                    }
                }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(authStatusText = "授权请求失败: ${t.message}")
                }
            }
        }
    }

    fun launchOAuthApp() {
        val challenge = _state.value.oauthChallenge ?: return
        val target = challenge.appLaunchUrl.ifBlank { challenge.verifyUrl }
        if (isTrustedOAuthUrl(target, _state.value.oauthProvider)) openExternalUrl(target)
    }

    fun openOAuthBrowserFallback() {
        val challenge = _state.value.oauthChallenge ?: return
        if (isTrustedOAuthUrl(challenge.verifyUrl, _state.value.oauthProvider)) openExternalUrl(challenge.verifyUrl)
    }

    fun logout() {
        oauthPollJob?.cancel()
        resumeJob?.cancel()
        saveAuthSession(null, null)
        scope.launch {
            rpc.disconnect()
            _state.update {
                it.copy(
                    hasEnteredWorkspace = false,
                    authUserName = null,
                    connectionStatus = ConnectionStatus.NEEDS_AUTH,
                )
            }
            if (_state.value.oauthEnabled) {
                startOAuthLoginFlow()
            }
        }
    }

    private suspend fun connectWithToken(
        wsUrl: String,
        token: String,
        isForegroundResume: Boolean = false,
    ): Boolean {
        _state.update {
            it.copy(
                connectionStatus = if (isForegroundResume) ConnectionStatus.RECONNECTING else ConnectionStatus.CONNECTING,
                connectionError = null,
            )
        }
        return try {
            // Verify & pin the Hub certificate BEFORE the token leaves the device.
            val baseUrl = hubBaseUrlOfWs(wsUrl)
            rpc.ensureHubTrusted(baseUrl, token)
            val hello = rpc.connectAndHello(
                wsUrl = wsUrl,
                token = token,
                platformName = platformName(),
            )
            // Authenticated: now it is safe to remember this address and the Hub's relays.
            if (!baseUrl.contains("127.0.0.1")) saveLastGoodHubUrl(baseUrl)
            val learned = (hello.relayUrls + if (baseUrl.contains("127.0.0.1")) emptyList() else listOf(baseUrl))
                .map { normalizeHubBaseUrl(it) }
            if (learned.isNotEmpty()) {
                saveRelayUrls((loadSavedRelayUrls().map { normalizeHubBaseUrl(it) } + learned).distinct().take(8))
            }
            val status = rpc.fetchStatus()
            val workspaces = rpc.listWorkspaces()
            val sessions = rpc.listSessions()

            val current = _state.value
            val uiEngines = status.engines.filterNot { it.id == "mock" }
            val chosenEngine = uiEngines.firstOrNull { it.id == current.selectedEngineId && it.status.ready }
                ?: uiEngines.firstOrNull { it.status.ready }
                ?: status.engines.firstOrNull()
            val chosenEngineId = chosenEngine?.id ?: "claude"

            val chosenModel = chosenEngine?.models?.firstOrNull { it.id == chosenEngine.defaultModel }
                ?: chosenEngine?.models?.firstOrNull { it.id == current.selectedModelId }
                ?: chosenEngine?.models?.firstOrNull()
            val chosenEffort = chosenEngine?.defaultEffort
                ?: chosenModel?.defaultEffort
                ?: current.selectedEffort
            val chosenMode = if (userManuallyPickedMode) {
                current.selectedMode
            } else {
                chosenEngine?.defaultMode ?: SessionMode.AUTO
            }

            val validWs = workspaces.firstOrNull { it.path == current.selectedWorkspacePath }?.path
                ?: workspaces.firstOrNull()?.path.orEmpty()

            _state.update {
                it.copy(
                    hasEnteredWorkspace = true,
                    connectionStatus = ConnectionStatus.CONNECTED,
                    connectionError = null,
                    hubFingerprint = HubTrust.observedFor(baseUrl)?.let { HubAttestation.shortFingerprint(it) },
                    hub = status.hub,
                    slaves = status.slaves,
                    engines = uiEngines.ifEmpty { status.engines },
                    providers = status.providers,
                    workspaces = workspaces,
                    selectedEngineId = chosenEngineId,
                    selectedProviderId = chosenModel?.providerId ?: current.selectedProviderId,
                    selectedModelId = chosenModel?.id,
                    selectedEffort = chosenEffort,
                    selectedMode = chosenMode,
                    selectedWorkspacePath = validWs,
                    currentBrowsePath = validWs,
                    sessions = sessions,
                )
            }
            persistCurrentPrefs()

            if (current.activeSessionId != null) {
                subscribedSessionId = current.activeSessionId
                runCatching { subscribeAndCatchUp(current.activeSessionId) }
            }
            true
        } catch (t: Throwable) {
            if (t is RpcCallException && t.code == ErrorCode.UNAUTHORIZED) {
                saveAuthSession(null, null)
                _state.update {
                    it.copy(
                        hasEnteredWorkspace = false,
                        authUserName = null,
                        connectionStatus = ConnectionStatus.NEEDS_AUTH,
                        connectionError = null,
                    )
                }
                if (isForegroundResume && _state.value.oauthEnabled) {
                    startOAuthLoginFlow(_state.value.discoveredBaseUrl, wsUrl)
                }
            } else {
                _state.update {
                    it.copy(
                        hasEnteredWorkspace = false,
                        connectionStatus = ConnectionStatus.ERROR,
                        connectionError = if (isForegroundResume) {
                            "唤醒重连未通过: ${t.message ?: "请检查网络后重试"}"
                        } else {
                            t.message ?: "连接失败"
                        },
                    )
                }
            }
            false
        }
    }

    fun refreshHostSessions() {
        scope.launch {
            runCatching {
                val sessions = rpc.listSessions()
                _state.update { it.copy(sessions = sessions) }
            }
        }
    }

    fun setDrawerEngineFilter(engineId: String?) {
        _state.update { it.copy(drawerEngineFilter = engineId) }
        refreshHostSessions()
    }

    /** Starts a clean blank draft session using the computer's aligned engine setup. */
    fun startNewDraftSession() {
        switchSubscriptionTo(null)
        _state.update { s ->
            val eng = s.selectedEngine
            s.copy(
                activeSessionId = null,
                checkpoints = emptyList(),
                selectedMode = eng?.defaultMode ?: s.selectedMode,
            )
        }
    }

    fun selectEngineAndModel(
        engineId: String,
        modelSpec: EngineModelSpec? = null,
        effort: ReasoningEffort? = null,
    ) {
        _state.update { s ->
            val eng = s.engines.firstOrNull { it.id == engineId }
            val chosenModel = modelSpec
                ?: eng?.models?.firstOrNull { it.id == eng.defaultModel }
                ?: eng?.models?.firstOrNull()
            val nextEffort = effort
                ?: eng?.defaultEffort
                ?: chosenModel?.defaultEffort
                ?: s.selectedEffort
            val nextMode = eng?.defaultMode ?: s.selectedMode
            s.copy(
                selectedEngineId = engineId,
                selectedProviderId = chosenModel?.providerId,
                selectedModelId = chosenModel?.id,
                selectedEffort = nextEffort,
                selectedMode = nextMode,
            )
        }
        persistCurrentPrefs()

        val active = _state.value.activeSession
        val modelId = _state.value.selectedModelId
        if (active != null && active.engineId == engineId && modelId != null) {
            scope.launch {
                runCatching { rpc.setSessionModel(active.id, modelId) }
            }
        }
    }

    fun selectEffort(effort: ReasoningEffort) {
        _state.update { it.copy(selectedEffort = effort) }
        persistCurrentPrefs()
    }

    fun selectMode(mode: SessionMode) {
        userManuallyPickedMode = true
        _state.update { it.copy(selectedMode = mode) }
        persistCurrentPrefs()
    }

    fun selectWorkspace(path: String) {
        val prevActive = _state.value.activeSession
        val resetDraft = if (prevActive != null && prevActive.cwd != path) null else _state.value.activeSessionId
        if (resetDraft == null) {
            switchSubscriptionTo(null)
        }
        _state.update {
            it.copy(
                selectedWorkspacePath = path,
                selectedExtraWorkspaces = it.selectedExtraWorkspaces.filterNot { w -> w == path },
                currentBrowsePath = path,
                activeSessionId = resetDraft,
                dirEntries = emptyList(),
                openedFilePath = null,
                openedFileContent = null,
            )
        }
        persistCurrentPrefs()
        browseDirectory(path)
    }

    fun toggleExtraWorkspace(path: String) {
        _state.update { s ->
            if (path.isBlank() || path == s.selectedWorkspacePath) return@update s
            val next = if (s.selectedExtraWorkspaces.contains(path)) {
                s.selectedExtraWorkspaces - path
            } else {
                s.selectedExtraWorkspaces + path
            }
            s.copy(selectedExtraWorkspaces = next)
        }
    }

    fun switchBrowseWorkspace(path: String) {
        if (path.isBlank()) return
        browseDirectory(path)
    }

    fun startNewDraftInWorkspace(path: String) {
        switchSubscriptionTo(null)
        _state.update {
            it.copy(
                activeSessionId = null,
                checkpoints = emptyList(),
                selectedWorkspacePath = path,
                selectedExtraWorkspaces = emptyList(),
                currentBrowsePath = path,
                dirEntries = emptyList(),
                openedFilePath = null,
                openedFileContent = null,
            )
        }
        persistCurrentPrefs()
        browseDirectory(path)
    }

    fun selectSession(sessionId: String) {
        switchSubscriptionTo(sessionId)
        val meta = _state.value.sessions.firstOrNull { it.id == sessionId }
        _state.update { s ->
            val targetEngineId = meta?.engineId ?: s.selectedEngineId
            val eng = s.engines.firstOrNull { it.id == targetEngineId }
            val resolvedMode = meta?.mode ?: eng?.defaultMode ?: s.selectedMode
            val resolvedEffort = meta?.reasoningEffort ?: eng?.defaultEffort ?: s.selectedEffort
            val resolvedModel = meta?.model ?: eng?.defaultModel ?: s.selectedModelId
            s.copy(
                activeSessionId = sessionId,
                checkpoints = emptyList(),
                selectedEngineId = targetEngineId,
                selectedModelId = resolvedModel,
                selectedEffort = resolvedEffort,
                selectedMode = resolvedMode,
                selectedWorkspacePath = meta?.cwd ?: s.selectedWorkspacePath,
                currentBrowsePath = meta?.cwd ?: s.currentBrowsePath,
            )
        }
        scope.launch {
            runCatching {
                subscribeAndCatchUp(sessionId)
                if (!sessionId.startsWith("host:") && _state.value.activeSessionId == sessionId) {
                    val ckpts = rpc.listCheckpoints(sessionId)
                    _state.update { it.copy(checkpoints = ckpts) }
                }
            }
        }
    }

    fun loadOlderHistory(sessionId: String = _state.value.activeSessionId ?: "") {
        if (sessionId.isEmpty()) return
        val cur = _state.value.transcripts[sessionId] ?: return
        val beforeSeq = cur.earliestSeq ?: return
        if (!cur.hasMoreBefore || cur.loadingOlder || beforeSeq <= 0) return

        _state.update { s ->
            val t = s.transcripts[sessionId] ?: return@update s
            s.copy(transcripts = s.transcripts + (sessionId to t.copy(loadingOlder = true)))
        }

        scope.launch {
            try {
                val snap = rpc.subscribeSession(
                    sessionId = sessionId,
                    afterSeq = -1,
                    beforeSeq = beforeSeq,
                    limit = 220,
                )
                val olderReduced = snap.events.fold(SessionTranscript()) { acc, ev ->
                    reduceTranscript(acc, ev)
                }
                _state.update { s ->
                    val latest = s.transcripts[sessionId] ?: SessionTranscript()
                    val existingKeys = latest.items.map { "${it.id}_${it::class.simpleName}" }.toHashSet()
                    val nonDupOlder = olderReduced.items.filterNot {
                        "${it.id}_${it::class.simpleName}" in existingKeys
                    }
                    val newEarliest = snap.earliestSeq
                        ?: snap.events.firstOrNull()?.seq
                        ?: latest.earliestSeq
                    val merged = latest.copy(
                        earliestSeq = newEarliest,
                        hasMoreBefore = snap.hasMoreBefore == true && snap.events.isNotEmpty(),
                        loadingOlder = false,
                        items = nonDupOlder + latest.items,
                    )
                    s.copy(transcripts = s.transcripts + (sessionId to merged))
                }
            } catch (_: Throwable) {
                _state.update { s ->
                    val latest = s.transcripts[sessionId] ?: return@update s
                    s.copy(transcripts = s.transcripts + (sessionId to latest.copy(loadingOlder = false)))
                }
            }
        }
    }

    fun sendPrompt(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val s = _state.value
        scope.launch {
            try {
                val sessionId = s.activeSessionId ?: run {
                    val cwd = s.selectedWorkspacePath.ifEmpty {
                        s.workspaces.firstOrNull()?.path ?: return@launch
                    }
                    val allWs = (listOf(cwd) + s.selectedExtraWorkspaces).filter { it.isNotBlank() }.distinct()
                    val created = rpc.createSession(
                        engineId = s.selectedEngineId,
                        cwd = cwd,
                        workspaces = allWs,
                        providerId = s.selectedProviderId,
                        modelId = s.selectedModelId,
                        reasoningEffort = s.selectedEffort,
                        mode = s.selectedMode,
                    )
                    switchSubscriptionTo(created.id)
                    _state.update { st ->
                        st.copy(
                            sessions = listOf(created) + st.sessions.filterNot { item -> item.id == created.id },
                            activeSessionId = created.id,
                        )
                    }
                    created.id
                }
                rpc.sendPrompt(
                    sessionId = sessionId,
                    text = trimmed,
                    modelId = s.selectedModelId,
                    reasoningEffort = s.selectedEffort,
                    mode = s.selectedMode,
                )
            } catch (t: Throwable) {
                _state.update { it.copy(connectionError = t.message) }
            }
        }
    }

    fun cancelActiveTurn() {
        val activeId = _state.value.activeSessionId ?: return
        scope.launch {
            runCatching { rpc.cancelSession(activeId) }
        }
    }

    fun respondApproval(requestId: String, optionId: String?, sessionId: String? = null) {
        val targetSessionId = sessionId ?: _state.value.activeSessionId ?: return
        scope.launch {
            try {
                rpc.respondApproval(
                    sessionId = targetSessionId,
                    requestId = requestId,
                    optionId = optionId,
                )
                _state.update { s ->
                    val prev = s.transcripts[targetSessionId] ?: return@update s
                    val updated = prev.copy(
                        pendingPermissions = prev.pendingPermissions.filterNot { it.requestId == requestId },
                    )
                    s.copy(transcripts = s.transcripts + (targetSessionId to updated))
                }
            } catch (t: Throwable) {
                _state.update { it.copy(connectionError = t.message) }
            }
        }
    }

    fun browseDirectory(path: String = _state.value.currentBrowsePath.ifEmpty { _state.value.selectedWorkspacePath }) {
        if (path.isBlank()) return
        scope.launch {
            runCatching {
                val res = rpc.listDirectory(path)
                _state.update {
                    it.copy(
                        currentBrowsePath = res.path,
                        dirEntries = res.entries,
                        openedFilePath = null,
                        openedFileContent = null,
                    )
                }
            }
        }
    }

    fun navigateUpDirectory() {
        val cur = _state.value.currentBrowsePath
        val parent = cur.substringBeforeLast('/', "")
        if (parent.isNotEmpty()) {
            browseDirectory(parent)
        }
    }

    fun openEntry(entry: FsEntry) {
        if (entry.isDirectory) {
            browseDirectory(entry.path)
        } else {
            scope.launch {
                runCatching {
                    val res = rpc.readFile(entry.path)
                    _state.update {
                        it.copy(
                            openedFilePath = res.path,
                            openedFileContent = res.content,
                        )
                    }
                }
            }
        }
    }

    fun closeOpenedFile() {
        _state.update { it.copy(openedFilePath = null, openedFileContent = null) }
    }

    private suspend fun subscribeAndCatchUp(sessionId: String) {
        val reqToken = subscribeSeqToken
        val existing = _state.value.transcripts[sessionId]
        val isInitialLoad = existing == null || existing.lastSeq < 0
        val snap = rpc.subscribeSession(
            sessionId = sessionId,
            afterSeq = if (isInitialLoad) -1 else existing!!.lastSeq,
            limit = if (isInitialLoad) 220 else null,
        )

        // Strict session check: if the user already switched away while `subscribeSession` was in flight,
        // unsubscribe immediately and do NOT contaminate the newly selected conversation.
        if (_state.value.activeSessionId != sessionId || reqToken != subscribeSeqToken) {
            runCatching { rpc.unsubscribeSession(sessionId) }
            return
        }

        _state.update { s ->
            if (s.activeSessionId != sessionId) return@update s
            val updatedSessions = s.sessions.map { item ->
                if (item.id == snap.session.id) snap.session else item
            }
            val nextTranscript = if (isInitialLoad) {
                val built = snap.events.fold(SessionTranscript()) { acc, ev ->
                    reduceTranscript(acc, ev)
                }
                built.copy(
                    earliestSeq = snap.earliestSeq ?: snap.events.firstOrNull()?.seq,
                    hasMoreBefore = snap.hasMoreBefore == true,
                    loadingOlder = false,
                )
            } else {
                var cur = s.transcripts[sessionId] ?: SessionTranscript()
                for (ev in snap.events) {
                    cur = reduceTranscript(cur, ev)
                }
                cur
            }
            val eng = s.engines.firstOrNull { it.id == snap.session.engineId }
            s.copy(
                sessions = updatedSessions,
                selectedMode = snap.session.mode ?: eng?.defaultMode ?: s.selectedMode,
                selectedEffort = snap.session.reasoningEffort ?: eng?.defaultEffort ?: s.selectedEffort,
                selectedWorkspacePath = snap.session.cwd,
                currentBrowsePath = snap.session.cwd,
                transcripts = s.transcripts + (sessionId to nextTranscript),
            )
        }
    }

    private fun applyIncomingEvent(event: AgentEvent) {
        // Strict active-session isolation: never apply stream events from another conversation
        if (event.sessionId != _state.value.activeSessionId) return
        _state.update { s ->
            if (event.sessionId != s.activeSessionId) return@update s
            val prev = s.transcripts[event.sessionId] ?: SessionTranscript()
            val next = reduceTranscript(prev, event)
            s.copy(transcripts = s.transcripts + (event.sessionId to next))
        }
    }
}
