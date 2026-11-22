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
    private var tsnetPollJob: Job? = null
    private var resumeJob: Job? = null
    private var networkHandoffJob: Job? = null
    private var activeTurnSyncJob: Job? = null
    private var reconnectRetryJob: Job? = null
    private var lastRealtimeActivityMs: Long = 0L
    private var subscribedSessionId: String? = null
    private var subscribeSeqToken: Long = 0L
    private var userManuallyPickedMode: Boolean = false
    private var manualPairingMode: Boolean = false
    private var pairingInFlight: Boolean = false
    private var pendingDeepLinkSessionId: String? = null
    private var mentionJob: Job? = null

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
                    val syncModel = if (s.activeSessionId == updated.id && !updated.model.isNullOrBlank()) {
                        updated.model
                    } else {
                        s.selectedModelId
                    }
                    val syncEffort = if (s.activeSessionId == updated.id && updated.reasoningEffort != null) {
                        updated.reasoningEffort
                    } else {
                        s.selectedEffort
                    }
                    s.copy(
                        sessions = nextList,
                        selectedMode = syncMode,
                        selectedModelId = syncModel,
                        selectedEffort = syncEffort,
                    )
                }
                if (transitionedToIdle && _state.value.activeSessionId == updated.id) {
                    scope.launch {
                        runCatching { subscribeAndCatchUp(updated.id) }
                        refreshActiveSessionUsage(updated.id)
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
            ),
        )
    }

    private fun upsertPairedHost(
        hub: HubInfo,
        fingerprint: String?,
        endpoints: List<String>,
        userName: String,
    ) {
        val cleanEndpoints = endpoints
            .map { normalizeHubBaseUrl(it) }
            .filter { it.isNotBlank() && !it.contains("127.0.0.1") }
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
        )
        val nextList = (listOf(updated) + existing.filterNot { it.hubId == hub.id }).take(12)
        savePairedHosts(nextList)
        saveActiveHostId(hub.id)
        _state.update { it.copy(pairedHosts = nextList, activeHostId = hub.id) }
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
        _state.update {
            it.copy(
                hasEnteredWorkspace = false,
                connectionStatus = ConnectionStatus.NEEDS_AUTH,
                oauthEnabled = false,
                oauthChallenge = null,
                connectionError = null,
                discoveredBaseUrl = "",
                discoveredWsUrl = "",
                pairedHosts = loadPairedHosts(),
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
                if (fromNetworkHandoff) {
                    // Transient signal drop (e.g., elevator Wi-Fi -> 5G transition): stay in workspace
                    // with RECONNECTING status so the next NetworkCallback event resumes automatically!
                    _state.update {
                        it.copy(
                            connectionStatus = ConnectionStatus.RECONNECTING,
                            connectionError = null,
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
        saveAuthSession(null, null)
        _state.update {
            it.copy(
                hasEnteredWorkspace = false,
                authUserName = null,
                connectionStatus = ConnectionStatus.NEEDS_AUTH,
                connectionError = null,
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
                endpoints = hello.endpoints + hello.relayUrls + listOf(baseUrl),
                userName = resolvedUserName,
            )
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

            manualPairingMode = false
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
            val defaultModel = eng?.models?.firstOrNull { it.id == eng.defaultModel }
                ?: eng?.models?.firstOrNull { it.id == s.selectedModelId }
                ?: eng?.models?.firstOrNull()
            s.copy(
                activeSessionId = null,
                loadingSessionId = null,
                checkpoints = emptyList(),
                activeSessionUsage = null,
                openedDiffContent = null,
                openedDiffTitle = null,
                checkpointNotice = null,
                selectedProviderId = defaultModel?.providerId ?: s.selectedProviderId,
                selectedModelId = eng?.defaultModel ?: defaultModel?.id ?: s.selectedModelId,
                selectedEffort = eng?.defaultEffort ?: defaultModel?.defaultEffort ?: s.selectedEffort,
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
                loadingSessionId = null,
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
            val alreadyCached = s.transcripts[sessionId]?.let { t -> t.lastSeq >= 0 || t.items.isNotEmpty() } == true
            s.copy(
                activeSessionId = sessionId,
                loadingSessionId = if (alreadyCached) null else sessionId,
                checkpoints = emptyList(),
                activeSessionUsage = null,
                openedDiffContent = null,
                openedDiffTitle = null,
                checkpointNotice = null,
                submittingApprovalRequestId = null,
                approvalErrorText = null,
                promptErrorText = null,
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

    fun updateComposerTextForMentions(nextText: String) {
        val query = extractTrailingMentionQuery(nextText)
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

    fun pickAndAttachImage() {
        if (!canPickImages()) return
        if (_state.value.composerAttachedImages.size >= 4) return
        requestImagePick { picked ->
            if (picked != null && picked.dataBase64.isNotBlank()) {
                attachImage(
                    PromptImageAttachment(
                        mimeType = picked.mimeType,
                        dataBase64 = picked.dataBase64,
                        fileName = picked.fileName.take(120),
                    ),
                )
            }
        }
    }

    fun attachImage(attachment: PromptImageAttachment) {
        _state.update { s ->
            if (s.composerAttachedImages.size >= 4) s
            else s.copy(composerAttachedImages = s.composerAttachedImages + attachment)
        }
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

    fun consumeRestoredDraft() {
        _state.update { it.copy(unsentDraftToRestore = null) }
    }

    private suspend fun ensureConnectedForSend(forceReconnect: Boolean = false): Boolean {
        if (!forceReconnect && _state.value.connectionStatus == ConnectionStatus.CONNECTED) {
            return true
        }
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
        var waited = 0L
        while (waited < 8_000L) {
            if (_state.value.connectionStatus == ConnectionStatus.CONNECTED) return true
            if (_state.value.connectionStatus == ConnectionStatus.NEEDS_AUTH) return false
            delay(200L)
            waited += 200L
        }
        return _state.value.connectionStatus == ConnectionStatus.CONNECTED
    }

    private suspend fun dispatchPromptRpc(
        trimmed: String,
        attached: List<PromptImageAttachment>,
    ) {
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
                unsentDraftToRestore = null,
            )
        }
        scope.launch {
            try {
                if (_state.value.connectionStatus != ConnectionStatus.CONNECTED) {
                    ensureConnectedForSend(forceReconnect = false)
                }
                try {
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
            } catch (ce: CancellationException) {
                if (!isActive) throw ce
                _state.update { st ->
                    st.copy(
                        composerAttachedImages = st.composerAttachedImages.ifEmpty { attached },
                        promptErrorText = "网络连接刚恢复，请再点一次发送",
                        unsentDraftToRestore = trimmed.takeIf { it.isNotEmpty() },
                    )
                }
            } catch (t: Throwable) {
                val errMsg = t.message?.takeIf { it.isNotBlank() } ?: "消息发送未成功，请检查连接后重试"
                _state.update { st ->
                    st.copy(
                        composerAttachedImages = st.composerAttachedImages.ifEmpty { attached },
                        promptErrorText = errMsg,
                        unsentDraftToRestore = trimmed.takeIf { it.isNotEmpty() },
                    )
                }
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
                val built = reduceTranscriptBatch(SessionTranscript(), snap.events)
                val concurrentPerms = s.transcripts[sessionId]?.pendingPermissions.orEmpty()
                val mergedPerms = if (built.pendingPermissions.any { it.requestId.startsWith("tty_perm_") }) {
                    built.pendingPermissions
                } else if (snap.session.state == SessionState.WAITING_PERMISSION) {
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
                if (snap.session.state != SessionState.WAITING_PERMISSION &&
                    snap.events.none { it is AgentEvent.PermissionRequested }
                ) {
                    cur = cur.copy(
                        pendingPermissions = cur.pendingPermissions.filterNot { it.requestId.startsWith("tty_perm_") },
                    )
                }
                cur
            }
            val eng = s.engines.firstOrNull { it.id == snap.session.engineId }
            s.copy(
                sessions = updatedSessions,
                loadingSessionId = if (s.loadingSessionId == sessionId) null else s.loadingSessionId,
                selectedEngineId = snap.session.engineId,
                selectedModelId = snap.session.model ?: eng?.defaultModel ?: s.selectedModelId,
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
            val clearErr = if (event is AgentEvent.PermissionRequested || event is AgentEvent.PermissionResolved) {
                null
            } else {
                s.approvalErrorText
            }
            s.copy(
                approvalErrorText = clearErr,
                transcripts = s.transcripts + (event.sessionId to next),
            )
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

internal fun extractTrailingMentionQuery(text: String): String? {
    val atIdx = text.lastIndexOf('@')
    if (atIdx < 0) return null
    if (atIdx > 0 && !text[atIdx - 1].isWhitespace()) return null
    val tail = text.substring(atIdx + 1)
    if (tail.any { it.isWhitespace() }) return null
    return tail
}

internal fun replaceTrailingMention(text: String, relativeFilePath: String): String {
    val atIdx = text.lastIndexOf('@')
    if (atIdx < 0) return "$text @$relativeFilePath "
    return text.substring(0, atIdx) + "@$relativeFilePath "
}

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
