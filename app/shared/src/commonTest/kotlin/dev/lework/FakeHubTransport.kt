package dev.lework

import dev.lework.protocol.*
import dev.lework.transport.DiscoveredEndpoint
import dev.lework.transport.HubTransport
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow

class FakeHubTransport : HubTransport {
    private val _events = MutableSharedFlow<AgentEvent>(
        replay = 0,
        extraBufferCapacity = 4096,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: SharedFlow<AgentEvent> = _events.asSharedFlow()

    private val _sessionUpdates = MutableSharedFlow<SessionMeta>(
        replay = 0,
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val sessionUpdates: SharedFlow<SessionMeta> = _sessionUpdates.asSharedFlow()

    private val _disconnected = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val disconnected: SharedFlow<String> = _disconnected.asSharedFlow()

    val hubInfo = HubInfo(
        id = "hub_fake_1",
        name = "TestMacBook",
        role = HubRole.SLAVE,
        platform = PlatformType.DARWIN,
        version = "0.3.9",
    )

    var sessions: MutableList<SessionMeta> = mutableListOf()
    val sessionEventsMap: MutableMap<String, MutableList<AgentEvent>> = mutableMapOf()
    val subscribeDelayMsBySession: MutableMap<String, Long> = mutableMapOf()

    var listSessionsException: Throwable? = null
    var connectHelloException: Throwable? = null
    var simulateTailscaleSameUser: Boolean = false
    var tailscaleHandshakeCount: Int = 0
    var pairRedeemCount: Int = 0
    var emitDisconnectOnClientClose: Boolean = false
    private var wsConnected: Boolean = false

    val unsubscribedIds = mutableListOf<String>()
    val respondedApprovals = mutableListOf<Triple<String, String, String?>>()
    val sentPrompts = mutableListOf<Pair<String, String>>()
    val sentPromptImages = mutableListOf<Pair<String, List<PromptImageAttachment>>>()
    var workspaceFilesList: List<String> = listOf(
        "README.md",
        "hub/src/server.ts",
        "hub/src/session-manager.ts",
        "app/shared/src/commonMain/kotlin/dev/lework/store/ConsoleStore.kt",
    )

    fun emitEvent(event: AgentEvent) {
        _events.tryEmit(event)
    }

    fun emitSessionUpdate(session: SessionMeta) {
        val idx = sessions.indexOfFirst { it.id == session.id }
        if (idx >= 0) sessions[idx] = session else sessions.add(0, session)
        _sessionUpdates.tryEmit(session)
    }

    fun emitDisconnected(reason: String = "Simulated disconnect") {
        _disconnected.tryEmit(reason)
    }

    override suspend fun discoverIntranetHub(
        candidates: List<String>,
        dynamicCandidates: StateFlow<List<String>>?,
        waitWindowMs: Long,
        preferredHubId: String?,
    ): DiscoveredEndpoint? {
        val chosen = candidates.firstOrNull() ?: "https://127.0.0.1:64450"
        return DiscoveredEndpoint(
            baseUrl = chosen,
            wsUrl = dev.lework.security.hubWsUrlOf(chosen),
            discover = HubDiscoverResult(
                ok = true,
                hub = hubInfo,
                oauthEnabled = !simulateTailscaleSameUser,
                oauthProvider = if (simulateTailscaleSameUser) null else "feishu",
                oauthDisplayName = if (simulateTailscaleSameUser) null else "飞书",
                tailscaleSameUser = simulateTailscaleSameUser,
                tailscaleUser = if (simulateTailscaleSameUser) "Owner" else null,
            ),
        )
    }

    override suspend fun authenticateViaTailscale(
        baseUrl: String,
        deviceName: String,
    ): DirectTokenAuthResult {
        tailscaleHandshakeCount++
        return DirectTokenAuthResult(
            ok = true,
            accessToken = "lework_dt.tailscale_mesh_token",
            user = OAuthUser(subject = "tailscale:owner@example.com", name = "Owner", email = "owner@example.com"),
            fingerprint = "a".repeat(64),
            proof = "b".repeat(64),
            hub = hubInfo,
            endpoints = listOf("https://100.88.12.34:64450"),
        )
    }

    override suspend fun redeemPairingCode(
        baseUrl: String,
        pairCode: String?,
        staticToken: String?,
        expectedFingerprint: String?,
        deviceName: String,
    ): DirectTokenAuthResult {
        pairRedeemCount++
        return DirectTokenAuthResult(
            ok = true,
            accessToken = "lework_dt.paired_qr_token",
            user = OAuthUser(subject = "pair:qr", name = deviceName),
            fingerprint = expectedFingerprint ?: "a".repeat(64),
            proof = "b".repeat(64),
            hub = hubInfo,
            endpoints = listOf(baseUrl),
        )
    }

    override suspend fun startOAuthDeviceFlow(baseUrl: String): OAuthStartResult =
        OAuthStartResult(
            sid = "oauth_sid_1",
            userCode = "ABCD-1234",
            verifyUrl = "https://open.feishu.cn/verify",
            appLaunchUrl = "https://applink.feishu.cn/client/web_url/open?mode=window",
            expiresIn = 300,
            interval = 60,
        )

    override suspend fun pollOAuthDeviceFlow(baseUrl: String, sid: String): OAuthPollResponse =
        OAuthPollResponse(status = "pending")

    override suspend fun connectAndHello(
        wsUrl: String,
        token: String,
        platformName: String,
    ): HubHelloResult {
        connectHelloException?.let { throw it }
        wsConnected = true
        return HubHelloResult(
            protocolVersion = PROTOCOL_VERSION,
            hub = hubInfo,
            capabilities = listOf("stream", "approvals"),
        )
    }

    override suspend fun fetchStatus(): HubStatusResult =
        HubStatusResult(
            hub = hubInfo,
            slaves = emptyList(),
            engines = listOf(
                EngineInfo(
                    id = "claude",
                    displayName = "Claude Code",
                    kind = EngineKind.CLI,
                    status = EngineStatus(installed = true, ready = true, version = "2.1.328"),
                    capabilities = EngineCapabilities(
                        permissions = true,
                        images = false,
                        modelSwitch = true,
                        modes = true,
                        history = true,
                        cancel = true,
                    ),
                    models = listOf(
                        EngineModelSpec(
                            id = "gemini-4-carbon-high",
                            name = "Gemini 4 Carbon High",
                            efforts = listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH),
                            defaultEffort = ReasoningEffort.HIGH,
                        ),
                    ),
                    defaultModel = "gemini-4-carbon-high",
                    defaultMode = SessionMode.AUTO,
                    defaultEffort = ReasoningEffort.HIGH,
                    supportedModes = listOf(
                        SessionMode.AUTO,
                        SessionMode.ACCEPT_EDITS,
                        SessionMode.PLAN,
                        SessionMode.DEFAULT,
                    ),
                ),
            ),
            providers = emptyList(),
            profiles = emptyList(),
        )

    override suspend fun listWorkspaces(): List<Workspace> =
        listOf(
            Workspace(
                id = "ws_1",
                name = "lework",
                path = "/workspace/lework",
            ),
        )

    override suspend fun searchWorkspaceFiles(
        path: String,
        query: String?,
        limit: Int,
    ): WorkspaceFilesResult {
        val q = query?.trim()?.lowercase().orEmpty()
        val matched = if (q.isEmpty()) {
            workspaceFilesList
        } else {
            workspaceFilesList.filter { it.lowercase().contains(q) }
        }
        return WorkspaceFilesResult(path = path, files = matched.take(limit))
    }

    override suspend fun listDirectory(path: String): FsListResult =
        FsListResult(path = path, entries = emptyList())

    override suspend fun readFile(path: String): FsReadResult =
        FsReadResult(path = path, content = "", truncated = false)

    override suspend fun upsertProfile(profile: ProfileInfo): List<ProfileInfo> = listOf(profile)

    override suspend fun listSessions(engineId: String?): List<SessionMeta> {
        listSessionsException?.let { throw it }
        return if (engineId == null) sessions.toList() else sessions.filter { it.engineId == engineId }
    }

    override suspend fun createSession(
        engineId: String,
        cwd: String,
        workspaces: List<String>?,
        title: String?,
        providerId: String?,
        modelId: String?,
        reasoningEffort: ReasoningEffort?,
        mode: SessionMode?,
    ): SessionMeta {
        val created = SessionMeta(
            id = "sess_${sessions.size + 1}",
            engineId = engineId,
            cwd = cwd,
            workspaces = workspaces,
            title = title ?: "New Session",
            state = SessionState.IDLE,
            createdAt = 1_000L,
            updatedAt = 1_000L,
            lastSeq = -1,
            model = modelId,
            reasoningEffort = reasoningEffort,
            mode = mode,
        )
        sessions.add(0, created)
        return created
    }

    override suspend fun sendPrompt(
        sessionId: String,
        text: String,
        images: List<PromptImageAttachment>?,
        modelId: String?,
        reasoningEffort: ReasoningEffort?,
        mode: SessionMode?,
    ): String {
        sentPrompts.add(sessionId to text)
        if (!images.isNullOrEmpty()) {
            sentPromptImages.add(sessionId to images)
        }
        return "turn_${sentPrompts.size}"
    }

    override suspend fun cancelSession(sessionId: String) = Unit

    override suspend fun setSessionModel(sessionId: String, modelId: String): SessionMeta =
        sessions.first { it.id == sessionId }.copy(model = modelId)

    override suspend fun renameSession(sessionId: String, title: String): SessionMeta {
        val idx = sessions.indexOfFirst { it.id == sessionId }
        if (idx < 0) throw dev.lework.transport.RpcCallException(ErrorCode.NOT_FOUND, "Session not found")
        val prev = sessions[idx]
        val clean = title.trim()
        val updated = if (clean.isEmpty()) {
            prev.copy(customTitle = null)
        } else {
            prev.copy(title = clean, customTitle = clean)
        }
        sessions[idx] = updated
        return updated
    }

    override suspend fun archiveSession(sessionId: String, archived: Boolean): SessionMeta {
        val idx = sessions.indexOfFirst { it.id == sessionId }
        if (idx < 0) throw dev.lework.transport.RpcCallException(ErrorCode.NOT_FOUND, "Session not found")
        val prev = sessions[idx]
        val updated = prev.copy(
            archived = archived,
            archivedAt = if (archived) 9_999L else null,
        )
        sessions[idx] = updated
        return updated
    }

    override suspend fun subscribeSession(
        sessionId: String,
        afterSeq: Int,
        beforeSeq: Int?,
        limit: Int?,
    ): StreamSubscribeResult {
        val delayMs = subscribeDelayMsBySession[sessionId] ?: 0L
        if (delayMs > 0L) delay(delayMs)

        val meta = sessions.firstOrNull { it.id == sessionId }
            ?: SessionMeta(
                id = sessionId,
                engineId = "claude",
                cwd = "/workspace/lework",
                title = sessionId,
                state = SessionState.IDLE,
                createdAt = 1_000L,
                updatedAt = 1_000L,
                lastSeq = -1,
            )
        val all = sessionEventsMap[sessionId].orEmpty()
        if (all.isEmpty()) {
            return StreamSubscribeResult(
                session = meta,
                events = emptyList(),
                truncated = false,
                earliestSeq = 0,
                hasMoreBefore = false,
            )
        }

        if (beforeSeq != null) {
            val older = all.filter { it.seq < beforeSeq }
            val pageSize = limit ?: 220
            val start = (older.size - pageSize).coerceAtLeast(0)
            val slice = older.subList(start, older.size)
            val earliest = slice.firstOrNull()?.seq ?: all.first().seq
            val hasMore = start > 0
            return StreamSubscribeResult(
                session = meta.copy(lastSeq = all.last().seq),
                events = slice,
                truncated = hasMore,
                earliestSeq = earliest,
                hasMoreBefore = hasMore,
            )
        }

        if (afterSeq == -1 && limit != null && all.size > limit) {
            val start = (all.size - limit).coerceAtLeast(0)
            val slice = all.subList(start, all.size)
            return StreamSubscribeResult(
                session = meta.copy(lastSeq = all.last().seq),
                events = slice,
                truncated = start > 0,
                earliestSeq = slice.first().seq,
                hasMoreBefore = start > 0,
            )
        }

        val delta = all.filter { it.seq > afterSeq }
        return StreamSubscribeResult(
            session = meta.copy(lastSeq = all.last().seq),
            events = delta,
            truncated = false,
            earliestSeq = delta.firstOrNull()?.seq ?: all.first().seq,
            hasMoreBefore = false,
        )
    }

    override suspend fun unsubscribeSession(sessionId: String) {
        unsubscribedIds.add(sessionId)
    }

    override suspend fun respondApproval(sessionId: String, requestId: String, optionId: String?) {
        respondedApprovals.add(Triple(sessionId, requestId, optionId))
    }

    private val checkpointsBySession = mutableMapOf<String, MutableList<CheckpointInfo>>()
    val restoredCheckpoints = mutableListOf<Pair<String, String>>()

    override suspend fun listCheckpoints(sessionId: String): List<CheckpointInfo> =
        checkpointsBySession[sessionId]?.toList().orEmpty()

    override suspend fun createCheckpoint(sessionId: String, label: String?): CheckpointInfo {
        val list = checkpointsBySession.getOrPut(sessionId) { mutableListOf() }
        val info = CheckpointInfo(
            id = "cp_${list.size + 1}",
            sessionId = sessionId,
            label = label ?: "手动快照 #${list.size + 1}",
            commitSha = "a1b2c3d4e5f6",
            createdAt = 1_700_000_000_000L,
        )
        list.add(0, info)
        return info
    }

    override suspend fun restoreCheckpoint(sessionId: String, checkpointId: String) {
        restoredCheckpoints.add(sessionId to checkpointId)
    }

    override suspend fun fetchUsageSummary(days: Int, engineId: String?): UsageSummaryResult =
        UsageSummaryResult(
            days = days,
            generatedAt = 1_700_000_000_000L,
            totals = UsageTotals(
                inputTokens = 120_000L,
                outputTokens = 30_000L,
                cacheReadTokens = 850_000L,
                cacheWriteTokens = 40_000L,
                totalTokens = 1_040_000L,
                requests = 42,
                estimatedCostUsd = 1.86,
            ),
            daily = listOf(
                DailyUsageEntry(
                    date = "2026-11-19",
                    inputTokens = 120_000L,
                    outputTokens = 30_000L,
                    cacheReadTokens = 850_000L,
                    cacheWriteTokens = 40_000L,
                    totalTokens = 1_040_000L,
                    requests = 42,
                    estimatedCostUsd = 1.86,
                ),
            ),
            byEngine = listOf(
                EngineUsageEntry(
                    engineId = "claude",
                    supported = true,
                    inputTokens = 120_000L,
                    outputTokens = 30_000L,
                    cacheReadTokens = 850_000L,
                    cacheWriteTokens = 40_000L,
                    totalTokens = 1_040_000L,
                    requests = 42,
                    estimatedCostUsd = 1.86,
                ),
            ),
            byModel = listOf(
                ModelUsageEntry(
                    model = "claude-opus-4-6",
                    engineId = "claude",
                    inputTokens = 120_000L,
                    outputTokens = 30_000L,
                    cacheReadTokens = 850_000L,
                    cacheWriteTokens = 40_000L,
                    totalTokens = 1_040_000L,
                    requests = 42,
                    estimatedCostUsd = 1.86,
                ),
            ),
        )

    override suspend fun fetchUsageQuota(): UsageQuotaResult =
        UsageQuotaResult(
            generatedAt = 1_700_000_000_000L,
            windows = listOf(
                QuotaWindowInfo(
                    id = "claude:5h",
                    engineId = "claude",
                    windowKind = "five_hour",
                    label = "Claude · 5 小时窗口",
                    source = "estimated",
                    active = true,
                    usedTokens = 420_000L,
                    estimatedCostUsd = 0.75,
                    burnRateTokensPerMin = 3_500.0,
                    windowMinutes = 300,
                    resetsAt = 1_700_007_200_000L,
                ),
                QuotaWindowInfo(
                    id = "codex:7d",
                    engineId = "codex",
                    windowKind = "weekly",
                    label = "Codex · 周额度",
                    source = "reported",
                    active = true,
                    usedPercent = 38.0,
                    usedTokens = 685_000L,
                    windowMinutes = 10_080,
                    resetsAt = 1_700_300_000_000L,
                ),
            ),
        )

    override suspend fun fetchSessionUsage(sessionId: String): SessionUsageResult =
        SessionUsageResult(
            sessionId = sessionId,
            engineId = "claude",
            model = "claude-opus-4-6",
            supported = true,
            contextTokens = 68_400L,
            contextWindow = 200_000L,
            contextPercent = 34.2,
            cacheHitPercent = 88.0,
            lastInputTokens = 1_200L,
            lastOutputTokens = 650L,
            totals = UsageTotals(
                inputTokens = 24_000L,
                outputTokens = 8_500L,
                cacheReadTokens = 180_000L,
                cacheWriteTokens = 12_000L,
                totalTokens = 224_500L,
                requests = 9,
                estimatedCostUsd = 0.42,
            ),
        )

    override suspend fun fetchProxyUsage(): ProxyUsageResult =
        ProxyUsageResult(
            endpoint = "http://127.0.0.1:8317",
            reachable = true,
            exposedModels = 6,
            managementEnabled = false,
            note = "本机代理在线（如需请求统计可在 ~/.lework/config.json 配置 cpaManagementKey）",
        )

    override suspend fun fetchGitStatus(path: String): GitStatusResult =
        GitStatusResult(
            path = path,
            isGitRepo = true,
            branch = "main",
            headSha = "83ae0f6",
            totalAdditions = 12,
            totalDeletions = 3,
            files = listOf(
                GitChangedFileEntry(path = "hub/src/server.ts", status = "modified", additions = 12, deletions = 3),
            ),
        )

    override suspend fun fetchGitDiff(
        path: String,
        filePath: String?,
        baseCommitSha: String?,
    ): GitDiffResult =
        GitDiffResult(
            path = path,
            filePath = filePath,
            diff = "--- a/hub/src/server.ts\n+++ b/hub/src/server.ts\n@@ -1,2 +1,2 @@\n-old line\n+new line",
            truncated = false,
        )

    override suspend fun disconnect() {
        val wasConnected = wsConnected
        wsConnected = false
        if (wasConnected && emitDisconnectOnClientClose) {
            _disconnected.tryEmit("Connection closed")
        }
    }
}
