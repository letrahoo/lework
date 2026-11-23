@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package dev.lework.store

import dev.lework.platform.*
import dev.lework.protocol.*
import dev.lework.security.HubAttestation
import dev.lework.security.HubTrust
import dev.lework.security.hubBaseUrlOfWs
import dev.lework.security.hubWsUrlOf
import dev.lework.security.isSafeExternalLink
import dev.lework.security.isTrustedOAuthUrl
import dev.lework.security.normalizeHubBaseUrl
import dev.lework.transport.HubRpcClient
import dev.lework.transport.HubTransport
import dev.lework.transport.HubTrustException
import dev.lework.transport.RpcCallException
import kotlin.io.encoding.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

class ConsoleStore(
    private val scope: CoroutineScope,
    private val rpc: HubTransport = HubRpcClient(scope),
) {
    companion object {
        internal const val SEAMLESS_CONTINUE_PROMPT =
            "网络已恢复，请基于刚才的进度继续完成上一轮未完成的任务。"
        private const val ERROR_AGGREGATION_WINDOW_MS = 120_000L
        private const val DUPLICATE_ERROR_DEDUP_MS = 2_000L
    }

    private val initialPrefs = loadSessionPrefs()
    private val _state = MutableStateFlow(
        ConsoleUiState(
            selectedEngineId = initialPrefs.engineId,
            selectedProviderId = initialPrefs.providerId,
            selectedModelId = initialPrefs.modelId,
            selectedEffort = parseEffort(initialPrefs.reasoningEffort),
            selectedMode = parseMode(initialPrefs.mode),
            favoriteModelKeys = initialPrefs.favoriteModelKeys,
            pinnedSessionIds = initialPrefs.pinnedSessionIds,
            selectedWorkspacePath = initialPrefs.workspacePath,
            currentBrowsePath = initialPrefs.workspacePath,
        ),
    )
    val state: StateFlow<ConsoleUiState> = _state.asStateFlow()
    private var discoverJob: Job? = null
    private var oauthPollJob: Job? = null
    private var tsnetPollJob: Job? = null
    private var resumeJob: Job? = null
    private var networkHandoffJob: Job? = null
    private var activeTurnSyncJob: Job? = null
    private var reconnectRetryJob: Job? = null
    private var lastRealtimeActivityMs: Long = 0L
    private var subscribedSessionId: String? = null
    private val warmSubscribedSessionIds = mutableSetOf<String>()
    private var subscribeSeqToken: Long = 0L
    private var userManuallyPickedModel: Boolean = false
    private var userManuallyPickedEffort: Boolean = false
    private var userManuallyPickedMode: Boolean = false
    private var modelSwitchVersion: Long = 0L
    private val pendingModelSwitchBySession = mutableMapOf<String, Pair<Long, Triple<String, ReasoningEffort, SessionMode>>>()
    private var manualPairingMode: Boolean = false
    private var pairingInFlight: Boolean = false
    private var pendingDeepLinkSessionId: String? = null
    private var mentionJob: Job? = null
    private val lastSubmittedPromptBySession = mutableMapOf<String, String>()
    private val lastErrorSignatureBySession = mutableMapOf<String, Pair<String, Long>>()
    private var consecutiveReconnectFailures: Int = 0
    private var activeUserSendOrRetryDepth: Int = 0
    private var subagentInspectJob: Job? = null
    private var subagentInspectToken: Long = 0L
    private var subscribedSubagentId: String? = null

    private fun switchSubscriptionTo(nextSessionId: String?) {
        val prev = subscribedSessionId
        subscribedSessionId = nextSessionId
        subscribeSeqToken++
        if (prev != nextSessionId) {
            clearInspectedSubagent(updateState = true)
        }
        if (prev != null && prev != nextSessionId) {
            val prevMeta = _state.value.sessions.firstOrNull { it.id == prev }
            val keepWarmOnDesktop =
                isDesktopPlatform() &&
                    prevMeta != null &&
                    !prevMeta.archived &&
                    (prevMeta.state == SessionState.RUNNING || prevMeta.state == SessionState.WAITING_PERMISSION)
            if (keepWarmOnDesktop) {
                warmSubscribedSessionIds.add(prev)
            } else {
                warmSubscribedSessionIds.remove(prev)
                scope.launch {
                    runCatching { rpc.unsubscribeSession(prev) }
                }
            }
        }
    }

    private fun reconcileDesktopRunningWarmSubscriptions(sessions: List<SessionMeta> = _state.value.sessions) {
        if (!isDesktopPlatform()) return
        val desiredRunningIds = sessions
            .filter {
                !it.archived &&
                    (it.state == SessionState.RUNNING || it.state == SessionState.WAITING_PERMISSION)
            }
            .take(6)
            .map { it.id }
            .toSet()

        val staleWarm = warmSubscribedSessionIds.filterNot { it in desiredRunningIds || it == subscribedSessionId }
        for (sid in staleWarm) {
            warmSubscribedSessionIds.remove(sid)
            scope.launch {
                runCatching { rpc.unsubscribeSession(sid) }
            }
        }

        for (sid in desiredRunningIds) {
            if (sid == subscribedSessionId || sid in warmSubscribedSessionIds) continue
            warmSubscribedSessionIds.add(sid)
            scope.launch {
                runCatching {
                    val existing = _state.value.transcripts[sid]
                    val isInitial = existing == null || existing.lastSeq < 0
                    val snap = rpc.subscribeSession(
                        sessionId = sid,
                        afterSeq = if (isInitial) -1 else existing!!.lastSeq,
                        limit = if (isInitial) 120 else null,
                    )
                    _state.update { s ->
                        val nextTranscript = if (isInitial) {
                            reduceTranscriptBatch(SessionTranscript(), snap.events).copy(
                                earliestSeq = snap.earliestSeq ?: snap.events.firstOrNull()?.seq,
                                hasMoreBefore = snap.hasMoreBefore == true,
                            )
                        } else {
                            reduceTranscriptBatch(s.transcripts[sid] ?: SessionTranscript(), snap.events)
                        }
                        s.copy(transcripts = s.transcripts + (sid to nextTranscript))
                    }
                }.onFailure {
                    warmSubscribedSessionIds.remove(sid)
                }
            }
        }
    }

    init {
        scope.launch {
            observeIncomingPairingUri().collect { uri ->
                if (!uri.isNullOrBlank()) {
                    consumeIncomingPairingUri()
                    val sessionLink = parseSessionDeepLink(uri)
                    if (sessionLink != null) {
                        handleSessionDeepLink(sessionLink.first, sessionLink.second)
                    } else {
                        promptDeepLinkPairingConfirmation(uri)
                    }
                }
            }
        }
        scope.launch {
            observeBonjourHubUrls().collect { urls ->
                if (!manualPairingMode && urls.isNotEmpty() && !_state.value.hasEnteredWorkspace && _state.value.connectionStatus == ConnectionStatus.ERROR) {
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
                    } else if (_state.value.tsnetLoggingIn) {
                        checkTsnetAfterForegroundReturn()
                    }
                }
            }
        }
        scope.launch {
            var lastSeenEpoch = observeNetworkEpoch().value
            observeNetworkEpoch().collect { epoch ->
                if (epoch <= lastSeenEpoch) return@collect
                lastSeenEpoch = epoch
                networkHandoffJob?.cancel()
                networkHandoffJob = scope.launch {
                    delay(650L)
                    val cur = _state.value
                    if (manualPairingMode || pairingInFlight) return@launch
                    if (cur.hasEnteredWorkspace && observeAppForeground().value) {
                        resumeJob?.cancel()
                        verifyAndResumeForegroundSession(fromNetworkHandoff = true)
                    } else if (!cur.hasEnteredWorkspace && cur.connectionStatus == ConnectionStatus.ERROR) {
                        bootstrapOrConnect()
                    }
                }
            }
        }
        scope.launch {
            rpc.events.collect { ev ->
                lastRealtimeActivityMs = epochMillis()
                applyIncomingEvent(ev)
            }
        }
        scope.launch {
            rpc.sessionUpdates.collect { updated ->
                lastRealtimeActivityMs = epochMillis()
                val prevActive = _state.value.activeSession?.takeIf { it.id == updated.id }
                val transitionedToIdle =
                    prevActive != null &&
                        (prevActive.state == SessionState.RUNNING || prevActive.state == SessionState.WAITING_PERMISSION) &&
                        updated.state == SessionState.IDLE
                _state.update { s ->
                    val pendingOverride = pendingModelSwitchBySession[updated.id]?.second
                    val mergedUpdated = if (pendingOverride != null) {
                        updated.copy(
                            model = pendingOverride.first,
                            reasoningEffort = pendingOverride.second,
                            mode = pendingOverride.third,
                        )
                    } else {
                        updated
                    }
                    val existing = s.sessions.indexOfFirst { it.id == mergedUpdated.id }
                    val nextList = if (existing >= 0) {
                        s.sessions.toMutableList().also { it[existing] = mergedUpdated }
                    } else {
                        listOf(mergedUpdated) + s.sessions
                    }
                    val syncMode = if (s.activeSessionId == mergedUpdated.id && mergedUpdated.mode != null) {
                        mergedUpdated.mode
                    } else {
                        s.selectedMode
                    }
                    val syncModel = if (s.activeSessionId == mergedUpdated.id && !mergedUpdated.model.isNullOrBlank()) {
                        mergedUpdated.model
                    } else {
                        s.selectedModelId
                    }
                    val syncEffort = if (s.activeSessionId == mergedUpdated.id && mergedUpdated.reasoningEffort != null) {
                        mergedUpdated.reasoningEffort
                    } else {
                        s.selectedEffort
                    }
                    val enteredErrorState =
                        s.activeSessionId == mergedUpdated.id &&
                            mergedUpdated.state == SessionState.ERROR &&
                            prevActive?.state != SessionState.ERROR
                    val nextBanner = if (enteredErrorState) {
                        val nowMs = epochMillis()
                        val errDetail = mergedUpdated.liveStatusText?.takeIf { it.isNotBlank() } ?: "上一轮任务执行中断"
                        if (isDuplicateRecentError(mergedUpdated.id, errDetail, nowMs)) {
                            s.turnRecoveryBanner
                        } else {
                            markRecentError(mergedUpdated.id, errDetail, nowMs)
                            buildTurnRecoveryBanner(
                                previous = s.turnRecoveryBanner,
                                sessionId = mergedUpdated.id,
                                rawMessage = errDetail,
                                nowMs = nowMs,
                            )
                        }
                    } else {
                        s.turnRecoveryBanner
                    }
                    val syncedSubagent = if (s.activeSessionId == mergedUpdated.id && s.inspectedSubagent != null) {
                        mergedUpdated.subagents?.firstOrNull { it.id == s.inspectedSubagent.id } ?: s.inspectedSubagent
                    } else {
                        s.inspectedSubagent
                    }
                    s.copy(
                        sessions = nextList,
                        selectedMode = syncMode,
                        selectedModelId = syncModel,
                        selectedEffort = syncEffort,
                        turnRecoveryBanner = nextBanner,
                        inspectedSubagent = syncedSubagent,
                    )
                }
                reconcileDesktopRunningWarmSubscriptions(_state.value.sessions)
                if (transitionedToIdle && _state.value.activeSessionId == updated.id) {
                    scope.launch {
                        runCatching { subscribeAndCatchUp(updated.id) }
                        refreshActiveSessionUsage(updated.id)
                        refreshSessionArtifacts(updated.id)
                    }
                }
            }
        }
        scope.launch {
            rpc.disconnected.collect { reason ->
                val cur = _state.value
                if (!manualPairingMode && cur.hasEnteredWorkspace) {
                    _state.update {
                        it.copy(
                            connectionStatus = ConnectionStatus.RECONNECTING,
                            connectionError = null,
                        )
                    }
                    if (observeAppForeground().value) {
                        verifyAndResumeForegroundSession(fromNetworkHandoff = true)
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
        // State-reactive watchdog: only runs while an active session is RUNNING/WAITING_PERMISSION
        // (catches missed frames & zombie half-open sockets within 4.5s) or while RECONNECTING in foreground.
        scope.launch {
            _state.collect { st ->
                val activeSess = st.activeSession?.takeIf { it.id == st.activeSessionId }
                val needTurnWatchdog =
                    st.hasEnteredWorkspace &&
                        st.connectionStatus == ConnectionStatus.CONNECTED &&
                        activeSess != null &&
                        (activeSess.state == SessionState.RUNNING || activeSess.state == SessionState.WAITING_PERMISSION)

                if (needTurnWatchdog) {
                    if (activeTurnSyncJob?.isActive != true) {
                        activeTurnSyncJob = scope.launch {
                            while (isActive) {
                                delay(4_500L)
                                val cur = _state.value
                                val sid = cur.activeSessionId ?: break
                                val curSess = cur.activeSession?.takeIf { it.id == sid } ?: break
                                if (cur.connectionStatus != ConnectionStatus.CONNECTED ||
                                    (curSess.state != SessionState.RUNNING && curSess.state != SessionState.WAITING_PERMISSION)
                                ) {
                                    break
                                }
                                if (!observeAppForeground().value) continue
                                if (epochMillis() - lastRealtimeActivityMs < 4_000L) continue
                                try {
                                    withTimeout(4_000L) {
                                        subscribeAndCatchUp(sid)
                                    }
                                    lastRealtimeActivityMs = epochMillis()
                                } catch (ce: CancellationException) {
                                    throw ce
                                } catch (_: Throwable) {
                                    // Dead / half-open zombie WebSocket detected during long turn: reconnect immediately!
                                    runCatching { rpc.disconnect() }
                                    _state.update {
                                        it.copy(connectionStatus = ConnectionStatus.RECONNECTING, connectionError = null)
                                    }
                                    verifyAndResumeForegroundSession(fromNetworkHandoff = true)
                                    break
                                }
                            }
                        }
                    }
                } else {
                    activeTurnSyncJob?.cancel()
                    activeTurnSyncJob = null
                }

                val needReconnectRetry =
                    !manualPairingMode &&
                        st.hasEnteredWorkspace &&
                        st.connectionStatus == ConnectionStatus.RECONNECTING
                if (needReconnectRetry) {
                    if (reconnectRetryJob?.isActive != true) {
                        reconnectRetryJob = scope.launch {
                            while (isActive) {
                                delay(3_500L)
                                val cur = _state.value
                                if (manualPairingMode || !cur.hasEnteredWorkspace || cur.connectionStatus != ConnectionStatus.RECONNECTING) {
                                    break
                                }
                                if (observeAppForeground().value && resumeJob?.isActive != true) {
                                    verifyAndResumeForegroundSession(fromNetworkHandoff = true)
                                }
                            }
                        }
                    }
                } else {
                    reconnectRetryJob?.cancel()
                    reconnectRetryJob = null
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
                favoriteModelKeys = st.favoriteModelKeys,
                pinnedSessionIds = st.pinnedSessionIds,
            ),
        )
    }

    private fun upsertPairedHost(
        hub: HubInfo,
        fingerprint: String?,
        endpoints: List<String>,
        userName: String,
    ) {
        val allowLoopback = isDesktopPlatform()
        val cleanEndpoints = endpoints
            .map { normalizeHubBaseUrl(it) }
            .filter { it.isNotBlank() && (allowLoopback || !it.contains("127.0.0.1")) }
            .distinct()
            .take(8)
        val existing = loadPairedHosts()
        val prev = existing.firstOrNull { it.hubId == hub.id }
        val mergedEndpoints = (cleanEndpoints + prev?.endpoints.orEmpty()).distinct().take(8)
        val updated = PairedHost(
            hubId = hub.id,
            name = hub.name,
            fingerprint = fingerprint?.lowercase() ?: prev?.fingerprint.orEmpty(),
            endpoints = mergedEndpoints,
            userName = userName,
            lastConnectedAt = epochMillis(),
            customName = prev?.customName,
        )
        val nextList = (listOf(updated) + existing.filterNot { it.hubId == hub.id }).take(12)
        savePairedHosts(nextList)
        saveActiveHostId(hub.id)
        val persistedHosts = loadPairedHosts()
        _state.update { it.copy(pairedHosts = persistedHosts, activeHostId = hub.id) }
    }

    fun bootstrapOrConnect() {
        if (pairingInFlight || _state.value.pendingDeepLinkPairing != null || !observeIncomingPairingUri().value.isNullOrBlank()) {
            return
        }
        bootstrapOrConnectToHost(loadActiveHostId())
    }

    private fun launchTsnetDiscoveryFeeder(): Job? {
        if (!isMobileTsnetAvailable() || !loadSavedTsnetEnabled()) return null
        return scope.launch {
            var cur = withTimeoutOrNull(10_000L) {
                runCatching { startMobileTsnet(loadSavedTsnetControlUrl()) }.getOrNull()
            }
            if (cur != null) {
                _state.update { it.copy(tsnetStatus = cur) }
            }
            var attempt = 0
            while (isActive && attempt < 7) {
                val hasReadyPeer = cur?.peers?.any { it.online && !it.localProxyUrl.isNullOrBlank() } == true
                if (hasReadyPeer) break
                delay(1_200L)
                val next = withTimeoutOrNull(5_000L) {
                    runCatching { queryMobileTsnetStatus() }.getOrNull()
                }
                if (next != null) {
                    cur = next
                    _state.update { it.copy(tsnetStatus = next) }
                }
                attempt++
            }
        }
    }

    private fun bootstrapOrConnectToHost(preferredHubId: String?) {
        manualPairingMode = false
        discoverJob?.cancel()
        oauthPollJob?.cancel()
        tsnetPollJob?.cancel()
        resumeJob?.cancel()
        startBonjourDiscovery()
        val tsnetActive = isMobileTsnetAvailable() && loadSavedTsnetEnabled()
        _state.update {
            it.copy(
                connectionStatus = ConnectionStatus.DISCOVERING,
                connectionError = null,
                pairedHosts = loadPairedHosts(),
                activeHostId = preferredHubId,
                tsnetStatus = it.tsnetStatus.copy(available = isMobileTsnetAvailable()),
                authStatusText = "正在自动发现同 Wi‑Fi 局域网、Tailscale 内网或已配对主机…",
            )
        }
        discoverJob = scope.launch {
            val feederJob = launchTsnetDiscoveryFeeder()
            val found = try {
                rpc.discoverIntranetHub(
                    candidates = candidateHubHttpUrls(),
                    dynamicCandidates = observeBonjourHubUrls(),
                    waitWindowMs = if (tsnetActive) 12_000L else 7_000L,
                    preferredHubId = preferredHubId,
                )
            } finally {
                feederJob?.cancel()
            }
            if (found == null) {
                _state.update {
                    it.copy(
                        connectionStatus = ConnectionStatus.ERROR,
                        connectionError = "未发现可用主机：请确认与主机处于同一 Wi‑Fi，或使用下方「扫码配对」/「Tailscale 同账号组网」接入",
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

            val hostToken = loadHostAccessToken(found.discover.hub.id)
            val cachedToken = hostToken ?: loadSavedAccessToken()
            if (!cachedToken.isNullOrBlank()) {
                val ok = connectWithToken(found.wsUrl, cachedToken, expectedHubId = found.discover.hub.id)
                if (ok) return@launch
                val stillHasToken =
                    !(loadHostAccessToken(found.discover.hub.id) ?: loadSavedAccessToken()).isNullOrBlank()
                if (stillHasToken && !found.discover.tailscaleSameUser) {
                    // Network/transport failure rather than invalid token: stay on retry screen without wiping token
                    return@launch
                }
            }

            // Zero-touch Tailscale same-account handshake: when both ends belong to the exact same
            // Tailscale user, automatically verify, mint a revocable device token, pin TLS, and enter!
            if (found.discover.tailscaleSameUser) {
                _state.update {
                    it.copy(
                        connectionStatus = ConnectionStatus.CONNECTING,
                        authStatusText = "已识别同账号 Tailscale 主机 ${found.discover.hub.name}，正在自动握手建联…",
                    )
                }
                val hs = runCatching {
                    rpc.authenticateViaTailscale(found.baseUrl, platformName())
                }.getOrNull()
                if (hs != null) {
                    val userName = hs.user?.name ?: found.discover.tailscaleUser ?: "Owner"
                    saveAuthSession(hs.accessToken, userName)
                    saveHostAccessToken(hs.hub.id, hs.accessToken)
                    upsertPairedHost(hs.hub, hs.fingerprint, hs.endpoints, userName)
                    _state.update { it.copy(authUserName = userName) }
                    val ok = connectWithToken(
                        found.wsUrl,
                        hs.accessToken,
                        expectedHubId = hs.hub.id,
                        expectedFingerprint = hs.fingerprint,
                    )
                    if (ok) return@launch
                }
            }

            if (found.discover.oauthEnabled) {
                startOAuthLoginFlow(found.baseUrl, found.wsUrl)
            } else {
                _state.update {
                    it.copy(
                        connectionStatus = ConnectionStatus.NEEDS_AUTH,
                        oauthChallenge = null,
                        authStatusText = "已发现主机 ${found.discover.hub.name}，请扫码配对、Tailscale 同账号组网或输入令牌连接",
                    )
                }
            }
        }
    }

    fun startMobileTsnetLogin(customControlUrl: String? = null) {
        if (!isMobileTsnetAvailable()) {
            _state.update {
                it.copy(
                    connectionError = "当前设备客户端未内置 Tailscale 模块，请使用扫码配对或手动地址连接",
                )
            }
            return
        }
        discoverJob?.cancel()
        oauthPollJob?.cancel()
        tsnetPollJob?.cancel()
        val cleanCtrl = customControlUrl?.trim()?.takeIf { it.isNotEmpty() }
        saveTsnetEnabled(true, cleanCtrl ?: "")
        _state.update {
            it.copy(
                tsnetLoggingIn = true,
                connectionStatus = ConnectionStatus.NEEDS_AUTH,
                connectionError = null,
                authStatusText = "正在启动内置 Tailscale 组网节点…",
            )
        }
        tsnetPollJob = scope.launch {
            val st = loginMobileTsnet(cleanCtrl)
            _state.update { it.copy(tsnetStatus = st) }
            if (st.backendState == "Running") {
                _state.update { it.copy(tsnetLoggingIn = false) }
                searchAndConnectViaTsnet()
                return@launch
            }
            val authUrl = st.authUrl
            if (!authUrl.isNullOrBlank() && isSafeExternalLink(authUrl)) {
                _state.update {
                    it.copy(
                        authStatusText = "请在浏览器登录与电脑主机相同的 Tailscale 账号，完成后自动发现并建联",
                    )
                }
                openExternalUrl(authUrl)
            } else if (!st.error.isNullOrBlank()) {
                _state.update {
                    it.copy(
                        tsnetLoggingIn = false,
                        connectionStatus = ConnectionStatus.ERROR,
                        connectionError = "Tailscale 启动失败: ${st.error}",
                    )
                }
                return@launch
            }

            while (isActive) {
                delay(2_000L)
                val cur = queryMobileTsnetStatus()
                _state.update { it.copy(tsnetStatus = cur) }
                if (cur.backendState == "Running") {
                    _state.update { it.copy(tsnetLoggingIn = false) }
                    searchAndConnectViaTsnet()
                    break
                }
            }
        }
    }

    private fun checkTsnetAfterForegroundReturn() {
        scope.launch {
            val cur = queryMobileTsnetStatus()
            _state.update { it.copy(tsnetStatus = cur) }
            if (cur.backendState == "Running") {
                tsnetPollJob?.cancel()
                _state.update { it.copy(tsnetLoggingIn = false) }
                searchAndConnectViaTsnet()
            }
        }
    }

    /**
     * Exclusively searches untagged Tailscale peers under the exact same account via `tsnet`,
     * probes their `:64450` Hub endpoints directly (never hijacked by LAN / OAuth / old relays),
     * and performs zero-touch same-account handshake.
     */
    fun searchAndConnectViaTsnet() {
        if (!isMobileTsnetAvailable()) return
        if (discoverJob?.isActive == true && _state.value.authStatusText.contains("Tailscale")) {
            return
        }
        discoverJob?.cancel()
        oauthPollJob?.cancel()
        tsnetPollJob?.cancel()

        discoverJob = scope.launch {
            _state.update {
                it.copy(
                    tsnetLoggingIn = false,
                    connectionStatus = ConnectionStatus.CONNECTING,
                    connectionError = null,
                    oauthChallenge = null,
                    authStatusText = "正在同步 Tailscale 同账号节点列表…",
                )
            }

            var status = startMobileTsnet(loadSavedTsnetControlUrl())
            _state.update { it.copy(tsnetStatus = status) }
            if (status.backendState != "Running") {
                _state.update {
                    it.copy(
                        connectionStatus = ConnectionStatus.NEEDS_AUTH,
                        authStatusText = "Tailscale 尚未完成登录，请点击下方按钮登录",
                    )
                }
                return@launch
            }

            for (attempt in 0 until 4) {
                if (status.peers.any { !it.localProxyUrl.isNullOrBlank() }) break
                delay(1_200L)
                status = queryMobileTsnetStatus()
                _state.update { it.copy(tsnetStatus = status) }
            }

            val candidates = status.peers.filter { !it.localProxyUrl.isNullOrBlank() }
            if (candidates.isEmpty()) {
                val acct = status.loginName ?: "当前账号"
                _state.update {
                    it.copy(
                        connectionStatus = ConnectionStatus.ERROR,
                        connectionError = "Tailscale ($acct) 下暂未发现其他同账号设备。请在电脑终端执行：lework tsnet login 并登录同一账号",
                    )
                }
                return@launch
            }

            var currentCandidates = candidates
            var probed = emptyList<Pair<dev.lework.platform.MobileTsnetPeer, dev.lework.transport.DiscoveredEndpoint>>()
            // Newly authorized or freshly woken tsnet nodes can flip to "Running" 1–3s before
            // remote peers receive the new WireGuard public key via Tailscale NetMap or DERP warms up.
            // Retry fast-failing probes up to 3 times before surfacing an error.
            for (probeRound in 0 until 3) {
                val peerSummary = currentCandidates.take(4).joinToString(", ") { it.hostName }
                _state.update {
                    it.copy(
                        connectionStatus = ConnectionStatus.CONNECTING,
                        authStatusText = "正在通过 Tailscale 探测同账号节点 ($peerSummary) 并握手…",
                    )
                }

                probed = coroutineScope {
                    currentCandidates.map { peer ->
                        async {
                            val proxyUrl = peer.localProxyUrl ?: return@async null
                            val ep = rpc.probeDirectEndpoint(proxyUrl, timeoutMs = 8_000L)
                            if (ep != null) peer to ep else null
                        }
                    }.mapNotNull { it.await() }
                }

                if (probed.any { it.second.discover.tailscaleSameUser } || (probed.isNotEmpty() && probeRound == 2)) {
                    break
                }
                if (probeRound < 2) {
                    delay(1_400L)
                    val refreshed = queryMobileTsnetStatus()
                    _state.update { it.copy(tsnetStatus = refreshed) }
                    val nextCandidates = refreshed.peers.filter { !it.localProxyUrl.isNullOrBlank() }
                    if (nextCandidates.isNotEmpty()) {
                        currentCandidates = nextCandidates
                    }
                }
            }

            if (probed.isEmpty()) {
                val peerSummary = currentCandidates.take(4).joinToString(", ") { it.hostName }
                _state.update {
                    it.copy(
                        connectionStatus = ConnectionStatus.ERROR,
                        connectionError = "已发现同账号节点 [$peerSummary]，但未检测到开启 Tailscale 组网的 Lework 主机服务。请在目标电脑执行：lework tsnet login",
                    )
                }
                return@launch
            }

            val (_, chosen) = probed.firstOrNull { it.second.discover.tailscaleSameUser } ?: probed.first()
            _state.update {
                it.copy(
                    discoveredBaseUrl = chosen.baseUrl,
                    discoveredWsUrl = chosen.wsUrl,
                    oauthEnabled = chosen.discover.oauthEnabled,
                    oauthProvider = chosen.discover.oauthProvider,
                    oauthDisplayName = chosen.discover.oauthDisplayName,
                    hub = chosen.discover.hub,
                )
            }

            var lastErr: String? = null
            if (chosen.discover.tailscaleSameUser) {
                _state.update {
                    it.copy(
                        authStatusText = "已验证同账号主机 ${chosen.discover.hub.name}，正在签发设备凭证…",
                    )
                }
                runCatching {
                    rpc.authenticateViaTailscale(chosen.baseUrl, platformName())
                }.onSuccess { hs ->
                    val userName = hs.user?.name ?: chosen.discover.tailscaleUser ?: status.displayName ?: "Owner"
                    saveAuthSession(hs.accessToken, userName)
                    saveHostAccessToken(hs.hub.id, hs.accessToken)
                    upsertPairedHost(hs.hub, hs.fingerprint, hs.endpoints, userName)
                    _state.update { it.copy(authUserName = userName) }
                    if (connectWithToken(
                            chosen.wsUrl,
                            hs.accessToken,
                            expectedHubId = hs.hub.id,
                            expectedFingerprint = hs.fingerprint,
                        )
                    ) {
                        return@launch
                    }
                }.onFailure { err ->
                    lastErr = err.message
                }
            }

            val existingToken = loadHostAccessToken(chosen.discover.hub.id) ?: loadSavedAccessToken()
            if (!existingToken.isNullOrBlank()) {
                if (connectWithToken(chosen.wsUrl, existingToken, expectedHubId = chosen.discover.hub.id)) {
                    return@launch
                }
            }

            _state.update {
                it.copy(
                    connectionStatus = ConnectionStatus.ERROR,
                    connectionError = lastErr
                        ?: "已连通主机 ${chosen.discover.hub.name}，但主机端未开启同账号校验，请在电脑执行：lework tsnet login",
                )
            }
        }
    }

    fun logoutMobileTsnetAccount() {
        tsnetPollJob?.cancel()
        saveTsnetEnabled(false)
        scope.launch {
            val st = logoutMobileTsnet()
            _state.update { it.copy(tsnetStatus = st, tsnetLoggingIn = false) }
        }
    }

    fun scanQrAndPair() {
        if (!canScanQrCode()) return
        requestQrCodeScan { scanned ->
            if (!scanned.isNullOrBlank()) {
                redeemPairingInput(scanned)
            }
        }
    }

    fun pastePairingFromClipboard() {
        val clip = readTextFromClipboard()?.trim().orEmpty()
        if (clip.startsWith("lework://", ignoreCase = true) || clip.startsWith("{")) {
            redeemPairingInput(clip)
        } else {
            _state.update {
                it.copy(
                    connectionStatus = ConnectionStatus.ERROR,
                    connectionError = "剪贴板中未检测到配对链接：请在开发机运行 lework pair 并复制 lework://pair?... 链接",
                )
            }
        }
    }

    fun pairViaSshHost(sshTarget: String) {
        val clean = sshTarget.trim()
        if (clean.isEmpty() || _state.value.sshPairingInProgress) return
        discoverJob?.cancel()
        oauthPollJob?.cancel()
        resumeJob?.cancel()
        _state.update {
            it.copy(
                sshPairingInProgress = true,
                connectionStatus = ConnectionStatus.CONNECTING,
                connectionError = null,
                authStatusText = "正在通过 SSH 连接 $clean 自动获取一次性配对凭证…",
            )
        }
        scope.launch {
            val res = requestSshHostPairingLink(clean)
            _state.update { it.copy(sshPairingInProgress = false) }
            res.fold(
                onSuccess = { deepLink ->
                    redeemPairingInput(deepLink)
                },
                onFailure = { err ->
                    _state.update {
                        it.copy(
                            connectionStatus = ConnectionStatus.ERROR,
                            connectionError = err.message ?: "通过 SSH 连接 $clean 失败",
                        )
                    }
                },
            )
        }
    }

    private fun promptDeepLinkPairingConfirmation(rawInput: String) {
        val payload = parsePairingPayload(rawInput)
        if (payload == null) {
            _state.update {
                it.copy(
                    connectionStatus = ConnectionStatus.ERROR,
                    connectionError = "无法识别配对链接，请在电脑主机运行 lework pair 后重新扫码",
                )
            }
            return
        }
        val primaryUrl = (payload.urls + listOf(payload.url))
            .map { normalizeHubBaseUrl(it) }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
        if (primaryUrl.isEmpty()) {
            _state.update {
                it.copy(
                    connectionStatus = ConnectionStatus.ERROR,
                    connectionError = "配对链接缺少有效主机地址",
                )
            }
            return
        }
        discoverJob?.cancel()
        oauthPollJob?.cancel()
        resumeJob?.cancel()
        val shortFp = payload.fp?.trim()?.takeIf { it.length >= 16 }?.let { HubAttestation.shortFingerprint(it) }
        _state.update {
            it.copy(
                pendingDeepLinkPairing = PendingDeepLinkPairing(
                    rawUri = rawInput,
                    hostName = payload.name.ifBlank { "Lework Hub" },
                    primaryUrl = primaryUrl,
                    shortFingerprint = shortFp,
                ),
            )
        }
    }

    fun confirmPendingDeepLinkPairing() {
        val pending = _state.value.pendingDeepLinkPairing ?: return
        _state.update { it.copy(pendingDeepLinkPairing = null) }
        redeemPairingInput(pending.rawUri)
    }

    fun dismissPendingDeepLinkPairing() {
        val wasPending = _state.value.pendingDeepLinkPairing != null
        _state.update { it.copy(pendingDeepLinkPairing = null) }
        if (wasPending && !_state.value.hasEnteredWorkspace) {
            bootstrapOrConnect()
        }
    }

    fun redeemPairingInput(rawInput: String) {
        val payload = parsePairingPayload(rawInput)
        if (payload == null) {
            _state.update {
                it.copy(
                    connectionStatus = ConnectionStatus.ERROR,
                    connectionError = "无法识别配对信息，请在电脑主机运行 hub pair 后重新扫码",
                )
            }
            return
        }
        val candidates = (payload.urls + listOf(payload.url))
            .map { normalizeHubBaseUrl(it) }
            .filter { it.isNotEmpty() }
            .distinct()
        if (candidates.isEmpty()) {
            _state.update {
                it.copy(
                    connectionStatus = ConnectionStatus.ERROR,
                    connectionError = "配对二维码缺少有效主机地址",
                )
            }
            return
        }

        manualPairingMode = false
        pairingInFlight = true
        discoverJob?.cancel()
        oauthPollJob?.cancel()
        tsnetPollJob?.cancel()
        resumeJob?.cancel()
        _state.update {
            it.copy(
                hasEnteredWorkspace = false,
                connectionStatus = ConnectionStatus.CONNECTING,
                connectionError = null,
                authStatusText = "正在与主机 ${payload.name.ifBlank { "Lework Hub" }} 验证指纹并建立安全连接…",
            )
        }
        discoverJob = scope.launch {
            try {
                var lastError: Throwable? = null
                for (baseUrl in candidates) {
                    try {
                        val auth = rpc.redeemPairingCode(
                            baseUrl = baseUrl,
                            pairCode = payload.pairCode,
                            staticToken = payload.token,
                            expectedFingerprint = payload.fp,
                            deviceName = platformName(),
                        )
                        val userName = auth.user?.name ?: "Owner"
                        saveAuthSession(auth.accessToken, userName)
                        saveHostAccessToken(auth.hub.id, auth.accessToken)
                        upsertPairedHost(
                            hub = auth.hub,
                            fingerprint = auth.fingerprint,
                            endpoints = auth.endpoints + candidates,
                            userName = userName,
                        )
                        val wsUrl = hubWsUrlOf(baseUrl)
                        _state.update {
                            it.copy(
                                discoveredBaseUrl = baseUrl,
                                discoveredWsUrl = wsUrl,
                                authUserName = userName,
                                hub = auth.hub,
                            )
                        }
                        if (connectWithToken(
                                wsUrl,
                                auth.accessToken,
                                expectedHubId = auth.hub.id,
                                expectedFingerprint = auth.fingerprint,
                            )
                        ) {
                            return@launch
                        }
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        lastError = t
                    }
                }
                _state.update {
                    it.copy(
                        connectionStatus = ConnectionStatus.ERROR,
                        connectionError = lastError?.message ?: "配对连接失败，请确认手机可访问主机网络",
                    )
                }
            } finally {
                pairingInFlight = false
            }
        }
    }

    fun switchPairedHost(hubId: String) {
        if (hubId == _state.value.hub?.id && _state.value.hasEnteredWorkspace) return
        manualPairingMode = false
        discoverJob?.cancel()
        oauthPollJob?.cancel()
        tsnetPollJob?.cancel()
        resumeJob?.cancel()
        switchSubscriptionTo(null)
        consecutiveReconnectFailures = 0
        saveActiveHostId(hubId)
        val hostToken = loadHostAccessToken(hubId)
        val hostMeta = loadPairedHosts().firstOrNull { it.hubId == hubId }
        if (!hostToken.isNullOrBlank()) {
            saveAuthSession(hostToken, hostMeta?.userName ?: "Owner")
        }
        _state.update {
            it.copy(
                hasEnteredWorkspace = false,
                connectionStatus = ConnectionStatus.DISCOVERING,
                connectionError = null,
                turnRecoveryBanner = null,
                retryingTurn = false,
                activeHostId = hubId,
                activeSessionId = null,
                authUserName = hostMeta?.userName ?: it.authUserName,
            )
        }
        scope.launch {
            rpc.disconnect()
            bootstrapOrConnectToHost(hubId)
        }
    }

    fun preparePairAnotherHost() {
        manualPairingMode = true
        discoverJob?.cancel()
        oauthPollJob?.cancel()
        tsnetPollJob?.cancel()
        resumeJob?.cancel()
        switchSubscriptionTo(null)
        consecutiveReconnectFailures = 0
        _state.update {
            it.copy(
                hasEnteredWorkspace = false,
                connectionStatus = ConnectionStatus.NEEDS_AUTH,
                oauthEnabled = false,
                oauthChallenge = null,
                connectionError = null,
                turnRecoveryBanner = null,
                retryingTurn = false,
                discoveredBaseUrl = "",
                discoveredWsUrl = "",
                pairedHosts = loadPairedHosts(),
                sshConfigHosts = listSshConfigHosts(),
                tsnetLoggingIn = false,
                tsnetStatus = it.tsnetStatus.copy(available = isMobileTsnetAvailable()),
                authStatusText = "可扫码配对新主机、使用 Tailscale 同账号发现，或点击已配对主机切换",
            )
        }
        scope.launch {
            rpc.disconnect()
            if (isMobileTsnetAvailable() && loadSavedTsnetEnabled()) {
                runCatching {
                    val st = startMobileTsnet(loadSavedTsnetControlUrl())
                    if (manualPairingMode && !_state.value.hasEnteredWorkspace) {
                        _state.update { it.copy(tsnetStatus = st) }
                    }
                }
            }
        }
    }

    fun renamePairedHost(hubId: String, newName: String) {
        val cleanId = hubId.trim()
        if (cleanId.isEmpty()) return
        val normalizedCustomName = newName.trim().take(64).ifEmpty { null }
        val existing = loadPairedHosts()
        val next = if (existing.any { it.hubId == cleanId }) {
            existing.map { host ->
                if (host.hubId == cleanId) host.copy(customName = normalizedCustomName) else host
            }
        } else {
            val current = _state.value
            val currentHub = current.hub?.takeIf { it.id == cleanId } ?: return
            val allowLoopback = isDesktopPlatform()
            val activeEndpoint = current.discoveredBaseUrl
                .let { normalizeHubBaseUrl(it) }
                .takeIf { it.isNotBlank() && (allowLoopback || !it.contains("127.0.0.1")) }
            val created = PairedHost(
                hubId = currentHub.id,
                name = currentHub.name,
                fingerprint = current.discoveredBaseUrl
                    .takeIf { it.isNotBlank() }
                    ?.let { HubTrust.observedFor(it) }
                    .orEmpty(),
                endpoints = listOfNotNull(activeEndpoint),
                userName = current.authUserName ?: loadSavedAuthUserName() ?: "Owner",
                lastConnectedAt = epochMillis(),
                customName = normalizedCustomName,
            )
            (listOf(created) + existing).take(12)
        }
        savePairedHosts(next)
        val persistedHosts = loadPairedHosts()
        _state.update { it.copy(pairedHosts = persistedHosts) }
    }

    fun removePairedHost(hubId: String) {
        saveHostAccessToken(hubId, null)
        val next = loadPairedHosts().filterNot { it.hubId == hubId }
        savePairedHosts(next)
        HubTrust.replacePins(next.mapNotNull { it.fingerprint.takeIf { fp -> fp.length == 64 } }.toSet())
        if (loadActiveHostId() == hubId) {
            saveActiveHostId(next.firstOrNull()?.hubId)
        }
        _state.update {
            it.copy(
                pairedHosts = next,
                activeHostId = loadActiveHostId(),
            )
        }
    }

    fun connectManualEndpoint(rawHostOrUrl: String, rawToken: String) {
        manualPairingMode = false
        val candidatePair = when {
            rawHostOrUrl.trim().startsWith("lework://", ignoreCase = true) ||
                rawHostOrUrl.trim().startsWith("{") -> rawHostOrUrl.trim()
            rawToken.trim().startsWith("lework://", ignoreCase = true) ||
                rawToken.trim().startsWith("{") -> rawToken.trim()
            else -> null
        }
        if (candidatePair != null) {
            redeemPairingInput(candidatePair)
            return
        }
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
    fun verifyAndResumeForegroundSession(fromNetworkHandoff: Boolean = false) {
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

            // If we were riding on embedded tsnet (`127.0.0.1:<port>`) and a network handoff just fired
            // (e.g., arriving home onto Wi-Fi), check whether a direct LAN endpoint is now reachable
            // so we can upgrade to direct LAN speed seamlessly.
            val currentBase = _state.value.discoveredBaseUrl
            var shouldUpgradeToLan = false
            if (fromNetworkHandoff && currentBase.startsWith("https://127.0.0.1:")) {
                val lanCandidates = candidateHubHttpUrls().filterNot {
                    it.startsWith("https://127.0.0.1:") || it.startsWith("https://10.0.2.2:") || it.startsWith("https://100.")
                }
                if (lanCandidates.isNotEmpty()) {
                    val preferredId = _state.value.hub?.id ?: loadActiveHostId()
                    val lanHit = rpc.discoverIntranetHub(
                        candidates = lanCandidates,
                        waitWindowMs = 800L,
                        preferredHubId = preferredId,
                    )
                    if (lanHit != null) {
                        shouldUpgradeToLan = true
                    }
                }
            }

            // Fast-path: if WebSocket remained alive across a brief lock or same-interface refresh, verify & catch up in place
            if (!shouldUpgradeToLan && _state.value.connectionStatus == ConnectionStatus.CONNECTED) {
                val fastProbeTimeoutMs = if (fromNetworkHandoff) 1_200L else 3_500L
                try {
                    val liveSessions = withTimeout(fastProbeTimeoutMs) { rpc.listSessions() }
                    consecutiveReconnectFailures = 0
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
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Throwable) {
                    // Dead socket after network handoff or sleep — fall through to reconnect
                }
            }

            runCatching { rpc.disconnect() }
            _state.update {
                it.copy(
                    connectionStatus = ConnectionStatus.RECONNECTING,
                    connectionError = null,
                )
            }
            startBonjourDiscovery()
            val preferredId = _state.value.hub?.id ?: loadActiveHostId()
            val tsnetActive = isMobileTsnetAvailable() && loadSavedTsnetEnabled()
            val feederJob = launchTsnetDiscoveryFeeder()
            val found = try {
                rpc.discoverIntranetHub(
                    candidates = candidateHubHttpUrls(),
                    dynamicCandidates = observeBonjourHubUrls(),
                    waitWindowMs = if (tsnetActive) 12_000L else 6_000L,
                    preferredHubId = preferredId,
                )
            } finally {
                feederJob?.cancel()
            }
            if (found == null) {
                if (fromNetworkHandoff || _state.value.hasEnteredWorkspace) {
                    // Transient signal drop, Wi-Fi wake delay, or brief Hub daemon restart:
                    // keep `hasEnteredWorkspace = true` with RECONNECTING status so `reconnectRetryJob`
                    // (every 3.5s) and NetworkCallback / Bonjour recover automatically without bouncing to login!
                    consecutiveReconnectFailures++
                    val shouldSurfaceBanner =
                        activeUserSendOrRetryDepth == 0 &&
                            _state.value.hasEnteredWorkspace &&
                            (consecutiveReconnectFailures >= 2 || _state.value.turnRecoveryBanner != null)
                    _state.update { s ->
                        val nextBanner = if (shouldSurfaceBanner) {
                            buildTurnRecoveryBanner(
                                previous = s.turnRecoveryBanner,
                                sessionId = s.activeSessionId,
                                rawMessage = "无法连接到主机服务（已连续重连失败 $consecutiveReconnectFailures 次）",
                                nowMs = epochMillis(),
                                forceNetworkError = true,
                                canResumePreviousTurn = s.activeSessionId != null ||
                                    !s.turnRecoveryBanner?.failedPromptText.isNullOrBlank(),
                            )
                        } else {
                            s.turnRecoveryBanner
                        }
                        s.copy(
                            connectionStatus = ConnectionStatus.RECONNECTING,
                            connectionError = if (fromNetworkHandoff) null else "正在重连主机，恢复网络或服务后将自动同步…",
                            turnRecoveryBanner = nextBanner,
                        )
                    }
                } else {
                    _state.update {
                        it.copy(
                            hasEnteredWorkspace = false,
                            connectionStatus = ConnectionStatus.ERROR,
                            connectionError = "唤醒校验未通过：未发现同一 Wi‑Fi 主机或 Tailscale 连接，请检查网络后点击重连",
                        )
                    }
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

            val resumeToken = loadHostAccessToken(found.discover.hub.id) ?: cachedToken
            val ok = connectWithToken(
                wsUrl = found.wsUrl,
                token = resumeToken,
                isForegroundResume = true,
                expectedHubId = found.discover.hub.id,
            )
            if (!ok && found.discover.tailscaleSameUser) {
                val hs = runCatching {
                    rpc.authenticateViaTailscale(found.baseUrl, platformName())
                }.getOrNull()
                if (hs != null) {
                    val userName = hs.user?.name ?: found.discover.tailscaleUser ?: "Owner"
                    saveAuthSession(hs.accessToken, userName)
                    saveHostAccessToken(hs.hub.id, hs.accessToken)
                    upsertPairedHost(hs.hub, hs.fingerprint, hs.endpoints, userName)
                    connectWithToken(
                        wsUrl = found.wsUrl,
                        token = hs.accessToken,
                        isForegroundResume = true,
                        expectedHubId = hs.hub.id,
                        expectedFingerprint = hs.fingerprint,
                    )
                }
            }
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
            } catch (ce: CancellationException) {
                throw ce
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
        discoverJob?.cancel()
        oauthPollJob?.cancel()
        tsnetPollJob?.cancel()
        resumeJob?.cancel()
        switchSubscriptionTo(null)
        consecutiveReconnectFailures = 0
        saveAuthSession(null, null)
        _state.update {
            it.copy(
                hasEnteredWorkspace = false,
                authUserName = null,
                connectionStatus = ConnectionStatus.NEEDS_AUTH,
                connectionError = null,
                turnRecoveryBanner = null,
                retryingTurn = false,
            )
        }
        scope.launch {
            rpc.disconnect()
            if (_state.value.oauthEnabled) {
                startOAuthLoginFlow()
            }
        }
    }

    fun resetCredentialsAndReconnect() {
        discoverJob?.cancel()
        oauthPollJob?.cancel()
        resumeJob?.cancel()
        for (h in loadPairedHosts()) {
            saveHostAccessToken(h.hubId, null)
        }
        savePairedHosts(emptyList())
        saveActiveHostId(null)
        saveAuthSession(null, null)
        HubTrust.clearPins()
        _state.update {
            it.copy(
                pairedHosts = emptyList(),
                activeHostId = null,
            )
        }
        bootstrapOrConnect()
    }

    private suspend fun connectWithToken(
        wsUrl: String,
        token: String,
        isForegroundResume: Boolean = false,
        expectedHubId: String? = null,
        expectedFingerprint: String? = null,
    ): Boolean {
        _state.update {
            it.copy(
                connectionStatus = if (isForegroundResume) ConnectionStatus.RECONNECTING else ConnectionStatus.CONNECTING,
                connectionError = null,
            )
        }
        val targetHubId = expectedHubId ?: _state.value.hub?.id ?: loadActiveHostId()
        return try {
            // Verify & pin the Hub certificate (bound to targetHubId when known) BEFORE the token leaves the device.
            val baseUrl = hubBaseUrlOfWs(wsUrl)
            val pinnedFp = expectedFingerprint?.takeIf { it.length == 64 }
                ?: targetHubId?.let { id ->
                    loadPairedHosts().firstOrNull { it.hubId == id }?.fingerprint?.takeIf { fp -> fp.length == 64 }
                }
            rpc.ensureHubTrusted(baseUrl, token, pinnedFp)
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
            val observedFp = HubTrust.observedFor(baseUrl)
            val resolvedUserName = current.authUserName ?: loadSavedAuthUserName() ?: "Owner"
            saveHostAccessToken(status.hub.id, token)
            upsertPairedHost(
                hub = status.hub,
                fingerprint = observedFp,
                endpoints = (if (isDesktopPlatform()) listOf(baseUrl) + hello.endpoints else hello.endpoints + listOf(baseUrl)) + hello.relayUrls,
                userName = resolvedUserName,
            )
            val uiEngines = status.engines.filterNot { it.id == "mock" }
            val chosenEngine = uiEngines.firstOrNull { it.id == current.selectedEngineId && it.status.ready }
                ?: uiEngines.firstOrNull { it.status.ready }
                ?: status.engines.firstOrNull()
            val chosenEngineId = chosenEngine?.id ?: "claude"

            val validWs = workspaces.firstOrNull { it.path == current.selectedWorkspacePath }?.path
                ?: workspaces.firstOrNull()?.path.orEmpty()

            val prevActiveId = current.activeSessionId
            val prevActiveMeta = prevActiveId?.let { id -> current.sessions.firstOrNull { it.id == id } }
            val resolvedActiveId = when {
                prevActiveId == null -> null
                sessions.any { it.id == prevActiveId } -> prevActiveId
                prevActiveId.startsWith("sess_") && prevActiveMeta != null -> {
                    // Hub restarted: in-memory `sess_*` is now discovered on disk as `host:<engine>:<nativeId>`
                    sessions.firstOrNull {
                        it.engineId == prevActiveMeta.engineId &&
                            (it.title == prevActiveMeta.title || it.cwd == prevActiveMeta.cwd)
                    }?.id
                }
                else -> prevActiveId
            }
            val resolvedActiveSession = resolvedActiveId?.let { id -> sessions.firstOrNull { it.id == id } }
            val preserveCurrentSelection =
                (userManuallyPickedModel || isForegroundResume || current.hasEnteredWorkspace) &&
                    current.selectedEngineId == chosenEngineId

            val chosenModel = if (preserveCurrentSelection) {
                chosenEngine?.models?.firstOrNull { it.id == current.selectedModelId }
                    ?: chosenEngine?.models?.firstOrNull { it.id == resolvedActiveSession?.model }
                    ?: chosenEngine?.models?.firstOrNull { it.id == chosenEngine.defaultModel }
                    ?: chosenEngine?.models?.firstOrNull()
            } else {
                chosenEngine?.models?.firstOrNull { it.id == chosenEngine.defaultModel }
                    ?: chosenEngine?.models?.firstOrNull { it.id == current.selectedModelId }
                    ?: chosenEngine?.models?.firstOrNull()
            }
            val chosenEffort = if (userManuallyPickedEffort || preserveCurrentSelection) {
                val supported = chosenModel?.efforts.orEmpty()
                if (supported.isNotEmpty()) {
                    current.selectedEffort.takeIf { it in supported }
                        ?: resolvedActiveSession?.reasoningEffort?.takeIf { it in supported }
                        ?: chosenModel?.defaultEffort?.takeIf { it in supported }
                        ?: chosenEngine?.defaultEffort?.takeIf { it in supported }
                        ?: supported.first()
                } else {
                    current.selectedEffort
                }
            } else {
                chosenEngine?.defaultEffort
                    ?: chosenModel?.defaultEffort
                    ?: current.selectedEffort
            }
            val chosenMode = if (userManuallyPickedMode || preserveCurrentSelection) {
                current.selectedMode
            } else {
                chosenEngine?.defaultMode ?: SessionMode.AUTO
            }

            manualPairingMode = false
            consecutiveReconnectFailures = 0
            lastRealtimeActivityMs = epochMillis()
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
                    activeSessionId = resolvedActiveId,
                )
            }
            persistCurrentPrefs()
            reconcileDesktopRunningWarmSubscriptions(sessions)

            if (resolvedActiveId != null) {
                subscribedSessionId = resolvedActiveId
                runCatching { subscribeAndCatchUp(resolvedActiveId) }
            }
            val deepLinkSid = pendingDeepLinkSessionId
            if (!deepLinkSid.isNullOrBlank()) {
                pendingDeepLinkSessionId = null
                selectSession(deepLinkSid)
            }
            true
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            if (t is RpcCallException && t.code == ErrorCode.UNAUTHORIZED) {
                targetHubId?.let { saveHostAccessToken(it, null) }
                if (targetHubId == null || targetHubId == loadActiveHostId()) {
                    saveAuthSession(null, null)
                }
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
            } else if (isForegroundResume && _state.value.hasEnteredWorkspace && t !is HubTrustException) {
                // Transient socket / Hub-restart failure while already authenticated in workspace:
                // keep `hasEnteredWorkspace = true` + `RECONNECTING` so `reconnectRetryJob` auto-recovers!
                consecutiveReconnectFailures++
                val shouldSurfaceBanner =
                    activeUserSendOrRetryDepth == 0 &&
                        (consecutiveReconnectFailures >= 2 || _state.value.turnRecoveryBanner != null)
                val rawErr = t.message?.takeIf { it.isNotBlank() } ?: "网络连接中断，重连主机未成功"
                _state.update { s ->
                    val nextBanner = if (shouldSurfaceBanner) {
                        buildTurnRecoveryBanner(
                            previous = s.turnRecoveryBanner,
                            sessionId = s.activeSessionId,
                            rawMessage = rawErr,
                            nowMs = epochMillis(),
                            forceNetworkError = true,
                            canResumePreviousTurn = s.activeSessionId != null ||
                                !s.turnRecoveryBanner?.failedPromptText.isNullOrBlank(),
                        )
                    } else {
                        s.turnRecoveryBanner
                    }
                    s.copy(
                        connectionStatus = ConnectionStatus.RECONNECTING,
                        connectionError = "连接中断，正在自动重连…",
                        turnRecoveryBanner = nextBanner,
                    )
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
                _state.update { s ->
                    val syncedSub = s.inspectedSubagent?.let { curSub ->
                        val activeParent = s.activeSessionId?.let { id -> sessions.firstOrNull { it.id == id } }
                        activeParent?.subagents?.firstOrNull { it.id == curSub.id } ?: curSub
                    }
                    s.copy(sessions = sessions, inspectedSubagent = syncedSub)
                }
            }
        }
    }

    fun setDrawerEngineFilter(engineId: String?) {
        _state.update { it.copy(drawerEngineFilter = engineId) }
        refreshHostSessions()
    }

    fun setDrawerShowArchived(showArchived: Boolean) {
        _state.update { it.copy(drawerShowArchived = showArchived) }
    }

    fun setDrawerSearchQuery(query: String) {
        _state.update { it.copy(drawerSearchQuery = query) }
    }

    fun renameSession(sessionId: String, newTitle: String) {
        if (sessionId.isBlank()) return
        val clean = newTitle.replace(Regex("\\s+"), " ").trim().take(120)
        val prevSession = _state.value.sessions.firstOrNull { it.id == sessionId }
        if (clean.isNotEmpty()) {
            _state.update { s ->
                s.copy(
                    sessions = s.sessions.map { item ->
                        if (item.id == sessionId) item.copy(title = clean, customTitle = clean) else item
                    },
                )
            }
        }
        scope.launch {
            try {
                if (_state.value.connectionStatus != ConnectionStatus.CONNECTED) {
                    ensureConnectedForSend(forceReconnect = false)
                }
                val updated = rpc.renameSession(sessionId, clean)
                _state.update { s ->
                    s.copy(
                        sessions = s.sessions.map { item -> if (item.id == updated.id) updated else item },
                    )
                }
            } catch (ce: CancellationException) {
                if (!isActive) throw ce
            } catch (t: Throwable) {
                if (prevSession != null) {
                    _state.update { s ->
                        s.copy(
                            sessions = s.sessions.map { item -> if (item.id == sessionId) prevSession else item },
                            promptErrorText = "重命名未成功: ${t.message ?: "请检查连接后重试"}",
                        )
                    }
                }
            }
        }
    }

    fun archiveSession(sessionId: String, archived: Boolean) {
        if (sessionId.isBlank()) return
        val prevSession = _state.value.sessions.firstOrNull { it.id == sessionId }
        val nowMs = epochMillis()
        _state.update { s ->
            s.copy(
                sessions = s.sessions.map { item ->
                    if (item.id == sessionId) {
                        item.copy(archived = archived, archivedAt = if (archived) nowMs else null)
                    } else {
                        item
                    }
                },
            )
        }
        scope.launch {
            try {
                if (_state.value.connectionStatus != ConnectionStatus.CONNECTED) {
                    ensureConnectedForSend(forceReconnect = false)
                }
                val updated = rpc.archiveSession(sessionId, archived)
                _state.update { s ->
                    s.copy(
                        sessions = s.sessions.map { item -> if (item.id == updated.id) updated else item },
                    )
                }
            } catch (ce: CancellationException) {
                if (!isActive) throw ce
            } catch (t: Throwable) {
                if (prevSession != null) {
                    _state.update { s ->
                        s.copy(
                            sessions = s.sessions.map { item -> if (item.id == sessionId) prevSession else item },
                            promptErrorText = "${if (archived) "归档" else "取消归档"}未成功: ${t.message ?: "请检查连接后重试"}",
                        )
                    }
                }
            }
        }
    }

    /** Starts a clean blank draft session using the computer's aligned engine setup. */
    fun startNewDraftSession() {
        userManuallyPickedModel = false
        userManuallyPickedEffort = false
        userManuallyPickedMode = false
        switchSubscriptionTo(null)
        _state.update { s ->
            val eng = s.selectedEngine
            val defaultModel = eng?.models?.firstOrNull { it.id == eng.defaultModel }
                ?: eng?.models?.firstOrNull { it.id == s.selectedModelId }
                ?: eng?.models?.firstOrNull()
            s.copy(
                activeSessionId = null,
                loadingSessionId = null,
                checkpoints = emptyList(),
                activeSessionUsage = null,
                activeSessionArtifacts = emptyList(),
                openedArtifact = null,
                artifactError = null,
                openedDiffContent = null,
                openedDiffTitle = null,
                checkpointNotice = null,
                promptErrorText = null,
                turnRecoveryBanner = null,
                retryingTurn = false,
                selectedProviderId = defaultModel?.providerId ?: s.selectedProviderId,
                selectedModelId = eng?.defaultModel ?: defaultModel?.id ?: s.selectedModelId,
                selectedEffort = eng?.defaultEffort ?: defaultModel?.defaultEffort ?: s.selectedEffort,
                selectedMode = eng?.defaultMode ?: s.selectedMode,
            )
        }
    }

    private fun syncSessionModelToHub(
        sessionId: String,
        modelId: String,
        effort: ReasoningEffort,
        mode: SessionMode,
    ) {
        val ver = ++modelSwitchVersion
        pendingModelSwitchBySession[sessionId] = ver to Triple(modelId, effort, mode)
        scope.launch {
            try {
                val updated = rpc.setSessionModel(sessionId, modelId, effort, mode)
                if (pendingModelSwitchBySession[sessionId]?.first == ver) {
                    _state.update { st ->
                        st.copy(
                            sessions = st.sessions.map { item -> if (item.id == updated.id) updated else item },
                        )
                    }
                }
            } catch (_: Throwable) {
                // Keep optimistic UI selection; next `session.prompt` also sends explicit overrides.
            } finally {
                if (pendingModelSwitchBySession[sessionId]?.first == ver) {
                    pendingModelSwitchBySession.remove(sessionId)
                }
            }
        }
    }

    fun selectEngineAndModel(
        engineId: String,
        modelSpec: EngineModelSpec? = null,
        effort: ReasoningEffort? = null,
    ) {
        userManuallyPickedModel = true
        if (effort != null) userManuallyPickedEffort = true
        val prevActive = _state.value.activeSession
        val switchedDifferentEngine = prevActive != null && prevActive.engineId != engineId
        if (switchedDifferentEngine) {
            switchSubscriptionTo(null)
        }

        _state.update { s ->
            val eng = s.engines.firstOrNull { it.id == engineId }
            val chosenModel = modelSpec
                ?: eng?.models?.firstOrNull { it.id == eng.defaultModel }
                ?: eng?.models?.firstOrNull()
            val supportedEfforts = chosenModel?.efforts.orEmpty()
            val nextEffort = when {
                effort != null -> effort
                supportedEfforts.isNotEmpty() -> {
                    s.selectedEffort.takeIf { s.selectedEngineId == engineId && it in supportedEfforts }
                        ?: chosenModel?.defaultEffort?.takeIf { it in supportedEfforts }
                        ?: eng?.defaultEffort?.takeIf { it in supportedEfforts }
                        ?: supportedEfforts.first()
                }
                else -> chosenModel?.defaultEffort ?: eng?.defaultEffort ?: s.selectedEffort
            }
            val nextMode = if (switchedDifferentEngine) {
                eng?.defaultMode ?: s.selectedMode
            } else {
                s.selectedMode
            }
            val updatedSessions = if (!switchedDifferentEngine && prevActive != null && chosenModel != null) {
                s.sessions.map { item ->
                    if (item.id == prevActive.id) {
                        item.copy(
                            model = chosenModel.id,
                            reasoningEffort = nextEffort,
                            mode = nextMode,
                        )
                    } else {
                        item
                    }
                }
            } else {
                s.sessions
            }
            s.copy(
                activeSessionId = if (switchedDifferentEngine) null else s.activeSessionId,
                loadingSessionId = if (switchedDifferentEngine) null else s.loadingSessionId,
                checkpoints = if (switchedDifferentEngine) emptyList() else s.checkpoints,
                activeSessionUsage = if (switchedDifferentEngine) null else s.activeSessionUsage,
                activeSessionArtifacts = if (switchedDifferentEngine) emptyList() else s.activeSessionArtifacts,
                openedArtifact = if (switchedDifferentEngine) null else s.openedArtifact,
                artifactError = if (switchedDifferentEngine) null else s.artifactError,
                openedDiffContent = if (switchedDifferentEngine) null else s.openedDiffContent,
                openedDiffTitle = if (switchedDifferentEngine) null else s.openedDiffTitle,
                checkpointNotice = if (switchedDifferentEngine) null else s.checkpointNotice,
                selectedWorkspacePath = if (switchedDifferentEngine) {
                    prevActive.cwd.ifEmpty { s.selectedWorkspacePath }
                } else {
                    s.selectedWorkspacePath
                },
                sessions = updatedSessions,
                selectedEngineId = engineId,
                selectedProviderId = chosenModel?.providerId,
                selectedModelId = chosenModel?.id,
                selectedEffort = nextEffort,
                selectedMode = nextMode,
            )
        }
        persistCurrentPrefs()

        val cur = _state.value
        val active = cur.activeSession
        val modelId = cur.selectedModelId
        if (!switchedDifferentEngine && active != null && active.engineId == engineId && modelId != null) {
            syncSessionModelToHub(active.id, modelId, cur.selectedEffort, cur.selectedMode)
        }
    }

    fun selectEffort(effort: ReasoningEffort) {
        userManuallyPickedEffort = true
        _state.update { s ->
            val activeId = s.activeSessionId
            s.copy(
                selectedEffort = effort,
                sessions = if (activeId != null) {
                    s.sessions.map { item ->
                        if (item.id == activeId) item.copy(reasoningEffort = effort) else item
                    }
                } else {
                    s.sessions
                },
            )
        }
        persistCurrentPrefs()

        val cur = _state.value
        val active = cur.activeSession
        val modelId = cur.selectedModelId ?: active?.model
        if (active != null && modelId != null) {
            syncSessionModelToHub(active.id, modelId, effort, cur.selectedMode)
        }
    }

    fun toggleFavoriteModel(engineId: String, modelId: String) {
        val key = "${engineId.trim()}:${modelId.trim()}"
        if (key.length < 3) return
        _state.update { s ->
            val next = if (key in s.favoriteModelKeys) {
                s.favoriteModelKeys - key
            } else {
                (s.favoriteModelKeys + key).distinct()
            }
            s.copy(favoriteModelKeys = next)
        }
        persistCurrentPrefs()
    }

    fun togglePinSession(sessionId: String) {
        val id = sessionId.trim()
        if (id.isEmpty()) return
        _state.update { s ->
            val next = if (id in s.pinnedSessionIds) {
                s.pinnedSessionIds - id
            } else {
                (listOf(id) + s.pinnedSessionIds).distinct()
            }
            s.copy(pinnedSessionIds = next)
        }
        persistCurrentPrefs()
    }

    fun selectMode(mode: SessionMode) {
        userManuallyPickedMode = true
        _state.update { s ->
            val activeId = s.activeSessionId
            s.copy(
                selectedMode = mode,
                sessions = if (activeId != null) {
                    s.sessions.map { item ->
                        if (item.id == activeId) item.copy(mode = mode) else item
                    }
                } else {
                    s.sessions
                },
            )
        }
        persistCurrentPrefs()

        val cur = _state.value
        val active = cur.activeSession
        val modelId = cur.selectedModelId ?: active?.model
        if (active != null && modelId != null) {
            syncSessionModelToHub(active.id, modelId, cur.selectedEffort, mode)
        }
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

    fun setDraftUseWorktree(enabled: Boolean) {
        _state.update {
            it.copy(
                draftUseWorktree = enabled,
                draftWorktreeBranch = if (enabled) it.draftWorktreeBranch else "",
            )
        }
    }

    fun setDraftWorktreeBranch(branch: String) {
        _state.update { it.copy(draftWorktreeBranch = branch.take(72)) }
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
                loadingSessionId = null,
                checkpoints = emptyList(),
                activeSessionUsage = null,
                activeSessionArtifacts = emptyList(),
                openedArtifact = null,
                artifactError = null,
                openedDiffContent = null,
                openedDiffTitle = null,
                checkpointNotice = null,
                promptErrorText = null,
                turnRecoveryBanner = null,
                retryingTurn = false,
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
        userManuallyPickedModel = false
        userManuallyPickedEffort = false
        userManuallyPickedMode = false
        switchSubscriptionTo(sessionId)
        val meta = _state.value.sessions.firstOrNull { it.id == sessionId }
        _state.update { s ->
            val targetEngineId = meta?.engineId ?: s.selectedEngineId
            val eng = s.engines.firstOrNull { it.id == targetEngineId }
            val resolvedMode = meta?.mode ?: eng?.defaultMode ?: s.selectedMode
            val resolvedEffort = meta?.reasoningEffort ?: eng?.defaultEffort ?: s.selectedEffort
            val resolvedModel = meta?.model ?: eng?.defaultModel ?: s.selectedModelId
            val alreadyCached = s.transcripts[sessionId]?.let { t -> t.lastSeq >= 0 || t.items.isNotEmpty() } == true
            s.copy(
                activeSessionId = sessionId,
                loadingSessionId = if (alreadyCached) null else sessionId,
                checkpoints = emptyList(),
                activeSessionUsage = null,
                activeSessionArtifacts = emptyList(),
                openedArtifact = null,
                artifactError = null,
                openedDiffContent = null,
                openedDiffTitle = null,
                checkpointNotice = null,
                submittingApprovalRequestId = null,
                approvalErrorText = null,
                promptErrorText = null,
                turnRecoveryBanner = s.turnRecoveryBanner?.takeIf { it.sessionId == sessionId },
                retryingTurn = false,
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
            try {
                subscribeAndCatchUp(sessionId)
            } catch (_: Throwable) {
                // Keep UI responsive even if initial subscribe fails transiently
            } finally {
                _state.update { s ->
                    if (s.loadingSessionId == sessionId) s.copy(loadingSessionId = null) else s
                }
            }
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
            refreshSessionArtifacts(sessionId)
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
                val olderReduced = reduceTranscriptBatch(SessionTranscript(), snap.events)
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

    fun handleSessionDeepLink(sessionId: String?, hubId: String? = null) {
        val cleanSid = sessionId?.trim()?.takeIf { it.isNotEmpty() }
        val cleanHubId = hubId?.trim()?.takeIf { it.isNotEmpty() }
        val cur = _state.value

        if (cur.hasEnteredWorkspace && (cleanHubId == null || cleanHubId == cur.hub?.id)) {
            if (cleanSid != null) {
                scope.launch {
                    if (cur.sessions.none { it.id == cleanSid }) {
                        runCatching {
                            val fresh = rpc.listSessions()
                            _state.update { it.copy(sessions = fresh) }
                        }
                    }
                    selectSession(cleanSid)
                }
            }
            return
        }

        pendingDeepLinkSessionId = cleanSid
        if (cleanHubId != null && loadPairedHosts().any { it.hubId == cleanHubId }) {
            switchPairedHost(cleanHubId)
        } else if (!cur.hasEnteredWorkspace) {
            bootstrapOrConnect()
        }
    }

    fun dismissSigningExpiryReminder() {
        _state.update { it.copy(signingExpiryDismissed = true) }
    }

    fun updateComposerTextForMentions(nextText: String, cursorIndex: Int = nextText.length) {
        val query = extractMentionQueryAtCursor(nextText, cursorIndex)
        if (query == null) {
            mentionJob?.cancel()
            if (_state.value.mentionQuery != null || _state.value.workspaceFileSuggestions.isNotEmpty()) {
                _state.update { it.copy(mentionQuery = null, workspaceFileSuggestions = emptyList()) }
            }
            return
        }
        val wsPath = _state.value.activeWorkspaces.firstOrNull()
            ?: _state.value.selectedWorkspacePath.takeIf { it.isNotBlank() }
            ?: _state.value.workspaces.firstOrNull()?.path
        if (wsPath.isNullOrBlank()) {
            _state.update { it.copy(mentionQuery = null, workspaceFileSuggestions = emptyList()) }
            return
        }
        _state.update { it.copy(mentionQuery = query) }
        mentionJob?.cancel()
        mentionJob = scope.launch {
            delay(100L)
            val files = runCatching {
                rpc.searchWorkspaceFiles(path = wsPath, query = query, limit = 10).files
            }.getOrDefault(emptyList())
            if (_state.value.mentionQuery == query) {
                _state.update { it.copy(workspaceFileSuggestions = files) }
            }
        }
    }

    fun dismissMentionSuggestions() {
        mentionJob?.cancel()
        _state.update { it.copy(mentionQuery = null, workspaceFileSuggestions = emptyList()) }
    }

    fun pickAndAttachImage(source: ImagePickSource = ImagePickSource.LIBRARY) {
        if (!canPickImages()) return
        val remaining = 4 - _state.value.composerAttachedImages.size
        if (remaining <= 0) return
        val limit = if (source == ImagePickSource.CAMERA) 1 else remaining.coerceIn(1, 4)
        requestMultiImagePick(source = source, maxCount = limit) { pickedList ->
            val valid = pickedList
                .filter { it.dataBase64.isNotBlank() }
                .map { picked ->
                    PromptImageAttachment(
                        mimeType = picked.mimeType,
                        dataBase64 = picked.dataBase64,
                        thumbnailBase64 = picked.thumbnailBase64?.takeIf { it.isNotBlank() },
                        fileName = picked.fileName.take(120),
                    )
                }
            if (valid.isNotEmpty()) {
                attachImages(valid)
            }
        }
    }

    fun captureAndAttachImage() {
        pickAndAttachImage(source = ImagePickSource.CAMERA)
    }

    fun attachImage(attachment: PromptImageAttachment) {
        attachImages(listOf(attachment))
    }

    fun attachImages(attachments: List<PromptImageAttachment>) {
        if (attachments.isEmpty()) return
        _state.update { s ->
            val slots = (4 - s.composerAttachedImages.size).coerceAtLeast(0)
            if (slots == 0) s
            else s.copy(composerAttachedImages = s.composerAttachedImages + attachments.take(slots))
        }
    }

    fun attachClipboardImages(images: List<PickedImagePayload>): Boolean {
        val valid = images
            .filter { it.dataBase64.isNotBlank() }
            .map { picked ->
                PromptImageAttachment(
                    mimeType = picked.mimeType,
                    dataBase64 = picked.dataBase64,
                    thumbnailBase64 = picked.thumbnailBase64?.takeIf { it.isNotBlank() },
                    fileName = picked.fileName.take(120),
                )
            }
        if (valid.isEmpty()) return false

        val cur = _state.value
        if (cur.selectedEngine?.capabilities?.images == false) {
            _state.update { it.copy(promptErrorText = "当前引擎暂不支持附带图片") }
            return true
        }

        val remaining = (4 - cur.composerAttachedImages.size).coerceAtLeast(0)
        if (remaining == 0) {
            _state.update { it.copy(promptErrorText = "单条消息最多附带 4 张图片") }
            return true
        }

        val toAdd = valid.take(remaining)
        val overflow = valid.size > remaining
        _state.update { s ->
            val slots = (4 - s.composerAttachedImages.size).coerceAtLeast(0)
            if (slots == 0) {
                s.copy(promptErrorText = "单条消息最多附带 4 张图片")
            } else {
                val added = valid.take(slots)
                s.copy(
                    composerAttachedImages = s.composerAttachedImages + added,
                    promptErrorText = if (overflow) "单条消息最多附带 4 张图片，已附加前 ${toAdd.size} 张" else null,
                )
            }
        }
        return true
    }

    fun pasteImagesFromClipboard(): Boolean {
        val cur = _state.value
        val engineSupportsImages = cur.selectedEngine?.capabilities?.images != false
        val remaining = (4 - cur.composerAttachedImages.size).coerceAtLeast(0)
        val readLimit = if (engineSupportsImages && remaining > 0) 4 else 1
        val clipboardImages = readImagesFromClipboard(maxCount = readLimit)
        if (clipboardImages.isEmpty()) return false
        return attachClipboardImages(clipboardImages)
    }

    fun removeAttachedImage(index: Int) {
        _state.update { s ->
            if (index !in s.composerAttachedImages.indices) s
            else s.copy(composerAttachedImages = s.composerAttachedImages.filterIndexed { i, _ -> i != index })
        }
    }

    fun dismissPromptError() {
        _state.update { it.copy(promptErrorText = null) }
    }

    fun dismissTurnRecoveryBanner() {
        _state.update { s ->
            val banner = s.turnRecoveryBanner
            s.copy(
                turnRecoveryBanner = null,
                promptErrorText = null,
                unsentDraftToRestore = s.unsentDraftToRestore ?: banner?.failedPromptText?.takeIf { it.isNotBlank() },
                composerAttachedImages = s.composerAttachedImages.ifEmpty { banner?.failedImages.orEmpty() },
            )
        }
    }

    fun consumeRestoredDraft() {
        _state.update { it.copy(unsentDraftToRestore = null) }
    }

    private fun markRecentError(sessionId: String?, message: String, nowMs: Long) {
        if (sessionId.isNullOrBlank()) return
        lastErrorSignatureBySession[sessionId] = message.trim() to nowMs
    }

    private fun isDuplicateRecentError(sessionId: String?, message: String, nowMs: Long): Boolean {
        if (sessionId.isNullOrBlank()) return false
        val prev = lastErrorSignatureBySession[sessionId] ?: return false
        if (nowMs - prev.second > DUPLICATE_ERROR_DEDUP_MS) return false
        val clean = message.trim()
        return clean.isEmpty() || prev.first == clean || prev.first.contains(clean) || clean.contains(prev.first)
    }

    private fun buildTurnRecoveryBanner(
        previous: TurnRecoveryBannerState?,
        sessionId: String?,
        rawMessage: String,
        nowMs: Long = epochMillis(),
        failedPromptText: String? = null,
        failedImages: List<PromptImageAttachment> = emptyList(),
        preserveUnsentOnSameContext: Boolean = true,
        forceNetworkError: Boolean = false,
        canResumePreviousTurn: Boolean = true,
    ): TurnRecoveryBannerState {
        val cleanReason = rawMessage.replace(Regex("\\s+"), " ").trim().take(220)
        val isNetwork = forceNetworkError || isLikelyNetworkError(cleanReason)
        val sameContext =
            previous != null &&
                (previous.sessionId == sessionId || previous.sessionId == null || sessionId == null)
        val withinWindow =
            previous != null &&
                (previous.lastErrorAtMs == 0L || (nowMs - previous.lastErrorAtMs) in 0L..ERROR_AGGREGATION_WINDOW_MS)
        val nextCount = if (sameContext && withinWindow) previous!!.errorCount + 1 else 1

        val effectiveFailedPrompt = failedPromptText?.takeIf { it.isNotBlank() }
            ?: if (preserveUnsentOnSameContext && sameContext) previous?.failedPromptText else null
        val effectiveFailedImages = when {
            failedImages.isNotEmpty() -> failedImages
            preserveUnsentOnSameContext && sameContext -> previous?.failedImages.orEmpty()
            else -> emptyList()
        }

        val headline = when {
            isNetwork && nextCount >= 2 -> "检测到网络频繁异常（已报错 ${nextCount} 次）"
            isNetwork -> "网络连接异常，任务已中断"
            nextCount >= 2 -> "上一轮任务连续中断（已报错 ${nextCount} 次）"
            else -> "上一轮任务执行中断"
        }
        val detail = when {
            isNetwork && nextCount >= 2 -> {
                val base = cleanReason.ifEmpty { "网络连接不稳定" }
                if (base.contains("请检查电脑网络")) base else "$base · 请检查电脑网络或代理后点击重试"
            }
            isNetwork -> cleanReason.ifEmpty { "请检查电脑网络或代理后点击重试" }
            else -> cleanReason.ifEmpty { "可点击重试继续上一轮任务" }
        }

        return TurnRecoveryBannerState(
            sessionId = sessionId ?: previous?.sessionId,
            isNetworkError = isNetwork,
            errorCount = nextCount,
            headline = headline,
            detail = detail,
            failedPromptText = effectiveFailedPrompt,
            failedImages = effectiveFailedImages,
            canResumePreviousTurn = canResumePreviousTurn ||
                !effectiveFailedPrompt.isNullOrBlank() ||
                effectiveFailedImages.isNotEmpty() ||
                sessionId != null,
            lastErrorAtMs = nowMs,
        )
    }

    private fun recordTurnError(
        sessionId: String?,
        rawMessage: String,
        failedPromptText: String? = null,
        failedImages: List<PromptImageAttachment> = emptyList(),
        preserveUnsentOnSameContext: Boolean = true,
        forceNetworkError: Boolean = false,
        canResumePreviousTurn: Boolean = true,
    ) {
        val nowMs = epochMillis()
        markRecentError(sessionId, rawMessage, nowMs)
        _state.update { s ->
            val nextBanner = buildTurnRecoveryBanner(
                previous = s.turnRecoveryBanner,
                sessionId = sessionId ?: s.activeSessionId,
                rawMessage = rawMessage,
                nowMs = nowMs,
                failedPromptText = failedPromptText,
                failedImages = failedImages,
                preserveUnsentOnSameContext = preserveUnsentOnSameContext,
                forceNetworkError = forceNetworkError,
                canResumePreviousTurn = canResumePreviousTurn,
            )
            s.copy(
                promptErrorText = null,
                turnRecoveryBanner = nextBanner,
            )
        }
    }

    private suspend fun ensureConnectedForSend(forceReconnect: Boolean = false): Boolean {
        if (!forceReconnect && _state.value.connectionStatus == ConnectionStatus.CONNECTED) {
            return true
        }
        activeUserSendOrRetryDepth++
        try {
            val cur = _state.value
            val wsUrl = cur.discoveredWsUrl
            val hubId = cur.hub?.id ?: loadActiveHostId()
            val token = (hubId?.let { loadHostAccessToken(it) } ?: loadSavedAccessToken()).orEmpty()
            if (wsUrl.isNotBlank() && token.isNotBlank()) {
                val fastOk = withTimeoutOrNull(5_000L) {
                    connectWithToken(
                        wsUrl = wsUrl,
                        token = token,
                        isForegroundResume = true,
                        expectedHubId = hubId,
                    )
                } == true
                if (fastOk) return true
            }
            verifyAndResumeForegroundSession(fromNetworkHandoff = true)
            withTimeoutOrNull(8_000L) {
                resumeJob?.join()
            }
            return _state.value.connectionStatus == ConnectionStatus.CONNECTED
        } finally {
            activeUserSendOrRetryDepth = (activeUserSendOrRetryDepth - 1).coerceAtLeast(0)
        }
    }

    private suspend fun dispatchPromptRpc(
        trimmed: String,
        attached: List<PromptImageAttachment>,
    ): String {
        val cur = _state.value
        val sessionId = cur.activeSessionId ?: run {
            val cwd = cur.selectedWorkspacePath.ifEmpty {
                cur.workspaces.firstOrNull()?.path ?: throw IllegalStateException("未找到可用工作区目录")
            }
            val allWs = (listOf(cwd) + cur.selectedExtraWorkspaces).filter { it.isNotBlank() }.distinct()
            val created = rpc.createSession(
                engineId = cur.selectedEngineId,
                cwd = cwd,
                workspaces = allWs,
                useWorktree = cur.draftUseWorktree.takeIf { it },
                worktreeBranch = cur.draftWorktreeBranch.trim().takeIf { cur.draftUseWorktree && it.isNotEmpty() },
                providerId = cur.selectedProviderId,
                modelId = cur.selectedModelId,
                reasoningEffort = cur.selectedEffort,
                mode = cur.selectedMode,
            )
            switchSubscriptionTo(created.id)
            _state.update { st ->
                st.copy(
                    sessions = listOf(created) + st.sessions.filterNot { item -> item.id == created.id },
                    activeSessionId = created.id,
                    selectedWorkspacePath = created.cwd,
                    currentBrowsePath = created.cwd,
                    currentDiffWorkspacePath = created.cwd,
                    draftWorktreeBranch = "",
                )
            }
            created.id
        }

        try {
            rpc.sendPrompt(
                sessionId = sessionId,
                text = trimmed,
                images = attached.takeIf { it.isNotEmpty() },
                modelId = cur.selectedModelId,
                reasoningEffort = cur.selectedEffort,
                mode = cur.selectedMode,
            )
            return sessionId
        } catch (e: RpcCallException) {
            // If `sessionId` was a pre-restart `sess_*` ID that is now persisted on disk as `host:*`,
            // automatically remap to the matching `host:*` session and send seamlessly!
            if (e.code == ErrorCode.NOT_FOUND && sessionId.startsWith("sess_")) {
                val prevMeta = cur.sessions.firstOrNull { it.id == sessionId }
                val freshList = rpc.listSessions()
                val remapped = freshList.firstOrNull {
                    it.engineId == (prevMeta?.engineId ?: cur.selectedEngineId) &&
                        (it.title == prevMeta?.title || it.cwd == (prevMeta?.cwd ?: cur.selectedWorkspacePath))
                } ?: throw e
                switchSubscriptionTo(remapped.id)
                _state.update { st ->
                    st.copy(sessions = freshList, activeSessionId = remapped.id)
                }
                runCatching { subscribeAndCatchUp(remapped.id) }
                rpc.sendPrompt(
                    sessionId = remapped.id,
                    text = trimmed,
                    images = attached.takeIf { it.isNotEmpty() },
                    modelId = cur.selectedModelId,
                    reasoningEffort = cur.selectedEffort,
                    mode = cur.selectedMode,
                )
                return remapped.id
            } else {
                throw e
            }
        }
    }

    fun sendPrompt(text: String) {
        val trimmed = text.trim()
        val s = _state.value
        val attached = s.composerAttachedImages
        if (trimmed.isEmpty() && attached.isEmpty()) return
        mentionJob?.cancel()
        _state.update {
            it.copy(
                composerAttachedImages = emptyList(),
                mentionQuery = null,
                workspaceFileSuggestions = emptyList(),
                promptErrorText = null,
                turnRecoveryBanner = null,
                unsentDraftToRestore = null,
            )
        }
        scope.launch {
            try {
                if (_state.value.connectionStatus != ConnectionStatus.CONNECTED) {
                    val connected = ensureConnectedForSend(forceReconnect = false)
                    if (!connected) {
                        throw IllegalStateException(_state.value.connectionError ?: "网络连接已断开，无法连接到主机")
                    }
                }
                val sentSessionId = try {
                    dispatchPromptRpc(trimmed, attached)
                } catch (firstErr: Throwable) {
                    if (firstErr is CancellationException && !isActive) throw firstErr
                    if (firstErr is RpcCallException) throw firstErr
                    // Socket dropped right as the user tapped Send: reconnect once and retry transparently!
                    if (ensureConnectedForSend(forceReconnect = true)) {
                        dispatchPromptRpc(trimmed, attached)
                    } else {
                        throw firstErr
                    }
                }
                consecutiveReconnectFailures = 0
                if (trimmed.isNotEmpty()) {
                    lastSubmittedPromptBySession[sentSessionId] = trimmed
                }
            } catch (ce: CancellationException) {
                if (!isActive) throw ce
                recordTurnError(
                    sessionId = _state.value.activeSessionId,
                    rawMessage = "网络连接刚恢复，消息尚未发出",
                    failedPromptText = trimmed.takeIf { it.isNotEmpty() },
                    failedImages = attached,
                    forceNetworkError = true,
                )
            } catch (t: Throwable) {
                val errMsg = t.message?.takeIf { it.isNotBlank() } ?: "消息发送未成功，请检查连接后重试"
                recordTurnError(
                    sessionId = _state.value.activeSessionId,
                    rawMessage = errMsg,
                    failedPromptText = trimmed.takeIf { it.isNotEmpty() },
                    failedImages = attached,
                    forceNetworkError = t !is RpcCallException,
                )
            }
        }
    }

    /**
     * One-tap recovery action for network disconnects & interrupted turns:
     * 1. Reconnects to the Hub & catches up missed session events if currently disconnected.
     * 2. If the previous prompt failed before reaching the Hub (`failedPromptText`/`failedImages`),
     *    resends that exact prompt & images with zero loss.
     * 3. Otherwise (upstream mid-turn abort after prompt reached the Hub), sends a seamless
     *    continuation instruction into the same active session so the agent resumes right where it stopped.
     */
    fun retryLastTurn() {
        val snap = _state.value
        if (snap.retryingTurn) return
        val banner = snap.turnRecoveryBanner
        val activeSid = snap.activeSessionId ?: banner?.sessionId
        val failedText = banner?.failedPromptText?.trim().orEmpty()
        val failedImages = banner?.failedImages.orEmpty()
        val hasUnsentPrompt = failedText.isNotEmpty() || failedImages.isNotEmpty()

        _state.update { it.copy(retryingTurn = true, promptErrorText = null) }
        scope.launch {
            try {
                if (_state.value.connectionStatus != ConnectionStatus.CONNECTED) {
                    val reconnected = ensureConnectedForSend(forceReconnect = true)
                    if (!reconnected) {
                        recordTurnError(
                            sessionId = _state.value.activeSessionId ?: activeSid,
                            rawMessage = _state.value.connectionError ?: "网络仍未恢复，重连主机失败",
                            failedPromptText = if (hasUnsentPrompt) failedText.ifEmpty { null } else null,
                            failedImages = if (hasUnsentPrompt) failedImages else emptyList(),
                            preserveUnsentOnSameContext = hasUnsentPrompt,
                            forceNetworkError = true,
                        )
                        return@launch
                    }
                    val currentSid = _state.value.activeSessionId ?: activeSid
                    if (currentSid != null) {
                        runCatching { subscribeAndCatchUp(currentSid) }
                    }
                }

                val targetSid = _state.value.activeSessionId ?: activeSid
                if (!hasUnsentPrompt && targetSid == null) {
                    consecutiveReconnectFailures = 0
                    _state.update { it.copy(turnRecoveryBanner = null, promptErrorText = null) }
                    return@launch
                }

                val promptToSend = if (hasUnsentPrompt) failedText else SEAMLESS_CONTINUE_PROMPT
                val imagesToSend = if (hasUnsentPrompt) failedImages else emptyList()

                val sentSessionId = try {
                    dispatchPromptRpc(promptToSend, imagesToSend)
                } catch (firstErr: Throwable) {
                    if (firstErr is CancellationException && !isActive) throw firstErr
                    if (firstErr is RpcCallException) throw firstErr
                    if (ensureConnectedForSend(forceReconnect = true)) {
                        dispatchPromptRpc(promptToSend, imagesToSend)
                    } else {
                        throw firstErr
                    }
                }

                consecutiveReconnectFailures = 0
                if (hasUnsentPrompt && failedText.isNotEmpty()) {
                    lastSubmittedPromptBySession[sentSessionId] = failedText
                }
                _state.update { st ->
                    st.copy(
                        turnRecoveryBanner = null,
                        promptErrorText = null,
                        unsentDraftToRestore = null,
                        composerAttachedImages = if (hasUnsentPrompt && st.composerAttachedImages == failedImages) {
                            emptyList()
                        } else {
                            st.composerAttachedImages
                        },
                    )
                }
            } catch (ce: CancellationException) {
                if (!isActive) throw ce
                recordTurnError(
                    sessionId = _state.value.activeSessionId ?: activeSid,
                    rawMessage = "重试已中断，请检查网络后再次点击重试",
                    failedPromptText = if (hasUnsentPrompt) failedText.ifEmpty { null } else null,
                    failedImages = if (hasUnsentPrompt) failedImages else emptyList(),
                    preserveUnsentOnSameContext = hasUnsentPrompt,
                    forceNetworkError = true,
                )
            } catch (t: Throwable) {
                val errMsg = t.message?.takeIf { it.isNotBlank() } ?: "重试发送失败，请检查网络连接"
                recordTurnError(
                    sessionId = _state.value.activeSessionId ?: activeSid,
                    rawMessage = errMsg,
                    failedPromptText = if (hasUnsentPrompt) failedText.ifEmpty { null } else null,
                    failedImages = if (hasUnsentPrompt) failedImages else emptyList(),
                    preserveUnsentOnSameContext = hasUnsentPrompt,
                    forceNetworkError = t !is RpcCallException,
                )
            } finally {
                _state.update { it.copy(retryingTurn = false) }
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
        if (_state.value.submittingApprovalRequestId == requestId) return
        _state.update {
            it.copy(
                submittingApprovalRequestId = requestId,
                approvalErrorText = null,
            )
        }
        scope.launch {
            try {
                rpc.respondApproval(
                    sessionId = targetSessionId,
                    requestId = requestId,
                    optionId = optionId,
                )
                _state.update { s ->
                    val prev = s.transcripts[targetSessionId]
                    val updatedTranscripts = if (prev != null) {
                        val updated = prev.copy(
                            pendingPermissions = prev.pendingPermissions.filterNot {
                                it.requestId == requestId ||
                                    (requestId.startsWith("tty_perm_") && it.requestId.startsWith("tty_perm_"))
                            },
                        )
                        s.transcripts + (targetSessionId to updated)
                    } else {
                        s.transcripts
                    }
                    // Optimistically clear WAITING_PERMISSION on the session until the next Hub update arrives
                    val updatedSessions = s.sessions.map { meta ->
                        if (meta.id == targetSessionId && meta.state == SessionState.WAITING_PERMISSION) {
                            meta.copy(state = SessionState.RUNNING, liveStatusText = "已提交审批，继续执行中…")
                        } else {
                            meta
                        }
                    }
                    s.copy(
                        submittingApprovalRequestId = null,
                        approvalErrorText = null,
                        sessions = updatedSessions,
                        transcripts = updatedTranscripts,
                    )
                }
            } catch (t: Throwable) {
                val msg = t.message?.takeIf { it.isNotBlank() } ?: "审批提交未成功，正在同步最新终端状态…"
                _state.update {
                    it.copy(
                        submittingApprovalRequestId = null,
                        approvalErrorText = msg,
                    )
                }
                // Immediately re-sync terminal & session events so stale/changed prompts update automatically
                runCatching { subscribeAndCatchUp(targetSessionId) }
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
        var isInitialLoad = existing == null || existing.lastSeq < 0
        var snap = rpc.subscribeSession(
            sessionId = sessionId,
            afterSeq = if (isInitialLoad) -1 else existing!!.lastSeq,
            limit = if (isInitialLoad) 220 else null,
        )

        // If Hub restarted or handed a `host:*` session back to disk transcript inspection with a lower
        // `lastSeq` than the client's cached `lastSeq`, reload the authoritative tail window immediately
        // so new events are never masked by a stale high-water mark.
        if (!isInitialLoad && snap.session.lastSeq >= 0 && snap.session.lastSeq < existing!!.lastSeq) {
            isInitialLoad = true
            snap = rpc.subscribeSession(
                sessionId = sessionId,
                afterSeq = -1,
                limit = 220,
            )
        }

        // Strict session check: if the user already switched away while `subscribeSession` was in flight,
        // unsubscribe immediately and do NOT contaminate the newly selected conversation.
        if (_state.value.activeSessionId != sessionId || reqToken != subscribeSeqToken) {
            runCatching { rpc.unsubscribeSession(sessionId) }
            return
        }

        _state.update { s ->
            if (s.activeSessionId != sessionId) return@update s
            val pendingOverride = pendingModelSwitchBySession[snap.session.id]?.second
            val effectiveSession = if (pendingOverride != null) {
                snap.session.copy(
                    model = pendingOverride.first,
                    reasoningEffort = pendingOverride.second,
                    mode = pendingOverride.third,
                )
            } else {
                snap.session
            }
            val updatedSessions = if (s.sessions.any { it.id == effectiveSession.id }) {
                s.sessions.map { item ->
                    if (item.id == effectiveSession.id) effectiveSession else item
                }
            } else {
                listOf(effectiveSession) + s.sessions
            }
            val nextTranscript = if (isInitialLoad) {
                val built = reduceTranscriptBatch(SessionTranscript(), snap.events)
                val concurrentPerms = s.transcripts[sessionId]?.pendingPermissions.orEmpty()
                val mergedPerms = if (built.pendingPermissions.any { it.requestId.startsWith("tty_perm_") }) {
                    built.pendingPermissions
                } else if (effectiveSession.state == SessionState.WAITING_PERMISSION) {
                    (built.pendingPermissions + concurrentPerms).distinctBy { it.requestId }
                } else {
                    built.pendingPermissions
                }
                built.copy(
                    earliestSeq = snap.earliestSeq ?: snap.events.firstOrNull()?.seq,
                    hasMoreBefore = snap.hasMoreBefore == true,
                    loadingOlder = false,
                    pendingPermissions = mergedPerms,
                )
            } else {
                var cur = reduceTranscriptBatch(s.transcripts[sessionId] ?: SessionTranscript(), snap.events)
                if (effectiveSession.state != SessionState.WAITING_PERMISSION &&
                    snap.events.none { it is AgentEvent.PermissionRequested }
                ) {
                    cur = cur.copy(
                        pendingPermissions = cur.pendingPermissions.filterNot { it.requestId.startsWith("tty_perm_") },
                    )
                }
                cur
            }
            val eng = s.engines.firstOrNull { it.id == effectiveSession.engineId }
            var nextBanner = s.turnRecoveryBanner
            if (!isInitialLoad && snap.events.isNotEmpty()) {
                val nowMs = epochMillis()
                for (ev in snap.events) {
                    when (ev) {
                        is AgentEvent.UserMessage -> {
                            if (ev.text.isNotBlank()) lastSubmittedPromptBySession[sessionId] = ev.text
                        }
                        is AgentEvent.ErrorEvent -> {
                            if (!isDuplicateRecentError(sessionId, ev.message, nowMs)) {
                                markRecentError(sessionId, ev.message, nowMs)
                                nextBanner = buildTurnRecoveryBanner(
                                    previous = nextBanner,
                                    sessionId = sessionId,
                                    rawMessage = ev.message,
                                    nowMs = nowMs,
                                    preserveUnsentOnSameContext = false,
                                )
                            }
                        }
                        is AgentEvent.TurnDone -> {
                            if (ev.stopReason == "error") {
                                val detail = ev.error?.takeIf { it.isNotBlank() } ?: "上一轮任务执行中断"
                                if (!isDuplicateRecentError(sessionId, detail, nowMs)) {
                                    markRecentError(sessionId, detail, nowMs)
                                    nextBanner = buildTurnRecoveryBanner(
                                        previous = nextBanner,
                                        sessionId = sessionId,
                                        rawMessage = detail,
                                        nowMs = nowMs,
                                        preserveUnsentOnSameContext = false,
                                    )
                                }
                            } else if (ev.stopReason != "cancelled") {
                                if (nextBanner?.sessionId == sessionId || nextBanner?.sessionId == null) {
                                    nextBanner = null
                                }
                            }
                        }
                        is AgentEvent.AssistantDelta,
                        is AgentEvent.ReasoningDelta,
                        is AgentEvent.ToolCall,
                        is AgentEvent.ToolUpdate -> {
                            if (nextBanner?.sessionId == sessionId || nextBanner?.sessionId == null) {
                                nextBanner = null
                            }
                        }
                        else -> Unit
                    }
                }
            } else if (effectiveSession.state == SessionState.ERROR && nextBanner == null) {
                val detail = effectiveSession.liveStatusText?.takeIf { it.isNotBlank() } ?: "上一轮任务执行中断"
                nextBanner = buildTurnRecoveryBanner(
                    previous = null,
                    sessionId = sessionId,
                    rawMessage = detail,
                    nowMs = epochMillis(),
                    preserveUnsentOnSameContext = false,
                )
            }
            val syncedSubagent = if (s.inspectedSubagent != null) {
                effectiveSession.subagents?.firstOrNull { it.id == s.inspectedSubagent.id } ?: s.inspectedSubagent
            } else {
                null
            }
            s.copy(
                sessions = updatedSessions,
                loadingSessionId = if (s.loadingSessionId == sessionId) null else s.loadingSessionId,
                selectedEngineId = effectiveSession.engineId,
                selectedModelId = effectiveSession.model ?: eng?.defaultModel ?: s.selectedModelId,
                selectedMode = effectiveSession.mode ?: eng?.defaultMode ?: s.selectedMode,
                selectedEffort = effectiveSession.reasoningEffort ?: eng?.defaultEffort ?: s.selectedEffort,
                selectedWorkspacePath = effectiveSession.cwd,
                currentBrowsePath = effectiveSession.cwd,
                transcripts = s.transcripts + (sessionId to nextTranscript),
                turnRecoveryBanner = nextBanner,
                inspectedSubagent = syncedSubagent,
            )
        }
    }

    private fun applyIncomingEvent(event: AgentEvent) {
        val inspectedSub = _state.value.inspectedSubagent
        if (inspectedSub != null && event.sessionId == inspectedSub.id) {
            _state.update { s ->
                if (s.inspectedSubagent?.id != event.sessionId) return@update s
                val nextSubTranscript = reduceTranscript(s.inspectedSubagentTranscript, event)
                s.copy(inspectedSubagentTranscript = nextSubTranscript)
            }
            if (event.sessionId != _state.value.activeSessionId) return
        }
        if (event is AgentEvent.UserMessage && event.text.isNotBlank()) {
            lastSubmittedPromptBySession[event.sessionId] = event.text
        }
        // Active session + Desktop warm running sessions receive live stream deltas
        val activeId = _state.value.activeSessionId
        val keepWarm = isDesktopPlatform() && event.sessionId in warmSubscribedSessionIds
        if (event.sessionId != activeId && !keepWarm) return
        _state.update { s ->
            if (event.sessionId != s.activeSessionId && !(isDesktopPlatform() && event.sessionId in warmSubscribedSessionIds)) {
                return@update s
            }
            val prev = s.transcripts[event.sessionId] ?: SessionTranscript()
            val next = reduceTranscript(prev, event)
            val isActiveTarget = event.sessionId == s.activeSessionId
            val clearErr = if (isActiveTarget &&
                (event is AgentEvent.PermissionRequested || event is AgentEvent.PermissionResolved)
            ) {
                null
            } else {
                s.approvalErrorText
            }
            var nextBanner = s.turnRecoveryBanner
            var nextPromptError = s.promptErrorText
            if (isActiveTarget) {
                val nowMs = epochMillis()
                when (event) {
                    is AgentEvent.ErrorEvent -> {
                        markRecentError(event.sessionId, event.message, nowMs)
                        nextBanner = buildTurnRecoveryBanner(
                            previous = s.turnRecoveryBanner,
                            sessionId = event.sessionId,
                            rawMessage = event.message,
                            nowMs = nowMs,
                            preserveUnsentOnSameContext = false,
                        )
                        nextPromptError = null
                    }
                    is AgentEvent.TurnDone -> {
                        if (event.stopReason == "error") {
                            val detail = event.error?.takeIf { it.isNotBlank() } ?: "上一轮任务执行中断"
                            if (!isDuplicateRecentError(event.sessionId, detail, nowMs)) {
                                markRecentError(event.sessionId, detail, nowMs)
                                nextBanner = buildTurnRecoveryBanner(
                                    previous = s.turnRecoveryBanner,
                                    sessionId = event.sessionId,
                                    rawMessage = detail,
                                    nowMs = nowMs,
                                    preserveUnsentOnSameContext = false,
                                )
                                nextPromptError = null
                            }
                        } else if (event.stopReason != "cancelled") {
                            if (nextBanner?.sessionId == event.sessionId || nextBanner?.sessionId == null) {
                                nextBanner = null
                            }
                            nextPromptError = null
                        }
                    }
                    is AgentEvent.SessionStateChanged -> {
                        if (event.state == SessionState.ERROR) {
                            val detail = event.detail?.takeIf { it.isNotBlank() } ?: "会话连接异常中断"
                            if (!isDuplicateRecentError(event.sessionId, detail, nowMs)) {
                                markRecentError(event.sessionId, detail, nowMs)
                                nextBanner = buildTurnRecoveryBanner(
                                    previous = s.turnRecoveryBanner,
                                    sessionId = event.sessionId,
                                    rawMessage = detail,
                                    nowMs = nowMs,
                                    preserveUnsentOnSameContext = false,
                                )
                                nextPromptError = null
                            }
                        }
                    }
                    is AgentEvent.AssistantDelta,
                    is AgentEvent.ReasoningDelta,
                    is AgentEvent.ToolCall,
                    is AgentEvent.ToolUpdate -> {
                        if (nextBanner?.sessionId == event.sessionId || nextBanner?.sessionId == null) {
                            nextBanner = null
                        }
                        nextPromptError = null
                    }
                    else -> Unit
                }
            }
            s.copy(
                approvalErrorText = clearErr,
                promptErrorText = nextPromptError,
                turnRecoveryBanner = nextBanner,
                transcripts = s.transcripts + (event.sessionId to next),
            )
        }
        if (event is AgentEvent.Usage || event is AgentEvent.TurnDone) {
            refreshActiveSessionUsage(event.sessionId)
        }
        if (event is AgentEvent.TurnDone || event is AgentEvent.PlanUpdated) {
            refreshSessionArtifacts(event.sessionId)
        }
    }

    // -----------------------------------------------------------------------
    // Unified Per-Engine Artifacts & Proposed Plan Actions
    // -----------------------------------------------------------------------

    fun refreshSessionArtifacts(sessionId: String = _state.value.activeSessionId.orEmpty()) {
        if (sessionId.isBlank()) return
        if (_state.value.activeSessionId == sessionId) {
            _state.update { it.copy(artifactsLoading = true, artifactError = null) }
        }
        scope.launch {
            runCatching {
                rpc.listArtifacts(sessionId)
            }.onSuccess { list ->
                if (_state.value.activeSessionId == sessionId) {
                    _state.update { s ->
                        val nextOpened = s.openedArtifact?.takeIf { opened ->
                            list.any { it.id == opened.artifact.id }
                        }
                        s.copy(
                            artifactsLoading = false,
                            activeSessionArtifacts = list,
                            openedArtifact = nextOpened,
                            artifactError = null,
                        )
                    }
                }
            }.onFailure { err ->
                if (_state.value.activeSessionId == sessionId) {
                    _state.update {
                        it.copy(
                            artifactsLoading = false,
                            artifactError = err.message ?: "无法获取会话产物",
                        )
                    }
                }
            }
        }
    }

    fun openArtifact(artifactId: String) {
        val sid = _state.value.activeSessionId ?: return
        if (artifactId.isBlank()) return
        _state.update { it.copy(artifactsLoading = true, artifactError = null) }
        scope.launch {
            runCatching {
                rpc.readArtifact(sid, artifactId)
            }.onSuccess { content ->
                if (_state.value.activeSessionId == sid) {
                    _state.update {
                        it.copy(
                            artifactsLoading = false,
                            openedArtifact = content,
                            artifactError = null,
                        )
                    }
                }
            }.onFailure { err ->
                if (_state.value.activeSessionId == sid) {
                    _state.update {
                        it.copy(
                            artifactsLoading = false,
                            artifactError = "无法读取产物内容：${err.message ?: "请稍后重试"}",
                        )
                    }
                }
            }
        }
    }

    fun closeOpenedArtifact() {
        _state.update { it.copy(openedArtifact = null, artifactError = null) }
    }

    fun approveAndExecutePlan(artifactTitle: String? = null) {
        val cur = _state.value
        if (cur.selectedMode == SessionMode.PLAN) {
            val nextMode = cur.availableModes.firstOrNull { it == SessionMode.AUTO }
                ?: cur.availableModes.firstOrNull { it == SessionMode.ACCEPT_EDITS }
                ?: cur.availableModes.firstOrNull { it != SessionMode.PLAN }
                ?: SessionMode.AUTO
            selectMode(nextMode)
        }
        val cleanTitle = artifactTitle?.trim()?.takeIf { it.isNotEmpty() }
        val prompt = if (cleanTitle != null) {
            "已确认执行方案《$cleanTitle》，请开始按方案实施。"
        } else {
            "已确认当前执行方案，请开始按方案实施。"
        }
        sendPrompt(prompt)
    }

    // -----------------------------------------------------------------------
    // Live SubAgents Drill-Down Inspector Actions
    // -----------------------------------------------------------------------

    fun openSubagentInspector(subagent: SubagentSummary) {
        val prevSubId = subscribedSubagentId
        if (prevSubId != null && prevSubId != subagent.id && prevSubId != subscribedSessionId && prevSubId !in warmSubscribedSessionIds) {
            scope.launch {
                runCatching { rpc.unsubscribeSession(prevSubId) }
            }
        }
        val keepExistingTranscript = _state.value.inspectedSubagent?.id == subagent.id
        subscribedSubagentId = subagent.id
        val reqToken = ++subagentInspectToken
        subagentInspectJob?.cancel()
        _state.update { s ->
            val latest = s.activeSession?.subagents?.firstOrNull { it.id == subagent.id } ?: subagent
            s.copy(
                inspectedSubagent = latest,
                inspectedSubagentTranscript = if (keepExistingTranscript) s.inspectedSubagentTranscript else SessionTranscript(),
                inspectedSubagentLoading = true,
                inspectedSubagentError = null,
            )
        }
        loadSubagentTranscript(subagent.id, reqToken)
    }

    fun refreshInspectedSubagent() {
        val current = _state.value.inspectedSubagent ?: return
        val latest = _state.value.activeSession?.subagents?.firstOrNull { it.id == current.id } ?: current
        subscribedSubagentId = latest.id
        val reqToken = ++subagentInspectToken
        subagentInspectJob?.cancel()
        _state.update { s ->
            s.copy(
                inspectedSubagent = latest,
                inspectedSubagentLoading = true,
                inspectedSubagentError = null,
            )
        }
        loadSubagentTranscript(latest.id, reqToken, refreshParentList = true)
    }

    fun closeSubagentInspector() {
        clearInspectedSubagent(updateState = true)
    }

    private fun clearInspectedSubagent(updateState: Boolean) {
        subagentInspectToken++
        subagentInspectJob?.cancel()
        subagentInspectJob = null
        val prevSubId = subscribedSubagentId
        subscribedSubagentId = null
        if (prevSubId != null && prevSubId != subscribedSessionId && prevSubId !in warmSubscribedSessionIds) {
            scope.launch {
                runCatching { rpc.unsubscribeSession(prevSubId) }
            }
        }
        if (updateState && (
            _state.value.inspectedSubagent != null ||
                _state.value.inspectedSubagentLoading ||
                _state.value.inspectedSubagentError != null ||
                _state.value.inspectedSubagentTranscript.items.isNotEmpty()
        )) {
            _state.update {
                it.copy(
                    inspectedSubagent = null,
                    inspectedSubagentTranscript = SessionTranscript(),
                    inspectedSubagentLoading = false,
                    inspectedSubagentError = null,
                )
            }
        }
    }

    private fun loadSubagentTranscript(
        subagentId: String,
        reqToken: Long,
        refreshParentList: Boolean = false,
    ) {
        subagentInspectJob = scope.launch {
            try {
                if (refreshParentList) {
                    runCatching {
                        val freshSessions = rpc.listSessions()
                        if (freshSessions.isNotEmpty() && reqToken == subagentInspectToken) {
                            _state.update { st ->
                                val parent = st.activeSessionId?.let { id -> freshSessions.firstOrNull { it.id == id } }
                                val freshSub = parent?.subagents?.firstOrNull { it.id == subagentId }
                                st.copy(
                                    sessions = freshSessions,
                                    inspectedSubagent = if (st.inspectedSubagent?.id == subagentId && freshSub != null) {
                                        freshSub
                                    } else {
                                        st.inspectedSubagent
                                    },
                                )
                            }
                        }
                    }
                }
                val snap = rpc.subscribeSession(
                    sessionId = subagentId,
                    afterSeq = -1,
                    limit = 160,
                )
                if (reqToken != subagentInspectToken || _state.value.inspectedSubagent?.id != subagentId) {
                    if (subagentId != subscribedSessionId && subagentId !in warmSubscribedSessionIds) {
                        runCatching { rpc.unsubscribeSession(subagentId) }
                    }
                    return@launch
                }
                val reduced = reduceTranscriptBatch(SessionTranscript(), snap.events).copy(
                    earliestSeq = snap.earliestSeq ?: snap.events.firstOrNull()?.seq,
                    hasMoreBefore = snap.hasMoreBefore == true,
                    loadingOlder = false,
                )
                _state.update { s ->
                    val currentSub = s.inspectedSubagent?.takeIf { it.id == subagentId } ?: return@update s
                    val fromParent = s.activeSession?.subagents?.firstOrNull { it.id == subagentId }
                    val base = fromParent ?: currentSub
                    val snapIsAuthoritative = snap.session.updatedAt >= base.updatedAt
                    val resolvedState = if (snapIsAuthoritative) snap.session.state else base.state
                    val synced = base.copy(
                        state = resolvedState,
                        statusText = when {
                            resolvedState == SessionState.RUNNING ->
                                snap.session.liveStatusText ?: base.statusText ?: "执行中…"
                            resolvedState == SessionState.ERROR ->
                                base.statusText ?: "执行出错"
                            fromParent != null && fromParent.state == resolvedState ->
                                fromParent.statusText ?: "已完成"
                            else ->
                                "已完成"
                        },
                        updatedAt = maxOf(base.updatedAt, snap.session.updatedAt),
                    )
                    s.copy(
                        inspectedSubagent = synced,
                        inspectedSubagentTranscript = reduced,
                        inspectedSubagentLoading = false,
                        inspectedSubagentError = null,
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                if (reqToken != subagentInspectToken || _state.value.inspectedSubagent?.id != subagentId) {
                    return@launch
                }
                _state.update { s ->
                    if (s.inspectedSubagent?.id != subagentId) return@update s
                    s.copy(
                        inspectedSubagentLoading = false,
                        inspectedSubagentError = t.message?.takeIf { it.isNotBlank() } ?: "无法读取 SubAgent 执行记录",
                    )
                }
            }
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

internal fun parsePairingPayload(raw: String): QrPairingPayloadDto? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    return runCatching {
        if (trimmed.startsWith("lework://", ignoreCase = true)) {
            val query = trimmed.substringAfter('?', "")
            val rawD = query
                .split('&')
                .firstOrNull { it.startsWith("d=") }
                ?.removePrefix("d=")
                ?.trim()
                ?: return null
            val cleanB64 = rawD
                .replace("%2B", "+")
                .replace("%2F", "/")
                .replace("%3D", "=")
                .replace('+', '-')
                .replace('/', '_')
                .trimEnd('=')
            val padded = cleanB64 + "=".repeat((4 - cleanB64.length % 4) % 4)
            val json = Base64.UrlSafe.decode(padded).decodeToString()
            ProtocolJson.decodeFromString(QrPairingPayloadDto.serializer(), json)
        } else if (trimmed.startsWith("{")) {
            ProtocolJson.decodeFromString(QrPairingPayloadDto.serializer(), trimmed)
        } else {
            null
        }
    }.getOrNull()
}

internal fun extractMentionQueryAtCursor(text: String, cursorIndex: Int = text.length): String? {
    if (text.isEmpty()) return null
    val safeCursor = cursorIndex.coerceIn(0, text.length)
    val beforeCursor = text.substring(0, safeCursor)
    val atIdx = beforeCursor.lastIndexOf('@')
    if (atIdx < 0) return null
    if (atIdx > 0 && !beforeCursor[atIdx - 1].isWhitespace()) return null
    val tail = beforeCursor.substring(atIdx + 1)
    if (tail.any { it.isWhitespace() }) return null
    return tail
}

internal fun extractTrailingMentionQuery(text: String): String? =
    extractMentionQueryAtCursor(text, text.length)

internal fun replaceMentionAtCursor(
    text: String,
    relativeFilePath: String,
    cursorIndex: Int = text.length,
): Pair<String, Int> {
    val safeCursor = cursorIndex.coerceIn(0, text.length)
    val beforeCursor = text.substring(0, safeCursor)
    val suffix = text.substring(safeCursor)
    val atIdx = beforeCursor.lastIndexOf('@')
    if (atIdx < 0) {
        val needsLeadingSpace = beforeCursor.isNotEmpty() && !beforeCursor.last().isWhitespace()
        val lead = if (needsLeadingSpace) " " else ""
        val hasTrailingSpace = suffix.startsWith(' ')
        val trail = if (hasTrailingSpace) "" else " "
        val inserted = "$lead@$relativeFilePath$trail"
        val nextText = beforeCursor + inserted + suffix
        val nextCursor = beforeCursor.length + lead.length + 1 + relativeFilePath.length + 1
        return nextText to nextCursor.coerceAtMost(nextText.length)
    }
    val prefix = beforeCursor.substring(0, atIdx)
    val hasTrailingSpace = suffix.startsWith(' ')
    val insertion = if (hasTrailingSpace) "@$relativeFilePath" else "@$relativeFilePath "
    val nextText = prefix + insertion + suffix
    val nextCursor = (prefix.length + 1 + relativeFilePath.length + 1).coerceAtMost(nextText.length)
    return nextText to nextCursor
}

internal fun insertAtTriggerAtCursor(
    text: String,
    cursorIndex: Int = text.length,
): Pair<String, Int> {
    val safeCursor = cursorIndex.coerceIn(0, text.length)
    val before = text.substring(0, safeCursor)
    val after = text.substring(safeCursor)
    val insert = if (before.isEmpty() || before.last().isWhitespace()) "@" else " @"
    val nextText = before + insert + after
    val nextCursor = before.length + insert.length
    return nextText to nextCursor
}

internal fun replaceTrailingMention(text: String, relativeFilePath: String): String =
    replaceMentionAtCursor(text, relativeFilePath, text.length).first

private fun decodeUrlComponent(encoded: String): String {
    val bytes = mutableListOf<Byte>()
    var i = 0
    while (i < encoded.length) {
        val ch = encoded[i]
        if (ch == '%' && i + 2 < encoded.length) {
            val hex = encoded.substring(i + 1, i + 3).toIntOrNull(16)
            if (hex != null) {
                bytes.add(hex.toByte())
                i += 3
                continue
            }
        } else if (ch == '+') {
            bytes.add(' '.code.toByte())
            i++
            continue
        }
        bytes.addAll(ch.toString().encodeToByteArray().toList())
        i++
    }
    return bytes.toByteArray().decodeToString()
}

internal fun parseSessionDeepLink(raw: String): Pair<String?, String?>? {
    val trimmed = raw.trim()
    if (!trimmed.startsWith("lework://session", ignoreCase = true)) return null
    val afterPrefix = trimmed.substring("lework://session".length)
    if (afterPrefix.isNotEmpty() && afterPrefix[0] != '/' && afterPrefix[0] != '?') return null

    val pathPart = afterPrefix.substringBefore('?').trim('/')
    val queryPart = afterPrefix.substringAfter('?', "")
    val params = queryPart
        .split('&')
        .filter { it.contains('=') }
        .associate {
            val k = it.substringBefore('=').trim()
            val v = decodeUrlComponent(it.substringAfter('=').trim())
            k to v
        }

    val sessionId = params["id"]?.takeIf { it.isNotEmpty() }
        ?: pathPart.takeIf { it.isNotEmpty() }?.let { decodeUrlComponent(it) }
    val hubId = params["hubId"]?.takeIf { it.isNotEmpty() }
    return sessionId to hubId
}
