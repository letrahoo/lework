package dev.lework

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import dev.lework.platform.JvmPlatformTestHooks
import dev.lework.protocol.*
import dev.lework.store.ChatTimelineItem
import dev.lework.store.ConsoleStore
import dev.lework.ui.ConsoleApp
import dev.lework.ui.buildProcessHeadline
import dev.lework.ui.parseCliTool
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
class SubagentInspectorTest {

    @BeforeTest
    fun setUp() {
        JvmPlatformTestHooks.reset()
    }

    @Test
    fun `store opens subagent inspector, streams live deltas, refreshes, and clears on parent session switch`() =
        runTest {
            val transport = FakeHubTransport()
            val parentA = "host:agy:parent_session_a"
            val parentB = "host:agy:parent_session_b"
            val subRunId = "host:agy:sub_live_1"
            val subDoneId = "host:agy:sub_done_2"

            val subRunning = SubagentSummary(
                id = subRunId,
                title = "Mermaid 渲染器实现",
                role = "Implementer",
                state = SessionState.RUNNING,
                statusText = "修改 MermaidViews.kt",
                createdAt = 1_000L,
                updatedAt = 2_000L,
                workspace = "/workspace/lework",
            )
            val subDone = SubagentSummary(
                id = subDoneId,
                title = "终端同步修复",
                state = SessionState.CLOSED,
                statusText = "已完成",
                createdAt = 900L,
                updatedAt = 1_500L,
                workspace = "/workspace/lework",
            )

            transport.sessions.addAll(
                listOf(
                    SessionMeta(
                        id = parentA,
                        engineId = "agy",
                        cwd = "/workspace/lework",
                        title = "并行派发主会话",
                        state = SessionState.RUNNING,
                        createdAt = 1_000L,
                        updatedAt = 3_000L,
                        lastSeq = 0,
                        subagents = listOf(subRunning, subDone),
                    ),
                    SessionMeta(
                        id = parentB,
                        engineId = "agy",
                        cwd = "/workspace/lework",
                        title = "另一个无关会话",
                        state = SessionState.IDLE,
                        createdAt = 1_000L,
                        updatedAt = 1_200L,
                        lastSeq = 0,
                    ),
                ),
            )
            transport.sessionEventsMap[parentA] = mutableListOf(
                AgentEvent.UserMessage(0, parentA, 1_000L, "u_p0", "启动两个子代理并行处理"),
            )
            transport.sessionEventsMap[subRunId] = mutableListOf(
                AgentEvent.UserMessage(0, subRunId, 1_050L, "u_s0", "实现 Mermaid 图表渲染"),
                AgentEvent.ToolCall(
                    seq = 1,
                    sessionId = subRunId,
                    ts = 1_100L,
                    toolCallId = "tc_sub_1",
                    title = "Update",
                    kind = ToolKind.EDIT,
                    status = ToolStatus.COMPLETED,
                    input = """{"file_path":"/workspace/lework/MermaidViews.kt"}""",
                ),
                AgentEvent.AssistantDelta(2, subRunId, 1_200L, "a_s1", "正在完善节点布局。"),
            )

            val storeScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
            try {
                val store = ConsoleStore(storeScope, transport)
                store.bootstrapOrConnect()
                runCurrent()

                store.selectSession(parentA)
                runCurrent()
                assertEquals(2, store.state.value.activeSubagents.size)

                // 1. Open inspector for running subagent
                store.openSubagentInspector(subRunning)
                runCurrent()

                assertEquals(subRunId, store.state.value.inspectedSubagent?.id)
                assertEquals(SessionState.RUNNING, store.state.value.inspectedSubagent?.state)
                assertEquals(false, store.state.value.inspectedSubagentLoading)
                assertNull(store.state.value.inspectedSubagentError)
                assertEquals(3, store.state.value.inspectedSubagentEvents.size)

                // 2. Receive live streaming delta for inspected subagent without touching parent transcript
                transport.emitEvent(
                    AgentEvent.AssistantDelta(3, subRunId, 1_300L, "a_s1", "已补充单测。"),
                )
                runCurrent()
                val assistant = store.state.value.inspectedSubagentEvents
                    .filterIsInstance<ChatTimelineItem.Assistant>()
                    .single()
                assertEquals("正在完善节点布局。已补充单测。", assistant.text)
                assertEquals(1, store.state.value.activeTranscript.items.size)

                // 3. Parent session.updated propagates subagent transition (RUNNING -> IDLE) into open inspector
                val updatedSubDone = subRunning.copy(
                    state = SessionState.IDLE,
                    statusText = "已完成",
                    updatedAt = 4_000L,
                )
                transport.emitSessionUpdate(
                    transport.sessions.first { it.id == parentA }.copy(
                        subagents = listOf(updatedSubDone, subDone),
                        updatedAt = 4_000L,
                    ),
                )
                runCurrent()
                assertEquals(SessionState.IDLE, store.state.value.inspectedSubagent?.state)
                assertEquals("已完成", store.state.value.inspectedSubagent?.statusText)

                // 4. Manual refresh pulls newly appended transcript items cleanly
                transport.sessionEventsMap[subRunId]!!.add(
                    AgentEvent.AssistantDelta(4, subRunId, 1_500L, "a_s2", "全部校验通过。"),
                )
                store.refreshInspectedSubagent()
                runCurrent()
                assertTrue(
                    store.state.value.inspectedSubagentEvents.any {
                        it is ChatTimelineItem.Assistant && it.text.contains("全部校验通过")
                    },
                )

                // 5. Switching parent session auto-closes inspector and unsubscribes subagent stream
                store.selectSession(parentB)
                runCurrent()
                assertNull(store.state.value.inspectedSubagent)
                assertTrue(store.state.value.inspectedSubagentEvents.isEmpty())
                assertTrue(subRunId in transport.unsubscribedIds)
            } finally {
                storeScope.cancel()
            }
        }

    @Test
    fun `process trace headline surfaces concise subagent role names instead of bare count`() {
        val t1 = ChatTimelineItem.Tool(
            id = "tc_1",
            title = "Task",
            kind = ToolKind.OTHER,
            status = ToolStatus.COMPLETED,
            input = """{"Role":"Mermaid Renderer","Prompt":"Render diagrams"}""",
        )
        val t2 = ChatTimelineItem.Tool(
            id = "tc_2",
            title = "invoke_subagent",
            kind = ToolKind.OTHER,
            status = ToolStatus.COMPLETED,
            input = """{"Role":"Lsof Fix","Prompt":"Fix lsof"}""",
        )
        val steps = listOf(t1, t2)
        val parsed = steps.map { it to parseCliTool(it) }
        val headline = buildProcessHeadline(
            steps = steps,
            tools = parsed,
            activeTool = null,
            hasFailure = false,
        ).text

        assertTrue(headline.contains("子任务×2"), "Expected task count prefix, got: $headline")
        assertTrue(headline.contains("Mermaid Renderer"), "Expected first role in headline, got: $headline")
        assertTrue(headline.contains("Lsof Fix"), "Expected second role in headline, got: $headline")
    }

    @Test
    fun `renders subagent status strip and drill-down inspector dialog cleanly on mobile and desktop viewports`() =
        runComposeUiTest {
            val transport = FakeHubTransport()
            val parentId = "host:agy:ui_parent_1"
            val subAId = "host:agy:ui_sub_a"
            val subBId = "host:agy:ui_sub_b"

            val subA = SubagentSummary(
                id = subAId,
                title = "前端协议排查",
                role = "Inspector",
                state = SessionState.RUNNING,
                statusText = "查阅 ConsoleStore.kt",
                createdAt = 1_000L,
                updatedAt = 3_000L,
                workspace = "/workspace/lework",
            )
            val subB = SubagentSummary(
                id = subBId,
                title = "单测补齐",
                state = SessionState.CLOSED,
                statusText = "已完成",
                createdAt = 1_000L,
                updatedAt = 2_000L,
                workspace = "/workspace/lework",
            )

            transport.sessions.add(
                SessionMeta(
                    id = parentId,
                    engineId = "agy",
                    cwd = "/workspace/lework",
                    title = "UI 并行子代理测试",
                    state = SessionState.RUNNING,
                    createdAt = 1_000L,
                    updatedAt = 3_000L,
                    lastSeq = 0,
                    subagents = listOf(subA, subB, subB.copy(id = "uncertain", state = SessionState.IDLE, statusText = null)),
                ),
            )
            transport.sessionEventsMap[parentId] = mutableListOf(
                AgentEvent.UserMessage(0, parentId, 1_000L, "u_0", "并行执行两个子代理"),
            )
            transport.sessionEventsMap[subAId] = mutableListOf(
                AgentEvent.UserMessage(0, subAId, 1_050L, "u_a0", "排查 SubAgent 协议状态同步"),
                AgentEvent.AssistantDelta(1, subAId, 1_100L, "a_a1", "已确认 `SubagentSummary` 字段对齐。"),
            )

            // Verify Mobile viewport (402x874)
            lateinit var store: ConsoleStore
            setContent {
                val scope: CoroutineScope = rememberCoroutineScope()
                val created = androidx.compose.runtime.remember { ConsoleStore(scope, transport) }
                store = created
                Box(modifier = Modifier.size(width = 402.dp, height = 874.dp)) {
                    ConsoleApp(externalStore = created, autoBootstrap = true)
                }
            }
            waitForIdle()

            runOnIdle { store.selectSession(parentId) }
            waitForIdle()

            // Strip is displayed with live running ratio pill and quick chips
            onNodeWithTag("subagent_status_strip").assertIsDisplayed()
            onNodeWithText("2 项待跟进", substring = true).assertIsDisplayed()
            onNodeWithTag("subagent_chip_$subAId").assertIsDisplayed()

            onNodeWithTag("subagent_chip_$subBId").assertDoesNotExist()
            onNodeWithTag("subagent_chip_uncertain").assertExists()

            // Expand strip to view full cards with role badge & statusText
            onNodeWithTag("subagent_strip_toggle").performClick()
            waitForIdle()
            onNodeWithTag("subagent_item_$subAId").assertIsDisplayed()
            onNodeWithTag("subagent_item_$subBId").assertDoesNotExist()
            onNodeWithText("已暂停 · 待确认").assertIsDisplayed()
            onNodeWithText("查看历史子任务（1）").performClick()
            onNodeWithTag("subagent_item_$subBId").assertIsDisplayed()
            onNodeWithText("查阅 ConsoleStore.kt", substring = true).assertIsDisplayed()

            // Click subagent item -> opens drill-down inspector dialog with live transcript
            onNodeWithTag("subagent_item_$subAId").performClick()
            waitForIdle()

            onNodeWithTag("subagent_inspector_dialog").assertIsDisplayed()
            onNodeWithText("SubagentSummary", substring = true).assertIsDisplayed()
            onNodeWithText("字段对齐", substring = true).assertIsDisplayed()
            assertNotNull(store.state.value.inspectedSubagent)

            // Refresh button updates transcript
            transport.sessionEventsMap[subAId]!!.add(
                AgentEvent.AssistantDelta(2, subAId, 1_200L, "a_a2", "刷新后最新进度：全部完成。"),
            )
            onNodeWithTag("subagent_inspector_refresh_btn").performClick()
            waitForIdle()
            onNodeWithText("刷新后最新进度：全部完成。", substring = true).assertIsDisplayed()

            // Close button dismisses dialog cleanly
            onNodeWithTag("subagent_inspector_close_btn").performClick()
            waitForIdle()
            onNodeWithTag("subagent_inspector_dialog").assertDoesNotExist()
            assertNull(store.state.value.inspectedSubagent)
        }

    @Test
    fun `renders subagent status strip and opens inspector via history on desktop layout`() =
        runComposeUiTest {
            JvmPlatformTestHooks.simulateDesktopLayout = true
            val transport = FakeHubTransport()
            val parentId = "host:claude:desktop_parent"
            val subId = "host:claude-subagent:desktop_parent:agent-a1"

            val sub = SubagentSummary(
                id = subId,
                title = "Claude 子任务代码审查",
                role = "Reviewer",
                state = SessionState.CLOSED,
                statusText = "已完成",
                createdAt = 1_000L,
                updatedAt = 2_000L,
                workspace = "/workspace/lework",
            )
            transport.sessions.add(
                SessionMeta(
                    id = parentId,
                    engineId = "claude",
                    cwd = "/workspace/lework",
                    title = "Desktop SubAgents 验证",
                    state = SessionState.IDLE,
                    createdAt = 1_000L,
                    updatedAt = 2_000L,
                    lastSeq = 0,
                    subagents = listOf(sub),
                ),
            )
            transport.sessionEventsMap[parentId] = mutableListOf(
                AgentEvent.UserMessage(0, parentId, 1_000L, "u_0", "审查修改"),
            )
            transport.sessionEventsMap[subId] = mutableListOf(
                AgentEvent.UserMessage(0, subId, 1_020L, "u_s0", "检查所有边界条件"),
                AgentEvent.AssistantDelta(1, subId, 1_100L, "a_s1", "审查结论：无并发竞态风险。"),
            )

            lateinit var store: ConsoleStore
            setContent {
                val scope: CoroutineScope = rememberCoroutineScope()
                val created = androidx.compose.runtime.remember { ConsoleStore(scope, transport) }
                store = created
                Box(modifier = Modifier.size(width = 1280.dp, height = 820.dp)) {
                    ConsoleApp(externalStore = created, autoBootstrap = true)
                }
            }
            waitForIdle()

            runOnIdle { store.selectSession(parentId) }
            waitForIdle()

            onNodeWithTag("subagent_status_strip").assertIsDisplayed()
            onNodeWithText("子任务历史 · 1", substring = true).assertIsDisplayed()

            onNodeWithTag("subagent_chip_$subId").assertDoesNotExist()
            onNodeWithTag("subagent_strip_toggle").performClick()
            onNodeWithTag("subagent_item_$subId").performClick()
            waitForIdle()

            onNodeWithTag("subagent_inspector_dialog").assertIsDisplayed()
            onNodeWithText("审查结论：无并发竞态风险。", substring = true).assertIsDisplayed()

            onNodeWithTag("subagent_inspector_close_btn").performClick()
            waitForIdle()
            onNodeWithTag("subagent_inspector_dialog").assertDoesNotExist()
        }
}
