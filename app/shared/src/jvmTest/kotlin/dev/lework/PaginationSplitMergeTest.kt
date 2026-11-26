package dev.lework

import dev.lework.platform.JvmPlatformTestHooks
import dev.lework.protocol.*
import dev.lework.store.ChatTimelineItem
import dev.lework.store.ConsoleStore
import dev.lework.store.mergePrependedOlderItems
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PaginationSplitMergeTest {

    @BeforeTest
    fun setUp() {
        JvmPlatformTestHooks.reset()
    }

    @Test
    fun `mergePrependedOlderItems stitches split assistant message across two and three pages in chronological order`() {
        val page3Tail = listOf<ChatTimelineItem>(
            ChatTimelineItem.Assistant(id = "msg_turn_1", text = "【后段】工程师的新定位是审核规范与验证边界。", ts = 3_000L, seq = 260),
            ChatTimelineItem.User(id = "u_2", text = "追问下一题", ts = 4_000L, seq = 400),
            ChatTimelineItem.Assistant(id = "msg_turn_2", text = "第二轮完整回答", ts = 4_100L, seq = 401),
        )
        val page2Middle = listOf<ChatTimelineItem>(
            ChatTimelineItem.Assistant(id = "msg_turn_1", text = "【中段】如果只靠单测，很容易被弱化断言蒙蔽。", ts = 2_000L, seq = 140),
        )
        val page1Head = listOf<ChatTimelineItem>(
            ChatTimelineItem.User(id = "u_1", text = "介绍一下这篇文章", ts = 1_000L, seq = 0),
            ChatTimelineItem.Tool(
                id = "tool_1",
                title = "search_web",
                kind = ToolKind.SEARCH,
                status = ToolStatus.COMPLETED,
                ts = 1_100L,
                seq = 1,
            ),
            ChatTimelineItem.Assistant(id = "msg_turn_1", text = "【前段】这篇文章讲了用 Rust 重写 Messages API。", ts = 1_500L, seq = 20),
        )

        val afterMiddle = mergePrependedOlderItems(page2Middle, page3Tail)
        assertEquals(3, afterMiddle.size)
        val midAssistant = afterMiddle[0] as ChatTimelineItem.Assistant
        assertEquals("msg_turn_1", midAssistant.id)
        assertEquals(140, midAssistant.seq)
        assertEquals(3_000L, midAssistant.ts)
        assertEquals(
            "【中段】如果只靠单测，很容易被弱化断言蒙蔽。【后段】工程师的新定位是审核规范与验证边界。",
            midAssistant.text,
        )

        val fullMerged = mergePrependedOlderItems(page1Head, afterMiddle)
        assertEquals(5, fullMerged.size)
        assertEquals("u_1", (fullMerged[0] as ChatTimelineItem.User).id)
        assertEquals("tool_1", (fullMerged[1] as ChatTimelineItem.Tool).id)
        val stitched = fullMerged[2] as ChatTimelineItem.Assistant
        assertEquals("msg_turn_1", stitched.id)
        assertEquals(20, stitched.seq)
        assertEquals(3_000L, stitched.ts)
        assertEquals(
            "【前段】这篇文章讲了用 Rust 重写 Messages API。【中段】如果只靠单测，很容易被弱化断言蒙蔽。【后段】工程师的新定位是审核规范与验证边界。",
            stitched.text,
        )
        assertEquals("u_2", (fullMerged[3] as ChatTimelineItem.User).id)
        assertEquals("msg_turn_2", (fullMerged[4] as ChatTimelineItem.Assistant).id)
    }

    @Test
    fun `mergePrependedOlderItems stitches split reasoning, merges split tools, and deduplicates overlapping retries`() {
        val current = listOf<ChatTimelineItem>(
            ChatTimelineItem.Reasoning(id = "r_1", text = "后半段思考过程。", ts = 1_800L, seq = 90),
            ChatTimelineItem.Tool(
                id = "t_1",
                title = "search_web",
                kind = ToolKind.SEARCH,
                status = ToolStatus.COMPLETED,
                input = null,
                output = "10 results",
                ts = 1_900L,
                seq = 95,
            ),
            ChatTimelineItem.Assistant(id = "a_overlap", text = "完整文本", ts = 2_000L, seq = 100),
        )
        val older = listOf<ChatTimelineItem>(
            ChatTimelineItem.Reasoning(id = "r_1", text = "前半段思考过程，", ts = 1_200L, seq = 10),
            ChatTimelineItem.Tool(
                id = "t_1",
                title = "search_web",
                kind = ToolKind.SEARCH,
                status = ToolStatus.IN_PROGRESS,
                input = """{"query":"migrating-api-to-rust"}""",
                output = null,
                ts = 1_300L,
                seq = 12,
            ),
            // Exact overlap retry (older.seq == existing.seq): must NOT double text
            ChatTimelineItem.Assistant(id = "a_overlap", text = "完整文本", ts = 2_000L, seq = 100),
        )

        val merged = mergePrependedOlderItems(older, current)
        assertEquals(3, merged.size)

        val reasoning = merged[0] as ChatTimelineItem.Reasoning
        assertEquals("前半段思考过程，后半段思考过程。", reasoning.text)
        assertEquals(10, reasoning.seq)
        assertEquals(1_800L, reasoning.ts)

        val tool = merged[1] as ChatTimelineItem.Tool
        assertEquals(ToolStatus.COMPLETED, tool.status)
        assertEquals("""{"query":"migrating-api-to-rust"}""", tool.input)
        assertEquals("10 results", tool.output)
        assertEquals(12, tool.seq)
        assertEquals(1_900L, tool.ts)

        val overlap = merged[2] as ChatTimelineItem.Assistant
        assertEquals("完整文本", overlap.text)
        assertEquals(100, overlap.seq)
    }

    @Test
    fun `loadOlderHistory reconstructs long streaming AGY reply split across initial 220-event window and preserves live tail appending`() = runTest {
        val transport = FakeHubTransport()
        val sid = "host:agy:long_reply_split_test"
        val events = mutableListOf<AgentEvent>()
        var seq = 0

        events.add(
            AgentEvent.UserMessage(
                seq = seq++,
                sessionId = sid,
                ts = 1_000L,
                messageId = "u_1",
                text = "https://www.anthropic.com/research/migrating-api-to-rust 帮我介绍下这篇文章",
            ),
        )

        // 11 tool calls (22 events), mirroring the AGY sensight + search_web sequence
        repeat(11) { idx ->
            val toolId = "tool_$idx"
            val title = if (idx == 2) "Skill(sensight)" else "search_web"
            events.add(
                AgentEvent.ToolCall(
                    seq = seq++,
                    sessionId = sid,
                    ts = 1_100L + idx * 20L,
                    toolCallId = toolId,
                    title = title,
                    kind = ToolKind.SEARCH,
                    status = ToolStatus.IN_PROGRESS,
                ),
            )
            events.add(
                AgentEvent.ToolUpdate(
                    seq = seq++,
                    sessionId = sid,
                    ts = 1_110L + idx * 20L,
                    toolCallId = toolId,
                    status = ToolStatus.COMPLETED,
                    output = "ok-$idx",
                ),
            )
        }

        // 300 streaming assistant delta chunks sharing one messageId so initial limit=220 cuts mid-message
        val expectedFullText = StringBuilder()
        repeat(300) { idx ->
            val chunk = when (idx) {
                0 -> "一、文章核心背景：Anthropic 将 Messages API 迁移至 Rust。"
                100 -> "有单测/集成测试很容易被“弱化断言”蒙蔽。\n3. 工程师的新定位是"
                else -> "[seg-$idx]"
            }
            expectedFullText.append(chunk)
            events.add(
                AgentEvent.AssistantDelta(
                    seq = seq++,
                    sessionId = sid,
                    ts = 2_000L + idx * 5L,
                    messageId = "msg_turn_1",
                    text = chunk,
                ),
            )
        }

        val lastHistoricalSeq = seq - 1
        transport.sessions.add(
            SessionMeta(
                id = sid,
                engineId = "agy",
                cwd = "/workspace/lework",
                title = "Migrating API to Rust",
                state = SessionState.RUNNING,
                createdAt = 1_000L,
                updatedAt = 5_000L,
                lastSeq = lastHistoricalSeq,
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

            val initialTranscript = store.state.value.activeTranscript
            assertTrue(initialTranscript.hasMoreBefore, "Initial 220-event window should have older history")
            val initialAssistant = initialTranscript.items.filterIsInstance<ChatTimelineItem.Assistant>().single()
            assertFalse(
                initialAssistant.text.startsWith("一、文章核心背景"),
                "Precondition: FakeHubTransport slices naively at 220 events, cutting off the reply prefix",
            )

            // User scrolls up -> loadOlderHistory prepends User + 11 Tools + missing prefix of msg_turn_1
            store.loadOlderHistory(sid)
            runCurrent()

            val afterOlder = store.state.value.activeTranscript
            assertFalse(afterOlder.hasMoreBefore)
            assertEquals(0, afterOlder.earliestSeq)
            assertEquals(lastHistoricalSeq, afterOlder.lastSeq)

            val users = afterOlder.items.filterIsInstance<ChatTimelineItem.User>()
            val tools = afterOlder.items.filterIsInstance<ChatTimelineItem.Tool>()
            val assistants = afterOlder.items.filterIsInstance<ChatTimelineItem.Assistant>()
            assertEquals(1, users.size)
            assertEquals(11, tools.size)
            assertEquals(1, assistants.size, "Split assistant message must stitch into a single timeline item")
            assertEquals(expectedFullText.toString(), assistants.single().text)

            // Subsequent live delta for the same streaming message must continue appending cleanly
            val liveSuffix = "——以及用形式化验证兜底。"
            transport.emitEvent(
                AgentEvent.AssistantDelta(
                    seq = lastHistoricalSeq + 1,
                    sessionId = sid,
                    ts = 6_000L,
                    messageId = "msg_turn_1",
                    text = liveSuffix,
                ),
            )
            runCurrent()

            val afterLive = store.state.value.activeTranscript
            assertEquals(lastHistoricalSeq + 1, afterLive.lastSeq)
            val liveAssistant = afterLive.items.filterIsInstance<ChatTimelineItem.Assistant>().single()
            assertEquals(expectedFullText.toString() + liveSuffix, liveAssistant.text)
        } finally {
            storeScope.cancel()
            JvmPlatformTestHooks.reset()
        }
    }
}
