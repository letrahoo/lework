package dev.lework

import dev.lework.platform.JvmPlatformTestHooks
import dev.lework.protocol.*
import dev.lework.store.ConsoleStore
import dev.lework.transport.RpcCallException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionSendIsolationTest {

    @BeforeTest
    fun setUp() {
        JvmPlatformTestHooks.reset()
        JvmPlatformTestHooks.savedToken = "tok_iso_test"
        JvmPlatformTestHooks.savedUserName = "Owner"
    }

    @AfterTest
    fun tearDown() {
        JvmPlatformTestHooks.reset()
    }

    private fun makeSession(
        id: String,
        title: String,
        cwd: String = "/workspace/lework",
        mode: SessionMode = SessionMode.PLAN,
        model: String = "gemini-4-neon",
    ): SessionMeta = SessionMeta(
        id = id,
        engineId = "agy",
        cwd = cwd,
        title = title,
        state = SessionState.IDLE,
        createdAt = 1_000L,
        updatedAt = 2_000L,
        lastSeq = 1,
        model = model,
        mode = mode,
    )

    private suspend fun awaitUntil(timeoutMs: Long = 3_000L, condition: () -> Boolean) {
        var waited = 0L
        while (!condition() && waited < timeoutMs) {
            delay(15L)
            waited += 15L
        }
    }

    private fun withConnectedStore(
        fake: FakeHubTransport,
        block: suspend (ConsoleStore) -> Unit,
    ) = runBlocking {
        val storeScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val store = ConsoleStore(rpc = fake, scope = storeScope)
            store.bootstrapOrConnect()
            awaitUntil { store.state.value.connectionStatus == dev.lework.store.ConnectionStatus.CONNECTED }
            block(store)
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun switchingSessionImmediatelyAfterSendPromptStillDeliversToOriginatingSession() {
        val fake = FakeHubTransport().apply {
            sessions.addAll(
                listOf(
                    makeSession("host:agy:conv_a", "会话 A"),
                    makeSession("host:agy:conv_b", "会话 B"),
                ),
            )
            sendPromptDelayMs = 80L
        }
        withConnectedStore(fake) { store ->
            store.selectSession("host:agy:conv_a")
            awaitUntil { store.state.value.activeSessionId == "host:agy:conv_a" }

            // User sends a message in Session A and immediately switches to Session B before RPC finishes
            store.sendPrompt("只发给会话A的消息")
            store.selectSession("host:agy:conv_b")
            awaitUntil { fake.sentPrompts.isNotEmpty() }
            delay(40L)

            assertEquals("host:agy:conv_b", store.state.value.activeSessionId)
            assertEquals(listOf("host:agy:conv_a" to "只发给会话A的消息"), fake.sentPrompts)
        }
    }

    @Test
    fun switchingToExistingSessionWhileNewDraftSessionIsCreatingDoesNotHijackActiveSession() {
        val fake = FakeHubTransport().apply {
            sessions.add(makeSession("host:agy:conv_existing", "已有会话"))
            createSessionDelayMs = 100L
        }
        withConnectedStore(fake) { store ->
            store.startNewDraftSession()
            assertEquals(null, store.state.value.activeSessionId)

            store.sendPrompt("新建会话的首条消息")
            store.selectSession("host:agy:conv_existing")
            awaitUntil { fake.sentPrompts.isNotEmpty() }
            delay(40L)

            assertEquals("host:agy:conv_existing", store.state.value.activeSessionId)
            assertEquals(1, fake.sentPrompts.size)
            assertTrue(fake.sentPrompts.first().first.startsWith("sess_"))
            assertEquals("新建会话的首条消息", fake.sentPrompts.first().second)
        }
    }

    @Test
    fun notFoundOnOldManagedSessionNeverRemapsByCwdAloneToDifferentConversation() {
        val fake = FakeHubTransport().apply {
            sessions.addAll(
                listOf(
                    makeSession("sess_old_1", "agy · lework", cwd = "/workspace/lework")
                        .copy(createdAt = 1_730_000_100_000L),
                    makeSession("host:agy:other_conv", "完全不相关的会话", cwd = "/workspace/lework")
                        .copy(createdAt = 1_730_000_000_000L),
                ),
            )
            sendPromptException = RpcCallException(ErrorCode.NOT_FOUND, "Session sess_old_1 not found")
        }
        withConnectedStore(fake) { store ->
            store.selectSession("sess_old_1")
            awaitUntil { store.state.value.activeSessionId == "sess_old_1" }

            store.sendPrompt("不要串到别的会话")
            awaitUntil { store.state.value.turnRecoveryBanner != null }

            assertTrue(fake.sentPrompts.isEmpty(), "Expected 0 prompts sent to unrelated session, got: ${fake.sentPrompts}")
            assertEquals("sess_old_1", store.state.value.activeSessionId)
        }
    }

    @Test
    fun staleDraftCallbackWithPreviousDraftKeyCannotOverwriteNewSessionDraft() {
        val fake = FakeHubTransport().apply {
            sessions.addAll(
                listOf(
                    makeSession("host:agy:conv_a", "会话 A"),
                    makeSession("host:agy:conv_b", "会话 B"),
                ),
            )
        }
        withConnectedStore(fake) { store ->
            store.selectSession("host:agy:conv_a")
            awaitUntil { store.state.value.activeSessionId == "host:agy:conv_a" }
            val keyA = store.state.value.activeDraftKey
            store.updateSessionDraft("A 的草稿", expectedDraftKey = keyA)

            store.selectSession("host:agy:conv_b")
            awaitUntil { store.state.value.activeSessionId == "host:agy:conv_b" }
            val keyB = store.state.value.activeDraftKey
            store.updateSessionDraft("B 的草稿", expectedDraftKey = keyB)

            // Late IME callback carrying Session A's draft key must be ignored
            store.updateSessionDraft("A 的陈旧回调", expectedDraftKey = keyA)

            assertEquals("B 的草稿", store.state.value.activeDraftText)
            assertEquals("A 的草稿", store.state.value.sessionDrafts[keyA])
        }
    }

    @Test
    fun crossSessionRetryUsesTargetSessionModeAndModelInsteadOfCurrentSessionSelection() {
        val fake = FakeHubTransport().apply {
            sessions.addAll(
                listOf(
                    makeSession("host:agy:conv_a", "安全规划会话", mode = SessionMode.PLAN, model = "gemini-4-neon"),
                    makeSession("host:agy:conv_b", "免审批会话", mode = SessionMode.BYPASS_PERMISSIONS, model = "claude-opus-4-6"),
                ),
            )
            sendPromptDelayMs = 80L
            sendPromptException = RpcCallException(ErrorCode.ENGINE_ERROR, "temporary error")
        }
        withConnectedStore(fake) { store ->
            store.selectSession("host:agy:conv_a")
            awaitUntil { store.state.value.activeSessionId == "host:agy:conv_a" }
            // User sends in A, immediately switches to B (BYPASS_PERMISSIONS + claude-opus-4-6), then A's send fails
            store.sendPrompt("在安全模式下分析代码")
            store.selectSession("host:agy:conv_b")
            awaitUntil {
                store.state.value.activeSessionId == "host:agy:conv_b" &&
                    store.state.value.turnRecoveryBanner?.sessionId == "host:agy:conv_a"
            }

            fake.sendPromptDelayMs = 0L
            fake.sendPromptException = null

            // Retry targets conv_a while active UI is conv_b -> MUST use conv_a's PLAN mode & model, NEVER conv_b's!
            store.retryLastTurn()
            awaitUntil { fake.sentPromptParams.isNotEmpty() }

            val retried = fake.sentPromptParams.last()
            assertEquals("host:agy:conv_a", retried.sessionId)
            assertEquals(SessionMode.PLAN, retried.mode)
            assertEquals("gemini-4-neon", retried.modelId)
        }
    }

    @Test
    fun respondApprovalKeepsWaitingPermissionStateWhenFollowUpQuestionIsAlreadyPending() {
        val sid = "host:agy:conv_multi_q"
        val fake = FakeHubTransport().apply {
            sessions.add(
                makeSession(sid, "多题问答会话").copy(state = SessionState.WAITING_PERMISSION),
            )
            sessionEventsMap[sid] = mutableListOf(
                AgentEvent.PermissionRequested(
                    seq = 1,
                    sessionId = sid,
                    ts = 1_000L,
                    requestId = "elicit_q1",
                    title = "请确认问题 (1/2)：选择部署方式",
                    interactionKind = InteractionKind.QUESTION,
                    options = listOf(PermissionOption("opt_1", "1. 灰度发布", PermissionOptionKind.ALLOW_ONCE)),
                ),
                AgentEvent.PermissionRequested(
                    seq = 2,
                    sessionId = sid,
                    ts = 1_050L,
                    requestId = "elicit_q2",
                    title = "请确认问题 (2/2)：选择环境",
                    interactionKind = InteractionKind.QUESTION,
                    options = listOf(PermissionOption("opt_2", "1. 测试环境", PermissionOptionKind.ALLOW_ONCE)),
                ),
            )
        }
        withConnectedStore(fake) { store ->
            store.selectSession(sid)
            awaitUntil { store.state.value.transcripts[sid]?.pendingPermissions?.size == 2 }

            store.respondApproval(requestId = "elicit_q1", optionId = "opt_1", sessionId = sid)
            awaitUntil {
                store.state.value.transcripts[sid]?.pendingPermissions?.map { it.requestId } == listOf("elicit_q2")
            }

            val meta = store.state.value.sessions.first { it.id == sid }
            assertEquals(SessionState.WAITING_PERMISSION, meta.state)
        }
    }
}
