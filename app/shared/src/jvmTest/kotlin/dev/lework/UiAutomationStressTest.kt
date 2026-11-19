package dev.lework

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import dev.lework.platform.JvmPlatformTestHooks
import dev.lework.protocol.*
import dev.lework.store.ConsoleStore
import dev.lework.ui.ConsoleApp
import dev.lework.ui.EverforestMarkdownContent
import kotlinx.coroutines.CoroutineScope
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class UiAutomationStressTest {

    @BeforeTest
    fun setUp() {
        JvmPlatformTestHooks.reset()
    }

    @Test
    fun `T8_1 survives 1000 streaming deltas interleaved with 20 rapid session switches without cross-talk or UI deadlock`() =
        runComposeUiTest {
            val transport = FakeHubTransport()
            val sessionIds = (1..5).map { idx ->
                val sid = "host:claude:stress_sess_$idx"
                transport.sessions.add(
                    SessionMeta(
                        id = sid,
                        engineId = "claude",
                        cwd = "/workspace/lework",
                        title = "压测会话 #$idx",
                        state = SessionState.RUNNING,
                        createdAt = 1_000L,
                        updatedAt = 2_000L + idx,
                        lastSeq = 0,
                    ),
                )
                transport.sessionEventsMap[sid] = mutableListOf(
                    AgentEvent.UserMessage(0, sid, 1_000L, "u_init_$idx", "启动压测会话 #$idx"),
                )
                sid
            }

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

            // Interleave 20 rapid session switches with 1,000 high-frequency streaming deltas & tool updates
            var globalSeq = 1
            for (switchIdx in 0 until 20) {
                val targetSid = sessionIds[switchIdx % sessionIds.size]
                runOnIdle { store.selectSession(targetSid) }

                // Blast 50 events per switch across both active and non-active sessions (20 * 50 = 1,000 events)
                for (burst in 0 until 50) {
                    val emitSid = if (burst % 3 == 0) sessionIds[(switchIdx + 1) % sessionIds.size] else targetSid
                    val seq = globalSeq++
                    if (burst % 10 == 0) {
                        transport.emitEvent(
                            AgentEvent.ToolCall(
                                seq = seq,
                                sessionId = emitSid,
                                ts = 2_000L + seq,
                                toolCallId = "tc_$seq",
                                title = "Bash(echo $seq)",
                                kind = ToolKind.EXECUTE,
                                status = ToolStatus.COMPLETED,
                                input = """{"command":"echo $seq"}""",
                                output = "ok-$seq",
                            ),
                        )
                    } else {
                        transport.emitEvent(
                            AgentEvent.AssistantDelta(
                                seq = seq,
                                sessionId = emitSid,
                                ts = 2_000L + seq,
                                messageId = "stream_msg_$emitSid",
                                text = "chunk-$seq ",
                            ),
                        )
                    }
                }
                waitForIdle()
            }

            // Finalize on session #5 with a distinct marker and verify zero cross-session pollution
            val finalSid = sessionIds.last()
            runOnIdle { store.selectSession(finalSid) }
            waitForIdle()
            transport.emitEvent(
                AgentEvent.AssistantDelta(
                    seq = 99_999,
                    sessionId = finalSid,
                    ts = 99_999L,
                    messageId = "final_marker_msg",
                    text = "FINAL_SESSION_5_CLEAN_MARKER",
                ),
            )
            waitForIdle()

            onNodeWithText("FINAL_SESSION_5_CLEAN_MARKER", substring = true).assertExists()
            onNodeWithText("启动压测会话 #1", substring = true).assertDoesNotExist()
        }

    @Test
    fun `T8_2 handles 2000-message virtual list rapid touch swipes and live reverse pagination smoothly`() =
        runComposeUiTest {
            val transport = FakeHubTransport()
            val sid = "host:agy:huge_2000_session"
            transport.sessions.add(
                SessionMeta(
                    id = sid,
                    engineId = "agy",
                    cwd = "/workspace/lework",
                    title = "2000条超长对话压测",
                    state = SessionState.IDLE,
                    createdAt = 1_000L,
                    updatedAt = 9_000L,
                    lastSeq = 1999,
                ),
            )
            transport.sessionEventsMap[sid] = (0 until 2000).map { seq ->
                if (seq % 2 == 0) {
                    AgentEvent.UserMessage(seq, sid, 1_000L + seq, "u_$seq", "历史提问条目 #$seq")
                } else {
                    AgentEvent.AssistantDelta(
                        seq,
                        sid,
                        1_000L + seq,
                        "a_$seq",
                        "历史回答详情 #$seq 包含 **重点结论** 与 `code_span_$seq`。",
                    )
                }
            }.toMutableList()

            lateinit var store: ConsoleStore
            setContent {
                val scope = rememberCoroutineScope()
                val created = androidx.compose.runtime.remember { ConsoleStore(scope, transport) }
                store = created
                Box(modifier = Modifier.size(width = 402.dp, height = 874.dp)) {
                    ConsoleApp(externalStore = created, autoBootstrap = true)
                }
            }
            waitForIdle()

            runOnIdle { store.selectSession(sid) }
            waitForIdle()

            // At bottom initially: newest message #1999 is visible, initial page = 220 items!
            onNodeWithText("历史回答详情 #1999", substring = true).assertExists()
            assertEquals(220, store.state.value.activeTranscript.items.size)

            // Perform 12 rapid touch swipe gestures on the reverseLayout LazyColumn to scroll upward & trigger pagination!
            repeat(12) {
                onNodeWithTag("conversation_lazy_list").performTouchInput {
                    swipeDown(startY = top + 120f, endY = bottom - 80f, durationMillis = 90L)
                }
                waitForIdle()
            }

            // Reverse pagination MUST have automatically loaded older pages (> 220 items) while keeping lastSeq == 1999!
            assertTrue(
                store.state.value.activeTranscript.items.size > 220,
                "Expected reverse pagination to load older history on upward swipe, got ${store.state.value.activeTranscript.items.size}",
            )
            assertEquals(1999, store.state.value.activeTranscript.lastSeq)
        }

    @Test
    fun `T8_3 survives 50 rapid rounds of Agent approval banner popup, button click, and fallback notice rendering`() =
        runComposeUiTest {
            val transport = FakeHubTransport()
            val sid = "host:claude:approval_stress"
            val baseMeta = SessionMeta(
                id = sid,
                engineId = "claude",
                cwd = "/workspace/lework",
                title = "审批连续点击压测",
                state = SessionState.RUNNING,
                createdAt = 1_000L,
                updatedAt = 2_000L,
                lastSeq = 0,
            )
            transport.sessions.add(baseMeta)
            transport.sessionEventsMap[sid] = mutableListOf(
                AgentEvent.UserMessage(0, sid, 1_000L, "u_0", "开始部署校验"),
            )

            lateinit var store: ConsoleStore
            setContent {
                val scope = rememberCoroutineScope()
                val created = androidx.compose.runtime.remember { ConsoleStore(scope, transport) }
                store = created
                Box(modifier = Modifier.size(width = 402.dp, height = 874.dp)) {
                    ConsoleApp(externalStore = created, autoBootstrap = true)
                }
            }
            waitForIdle()
            runOnIdle { store.selectSession(sid) }
            waitForIdle()

            // 1. Invariant check: if session enters WAITING_PERMISSION before options arrive, fallback banner MUST appear (never blank!)
            transport.emitSessionUpdate(baseMeta.copy(state = SessionState.WAITING_PERMISSION))
            waitForIdle()
            onNodeWithTag("inline_approval_banner").assertExists()

            // Reset to RUNNING and execute 50 rapid interactive approval -> click -> resolve rounds
            transport.emitSessionUpdate(baseMeta.copy(state = SessionState.RUNNING))
            waitForIdle()
            onNodeWithTag("inline_approval_banner").assertDoesNotExist()

            for (round in 1..50) {
                val reqId = "tty_perm_round_$round"
                transport.emitEvent(
                    AgentEvent.PermissionRequested(
                        seq = round,
                        sessionId = sid,
                        ts = 3_000L + round,
                        requestId = reqId,
                        title = "Bash command · 第 $round 轮命令审批\ngit status --short",
                        options = listOf(
                            PermissionOption("tty_key:1", "1. Yes", PermissionOptionKind.ALLOW_ONCE),
                            PermissionOption("tty_key:2", "2. No", PermissionOptionKind.REJECT_ONCE),
                        ),
                    ),
                )
                waitForIdle()

                onNodeWithTag("inline_approval_banner").assertExists()
                onNodeWithText("1. Yes").performClick()
                waitForIdle()

                // Banner MUST dismiss immediately upon click without ghost residue
                onNodeWithTag("inline_approval_banner").assertDoesNotExist()
            }

            assertEquals(50, transport.respondedApprovals.size)
            assertTrue(transport.respondedApprovals.all { it.third == "tty_key:1" })
        }

    @Test
    fun `T8_4 survives 30 lock-screen background-foreground disconnect cycles without losing workspace or composer draft`() =
        runComposeUiTest {
            val transport = FakeHubTransport()
            val sid = "host:agy:lock_storm"
            transport.sessions.add(
                SessionMeta(
                    id = sid,
                    engineId = "agy",
                    cwd = "/workspace/lework",
                    title = "锁屏风暴压测",
                    state = SessionState.RUNNING,
                    createdAt = 1_000L,
                    updatedAt = 2_000L,
                    lastSeq = 0,
                ),
            )
            transport.sessionEventsMap[sid] = mutableListOf(
                AgentEvent.UserMessage(0, sid, 1_000L, "u_0", "保持上下文在线"),
            )

            lateinit var store: ConsoleStore
            setContent {
                val scope = rememberCoroutineScope()
                val created = androidx.compose.runtime.remember { ConsoleStore(scope, transport) }
                store = created
                Box(modifier = Modifier.size(width = 402.dp, height = 874.dp)) {
                    ConsoleApp(externalStore = created, autoBootstrap = true)
                }
            }
            waitForIdle()
            runOnIdle { store.selectSession(sid) }
            waitForIdle()

            // Run 30 rapid lock-screen -> socket drop -> foreground wake cycles
            repeat(30) { cycle ->
                JvmPlatformTestHooks.setForeground(false)
                waitForIdle()
                transport.emitDisconnected("Cycle $cycle sleep disconnect")
                waitForIdle()

                // Must NEVER bounce back to Feishu login home screen!
                assertTrue(store.state.value.hasEnteredWorkspace)

                JvmPlatformTestHooks.setForeground(true)
                waitForIdle()
                assertTrue(store.state.value.hasEnteredWorkspace)
                assertEquals(sid, store.state.value.activeSessionId)
            }

            onNodeWithText("保持上下文在线", substring = true).assertExists()
        }

    @Test
    fun `T8_3 aligns Markdown table columns strictly across header and uneven multi-line body rows`() =
        runComposeUiTest {
            val sampleTable = """
                | # | 模块说明 | 命令 / 状态 |
                | - | :--- | ---: |
                | 1 | 这是一段非常长的描述文案，用来模拟移动端窄屏下自动折行的多行单元格内容，确保不会把其他行的列位置挤歪 | `hub tokens [list | revoke-all]` |
                | 2 | 短文案 | 已完成 |
            """.trimIndent()

            setContent {
                Box(modifier = Modifier.size(width = 390.dp, height = 600.dp)) {
                    EverforestMarkdownContent(markdown = sampleTable)
                }
            }
            waitForIdle()

            for (c in 0 until 3) {
                val hBounds = onNodeWithTag("md_table_cell_0_$c", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                val r1Bounds = onNodeWithTag("md_table_cell_1_$c", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                val r2Bounds = onNodeWithTag("md_table_cell_2_$c", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

                assertEquals(hBounds.left, r1Bounds.left, 0.5f, "Col $c left edge misaligned between header and row 1")
                assertEquals(hBounds.left, r2Bounds.left, 0.5f, "Col $c left edge misaligned between header and row 2")
                assertEquals(hBounds.right, r1Bounds.right, 0.5f, "Col $c right edge misaligned between header and row 1")
                assertEquals(hBounds.right, r2Bounds.right, 0.5f, "Col $c right edge misaligned between header and row 2")
            }

            // Columns must never overlap horizontally
            val col0 = onNodeWithTag("md_table_cell_1_0", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val col1 = onNodeWithTag("md_table_cell_1_1", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val col2 = onNodeWithTag("md_table_cell_1_2", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue(col0.right < col1.left, "Col 0 overlapped Col 1: $col0 vs $col1")
            assertTrue(col1.right < col2.left, "Col 1 overlapped Col 2: $col1 vs $col2")
        }
}
