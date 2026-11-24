package dev.lework

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import dev.lework.platform.JvmPlatformTestHooks
import dev.lework.protocol.AgentEvent
import dev.lework.protocol.SearchMatchRole
import dev.lework.protocol.SearchRoleFilter
import dev.lework.protocol.SessionMeta
import dev.lework.protocol.SessionState
import dev.lework.store.ConsoleStore
import dev.lework.ui.ConsoleApp
import dev.lework.ui.desktop.buildHighlightedSearchText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
class DesktopConversationSearchTest {

    @BeforeTest
    fun setUp() {
        JvmPlatformTestHooks.reset()
        JvmPlatformTestHooks.simulateDesktopLayout = true
    }

    @Test
    fun `global search finds hidden message across engines, jumps to historical aroundSeq window, and jumps back to latest`() = runTest {
        val transport = FakeHubTransport()
        val deepSid = "host:agy:conv_harmony_approval"
        val otherSid = "host:claude:conv_refactor"

        val deepEvents = mutableListOf<AgentEvent>()
        var seq = 0
        for (i in 0 until 320) {
            val uText = if (i == 8) {
                "复用企业证书的 Harmony 线上流程是什么预审批链路？"
            } else {
                "普通迭代需求讨论 #$i"
            }
            val aText = if (i == 8) {
                "鸿蒙企业签名走 AGC 建应用 + ByteSign 复制密钥审批。"
            } else {
                "已收到第 $i 条讨论。"
            }
            deepEvents.add(AgentEvent.UserMessage(seq++, deepSid, 1_000L + i * 100L, "u_$i", uText))
            deepEvents.add(AgentEvent.AssistantDelta(seq++, deepSid, 1_050L + i * 100L, "a_$i", aText))
        }

        transport.sessions.add(
            SessionMeta(
                id = deepSid,
                engineId = "agy",
                cwd = "/workspace/demo-app",
                title = "App 迭代与规划",
                state = SessionState.IDLE,
                createdAt = 1_000L,
                updatedAt = 50_000L,
                lastSeq = deepEvents.last().seq,
            ),
        )
        transport.sessions.add(
            SessionMeta(
                id = otherSid,
                engineId = "claude",
                cwd = "/workspace/lework",
                title = "WebSocket 优化",
                state = SessionState.IDLE,
                createdAt = 2_000L,
                updatedAt = 60_000L,
                lastSeq = 1,
            ),
        )
        transport.sessionEventsMap[deepSid] = deepEvents
        transport.sessionEventsMap[otherSid] = mutableListOf(
            AgentEvent.UserMessage(0, otherSid, 2_000L, "u_o0", "排查连接重连循环"),
        )

        val storeScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val store = ConsoleStore(storeScope, transport)
            store.bootstrapOrConnect()
            runCurrent()

            // 1. Search with role=USER finds turn #8 (seq=16) even though title does not mention Harmony
            store.searchConversations(
                query = "Harmony 预审批",
                roleFilter = SearchRoleFilter.USER,
                immediate = true,
            )
            runCurrent()

            val hits = store.state.value.searchHits
            assertEquals(1, hits.size)
            assertEquals(deepSid, hits.first().sessionId)
            val hitMatch = hits.first().matches.first()
            assertEquals(16, hitMatch.seq)
            assertEquals(SearchMatchRole.USER, hitMatch.role)

            // 2. Jump to that historical seq -> loads aroundSeq slice containing seq=16 with hasMoreAfter=true
            store.jumpToSearchHit(deepSid, hitMatch.seq)
            runCurrent()

            assertEquals(deepSid, store.state.value.activeSessionId)
            assertEquals(16, store.state.value.highlightedSeq)
            val jumpedTranscript = store.state.value.activeTranscript
            assertTrue(jumpedTranscript.items.any { it.seq == 16 }, "Loaded window must include jumped seq=16")
            assertTrue(jumpedTranscript.hasMoreAfter, "Jumping deep into history should expose jump-to-latest flag")

            // 3. Jump back to latest tail restores live tail and clears hasMoreAfter
            store.jumpBackToLatest(deepSid)
            runCurrent()

            assertFalse(store.state.value.activeTranscript.hasMoreAfter)
            assertEquals(deepEvents.last().seq, store.state.value.activeTranscript.lastSeq)
            assertNull(store.state.value.highlightedSeq)
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun `in-session find discovers matches across full history and cycles prev and next cleanly`() = runTest {
        val transport = FakeHubTransport()
        val sid = "host:agy:conv_in_session_find"
        val events = mutableListOf<AgentEvent>(
            AgentEvent.UserMessage(0, sid, 1_000L, "u_0", "第一次提到 审批链路 的需求"),
            AgentEvent.AssistantDelta(1, sid, 1_100L, "a_0", "好的，先看整体架构"),
            AgentEvent.UserMessage(2, sid, 2_000L, "u_1", "无关的中间轮次"),
            AgentEvent.AssistantDelta(3, sid, 2_100L, "a_1", "总结：审批链路 共包含三个关键节点"),
        )
        transport.sessions.add(
            SessionMeta(
                id = sid,
                engineId = "agy",
                cwd = "/workspace/demo-app",
                title = "审批排查",
                state = SessionState.IDLE,
                createdAt = 1_000L,
                updatedAt = 3_000L,
                lastSeq = 3,
            ),
        )
        transport.sessionEventsMap[sid] = events

        val storeScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val store = ConsoleStore(storeScope, transport)
            store.bootstrapOrConnect()
            runCurrent()
            store.selectSession(sid)
            runCurrent()

            store.openInSessionFind()
            store.updateInSessionFindQuery("审批链路")
            advanceTimeBy(300L)
            runCurrent()

            assertTrue(store.state.value.inSessionFindOpen)
            assertEquals(2, store.state.value.inSessionFindMatches.size)
            assertEquals(listOf(0, 3), store.state.value.inSessionFindMatches.map { it.seq })
            // Defaults to latest match (index 1 -> seq 3)
            assertEquals(1, store.state.value.inSessionFindActiveIndex)
            assertEquals(3, store.state.value.highlightedSeq)

            // Previous match moves to seq 0
            store.navigateInSessionFind(-1)
            runCurrent()
            assertEquals(0, store.state.value.inSessionFindActiveIndex)
            assertEquals(0, store.state.value.highlightedSeq)

            // Next match wraps forward back to seq 3
            store.navigateInSessionFind(+1)
            runCurrent()
            assertEquals(1, store.state.value.inSessionFindActiveIndex)
            assertEquals(3, store.state.value.highlightedSeq)

            // Closing find bar clears state & highlight
            store.closeInSessionFind()
            assertFalse(store.state.value.inSessionFindOpen)
            assertTrue(store.state.value.inSessionFindMatches.isEmpty())
            assertNull(store.state.value.highlightedSeq)
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun `highlighted search text annotates multi-term matches case-insensitively`() {
        val annotated = buildHighlightedSearchText(
            text = "Harmony 线上流程 预审批链路 说明",
            query = "harmony 预审批",
        )
        assertEquals(2, annotated.spanStyles.size)
        assertEquals(0, annotated.spanStyles[0].start)
        assertEquals(7, annotated.spanStyles[0].end)
    }

    @Test
    fun `Desktop UI renders sidebar full-text search bridge, global search dialog, and in-session find bar`() = runComposeUiTest {
        val transport = FakeHubTransport()
        val sid = "host:agy:conv_ui_search"
        transport.sessions.add(
            SessionMeta(
                id = sid,
                engineId = "agy",
                cwd = "/workspace/demo-app",
                title = "迭代规划",
                state = SessionState.IDLE,
                createdAt = 1_000L,
                updatedAt = 4_000L,
                lastSeq = 1,
            ),
        )
        transport.sessionEventsMap[sid] = mutableListOf(
            AgentEvent.UserMessage(0, sid, 1_000L, "u_0", "Harmony 预审批链路怎么走"),
            AgentEvent.AssistantDelta(1, sid, 2_000L, "a_0", "先创建正式 AppID 再复制企业证书密钥"),
        )

        val scope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined)
        val store = ConsoleStore(scope, transport)
        store.bootstrapOrConnect()

        try {
            setContent {
                ConsoleApp(externalStore = store, autoBootstrap = false)
            }
            waitForIdle()

            // 1. Typing in sidebar search reveals full-text search bridge button -> clicking opens Global Search Dialog
            onNodeWithTag("drawer_search_input").performTextInput("预审批")
            waitForIdle()
            onNodeWithTag("sidebar_btn_fulltext_search").assertIsDisplayed().performClick()
            waitForIdle()

            onNodeWithTag("desktop_global_search_dialog").assertIsDisplayed()
            onNodeWithTag("search_hit_${sid}_0").assertIsDisplayed().performClick()
            waitForIdle()

            // 2. Clicking the hit switches to the session and highlights seq 0
            assertEquals(sid, store.state.value.activeSessionId)

            // 3. Opening in-session find bar (⌘F) renders bar and match counter
            store.openInSessionFind()
            waitForIdle()
            onNodeWithTag("desktop_in_session_find_bar").assertIsDisplayed()
            onNodeWithTag("desktop_in_session_find_input").performTextInput("AppID")
            waitForIdle()
            onNodeWithTag("desktop_in_session_find_counter").assertIsDisplayed()
            onNodeWithText("1 / 1").assertIsDisplayed()
        } finally {
            scope.cancel()
        }
    }
}
