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
    var customHubName: String? = null
    var customEngines: List<EngineInfo>? = null

    var sessions: MutableList<SessionMeta> = mutableListOf()
    val artifactsBySession: MutableMap<String, MutableList<ArtifactInfo>> = mutableMapOf()
    val artifactContents: MutableMap<Pair<String, String>, ArtifactContentResult> = mutableMapOf()
    val sessionEventsMap: MutableMap<String, MutableList<AgentEvent>> = mutableMapOf()
    val subscribeDelayMsBySession: MutableMap<String, Long> = mutableMapOf()

    var listSessionsException: Throwable? = null
    var connectHelloException: Throwable? = null
    var sendPromptException: Throwable? = null
    var sendPromptFailuresRemaining: Int = 0
    var sendPromptDelayMs: Long = 0L
    var createSessionDelayMs: Long = 0L
    var cancelSessionException: Throwable? = null
    var cancelSessionDelayMs: Long = 0L
    val cancelledSessionIds = mutableListOf<String>()
    var discoverNullRemaining: Int = 0
    var subscribeSessionCount: Int = 0
    var simulateTailscaleSameUser: Boolean = false
    var tailscaleHandshakeCount: Int = 0
    var pairRedeemCount: Int = 0
    var emitDisconnectOnClientClose: Boolean = false
    private var wsConnected: Boolean = false

    val unsubscribedIds = mutableListOf<String>()
    val respondedApprovals = mutableListOf<Triple<String, String, String?>>()
    val sentPrompts = mutableListOf<Pair<String, String>>()
    val sentPromptParams = mutableListOf<SessionPromptParams>()
    val sentPromptImages = mutableListOf<Pair<String, List<PromptImageAttachment>>>()
    val sentPromptFiles = mutableListOf<Pair<String, List<PromptFileAttachment>>>()
    var lastCreateUseWorktree: Boolean? = null
    var lastCreateWorktreeBranch: String? = null
    val setModelCalls = mutableListOf<SessionSetModelParams>()
    val cancelQueuedCalls = mutableListOf<Triple<String, Int?, Boolean>>()
    var cancelQueuedException: Throwable? = null
    var cancelQueuedNotice: String? = null
    var cancelQueuedDelayMs: Long = 0L
    var enginesOverride: List<EngineInfo>? = null
    var providersList: MutableList<ProviderInfo> = mutableListOf(
        ProviderInfo(
            id = "cpa_local",
            name = "CPA",
            type = ProviderType.ENDPOINT,
            adapter = ProviderAdapterKind.CPA,
            baseUrl = "http://127.0.0.1:8317",
            wireApi = WireApi.ANTHROPIC_MESSAGES,
            wireApis = listOf(WireApi.ANTHROPIC_MESSAGES, WireApi.OPENAI_RESPONSES, WireApi.OPENAI_CHAT),
            enabledEngines = listOf("claude", "codex", "pi"),
            enabled = true,
            builtIn = true,
            hasApiKey = true,
            healthy = true,
            authStatus = "authenticated",
            latencyMs = 12,
            models = listOf("claude-opus-4-6", "gpt-5.4"),
        ),
    )
    var probeProviderHandler: ((String?, ProviderConfigInput?) -> ProviderInfo)? = null
    val upsertedProviders = mutableListOf<ProviderConfigInput>()
    val removedProviderIds = mutableListOf<String>()
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
        if (discoverNullRemaining > 0) {
            discoverNullRemaining--
            return null
        }
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
            hub = customHubName?.let { hubInfo.copy(name = it) } ?: hubInfo,
            slaves = emptyList(),
            engines = customEngines ?: enginesOverride ?: listOf(
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
            providers = providersList.toList(),
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

    var listDirectoryHandler: ((String) -> FsListResult)? = null

    override suspend fun listDirectory(path: String): FsListResult =
        listDirectoryHandler?.invoke(path) ?: FsListResult(path = path, entries = emptyList())

    var readFileHandler: ((String) -> FsReadResult)? = null

    override suspend fun readFile(path: String): FsReadResult =
        readFileHandler?.invoke(path) ?: FsReadResult(path = path, content = "", truncated = false)

    override suspend fun upsertProfile(profile: ProfileInfo): List<ProfileInfo> = listOf(profile)

    override suspend fun listSessions(engineId: String?): List<SessionMeta> {
        listSessionsException?.let { throw it }
        return if (engineId == null) sessions.toList() else sessions.filter { it.engineId == engineId }
    }

    override suspend fun createSession(
        engineId: String,
        cwd: String,
        workspaces: List<String>?,
        useWorktree: Boolean?,
        worktreeBranch: String?,
        title: String?,
        providerId: String?,
        modelId: String?,
        reasoningEffort: ReasoningEffort?,
        mode: SessionMode?,
    ): SessionMeta {
        lastCreateUseWorktree = useWorktree
        lastCreateWorktreeBranch = worktreeBranch
        val wtInfo = if (useWorktree == true) {
            val branch = worktreeBranch?.trim()?.takeIf { it.isNotEmpty() }?.let {
                if (it.contains('/')) it else "lework/$it"
            } ?: "lework/1123-s${sessions.size + 1}"
            SessionWorktreeInfo(
                baseRepoPath = cwd,
                worktreePath = "/Users/test/.lework-worktrees/repo/${branch.replace('/', '-')}",
                branch = branch,
                baseBranch = "main",
                cleanedUp = false,
            )
        } else {
            null
        }
        val effectiveCwd = wtInfo?.worktreePath ?: cwd
        if (createSessionDelayMs > 0L) delay(createSessionDelayMs)
        val created = SessionMeta(
            id = "sess_${sessions.size + 1}",
            engineId = engineId,
            cwd = effectiveCwd,
            workspaces = if (wtInfo != null) listOf(effectiveCwd) else workspaces,
            worktree = wtInfo,
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
        files: List<PromptFileAttachment>?,
        providerId: String?,
        modelId: String?,
        reasoningEffort: ReasoningEffort?,
        mode: SessionMode?,
    ): String {
        if (sendPromptDelayMs > 0L) delay(sendPromptDelayMs)
        if (sendPromptFailuresRemaining > 0) {
            sendPromptFailuresRemaining--
            throw sendPromptException ?: RuntimeException("ECONNRESET: socket hang up")
        }
        sendPromptException?.let { throw it }
        sentPrompts.add(sessionId to text)
        sentPromptParams.add(
            SessionPromptParams(
                sessionId = sessionId,
                text = text,
                images = images,
                files = files,
                providerId = providerId,
                modelId = modelId,
                reasoningEffort = reasoningEffort,
                mode = mode,
            ),
        )
        if (!images.isNullOrEmpty()) {
            sentPromptImages.add(sessionId to images)
        }
        if (!files.isNullOrEmpty()) {
            sentPromptFiles.add(sessionId to files)
        }
        return "turn_${sentPrompts.size}"
    }

    override suspend fun cancelSession(sessionId: String) {
        if (cancelSessionDelayMs > 0L) delay(cancelSessionDelayMs)
        cancelledSessionIds.add(sessionId)
        cancelSessionException?.let { throw it }
    }

    override suspend fun cancelQueuedPrompt(
        sessionId: String,
        index: Int?,
        all: Boolean,
    ): SessionCancelQueuedResult {
        cancelQueuedCalls.add(Triple(sessionId, index, all))
        if (cancelQueuedDelayMs > 0L) delay(cancelQueuedDelayMs)
        cancelQueuedException?.let { throw it }
        val idx = sessions.indexOfFirst { it.id == sessionId }
        if (idx < 0) {
            throw dev.lework.transport.RpcCallException(ErrorCode.NOT_FOUND, "Session not found")
        }
        val prev = sessions[idx]
        val current = prev.queuedPrompts.orEmpty()
        val (remaining, removedCount) = when {
            all || index == null -> emptyList<String>() to current.size
            index in current.indices -> current.filterIndexed { i, _ -> i != index } to 1
            else -> current to 0
        }
        val updated = prev.copy(queuedPrompts = remaining.takeIf { it.isNotEmpty() })
        sessions[idx] = updated
        if (removedCount > 0) {
            _sessionUpdates.tryEmit(updated)
        }
        return SessionCancelQueuedResult(
            ok = true,
            queuedPrompts = remaining,
            removedCount = removedCount,
            notice = cancelQueuedNotice?.takeIf { removedCount > 0 },
        )
    }

    override suspend fun setSessionModel(
        sessionId: String,
        modelId: String,
        providerId: String?,
        reasoningEffort: ReasoningEffort?,
        mode: SessionMode?,
    ): SessionMeta {
        setModelCalls.add(
            SessionSetModelParams(
                sessionId = sessionId,
                providerId = providerId,
                modelId = modelId,
                reasoningEffort = reasoningEffort,
                mode = mode,
            ),
        )
        val idx = sessions.indexOfFirst { it.id == sessionId }
        if (idx < 0) throw dev.lework.transport.RpcCallException(ErrorCode.NOT_FOUND, "Session not found")
        val prev = sessions[idx]
        val updated = prev.copy(
            providerId = providerId ?: prev.providerId,
            model = modelId,
            reasoningEffort = reasoningEffort ?: prev.reasoningEffort,
            mode = mode ?: prev.mode,
        )
        sessions[idx] = updated
        return updated
    }

    override suspend fun listProviders(): ProviderListResult =
        ProviderListResult(
            providers = providersList.toList(),
            engines = currentEnginesSnapshot(),
        )

    override suspend fun upsertProvider(input: ProviderConfigInput): ProviderUpsertResult {
        upsertedProviders.add(input)
        val id = input.id?.takeIf { it.isNotBlank() }
            ?: "prov_${input.name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { "custom" }}"
        val existing = providersList.firstOrNull { it.id == id }
        val wireApis = input.wireApis?.takeIf { it.isNotEmpty() }
            ?: existing?.wireApis?.takeIf { it.isNotEmpty() }
            ?: listOf(WireApi.OPENAI_CHAT, WireApi.OPENAI_RESPONSES)
        val engines = input.enabledEngines ?: existing?.enabledEngines ?: listOf("claude", "codex", "pi")
        val enabled = input.enabled ?: existing?.enabled ?: true
        val models = input.manualModels?.takeIf { it.isNotEmpty() }
            ?: existing?.models?.takeIf { it.isNotEmpty() }
            ?: listOf("gpt-5.4", "claude-opus-4-6")
        val saved = ProviderInfo(
            id = id,
            name = input.name.trim(),
            type = ProviderType.ENDPOINT,
            adapter = input.adapter ?: existing?.adapter ?: ProviderAdapterKind.GENERIC,
            baseUrl = input.baseUrl.trim().trimEnd('/'),
            wireApi = wireApis.firstOrNull(),
            wireApis = wireApis,
            enabledEngines = engines,
            enabled = enabled,
            builtIn = existing?.builtIn == true,
            hasApiKey = when {
                input.clearApiKey == true -> false
                !input.apiKey.isNullOrBlank() -> true
                !input.apiKeyEnv.isNullOrBlank() -> true
                else -> existing?.hasApiKey == true
            },
            apiKeyEnv = input.apiKeyEnv ?: existing?.apiKeyEnv,
            manualModels = input.manualModels ?: existing?.manualModels.orEmpty(),
            selectedModels = input.selectedModels ?: existing?.selectedModels.orEmpty(),
            healthy = enabled && models.isNotEmpty(),
            authStatus = if (enabled) "authenticated" else null,
            latencyMs = if (enabled) 18 else null,
            models = if (enabled) models else emptyList(),
        )
        val idx = providersList.indexOfFirst { it.id == id }
        if (idx >= 0) providersList[idx] = saved else providersList.add(saved)
        return ProviderUpsertResult(
            provider = saved,
            providers = providersList.toList(),
            engines = currentEnginesSnapshot(),
        )
    }

    override suspend fun removeProvider(providerId: String): ProviderListResult {
        removedProviderIds.add(providerId)
        providersList.removeAll { it.id == providerId }
        return ProviderListResult(
            providers = providersList.toList(),
            engines = currentEnginesSnapshot(),
        )
    }

    override suspend fun probeProvider(
        providerId: String?,
        provider: ProviderConfigInput?,
    ): ProviderProbeResult {
        val custom = probeProviderHandler?.invoke(providerId, provider)
        val probed = custom ?: when {
            provider != null -> ProviderInfo(
                id = provider.id ?: "probe_draft",
                name = provider.name,
                type = ProviderType.ENDPOINT,
                adapter = provider.adapter ?: ProviderAdapterKind.GENERIC,
                baseUrl = provider.baseUrl.trim().trimEnd('/'),
                wireApis = provider.wireApis ?: listOf(WireApi.OPENAI_CHAT),
                enabledEngines = provider.enabledEngines ?: listOf("claude", "codex", "pi"),
                enabled = true,
                hasApiKey = !provider.apiKey.isNullOrBlank() || !provider.apiKeyEnv.isNullOrBlank(),
                healthy = true,
                authStatus = "authenticated",
                latencyMs = 15,
                models = provider.manualModels?.takeIf { it.isNotEmpty() } ?: listOf("gpt-5.4", "claude-opus-4-6"),
            )
            providerId != null -> providersList.firstOrNull { it.id == providerId }
            else -> null
        }
        return ProviderProbeResult(
            provider = probed,
            providers = providersList.toList(),
            engines = currentEnginesSnapshot(),
        )
    }

    private fun currentEnginesSnapshot(): List<EngineInfo> =
        customEngines ?: enginesOverride ?: emptyList()

    val regeneratedTitleSessionIds = mutableListOf<String>()
    var regenerateTitleHandler: ((SessionMeta) -> String)? = null

    override suspend fun renameSession(sessionId: String, title: String): SessionMeta {
        val idx = sessions.indexOfFirst { it.id == sessionId }
        if (idx < 0) throw dev.lework.transport.RpcCallException(ErrorCode.NOT_FOUND, "Session not found")
        val prev = sessions[idx]
        val clean = title.trim()
        val updated = if (clean.isEmpty()) {
            prev.copy(title = prev.autoTitle ?: prev.title, customTitle = null)
        } else {
            prev.copy(title = clean, customTitle = clean)
        }
        sessions[idx] = updated
        return updated
    }

    override suspend fun regenerateSessionTitle(sessionId: String): SessionMeta {
        regeneratedTitleSessionIds.add(sessionId)
        val idx = sessions.indexOfFirst { it.id == sessionId }
        if (idx < 0) throw dev.lework.transport.RpcCallException(ErrorCode.NOT_FOUND, "Session not found")
        val prev = sessions[idx]
        val scope = prev.cwd.trimEnd('/').substringAfterLast('/').ifBlank { "session" }
        val nextAuto = regenerateTitleHandler?.invoke(prev) ?: "$scope-自动摘要标题"
        val updated = prev.copy(title = nextAuto, autoTitle = nextAuto, customTitle = null)
        sessions[idx] = updated
        _sessionUpdates.tryEmit(updated)
        return updated
    }

    override suspend fun archiveSession(sessionId: String, archived: Boolean): SessionMeta {
        val idx = sessions.indexOfFirst { it.id == sessionId }
        if (idx < 0) throw dev.lework.transport.RpcCallException(ErrorCode.NOT_FOUND, "Session not found")
        val prev = sessions[idx]
        val updatedWt = if (archived && prev.worktree != null) {
            prev.worktree.copy(cleanedUp = true)
        } else {
            prev.worktree
        }
        val updated = prev.copy(
            archived = archived,
            archivedAt = if (archived) 9_999L else null,
            worktree = updatedWt,
        )
        sessions[idx] = updated
        return updated
    }

    override suspend fun listArtifacts(sessionId: String): List<ArtifactInfo> =
        artifactsBySession[sessionId]?.toList().orEmpty()

    override suspend fun readArtifact(sessionId: String, artifactId: String): ArtifactContentResult =
        artifactContents[sessionId to artifactId]
            ?: throw dev.lework.transport.RpcCallException(ErrorCode.NOT_FOUND, "Artifact not found: $artifactId")

    override suspend fun searchSessions(params: SessionSearchParams): SessionSearchResult {
        val raw = params.query.trim()
        if (raw.isEmpty()) {
            return SessionSearchResult(
                query = "",
                hits = emptyList(),
                indexedSessions = sessions.size,
                totalSessions = sessions.size,
                indexing = false,
            )
        }
        val terms = raw.split(Regex("\\s+")).filter { it.isNotBlank() }
        val lowerTerms = terms.map { it.lowercase() }
        val roleFilter = params.role ?: SearchRoleFilter.ALL
        val includeArchived = params.includeArchived == true || !params.sessionId.isNullOrBlank()
        val matchesLimit = params.matchesPerSession ?: if (!params.sessionId.isNullOrBlank()) 50 else 3

        val hits = mutableListOf<SessionSearchHit>()
        for (meta in sessions) {
            if (params.sessionId != null && meta.id != params.sessionId) continue
            if (params.engineId != null && meta.engineId != params.engineId) continue
            if (!includeArchived && meta.archived) continue
            if (params.cwd != null && meta.cwd != params.cwd) continue

            val events = sessionEventsMap[meta.id].orEmpty()
            val msgs = mutableListOf<Triple<Int, SearchMatchRole, Pair<Long, String>>>()
            val assistantAccum = linkedMapOf<String, Triple<Int, Long, StringBuilder>>()
            for (ev in events) {
                when (ev) {
                    is AgentEvent.UserMessage -> {
                        msgs.add(Triple(ev.seq, SearchMatchRole.USER, ev.ts to ev.text))
                    }
                    is AgentEvent.AssistantDelta -> {
                        val existing = assistantAccum[ev.messageId]
                        if (existing != null) {
                            existing.third.append(ev.text)
                        } else {
                            assistantAccum[ev.messageId] = Triple(ev.seq, ev.ts, StringBuilder(ev.text))
                        }
                    }
                    else -> Unit
                }
            }
            for ((seq, ts, sb) in assistantAccum.values) {
                msgs.add(Triple(seq, SearchMatchRole.ASSISTANT, ts to sb.toString()))
            }

            val matched = msgs.filter { (_, role, pair) ->
                val roleOk = when (roleFilter) {
                    SearchRoleFilter.ALL -> true
                    SearchRoleFilter.USER -> role == SearchMatchRole.USER
                    SearchRoleFilter.ASSISTANT -> role == SearchMatchRole.ASSISTANT
                }
                if (!roleOk) return@filter false
                val lower = pair.second.lowercase()
                lowerTerms.all { it in lower }
            }.sortedWith(
                if (params.sessionId != null) {
                    compareBy { it.first }
                } else {
                    compareByDescending<Triple<Int, SearchMatchRole, Pair<Long, String>>> {
                        if (it.second == SearchMatchRole.USER) 1 else 0
                    }.thenByDescending { it.first }
                },
            )

            val titleMatch = params.sessionId == null && roleFilter == SearchRoleFilter.ALL &&
                lowerTerms.all { it in "${meta.title} ${meta.cwd}".lowercase() }
            if (matched.isEmpty() && !titleMatch) continue

            val matchItems = matched.take(matchesLimit).map { (seq, role, pair) ->
                val text = pair.second.replace(Regex("\\s+"), " ").trim()
                val idx = text.lowercase().indexOf(lowerTerms.first()).coerceAtLeast(0)
                val end = (idx + lowerTerms.first().length).coerceAtMost(text.length)
                SessionSearchMatch(
                    seq = seq,
                    role = role,
                    ts = pair.first,
                    snippet = text.take(140),
                    matchStart = idx.coerceAtMost(text.take(140).length),
                    matchEnd = end.coerceAtMost(text.take(140).length),
                )
            }.ifEmpty {
                listOf(
                    SessionSearchMatch(
                        seq = 0,
                        role = SearchMatchRole.USER,
                        ts = meta.updatedAt,
                        snippet = "${meta.title} · ${meta.cwd}",
                        matchStart = 0,
                        matchEnd = 0,
                    ),
                )
            }
            hits.add(
                SessionSearchHit(
                    sessionId = meta.id,
                    engineId = meta.engineId,
                    title = meta.title,
                    cwd = meta.cwd,
                    updatedAt = meta.updatedAt,
                    archived = meta.archived,
                    matchCount = maxOf(matched.size, 1),
                    matches = matchItems,
                ),
            )
        }
        return SessionSearchResult(
            query = raw,
            hits = hits.sortedByDescending { it.updatedAt }.take(params.limit ?: 30),
            indexedSessions = sessions.size,
            totalSessions = sessions.size,
            indexing = false,
        )
    }

    override suspend fun subscribeSession(
        sessionId: String,
        afterSeq: Int,
        beforeSeq: Int?,
        aroundSeq: Int?,
        limit: Int?,
    ): StreamSubscribeResult {
        subscribeSessionCount++
        val delayMs = subscribeDelayMsBySession[sessionId] ?: 0L
        if (delayMs > 0L) delay(delayMs)

        val meta = sessions.firstOrNull { it.id == sessionId }
            ?: sessions.firstNotNullOfOrNull { parent ->
                parent.subagents?.firstOrNull { it.id == sessionId }?.let { sub ->
                    SessionMeta(
                        id = sub.id,
                        engineId = parent.engineId,
                        cwd = sub.workspace ?: parent.cwd,
                        title = sub.title,
                        state = sub.state,
                        createdAt = sub.createdAt ?: sub.updatedAt,
                        updatedAt = sub.updatedAt,
                        lastSeq = -1,
                        source = SessionSource.HOST,
                        liveStatusText = if (sub.state == SessionState.RUNNING) sub.statusText else null,
                    )
                }
            }
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
                hasMoreAfter = false,
            )
        }

        if (aroundSeq != null) {
            val targetIdx = all.indexOfFirst { it.seq >= aroundSeq }.let { if (it < 0) all.lastIndex else it }
            val pageSize = (limit ?: 260).coerceAtLeast(20)
            val start = (targetIdx - (pageSize / 3)).coerceAtLeast(0)
            val end = if (all.size - start <= maxOf(pageSize, 500)) {
                all.size
            } else {
                (start + pageSize).coerceAtMost(all.size)
            }
            val slice = all.subList(start, end)
            return StreamSubscribeResult(
                session = meta.copy(lastSeq = all.last().seq),
                events = slice,
                truncated = start > 0 || end < all.size,
                earliestSeq = slice.firstOrNull()?.seq ?: all.first().seq,
                hasMoreBefore = start > 0,
                hasMoreAfter = end < all.size,
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

    val respondedCustomInputs = mutableMapOf<String, String>()

    override suspend fun respondApproval(
        sessionId: String,
        requestId: String,
        optionId: String?,
        customInput: String?,
    ) {
        respondedApprovals.add(Triple(sessionId, requestId, optionId))
        if (!customInput.isNullOrBlank()) {
            respondedCustomInputs[requestId] = customInput
        }
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
