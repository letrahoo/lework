package dev.lework.transport

import dev.lework.protocol.*
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement

class RpcCallException(
    val code: Int,
    override val message: String,
) : Exception(message)

data class DiscoveredEndpoint(
    val baseUrl: String,
    val wsUrl: String,
    val discover: FeishuDiscoverResult,
)

interface HubTransport {
    val events: SharedFlow<AgentEvent>
    val sessionUpdates: SharedFlow<SessionMeta>
    val disconnected: SharedFlow<String>

    suspend fun discoverIntranetHub(
        candidates: List<String>,
        dynamicCandidates: StateFlow<List<String>>? = null,
        waitWindowMs: Long = 6_500L,
    ): DiscoveredEndpoint?

    suspend fun startFeishuDeviceFlow(baseUrl: String): FeishuStartResult
    suspend fun pollFeishuDeviceFlow(baseUrl: String, sid: String): FeishuPollResponse

    suspend fun connectAndHello(
        wsUrl: String,
        token: String,
        platformName: String,
    ): HubHelloResult

    suspend fun fetchStatus(): HubStatusResult
    suspend fun listWorkspaces(): List<Workspace>
    suspend fun listDirectory(path: String): FsListResult
    suspend fun readFile(path: String): FsReadResult
    suspend fun upsertProfile(profile: ProfileInfo): List<ProfileInfo>
    suspend fun listSessions(engineId: String? = null): List<SessionMeta>

    suspend fun createSession(
        engineId: String,
        cwd: String,
        workspaces: List<String>? = null,
        title: String? = null,
        providerId: String? = null,
        modelId: String? = null,
        reasoningEffort: ReasoningEffort? = null,
        mode: SessionMode? = null,
    ): SessionMeta

    suspend fun sendPrompt(
        sessionId: String,
        text: String,
        modelId: String? = null,
        reasoningEffort: ReasoningEffort? = null,
        mode: SessionMode? = null,
    ): String

    suspend fun cancelSession(sessionId: String)
    suspend fun setSessionModel(sessionId: String, modelId: String): SessionMeta

    suspend fun subscribeSession(
        sessionId: String,
        afterSeq: Int,
        beforeSeq: Int? = null,
        limit: Int? = null,
    ): StreamSubscribeResult

    suspend fun unsubscribeSession(sessionId: String)
    suspend fun respondApproval(sessionId: String, requestId: String, optionId: String?)
    suspend fun listCheckpoints(sessionId: String): List<CheckpointInfo>
    suspend fun createCheckpoint(sessionId: String, label: String? = null): CheckpointInfo
    suspend fun restoreCheckpoint(sessionId: String, checkpointId: String)
    suspend fun disconnect()
}

class HubRpcClient(
    private val scope: CoroutineScope,
    private val httpClient: HttpClient = HttpClient { install(WebSockets) },
) : HubTransport {
    private var wsSession: DefaultClientWebSocketSession? = null
    private var readerJob: Job? = null
    private var nextId: Int = 1
    private val pendingMutex = Mutex()
    private val pending = mutableMapOf<Int, CompletableDeferred<JsonElement?>>()

    private val _events = MutableSharedFlow<AgentEvent>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: SharedFlow<AgentEvent> = _events.asSharedFlow()

    private val _sessionUpdates = MutableSharedFlow<SessionMeta>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val sessionUpdates: SharedFlow<SessionMeta> = _sessionUpdates.asSharedFlow()

    private val _disconnected = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val disconnected: SharedFlow<String> = _disconnected.asSharedFlow()

    override suspend fun discoverIntranetHub(
        candidates: List<String>,
        dynamicCandidates: StateFlow<List<String>>?,
        waitWindowMs: Long,
    ): DiscoveredEndpoint? {
        val winner = CompletableDeferred<DiscoveredEndpoint?>()
        val probedMutex = Mutex()
        val probed = mutableSetOf<String>()
        val jobs = mutableListOf<Job>()

        suspend fun launchProbeIfNew(base: String) {
            val normalized = base.trim().trimEnd('/')
            if (normalized.isEmpty()) return
            val shouldStart = probedMutex.withLock { probed.add(normalized) }
            if (!shouldStart) return

            val job = scope.launch {
                try {
                    val res = withTimeout(4_500L) {
                        httpClient.get("$normalized/v1/auth/discover")
                    }
                    if (res.status.isSuccess()) {
                        val text = res.bodyAsText()
                        val info = ProtocolJson.decodeFromString(FeishuDiscoverResult.serializer(), text)
                        val wsScheme = if (normalized.startsWith("https://")) "wss://" else "ws://"
                        val hostPort = normalized.removePrefix("https://").removePrefix("http://")
                        winner.complete(
                            DiscoveredEndpoint(
                                baseUrl = normalized,
                                wsUrl = "$wsScheme$hostPort${info.wsPath}",
                                discover = info,
                            ),
                        )
                    }
                } catch (_: Throwable) {
                    // ignore individual candidate failure
                }
            }
            probedMutex.withLock { jobs.add(job) }
        }

        candidates.forEach { launchProbeIfNew(it) }

        val dynamicCollector = dynamicCandidates?.let { flow ->
            scope.launch {
                flow.collect { urls ->
                    for (u in urls) {
                        launchProbeIfNew(u)
                    }
                }
            }
        }

        if (dynamicCandidates == null) {
            scope.launch {
                val snapshot = probedMutex.withLock { jobs.toList() }
                snapshot.forEach { it.join() }
                if (!winner.isCompleted) winner.complete(null)
            }
        }

        val result = withTimeoutOrNull(waitWindowMs) {
            winner.await()
        }
        dynamicCollector?.cancel()
        val allJobs = probedMutex.withLock { jobs.toList() }
        allJobs.forEach { it.cancel() }
        return result
    }

    override suspend fun startFeishuDeviceFlow(baseUrl: String): FeishuStartResult {
        val res = withTimeout(8_000L) {
            httpClient.post("$baseUrl/v1/auth/feishu/start")
        }
        val body = res.bodyAsText()
        if (!res.status.isSuccess()) {
            throw IllegalStateException("飞书认证初始化失败: $body")
        }
        return ProtocolJson.decodeFromString(FeishuStartResult.serializer(), body)
    }

    override suspend fun pollFeishuDeviceFlow(baseUrl: String, sid: String): FeishuPollResponse {
        val reqJson = ProtocolJson.encodeToString(FeishuPollRequest.serializer(), FeishuPollRequest(sid))
        val res = withTimeout(8_000L) {
            httpClient.post("$baseUrl/v1/auth/feishu/poll") {
                setBody(reqJson)
            }
        }
        return ProtocolJson.decodeFromString(FeishuPollResponse.serializer(), res.bodyAsText())
    }

    override suspend fun connectAndHello(
        wsUrl: String,
        token: String,
        platformName: String,
    ): HubHelloResult {
        disconnect()
        val session = httpClient.webSocketSession(urlString = wsUrl)
        wsSession = session
        readerJob = scope.launch {
            readLoop(session)
        }
        return call(
            method = "hub.hello",
            params = encodeParams(
                HubHelloParams(
                    protocolVersion = PROTOCOL_VERSION,
                    token = token,
                    client = ClientInfo(
                        name = "lework-mobile",
                        version = "0.3.9",
                        platform = platformName,
                    ),
                ),
            ),
        )
    }

    override suspend fun fetchStatus(): HubStatusResult = call("hub.status")

    override suspend fun listWorkspaces(): List<Workspace> =
        call<WorkspaceListResult>("workspace.list").workspaces

    override suspend fun listDirectory(path: String): FsListResult =
        call("fs.list", encodeParams(FsListParams(path = path)))

    override suspend fun readFile(path: String): FsReadResult =
        call("fs.read", encodeParams(FsReadParams(path = path)))

    override suspend fun upsertProfile(profile: ProfileInfo): List<ProfileInfo> =
        call<ProfileListResult>("profile.upsert", encodeParams(ProfileUpsertParams(profile))).profiles

    override suspend fun listSessions(engineId: String?): List<SessionMeta> =
        call<SessionListResult>("session.list", encodeParams(SessionListParams(engineId))).sessions

    override suspend fun createSession(
        engineId: String,
        cwd: String,
        workspaces: List<String>?,
        title: String?,
        providerId: String?,
        modelId: String?,
        reasoningEffort: ReasoningEffort?,
        mode: SessionMode?,
    ): SessionMeta =
        call<SessionCreateResult>(
            "session.create",
            encodeParams(
                SessionCreateParams(
                    engineId = engineId,
                    cwd = cwd,
                    workspaces = workspaces,
                    title = title,
                    providerId = providerId,
                    modelId = modelId,
                    reasoningEffort = reasoningEffort,
                    mode = mode,
                ),
            ),
        ).session

    override suspend fun sendPrompt(
        sessionId: String,
        text: String,
        modelId: String?,
        reasoningEffort: ReasoningEffort?,
        mode: SessionMode?,
    ): String =
        call<SessionPromptResult>(
            "session.prompt",
            encodeParams(
                SessionPromptParams(
                    sessionId = sessionId,
                    text = text,
                    modelId = modelId,
                    reasoningEffort = reasoningEffort,
                    mode = mode,
                ),
            ),
        ).turnId

    override suspend fun cancelSession(sessionId: String) {
        call<OkResult>("session.cancel", encodeParams(SessionCancelParams(sessionId = sessionId)))
    }

    override suspend fun setSessionModel(sessionId: String, modelId: String): SessionMeta =
        call<SessionCreateResult>(
            "session.setModel",
            encodeParams(SessionSetModelParams(sessionId = sessionId, modelId = modelId)),
        ).session

    override suspend fun subscribeSession(
        sessionId: String,
        afterSeq: Int,
        beforeSeq: Int?,
        limit: Int?,
    ): StreamSubscribeResult =
        call(
            "stream.subscribe",
            encodeParams(
                StreamSubscribeParams(
                    sessionId = sessionId,
                    afterSeq = afterSeq,
                    beforeSeq = beforeSeq,
                    limit = limit,
                ),
            ),
        )

    override suspend fun unsubscribeSession(sessionId: String) {
        call<OkResult>(
            "stream.unsubscribe",
            encodeParams(SessionCancelParams(sessionId = sessionId)),
        )
    }

    override suspend fun respondApproval(sessionId: String, requestId: String, optionId: String?) {
        call<OkResult>(
            "approval.respond",
            encodeParams(
                ApprovalRespondParams(
                    sessionId = sessionId,
                    requestId = requestId,
                    optionId = optionId,
                ),
            ),
        )
    }

    override suspend fun listCheckpoints(sessionId: String): List<CheckpointInfo> =
        call<CheckpointListResult>(
            "checkpoint.list",
            encodeParams(CheckpointListParams(sessionId = sessionId)),
        ).checkpoints

    override suspend fun createCheckpoint(sessionId: String, label: String?): CheckpointInfo =
        call<CheckpointCreateResult>(
            "checkpoint.create",
            encodeParams(CheckpointCreateParams(sessionId = sessionId, label = label)),
        ).checkpoint

    override suspend fun restoreCheckpoint(sessionId: String, checkpointId: String) {
        call<OkResult>(
            "checkpoint.restore",
            encodeParams(CheckpointRestoreParams(sessionId = sessionId, checkpointId = checkpointId)),
        )
    }

    override suspend fun disconnect() {
        readerJob?.cancel()
        readerJob = null
        val s = wsSession
        wsSession = null
        runCatching { s?.close() }
        failAllPending("Disconnected")
    }

    private suspend inline fun <reified R> call(
        method: String,
        params: JsonElement? = null,
        timeoutMs: Long = 20_000L,
    ): R {
        val session = wsSession ?: throw IllegalStateException("Not connected to Hub")
        val deferred = CompletableDeferred<JsonElement?>()
        val id = pendingMutex.withLock {
            val allocated = nextId++
            pending[allocated] = deferred
            allocated
        }
        val payload = encodeRpcRequest(id = id, method = method, params = params)
        try {
            session.send(Frame.Text(payload))
            val resultElem = withTimeout(timeoutMs) { deferred.await() }
            return decodeResult(resultElem)
        } catch (t: Throwable) {
            pendingMutex.withLock { pending.remove(id) }
            throw t
        }
    }

    private suspend fun readLoop(session: DefaultClientWebSocketSession) {
        try {
            for (frame in session.incoming) {
                if (frame is Frame.Text) {
                    val decoded = decodeIncomingRpcFrame(frame.readText())
                    when (decoded) {
                        is IncomingRpcFrame.Response -> {
                            val waiter = pendingMutex.withLock { pending.remove(decoded.id) }
                            if (waiter != null) {
                                if (decoded.error != null) {
                                    waiter.completeExceptionally(
                                        RpcCallException(decoded.error.code, decoded.error.message),
                                    )
                                } else {
                                    waiter.complete(decoded.result)
                                }
                            }
                        }
                        is IncomingRpcFrame.EventNotification -> {
                            _events.tryEmit(decoded.event)
                        }
                        is IncomingRpcFrame.SessionNotification -> {
                            _sessionUpdates.tryEmit(decoded.session)
                        }
                        IncomingRpcFrame.Unknown -> Unit
                    }
                }
            }
            failAllPending("Connection closed by Hub")
            _disconnected.tryEmit("Connection closed")
        } catch (t: Throwable) {
            failAllPending(t.message ?: "WebSocket error")
            _disconnected.tryEmit(t.message ?: "Disconnected")
        }
    }

    private suspend fun failAllPending(reason: String) {
        val items = pendingMutex.withLock {
            val copy = pending.values.toList()
            pending.clear()
            copy
        }
        for (d in items) {
            d.completeExceptionally(IllegalStateException(reason))
        }
    }
}
