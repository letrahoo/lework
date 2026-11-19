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
    private var subscribedSessionId: String? = null
    private var subscribeSeqToken: Long = 0L
    private var userManuallyPickedMode: Boolean = false
    private var manualPairingMode: Boolean = false

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
                    redeemPairingInput(uri)
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
            }
        }
        scope.launch {
            rpc.disconnected.collect { reason ->
                val cur = _state.value
                if (!manualPairingMode && cur.hasEnteredWorkspace) {
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
        bootstrapOrConnectToHost(loadActiveHostId())
    }

    private fun bootstrapOrConnectToHost(preferredHubId: String?) {
        manualPairingMode = false
        discoverJob?.cancel()
        oauthPollJob?.cancel()
        tsnetPollJob?.cancel()
        resumeJob?.cancel()
        startBonjourDiscovery()
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
            if (isMobileTsnetAvailable() && loadSavedTsnetEnabled()) {
                withTimeoutOrNull(2_000L) {
                    runCatching {
                        val st = startMobileTsnet(loadSavedTsnetControlUrl())
                        _state.update { it.copy(tsnetStatus = st) }
                    }
                }
            }
            val found = rpc.discoverIntranetHub(
                candidates = candidateHubHttpUrls(),
                dynamicCandidates = observeBonjourHubUrls(),
                waitWindowMs = 7_000L,
                preferredHubId = preferredHubId,
            )
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
                val ok = connectWithToken(found.wsUrl, cachedToken)
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
                    val ok = connectWithToken(found.wsUrl, hs.accessToken)
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
                        connectionError = "Tailscale ($acct) 下暂未发现其他同账号设备。请在电脑终端执行：pnpm --filter @lework/hub cli tsnet login 并登录同一账号",
                    )
                }
                return@launch
            }

            val peerSummary = candidates.take(4).joinToString(", ") { it.hostName }
            _state.update {
                it.copy(
                    connectionStatus = ConnectionStatus.CONNECTING,
                    authStatusText = "正在通过 Tailscale 探测同账号节点 ($peerSummary) 并握手…",
                )
            }

            val probed = coroutineScope {
                candidates.map { peer ->
                    async {
                        val proxyUrl = peer.localProxyUrl ?: return@async null
                        val ep = rpc.probeDirectEndpoint(proxyUrl, timeoutMs = 10_000L)
                        if (ep != null) peer to ep else null
                    }
                }.mapNotNull { it.await() }
            }

            if (probed.isEmpty()) {
                _state.update {
                    it.copy(
                        connectionStatus = ConnectionStatus.ERROR,
                        connectionError = "已发现同账号节点 [$peerSummary]，但未检测到开启 Tailscale 组网的 Lework 主机服务。请在目标电脑执行：pnpm --filter @lework/hub cli tsnet login",
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
                    if (connectWithToken(chosen.wsUrl, hs.accessToken)) {
                        return@launch
                    }
                }.onFailure { err ->
                    lastErr = err.message
                }
            }

            val existingToken = loadHostAccessToken(chosen.discover.hub.id) ?: loadSavedAccessToken()
            if (!existingToken.isNullOrBlank()) {
                if (connectWithToken(chosen.wsUrl, existingToken)) {
                    return@launch
                }
            }

            _state.update {
                it.copy(
                    connectionStatus = ConnectionStatus.ERROR,
                    connectionError = lastErr
                        ?: "已连通主机 ${chosen.discover.hub.name}，但主机端未开启同账号校验，请在电脑执行：pnpm --filter @lework/hub cli tsnet login",
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
                    if (connectWithToken(wsUrl, auth.accessToken)) {
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
                } catch (ce: CancellationException) {
                    throw ce
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
        saveAuthSession(null, null)
        HubTrust.clearPins()
        bootstrapOrConnect()
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

            manualPairingMode = false
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
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            if (t is RpcCallException && t.code == ErrorCode.UNAUTHORIZED) {
                _state.value.hub?.id?.let { saveHostAccessToken(it, null) }
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
            val defaultModel = eng?.models?.firstOrNull { it.id == eng.defaultModel }
                ?: eng?.models?.firstOrNull { it.id == s.selectedModelId }
                ?: eng?.models?.firstOrNull()
            s.copy(
                activeSessionId = null,
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
                submittingApprovalRequestId = null,
                approvalErrorText = null,
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
                val built = snap.events.fold(SessionTranscript()) { acc, ev ->
                    reduceTranscript(acc, ev)
                }
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
                var cur = s.transcripts[sessionId] ?: SessionTranscript()
                for (ev in snap.events) {
                    cur = reduceTranscript(cur, ev)
                }
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
