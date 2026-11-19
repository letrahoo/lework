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

    val unsubscribedIds = mutableListOf<String>()
    val respondedApprovals = mutableListOf<Triple<String, String, String?>>()
    val sentPrompts = mutableListOf<Pair<String, String>>()

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
    ): DiscoveredEndpoint? {
        val chosen = candidates.firstOrNull() ?: "https://127.0.0.1:64450"
        return DiscoveredEndpoint(
            baseUrl = chosen,
            wsUrl = "wss://127.0.0.1:64450/v1/ws",
            discover = HubDiscoverResult(
                ok = true,
                hub = hubInfo,
                oauthEnabled = true,
                oauthProvider = "feishu",
                oauthDisplayName = "飞书",
            ),
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
        modelId: String?,
        reasoningEffort: ReasoningEffort?,
        mode: SessionMode?,
    ): String {
        sentPrompts.add(sessionId to text)
        return "turn_${sentPrompts.size}"
    }

    override suspend fun cancelSession(sessionId: String) = Unit

    override suspend fun setSessionModel(sessionId: String, modelId: String): SessionMeta =
        sessions.first { it.id == sessionId }.copy(model = modelId)

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

    override suspend fun listCheckpoints(sessionId: String): List<CheckpointInfo> = emptyList()

    override suspend fun createCheckpoint(sessionId: String, label: String?): CheckpointInfo =
        CheckpointInfo("cp_1", sessionId, label ?: "cp", "abc1234", 1_000L)

    override suspend fun restoreCheckpoint(sessionId: String, checkpointId: String) = Unit

    override suspend fun disconnect() = Unit
}
