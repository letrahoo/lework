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
        if (baseUrl.isNotBlank() && !baseUrl.contains("127.0.0.1")) {
            saveLastGoodHubUrl(baseUrl)
            saveRelayUrls((listOf(baseUrl) + loadSavedRelayUrls()).distinct().take(8))
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
        } else if (baseUrl.isNotBlank()) {
            val wsUrl = hubWsUrlOf(baseUrl)
            _state.update { it.copy(discoveredBaseUrl = baseUrl, discoveredWsUrl = wsUrl) }
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
                activeSessionUsage = null,
                openedDiffContent = null,
                openedDiffTitle = null,
                checkpointNotice = null,
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
                activeSessionUsage = null,
                openedDiffContent = null,
                openedDiffTitle = null,
                checkpointNotice = null,
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
                activeSessionUsage = null,
                openedDiffContent = null,
                openedDiffTitle = null,
                checkpointNotice = null,
                selectedEngineId = targetEngineId,
                selectedModelId = resolvedModel,
                selectedEffort = resolvedEffort,
                selectedMode = resolvedMode,
                selectedWorkspacePath = meta?.cwd ?: s.selectedWorkspacePath,
                currentBrowsePath = meta?.cwd ?: s.currentBrowsePath,
                currentDiffWorkspacePath = meta?.cwd ?: s.selectedWorkspacePath,
            )
        }
        scope.launch {
            runCatching { subscribeAndCatchUp(sessionId) }
            runCatching {
                val ckpts = rpc.listCheckpoints(sessionId)
                if (_state.value.activeSessionId == sessionId) {
                    _state.update { it.copy(checkpoints = ckpts) }
                }
            }
            runCatching {
                val usage = rpc.fetchSessionUsage(sessionId)
                if (_state.value.activeSessionId == sessionId) {
                    _state.update { it.copy(activeSessionUsage = usage) }
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
        if (event is AgentEvent.Usage || event is AgentEvent.TurnDone) {
            refreshActiveSessionUsage(event.sessionId)
        }
    }

    // -----------------------------------------------------------------------
    // Usage & Subscription Quota Telemetry Actions
    // -----------------------------------------------------------------------

    fun refreshActiveSessionUsage(sessionId: String = _state.value.activeSessionId.orEmpty()) {
        if (sessionId.isBlank()) return
        scope.launch {
            runCatching {
                val usage = rpc.fetchSessionUsage(sessionId)
                if (_state.value.activeSessionId == sessionId) {
                    _state.update { it.copy(activeSessionUsage = usage) }
                }
            }
        }
    }

    fun refreshUsageDashboard(days: Int = _state.value.usageSelectedDays) {
        val clampedDays = days.coerceIn(1, 90)
        _state.update { it.copy(usageSelectedDays = clampedDays, usageLoading = true) }
        val activeSid = _state.value.activeSessionId
        scope.launch {
            val summary = runCatching { rpc.fetchUsageSummary(days = clampedDays) }.getOrNull()
            val quota = runCatching { rpc.fetchUsageQuota() }.getOrNull()
            val proxy = runCatching { rpc.fetchProxyUsage() }.getOrNull()
            val sessUsage = activeSid?.let { sid -> runCatching { rpc.fetchSessionUsage(sid) }.getOrNull() }
            _state.update { s ->
                s.copy(
                    usageLoading = false,
                    usageSummary = summary ?: s.usageSummary,
                    usageQuota = quota ?: s.usageQuota,
                    proxyUsage = proxy ?: s.proxyUsage,
                    activeSessionUsage = if (activeSid != null && s.activeSessionId == activeSid) {
                        sessUsage ?: s.activeSessionUsage
                    } else {
                        s.activeSessionUsage
                    },
                )
            }
        }
    }

    // -----------------------------------------------------------------------
    // Workspace Git Diff & Snapshot Checkpoints Actions
    // -----------------------------------------------------------------------

    fun refreshDiffAndCheckpoints(workspacePath: String? = null) {
        val resolvedPath = (workspacePath ?: _state.value.currentDiffWorkspacePath)
            .ifBlank { _state.value.activeWorkspaces.firstOrNull().orEmpty() }
            .ifBlank { _state.value.selectedWorkspacePath }
        if (resolvedPath.isBlank()) return
        val activeSid = _state.value.activeSessionId
        _state.update {
            it.copy(
                currentDiffWorkspacePath = resolvedPath,
                gitLoading = true,
                checkpointNotice = null,
            )
        }
        scope.launch {
            val status = runCatching { rpc.fetchGitStatus(resolvedPath) }.getOrNull()
            val ckpts = activeSid?.let { sid -> runCatching { rpc.listCheckpoints(sid) }.getOrNull() }
            _state.update { s ->
                s.copy(
                    gitLoading = false,
                    gitStatus = status ?: s.gitStatus,
                    checkpoints = ckpts ?: s.checkpoints,
                )
            }
        }
    }

    fun switchDiffWorkspace(path: String) {
        if (path.isBlank()) return
        _state.update {
            it.copy(
                currentDiffWorkspacePath = path,
                openedDiffContent = null,
                openedDiffTitle = null,
                openedDiffTruncated = false,
            )
        }
        refreshDiffAndCheckpoints(path)
    }

    fun openGitDiff(filePath: String?, baseCommitSha: String?, title: String) {
        val wsPath = _state.value.currentDiffWorkspacePath
            .ifBlank { _state.value.activeWorkspaces.firstOrNull().orEmpty() }
            .ifBlank { _state.value.selectedWorkspacePath }
        if (wsPath.isBlank()) return
        _state.update { it.copy(gitLoading = true) }
        scope.launch {
            runCatching {
                val res = rpc.fetchGitDiff(
                    path = wsPath,
                    filePath = filePath,
                    baseCommitSha = baseCommitSha,
                )
                _state.update {
                    it.copy(
                        gitLoading = false,
                        openedDiffTitle = title,
                        openedDiffContent = res.diff,
                        openedDiffTruncated = res.truncated,
                    )
                }
            }.onFailure { err ->
                _state.update {
                    it.copy(
                        gitLoading = false,
                        openedDiffTitle = title,
                        openedDiffContent = "无法读取对比差异: ${err.message}",
                        openedDiffTruncated = false,
                    )
                }
            }
        }
    }

    fun closeGitDiff() {
        _state.update {
            it.copy(
                openedDiffTitle = null,
                openedDiffContent = null,
                openedDiffTruncated = false,
            )
        }
    }

    fun createManualCheckpoint(label: String? = null) {
        val sid = _state.value.activeSessionId ?: return
        _state.update { it.copy(gitLoading = true, checkpointNotice = null) }
        scope.launch {
            runCatching {
                val created = rpc.createCheckpoint(sid, label)
                val list = rpc.listCheckpoints(sid)
                _state.update {
                    it.copy(
                        gitLoading = false,
                        checkpoints = list,
                        checkpointNotice = "已保存快照：${created.label}",
                    )
                }
            }.onFailure { err ->
                _state.update {
                    it.copy(
                        gitLoading = false,
                        checkpointNotice = "保存快照失败：${err.message}",
                    )
                }
            }
        }
    }

    fun restoreCheckpoint(checkpoint: CheckpointInfo) {
        val sid = _state.value.activeSessionId ?: return
        _state.update { it.copy(gitLoading = true, checkpointNotice = null) }
        scope.launch {
            runCatching {
                rpc.restoreCheckpoint(sid, checkpoint.id)
                val wsPath = _state.value.currentDiffWorkspacePath
                    .ifBlank { _state.value.activeWorkspaces.firstOrNull().orEmpty() }
                val nextStatus = wsPath.takeIf { it.isNotBlank() }?.let {
                    runCatching { rpc.fetchGitStatus(it) }.getOrNull()
                }
                val list = rpc.listCheckpoints(sid)
                _state.update { s ->
                    s.copy(
                        gitLoading = false,
                        gitStatus = nextStatus ?: s.gitStatus,
                        checkpoints = list,
                        openedDiffContent = null,
                        openedDiffTitle = null,
                        checkpointNotice = "已回退至「${checkpoint.label}」（回退前改动已自动备份）",
                    )
                }
            }.onFailure { err ->
                _state.update {
                    it.copy(
                        gitLoading = false,
                        checkpointNotice = "回退失败：${err.message}",
                    )
                }
            }
        }
    }
}
