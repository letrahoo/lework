package dev.lework.transport

import dev.lework.protocol.*
import dev.lework.security.HubAttestation
import dev.lework.security.HubTrust
import dev.lework.security.constantTimeEquals
import dev.lework.security.hubWsUrlOf
import dev.lework.security.normalizeHubBaseUrl
import dev.lework.security.sha256
import dev.lework.security.toHex
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
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

/** The endpoint could not prove it is the Hub that knows our token — the token was NOT sent. */
class HubTrustException(message: String) : Exception(message)

@kotlinx.serialization.Serializable
private data class AttestRequest(val nonce: String, val kid: String)

@kotlinx.serialization.Serializable
private data class AttestResponse(val fingerprint: String, val proof: String)

@kotlinx.serialization.Serializable
private data class OAuthStartRequest(val codeChallenge: String)

@kotlinx.serialization.Serializable
private data class OAuthPollWithVerifier(val sid: String, val codeVerifier: String)

@kotlinx.serialization.Serializable
private data class TailscaleHandshakeRequest(val nonce: String, val deviceName: String)

@kotlinx.serialization.Serializable
private data class PairRedeemRequest(
    val nonce: String,
    val pairCode: String? = null,
    val token: String? = null,
    val deviceName: String,
)

data class DiscoveredEndpoint(
    val baseUrl: String,
    val wsUrl: String,
    val discover: HubDiscoverResult,
)

interface HubTransport {
    val events: SharedFlow<AgentEvent>
    val sessionUpdates: SharedFlow<SessionMeta>
    val disconnected: SharedFlow<String>

    suspend fun discoverIntranetHub(
        candidates: List<String>,
        dynamicCandidates: StateFlow<List<String>>? = null,
        waitWindowMs: Long = 6_500L,
        preferredHubId: String? = null,
    ): DiscoveredEndpoint?

    suspend fun probeDirectEndpoint(
        baseUrl: String,
        timeoutMs: Long = 10_000L,
    ): DiscoveredEndpoint? = discoverIntranetHub(listOf(baseUrl), waitWindowMs = timeoutMs)

    /**
     * Verifies (and pins) the Hub's TLS certificate *before* any token is sent to it, using
     * host-scoped fingerprint matching or token-bound attestation. Throws [HubTrustException] if
     * the endpoint cannot prove itself.
     */
    suspend fun ensureHubTrusted(baseUrl: String, token: String, expectedFingerprint: String? = null) {}

    suspend fun authenticateViaTailscale(baseUrl: String, deviceName: String): DirectTokenAuthResult {
        throw UnsupportedOperationException("Tailscale handshake not implemented in this transport")
    }

    suspend fun redeemPairingCode(
        baseUrl: String,
        pairCode: String?,
        staticToken: String?,
        expectedFingerprint: String?,
        deviceName: String,
    ): DirectTokenAuthResult {
        throw UnsupportedOperationException("Pairing redeem not implemented in this transport")
    }

    suspend fun startOAuthDeviceFlow(baseUrl: String): OAuthStartResult
    suspend fun pollOAuthDeviceFlow(baseUrl: String, sid: String): OAuthPollResponse

    suspend fun connectAndHello(
        wsUrl: String,
        token: String,
        platformName: String,
    ): HubHelloResult

    suspend fun fetchStatus(): HubStatusResult
    suspend fun listWorkspaces(): List<Workspace>
    suspend fun searchWorkspaceFiles(
        path: String,
        query: String? = null,
        limit: Int = 50,
    ): WorkspaceFilesResult = WorkspaceFilesResult(path = path, files = emptyList())
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
        images: List<PromptImageAttachment>? = null,
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
    suspend fun fetchUsageSummary(days: Int = 14, engineId: String? = null): UsageSummaryResult
    suspend fun fetchUsageQuota(): UsageQuotaResult
    suspend fun fetchSessionUsage(sessionId: String): SessionUsageResult
    suspend fun fetchProxyUsage(): ProxyUsageResult
    suspend fun fetchGitStatus(path: String): GitStatusResult
    suspend fun fetchGitDiff(
        path: String,
        filePath: String? = null,
        baseCommitSha: String? = null,
    ): GitDiffResult
    suspend fun disconnect()
}

class HubRpcClient(
    private val scope: CoroutineScope,
    /** Accepts any certificate (records fingerprints). Never carries the access token. */
    private val probeClient: HttpClient = createHubHttpClient(pinnedOnly = false),
    /** Handshake only succeeds with a pinned Hub certificate. Carries the token & all RPC. */
    private val secureClient: HttpClient = createHubHttpClient(pinnedOnly = true),
) : HubTransport {
    private val oauthVerifiers = mutableMapOf<String, String>()

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
        preferredHubId: String?,
    ): DiscoveredEndpoint? {
        val winner = CompletableDeferred<DiscoveredEndpoint?>()
        // Prefer endpoints presenting an already-pinned certificate (or a verified same-account
        // Tailscale node); an unpinned responder is only used as fallback and must pass attestation.
        var firstUnpinned: DiscoveredEndpoint? = null
        val requirePinned = HubTrust.pinned().isNotEmpty()
        val probedMutex = Mutex()
        val probed = mutableSetOf<String>()
        val jobs = mutableListOf<Job>()

        suspend fun launchProbeIfNew(base: String) {
            val normalized = normalizeHubBaseUrl(base)
            if (normalized.isEmpty()) return
            val shouldStart = probedMutex.withLock { probed.add(normalized) }
            if (!shouldStart) return
            val perProbeTimeoutMs =
                if (normalized.startsWith("https://127.0.0.1:") || normalized.startsWith("https://100.")) 9_000L else 5_000L

            val job = scope.launch {
                try {
                    val res = withTimeout(perProbeTimeoutMs) {
                        probeClient.get("$normalized/v1/auth/discover")
                    }
                    if (res.status.isSuccess()) {
                        val text = res.bodyAsText()
                        val info = ProtocolJson.decodeFromString(HubDiscoverResult.serializer(), text)
                        val endpoint = DiscoveredEndpoint(
                            baseUrl = normalized,
                            // Never trust a server-supplied path (could smuggle userinfo/host)
                            wsUrl = hubWsUrlOf(normalized),
                            discover = info.copy(relayUrls = emptyList()),
                        )
                        val fp = HubTrust.observedFor(normalized)
                        val matchesPreferred = preferredHubId == null || info.hub.id == preferredHubId
                        val trustedOrSameAccount =
                            !requirePinned || (fp != null && HubTrust.isPinned(fp)) || info.tailscaleSameUser
                        if (matchesPreferred && trustedOrSameAccount) {
                            winner.complete(endpoint)
                        } else {
                            probedMutex.withLock {
                                if (firstUnpinned == null || (preferredHubId != null && info.hub.id == preferredHubId)) {
                                    firstUnpinned = endpoint
                                }
                            }
                        }
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
                if (!winner.isCompleted) winner.complete(probedMutex.withLock { firstUnpinned })
            }
        }

        val result = withTimeoutOrNull(waitWindowMs) {
            winner.await()
        } ?: probedMutex.withLock { firstUnpinned }
        dynamicCollector?.cancel()
        val allJobs = probedMutex.withLock { jobs.toList() }
        allJobs.forEach { it.cancel() }
        return result
    }

    override suspend fun probeDirectEndpoint(
        baseUrl: String,
        timeoutMs: Long,
    ): DiscoveredEndpoint? {
        val normalized = normalizeHubBaseUrl(baseUrl)
        if (normalized.isEmpty()) return null
        return try {
            val res = withTimeout(timeoutMs) {
                probeClient.get("$normalized/v1/auth/discover")
            }
            if (!res.status.isSuccess()) return null
            val info = ProtocolJson.decodeFromString(HubDiscoverResult.serializer(), res.bodyAsText())
            DiscoveredEndpoint(
                baseUrl = normalized,
                wsUrl = hubWsUrlOf(normalized),
                discover = info.copy(relayUrls = emptyList()),
            )
        } catch (_: Throwable) {
            null
        }
    }

    /** Once a Hub is pinned, OAuth also runs only against pinned Hubs (no impostor login pages). */
    private fun oauthClient(): HttpClient = if (HubTrust.pinned().isNotEmpty()) secureClient else probeClient

    override suspend fun startOAuthDeviceFlow(baseUrl: String): OAuthStartResult {
        val base = normalizeHubBaseUrl(baseUrl)
        // PKCE-style binding: only this client (holding the verifier) can redeem the login.
        val verifier = HubAttestation.newNonce() + HubAttestation.newNonce()
        val challenge = sha256(verifier.encodeToByteArray()).toHex()
        val res = withTimeout(8_000L) {
            oauthClient().post("$base/v1/auth/oauth/start") {
                setBody(ProtocolJson.encodeToString(OAuthStartRequest.serializer(), OAuthStartRequest(challenge)))
            }
        }
        val body = res.bodyAsText()
        if (!res.status.isSuccess()) {
            throw IllegalStateException("身份授权初始化失败: $body")
        }
        val result = ProtocolJson.decodeFromString(OAuthStartResult.serializer(), body)
        pendingMutex.withLock {
            if (oauthVerifiers.size > 8) oauthVerifiers.clear()
            oauthVerifiers[result.sid] = verifier
        }
        return result
    }

    override suspend fun pollOAuthDeviceFlow(baseUrl: String, sid: String): OAuthPollResponse {
        val base = normalizeHubBaseUrl(baseUrl)
        val verifier = pendingMutex.withLock { oauthVerifiers[sid] }
            ?: return OAuthPollResponse(status = "expired")
        val reqJson = ProtocolJson.encodeToString(
            OAuthPollWithVerifier.serializer(),
            OAuthPollWithVerifier(sid, verifier),
        )
        val res = withTimeout(8_000L) {
            oauthClient().post("$base/v1/auth/oauth/poll") {
                setBody(reqJson)
            }
        }
        val parsed = ProtocolJson.decodeFromString(OAuthPollResponse.serializer(), res.bodyAsText())
        if (parsed.status != "pending") pendingMutex.withLock { oauthVerifiers.remove(sid) }
        return parsed
    }

    override suspend fun ensureHubTrusted(baseUrl: String, token: String, expectedFingerprint: String?) {
        val base = normalizeHubBaseUrl(baseUrl)
        val cleanExpected = expectedFingerprint?.trim()?.lowercase()?.takeIf { it.length == 64 }
        val observed = HubTrust.observedFor(base)
        if (cleanExpected != null && observed != null) {
            if (!constantTimeEquals(observed, cleanExpected)) {
                throw HubTrustException("主机证书指纹与已配对记录不匹配，已阻止发送令牌")
            }
            if (HubTrust.isPinned(observed)) return
        }

        val nonce = HubAttestation.newNonce()
        val res = try {
            withTimeout(8_000L) {
                probeClient.post("$base/v1/auth/attest") {
                    setBody(
                        ProtocolJson.encodeToString(
                            AttestRequest.serializer(),
                            AttestRequest(nonce = nonce, kid = HubAttestation.kid(token)),
                        ),
                    )
                }
            }
        } catch (t: Throwable) {
            throw HubTrustException("无法连接主机进行身份校验: ${t.message ?: "网络错误"}")
        }
        if (!res.status.isSuccess()) {
            throw HubTrustException("主机身份校验未通过（令牌不被该主机认可），已阻止发送令牌")
        }
        val att = runCatching {
            ProtocolJson.decodeFromString(AttestResponse.serializer(), res.bodyAsText())
        }.getOrNull() ?: throw HubTrustException("主机身份校验响应无效，已阻止发送令牌")
        val seen = HubTrust.observedFor(base)
            ?: throw HubTrustException("未获取到主机证书，已阻止发送令牌")
        if (cleanExpected != null && !constantTimeEquals(seen, cleanExpected)) {
            throw HubTrustException("主机证书指纹与已配对记录不匹配，已阻止发送令牌")
        }
        val expectedProof = HubAttestation.proof(token, seen, nonce)
        if (!constantTimeEquals(seen, att.fingerprint.lowercase()) ||
            !constantTimeEquals(expectedProof, att.proof.lowercase())
        ) {
            throw HubTrustException("主机证书与令牌不匹配（可能存在中间人或冒充主机），已阻止发送令牌")
        }
        HubTrust.pin(seen)
    }

    override suspend fun authenticateViaTailscale(
        baseUrl: String,
        deviceName: String,
    ): DirectTokenAuthResult {
        val base = normalizeHubBaseUrl(baseUrl)
        val nonce = HubAttestation.newNonce()
        val res = withTimeout(12_000L) {
            probeClient.post("$base/v1/auth/tailscale") {
                setBody(
                    ProtocolJson.encodeToString(
                        TailscaleHandshakeRequest.serializer(),
                        TailscaleHandshakeRequest(nonce = nonce, deviceName = deviceName),
                    ),
                )
            }
        }
        val body = res.bodyAsText()
        if (!res.status.isSuccess()) {
            throw HubTrustException("Tailscale 同账号握手未通过: $body")
        }
        val auth = ProtocolJson.decodeFromString(DirectTokenAuthResult.serializer(), body)
        val seen = HubTrust.observedFor(base)
            ?: throw HubTrustException("未获取到主机 TLS 证书，已阻止建联")
        val expectedProof = HubAttestation.proof(auth.accessToken, seen, nonce)
        if (!constantTimeEquals(seen, auth.fingerprint.lowercase()) ||
            !constantTimeEquals(expectedProof, auth.proof.lowercase())
        ) {
            throw HubTrustException("主机 TLS 证书校验未通过，已阻止建联")
        }
        HubTrust.pin(seen)
        return auth
    }

    override suspend fun redeemPairingCode(
        baseUrl: String,
        pairCode: String?,
        staticToken: String?,
        expectedFingerprint: String?,
        deviceName: String,
    ): DirectTokenAuthResult {
        val base = normalizeHubBaseUrl(baseUrl)
        val expectedFp = expectedFingerprint?.trim()?.lowercase()?.takeIf { it.length == 64 }
        if (expectedFp != null) {
            if (HubTrust.observedFor(base) == null) {
                withTimeout(5_000L) {
                    probeClient.get("$base/v1/auth/discover")
                }
            }
            val preSeen = HubTrust.observedFor(base)
                ?: throw HubTrustException("未获取到主机 TLS 证书，已阻止配对")
            if (!constantTimeEquals(preSeen, expectedFp)) {
                throw HubTrustException("主机证书指纹与配对二维码不一致，已阻止发送凭证")
            }
        }

        val nonce = HubAttestation.newNonce()
        val res = withTimeout(8_000L) {
            probeClient.post("$base/v1/auth/pair") {
                setBody(
                    ProtocolJson.encodeToString(
                        PairRedeemRequest.serializer(),
                        PairRedeemRequest(
                            nonce = nonce,
                            pairCode = pairCode,
                            token = staticToken,
                            deviceName = deviceName,
                        ),
                    ),
                )
            }
        }
        val body = res.bodyAsText()
        if (!res.status.isSuccess()) {
            throw HubTrustException("配对码已过期或无效，请在主机重新执行 hub pair")
        }
        val auth = ProtocolJson.decodeFromString(DirectTokenAuthResult.serializer(), body)
        val seen = HubTrust.observedFor(base)
            ?: throw HubTrustException("未获取到主机 TLS 证书，已阻止保存凭证")
        if (expectedFp != null && !constantTimeEquals(seen, expectedFp)) {
            throw HubTrustException("主机证书指纹与配对二维码不匹配")
        }
        val expectedProof = HubAttestation.proof(auth.accessToken, seen, nonce)
        if (!constantTimeEquals(seen, auth.fingerprint.lowercase()) ||
            !constantTimeEquals(expectedProof, auth.proof.lowercase())
        ) {
            throw HubTrustException("主机证书证明校验未通过")
        }
        HubTrust.pin(seen)
        return auth
    }

    override suspend fun connectAndHello(
        wsUrl: String,
        token: String,
        platformName: String,
    ): HubHelloResult {
        disconnect()
        val session = secureClient.webSocketSession(urlString = hubWsUrlOf(wsUrl))
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
                        version = "0.6.2",
                        platform = platformName,
                    ),
                ),
            ),
        )
    }

    override suspend fun fetchStatus(): HubStatusResult = call("hub.status")

    override suspend fun listWorkspaces(): List<Workspace> =
        call<WorkspaceListResult>("workspace.list").workspaces

    override suspend fun searchWorkspaceFiles(
        path: String,
        query: String?,
        limit: Int,
    ): WorkspaceFilesResult =
        call(
            "workspace.files",
            encodeParams(WorkspaceFilesParams(path = path, query = query, limit = limit)),
        )

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
        images: List<PromptImageAttachment>?,
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
                    images = images,
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

    override suspend fun fetchUsageSummary(days: Int, engineId: String?): UsageSummaryResult =
        call(
            "usage.summary",
            encodeParams(UsageSummaryParams(days = days, engineId = engineId)),
        )

    override suspend fun fetchUsageQuota(): UsageQuotaResult =
        call("usage.quota")

    override suspend fun fetchSessionUsage(sessionId: String): SessionUsageResult =
        call(
            "usage.session",
            encodeParams(UsageSessionParams(sessionId = sessionId)),
        )

    override suspend fun fetchProxyUsage(): ProxyUsageResult =
        call("usage.proxy")

    override suspend fun fetchGitStatus(path: String): GitStatusResult =
        call(
            "git.status",
            encodeParams(GitStatusParams(path = path)),
        )

    override suspend fun fetchGitDiff(
        path: String,
        filePath: String?,
        baseCommitSha: String?,
    ): GitDiffResult =
        call(
            "git.diff",
            encodeParams(GitDiffParams(path = path, filePath = filePath, baseCommitSha = baseCommitSha)),
        )

    override suspend fun disconnect() {
        val s = wsSession
        wsSession = null
        readerJob?.cancel()
        readerJob = null
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
            if (wsSession === session) {
                wsSession = null
                _disconnected.tryEmit("Connection closed")
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            failAllPending("Cancelled")
            throw ce
        } catch (t: Throwable) {
            failAllPending(t.message ?: "WebSocket error")
            if (wsSession === session) {
                wsSession = null
                _disconnected.tryEmit(t.message ?: "Disconnected")
            }
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
