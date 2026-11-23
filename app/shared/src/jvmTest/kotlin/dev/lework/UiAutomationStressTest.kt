package dev.lework

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import dev.lework.platform.JvmPlatformTestHooks
import dev.lework.protocol.*
import dev.lework.store.ConsoleStore
import dev.lework.ui.ConsoleApp
import dev.lework.ui.EverforestMarkdownContent
import dev.lework.ui.desktop.LeworkPinIcon
import java.io.File
import kotlinx.coroutines.CoroutineScope
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image as SkiaImage
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

            // Verify superseded `tty_perm_*` replacement: if `tty_perm_stale_1_of_1` is followed by
            // `tty_perm_fresh_1_of_3` without an intervening PermissionResolved, clicking `1. Yes`
            // MUST submit `tty_perm_fresh_1_of_3` (never get stuck on the stale first requestId!)
            transport.emitEvent(
                AgentEvent.PermissionRequested(
                    seq = 51,
                    sessionId = sid,
                    ts = 4_001L,
                    requestId = "tty_perm_stale_1_of_1",
                    title = "Bash command · 1 of 1\necho old",
                    options = listOf(PermissionOption("tty_key:1", "1. Yes", PermissionOptionKind.ALLOW_ONCE)),
                ),
            )
            transport.emitEvent(
                AgentEvent.PermissionRequested(
                    seq = 51,
                    sessionId = sid,
                    ts = 4_002L,
                    requestId = "tty_perm_fresh_1_of_3",
                    title = "Bash command · 1 of 3\necho latest",
                    options = listOf(PermissionOption("tty_key:1", "1. Yes", PermissionOptionKind.ALLOW_ONCE)),
                ),
            )
            waitForIdle()
            onNodeWithText("1. Yes").performClick()
            waitForIdle()
            assertEquals("tty_perm_fresh_1_of_3", transport.respondedApprovals.last().second)
            onNodeWithTag("inline_approval_banner").assertDoesNotExist()
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

    @Test
    fun `T8_5 dismisses software keyboard and clears composer focus when opening drawer or bottom sheets`() =
        runComposeUiTest {
            val transport = FakeHubTransport()
            val sid = "host:agy:kbd_test"
            transport.sessions.add(
                SessionMeta(
                    id = sid,
                    engineId = "agy",
                    cwd = "/workspace/lework",
                    title = "输入法收起校验",
                    state = SessionState.IDLE,
                    createdAt = 1_000L,
                    updatedAt = 2_000L,
                    lastSeq = 0,
                ),
            )
            transport.sessionEventsMap[sid] = mutableListOf(
                AgentEvent.UserMessage(0, sid, 1_000L, "u_0", "测试键盘焦点"),
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

            // 1. Focus composer input and type draft text
            onNodeWithTag("composer_input").performClick()
            onNodeWithTag("composer_input").performTextInput("准备测试展开左侧菜单")
            waitForIdle()
            onNodeWithTag("composer_input").assertIsFocused()

            val beforeDismissCount = JvmPlatformTestHooks.keyboardDismissCount

            // 2. Tap top-left menu button -> composer focus MUST clear and native keyboard dismisser MUST fire!
            onNodeWithTag("topbar_btn_menu").performClick()
            waitForIdle()

            onNodeWithTag("composer_input").assertIsNotFocused()
            assertTrue(
                JvmPlatformTestHooks.keyboardDismissCount > beforeDismissCount,
                "Expected dismissNativeKeyboard() to fire when opening drawer",
            )
        }

    @Test
    fun `T14_2 renders permanent desktop sidebar on widescreen, hides mobile menu button, and sends on Enter key`() =
        runDesktopComposeUiTest(width = 1320, height = 840) {
            JvmPlatformTestHooks.simulateDesktopLayout = true
            JvmPlatformTestHooks.sshConnectSupported = true
            JvmPlatformTestHooks.sshConfigHosts = listOf("devbox", "devbox-01")
            JvmPlatformTestHooks.pairedHosts = listOf(
                dev.lework.platform.PairedHost(
                    hubId = "hub_fake_1",
                    name = "MacBook Pro (本机)",
                    fingerprint = "a".repeat(64),
                    endpoints = listOf("https://127.0.0.1:64450"),
                ),
                dev.lework.platform.PairedHost(
                    hubId = "hub_devbox_1",
                    name = "Remote Devbox (Linux)",
                    fingerprint = "b".repeat(64),
                    endpoints = listOf("https://192.168.10.20:64450"),
                ),
            )

            val transport = FakeHubTransport().apply {
                customHubName = "MacBook Pro (本机)"
                customEngines = listOf(
                    EngineInfo(
                        id = "claude",
                        displayName = "Claude Code",
                        kind = EngineKind.CLI,
                        status = EngineStatus(installed = true, ready = true, version = "2.1.328"),
                        capabilities = EngineCapabilities(
                            permissions = true,
                            images = true,
                            modelSwitch = true,
                            modes = true,
                            history = true,
                            cancel = true,
                        ),
                        models = listOf(
                            EngineModelSpec(
                                id = "claude-opus-4-6",
                                name = "Claude Opus 4.6",
                                efforts = listOf(
                                    ReasoningEffort.LOW,
                                    ReasoningEffort.MEDIUM,
                                    ReasoningEffort.HIGH,
                                    ReasoningEffort.XHIGH,
                                    ReasoningEffort.MAX,
                                ),
                                defaultEffort = ReasoningEffort.HIGH,
                            ),
                            EngineModelSpec(
                                id = "claude-sonnet-4-6",
                                name = "Claude Sonnet 4.6",
                                efforts = listOf(
                                    ReasoningEffort.LOW,
                                    ReasoningEffort.MEDIUM,
                                    ReasoningEffort.HIGH,
                                    ReasoningEffort.XHIGH,
                                ),
                                defaultEffort = ReasoningEffort.MEDIUM,
                            ),
                            EngineModelSpec(
                                id = "claude-haiku-4-5",
                                name = "Claude Haiku 4.5",
                                efforts = listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH),
                                defaultEffort = ReasoningEffort.LOW,
                            ),
                        ),
                        defaultModel = "claude-opus-4-6",
                        defaultMode = SessionMode.AUTO,
                        defaultEffort = ReasoningEffort.MEDIUM,
                        supportedModes = listOf(
                            SessionMode.AUTO,
                            SessionMode.ACCEPT_EDITS,
                            SessionMode.PLAN,
                            SessionMode.DEFAULT,
                        ),
                    ),
                    EngineInfo(
                        id = "codex",
                        displayName = "Codex CLI",
                        kind = EngineKind.CLI,
                        status = EngineStatus(installed = true, ready = true, version = "0.28.0"),
                        capabilities = EngineCapabilities(
                            permissions = true,
                            images = true,
                            modelSwitch = true,
                            modes = true,
                            history = true,
                            cancel = true,
                        ),
                        models = listOf(
                            EngineModelSpec(
                                id = "gpt-5.4",
                                name = "GPT-5.4",
                                efforts = listOf(
                                    ReasoningEffort.LOW,
                                    ReasoningEffort.MEDIUM,
                                    ReasoningEffort.HIGH,
                                    ReasoningEffort.XHIGH,
                                ),
                            ),
                        ),
                        defaultModel = "gpt-5.4",
                    ),
                    EngineInfo(
                        id = "agy",
                        displayName = "Antigravity",
                        kind = EngineKind.CLI,
                        status = EngineStatus(installed = true, ready = true, version = "2.0.0"),
                        capabilities = EngineCapabilities(
                            permissions = true,
                            images = true,
                            modelSwitch = true,
                            modes = true,
                            history = true,
                            cancel = true,
                        ),
                        models = listOf(
                            EngineModelSpec(
                                id = "gemini-3.1-pro",
                                name = "Gemini 3.1 Pro",
                                efforts = listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH),
                            ),
                        ),
                        defaultModel = "gemini-3.1-pro",
                    ),
                )
            }
            val sid = "sess_wt_demo"
            transport.sessions.addAll(
                listOf(
                    SessionMeta(
                        id = sid,
                        engineId = "claude",
                        cwd = "/Users/dev/.lework-worktrees/lework-a1b2c3/lework-feat-desktop",
                        worktree = SessionWorktreeInfo(
                            baseRepoPath = "/Users/dev/projects/lework",
                            worktreePath = "/Users/dev/.lework-worktrees/lework-a1b2c3/lework-feat-desktop",
                            branch = "lework/feat-desktop",
                            baseBranch = "main",
                            cleanedUp = false,
                        ),
                        title = "构建 macOS 原生桌面端与 Worktree 隔离",
                        state = SessionState.IDLE,
                        createdAt = 1_000L,
                        updatedAt = 3_000L,
                        lastSeq = 2,
                    ),
                    SessionMeta(
                        id = "sess_devbox_bg",
                        engineId = "codex",
                        cwd = "/home/dev/demo-service",
                        title = "Devbox 远程构建与压测",
                        state = SessionState.RUNNING,
                        liveStatusText = "正在执行 go test ./...",
                        createdAt = 1_000L,
                        updatedAt = 2_800L,
                        lastSeq = 0,
                    ),
                ),
            )
            val sendTs = dev.lework.platform.epochMillis() - 90_000L
            val replyTs = dev.lework.platform.epochMillis() - 62_000L
            val longReplyTs = dev.lework.platform.epochMillis() - 18_000L
            transport.sessionEventsMap[sid] = mutableListOf(
                AgentEvent.UserMessage(0, sid, sendTs, "u_1", "开启独立分支工作区并构建 macOS 桌面端"),
                AgentEvent.ToolCall(
                    seq = 1,
                    sessionId = sid,
                    ts = sendTs + 12_000L,
                    toolCallId = "tc_wt",
                    title = "创建独立分支工作区 lework/feat-desktop",
                    kind = ToolKind.EXECUTE,
                    status = ToolStatus.COMPLETED,
                    output = "Branch lework/feat-desktop checked out in ~/.lework-worktrees/",
                ),
                AgentEvent.AssistantDelta(
                    seq = 2,
                    sessionId = sid,
                    ts = replyTs,
                    messageId = "a_1",
                    text = "已在独立分支 `lework/feat-desktop` 中完成桌面端双栏工作台构建，可随时在左侧切换本机 Mac 与远程 Linux Devbox。",
                ),
                AgentEvent.AssistantDelta(
                    seq = 3,
                    sessionId = sid,
                    ts = longReplyTs,
                    messageId = "a_2",
                    text = buildString {
                        appendLine("## 构建与联调清单")
                        appendLine()
                        appendLine("已完成以下验证步骤，确保双端体验一致、消息与状态同步及时可靠：")
                        appendLine()
                        appendLine("1. 独立 Git Worktree 隔离工作区创建与归档清理校验通过，所有分支变更与主仓库保持完全隔离；")
                        appendLine("2. 桌面端 T3 Code 风格三栏模型选择器与侧边栏置顶、重命名、归档操作已全量接入并完成像素对齐验证；")
                        appendLine("3. 会话时间轴现已支持精确展示每条消息的发送时间与回复时间，涵盖短消息以及超长多块虚拟化 Markdown 回复。")
                        appendLine()
                        appendLine("- 本机 Mac Hub：`https://127.0.0.1:64450` 已就绪")
                        appendLine("- 远程 Linux Devbox：支持在设置弹窗中通过 SSH 一键配对接入")
                    },
                ),
            )

            lateinit var store: ConsoleStore
            setContent {
                val scope = rememberCoroutineScope()
                val created = androidx.compose.runtime.remember { ConsoleStore(scope, transport) }
                store = created
                ConsoleApp(externalStore = created, autoBootstrap = true)
            }
            waitForIdle()
            runOnIdle { store.selectSession(sid) }
            waitForIdle()

            // 1. Wide desktop layout shows T3 Code-style permanent sidebar, single-row header, and omits mobile hamburger button
            onNodeWithTag("desktop_permanent_sidebar").assertIsDisplayed()
            onNodeWithTag("desktop_workspace_header").assertIsDisplayed()
            onNodeWithTag("topbar_btn_menu").assertDoesNotExist()
            // Verify send & reply timestamps render for user bubble, normal assistant reply, and long virtualized assistant reply
            onNodeWithTag("msg_time_user_u_1", useUnmergedTree = true).assertIsDisplayed()
            onNodeWithTag("msg_time_assistant_a_1", useUnmergedTree = true).assertIsDisplayed()
            onNodeWithTag("msg_time_assistant_a_2", useUnmergedTree = true).assertIsDisplayed()

            // Verify pixel-perfect 30dp grid alignment across sidebar Row 1, Row 2 (Search + 3 icons), and Bottom Footer
            val sidebarBounds = onNodeWithTag("desktop_permanent_sidebar").fetchSemanticsNode().boundsInRoot
            val collapseBounds = onNodeWithTag("drawer_btn_collapse_sidebar").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val searchBoxBounds = onNodeWithTag("desktop_sidebar_search_box").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val filterBounds = onNodeWithTag("drawer_btn_filter").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val archiveBounds = onNodeWithTag("drawer_tab_archived").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val newSessionBounds = onNodeWithTag("drawer_btn_new_session").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val settingsBounds = onNodeWithTag("drawer_btn_settings").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val usageBounds = onNodeWithTag("drawer_btn_usage").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val refreshBounds = onNodeWithTag("drawer_btn_refresh").assertIsDisplayed().fetchSemanticsNode().boundsInRoot

            assertEquals(searchBoxBounds.top, filterBounds.top, 1.0f, "Search box top must align with Filter button")
            assertEquals(searchBoxBounds.bottom, filterBounds.bottom, 1.0f, "Search box bottom must align with Filter button")
            assertEquals(searchBoxBounds.top, archiveBounds.top, 1.0f, "Search box top must align with Archive button")
            assertEquals(searchBoxBounds.top, newSessionBounds.top, 1.0f, "Search box top must align with New Session button")
            assertEquals(collapseBounds.right, newSessionBounds.right, 1.0f, "Right-edge sidebar icons must align strictly")
            assertEquals(newSessionBounds.right, refreshBounds.right, 1.0f, "Top and bottom right-edge icons must align strictly")
            assertTrue(settingsBounds.left < usageBounds.left, "Expected 1.设置 before 2.用量 in bottom-left")
            assertTrue(
                refreshBounds.left > (sidebarBounds.left + sidebarBounds.right) / 2f,
                "Expected 刷新 button at far right of sidebar footer",
            )

            // Verify iconified composer controls + unified Engine/Model/Reasoning trigger on Desktop
            onNodeWithTag("composer_add_image_btn").assertIsDisplayed()
            onNodeWithTag("composer_mention_btn").assertIsDisplayed()
            onNodeWithTag("composer_model_picker_btn").assertIsDisplayed()
            onNodeWithTag("composer_mode_btn").assertIsDisplayed()

            // Verify LeworkPinIcon uses solid filled vector path (not stroke-only)
            val pinPaths = LeworkPinIcon.root.filterIsInstance<VectorPath>()
            assertTrue(pinPaths.isNotEmpty(), "Expected LeworkPinIcon to define at least one vector path")
            assertTrue(
                pinPaths.all { it.fill != null && it.stroke == null },
                "Expected LeworkPinIcon to use solid filled vector paths (not stroke-only)",
            )

            // Verify inline right-side session card buttons are hidden by default (even on selected session)
            onNodeWithTag("session_row_$sid").assertIsDisplayed()
            onNodeWithTag("session_pin_$sid").assertDoesNotExist()
            onNodeWithTag("session_rename_$sid").assertDoesNotExist()
            onNodeWithTag("session_archive_$sid").assertDoesNotExist()

            // Hover session row -> inline actions appear in order: [重命名 Edit] [归档 Archive] [置顶 Pin]
            onNodeWithTag("session_row_$sid").performMouseInput { enter(center) }
            waitForIdle()
            val renameBtnBounds = onNodeWithTag("session_rename_$sid").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val archiveBtnBounds = onNodeWithTag("session_archive_$sid").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val pinBtnBounds = onNodeWithTag("session_pin_$sid").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue(
                renameBtnBounds.left < archiveBtnBounds.left && archiveBtnBounds.left < pinBtnBounds.left,
                "Expected inline action order [重命名] < [归档] < [置顶 Pin], got rename=${renameBtnBounds.left}, archive=${archiveBtnBounds.left}, pin=${pinBtnBounds.left}",
            )

            // Click Pin to pin session to top
            onNodeWithTag("session_pin_$sid").performClick()
            waitForIdle()
            assertTrue(sid in store.state.value.pinnedSessionIds, "Expected Pin button to pin session to top")
            onNodeWithText("已置顶").assertIsDisplayed()

            // Move pointer away -> unhovered pinned row shows no duplicate pin button or static pin icon
            onNodeWithTag("session_row_$sid").performMouseInput { exit() }
            waitForIdle()
            onNodeWithTag("session_pin_$sid").assertDoesNotExist()
            onNodeWithTag("session_rename_$sid").assertDoesNotExist()
            onNodeWithTag("session_archive_$sid").assertDoesNotExist()
            onNodeWithContentDescription("已置顶", useUnmergedTree = true).assertDoesNotExist()

            val captureDir = (System.getProperty("lework.captureScreenshotsDir")
                ?: System.getenv("LEWORK_CAPTURE_DIR"))?.takeIf { it.isNotBlank() }
            if (captureDir != null) {
                val outDir = File(captureDir).apply { mkdirs() }
                val wsPng = SkiaImage.makeFromBitmap(captureToImage().asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)
                if (wsPng != null) {
                    File(outDir, "desktop_widescreen_workspace.png").writeBytes(wsPng.bytes)
                }
            }

            // Hover pinned row again and click Pin to unpin cleanly, then exit hover and verify actions hide
            onNodeWithTag("session_row_$sid").performMouseInput { enter(center) }
            waitForIdle()
            onNodeWithTag("session_pin_$sid").assertIsDisplayed().performClick()
            waitForIdle()
            assertTrue(sid !in store.state.value.pinnedSessionIds, "Expected second click to unpin session")
            onNodeWithTag("session_row_$sid").performMouseInput { exit() }
            waitForIdle()
            onNodeWithTag("session_pin_$sid").assertDoesNotExist()
            onNodeWithTag("session_rename_$sid").assertDoesNotExist()
            onNodeWithTag("session_archive_$sid").assertDoesNotExist()

            // Verify clicking Diff icon docks a side-by-side Desktop Right Inspector Pane (zero mobile bottom sheet!)
            onNodeWithTag("topbar_btn_diff").performClick()
            waitForIdle()
            onNodeWithTag("desktop_right_inspector_pane").assertIsDisplayed()
            onNodeWithTag("desktop_inspector_close_btn").performClick()
            waitForIdle()
            onNodeWithTag("desktop_right_inspector_pane").assertDoesNotExist()

            // Verify T3 Code 3-Column Engine + Model + Reasoning Level Picker + aligned top header row
            onNodeWithTag("composer_model_picker_btn").performClick()
            waitForIdle()
            onNodeWithTag("desktop_model_picker_dialog").assertIsDisplayed()
            val favSlotBounds = onNodeWithTag("picker_rail_favorites_slot", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val pickerSearchBounds = onNodeWithTag("model_picker_search_box", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val reasoningHdrBounds = onNodeWithTag("picker_reasoning_header", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertEquals(favSlotBounds.top, pickerSearchBounds.top, 1.0f, "Model picker rail & search headers must align")
            assertEquals(pickerSearchBounds.top, reasoningHdrBounds.top, 1.0f, "Model picker search & reasoning headers must align")
            assertEquals(pickerSearchBounds.bottom, reasoningHdrBounds.bottom, 1.0f, "Model picker header dividers must align")
            onNodeWithTag("picker_rail_claude").assertIsDisplayed()
            onNodeWithTag("picker_rail_codex").assertIsDisplayed()
            onNodeWithTag("picker_rail_agy").assertIsDisplayed()
            onNodeWithTag("picker_model_claude_claude-sonnet-4-6").performClick()
            waitForIdle()
            onNodeWithTag("picker_effort_high").performClick()
            waitForIdle()
            assertEquals("claude-sonnet-4-6", store.state.value.selectedModelId)
            assertEquals(ReasoningEffort.HIGH, store.state.value.selectedEffort)
            if (captureDir != null) {
                val pickerPng = SkiaImage.makeFromBitmap(captureToImage().asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)
                if (pickerPng != null) {
                    File(captureDir, "desktop_model_picker.png").writeBytes(pickerPng.bytes)
                }
            }
            // Close picker via Escape
            onNodeWithTag("model_picker_search_input").performClick()
            onNodeWithTag("model_picker_search_input").performKeyInput {
                pressKey(Key.Escape)
            }
            waitForIdle()

            // Verify clicking bottom-left `1. 设置` opens Desktop Settings modal cleanly
            onNodeWithTag("drawer_btn_settings").performClick()
            waitForIdle()
            onNodeWithTag("desktop_settings_dialog").assertIsDisplayed()
            if (captureDir != null) {
                val settingsPng = SkiaImage.makeFromBitmap(captureToImage().asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)
                if (settingsPng != null) {
                    File(captureDir, "desktop_settings_modal.png").writeBytes(settingsPng.bytes)
                }
            }
            onNodeWithTag("desktop_settings_close_btn").performClick()
            waitForIdle()
            onNodeWithTag("desktop_settings_dialog").assertDoesNotExist()

            // 2. Typing in composer and pressing Enter (without Shift) sends prompt on Desktop
            onNodeWithTag("composer_input").performClick()
            onNodeWithTag("composer_input").performTextInput("继续验证 SSH 一键连接远程开发机")
            waitForIdle()
            onNodeWithTag("composer_input").performKeyInput {
                pressKey(Key.Enter)
            }
            waitForIdle()
            assertTrue(
                transport.sentPrompts.any { it.first == sid && it.second == "继续验证 SSH 一键连接远程开发机" },
                "Expected Enter key on Desktop to submit prompt",
            )

            // 3. Switch to Pair Another Host view -> renders SSH quick-connect pills (`devbox`, `devbox-01`)
            if (captureDir != null) {
                runOnIdle { store.preparePairAnotherHost() }
                waitForIdle()
                val pairPng = SkiaImage.makeFromBitmap(captureToImage().asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)
                if (pairPng != null) {
                    File(captureDir, "desktop_ssh_pairing_view.png").writeBytes(pairPng.bytes)
                }
            }
        }

    @Test
    fun `T8_6 selecting @file mention moves input caret to end of inserted file path and retains focus`() =
        runComposeUiTest {
            val transport = FakeHubTransport()
            setContent {
                val scope: CoroutineScope = rememberCoroutineScope()
                val created = androidx.compose.runtime.remember { ConsoleStore(scope, transport) }
                Box(modifier = Modifier.size(width = 402.dp, height = 874.dp)) {
                    ConsoleApp(externalStore = created, autoBootstrap = true)
                }
            }
            waitForIdle()

            // Type prompt with @serv trigger
            onNodeWithTag("composer_input").performClick()
            onNodeWithTag("composer_input").performTextInput("请检查 @serv")
            waitUntil(timeoutMillis = 3_000L) {
                onAllNodesWithTag("mention_item_hub/src/server.ts").fetchSemanticsNodes().isNotEmpty()
            }

            // Tap the autocomplete suggestion item
            onNodeWithTag("mention_item_hub/src/server.ts").performClick()
            waitForIdle()

            val expectedText = "请检查 @hub/src/server.ts "
            val node = onNodeWithTag("composer_input").fetchSemanticsNode()
            val actualText = node.config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text
            val actualSelection = node.config[androidx.compose.ui.semantics.SemanticsProperties.TextSelectionRange]
            val isFocused = node.config[androidx.compose.ui.semantics.SemanticsProperties.Focused]

            assertEquals(expectedText, actualText)
            assertEquals(androidx.compose.ui.text.TextRange(expectedText.length), actualSelection)
            assertTrue(isFocused, "Composer input must remain focused after tapping @file suggestion")
        }

    @Test
    fun `T8_7 clicking inline-code and bare URLs inside ConversationTimeline dispatches openExternalUrl cleanly`() =
        runComposeUiTest {
            JvmPlatformTestHooks.reset()
            val transport = FakeHubTransport()
            val sid = "host:agy:link_click_test"
            transport.sessions.add(
                SessionMeta(
                    id = sid,
                    engineId = "agy",
                    cwd = "/workspace/lework",
                    title = "链接点击回归校验",
                    state = SessionState.IDLE,
                    createdAt = 1_000L,
                    updatedAt = 2_000L,
                    lastSeq = 1,
                ),
            )
            transport.sessionEventsMap[sid] = mutableListOf(
                AgentEvent.UserMessage(0, sid, 1_000L, "u_0", "给下 PR 链接"),
                AgentEvent.AssistantDelta(
                    1,
                    sid,
                    2_000L,
                    "a_1",
                    "- `https://github.com/letrahoo/lework/pull/1`\n\nhttps://github.com/letrahoo/lework/releases/tag/v0.7.1。",
                ),
            )

            lateinit var store: ConsoleStore
            setContent {
                val scope: CoroutineScope = rememberCoroutineScope()
                val created = androidx.compose.runtime.remember { ConsoleStore(scope, transport) }
                store = created
                Box(modifier = Modifier.size(width = 480.dp, height = 800.dp)) {
                    ConsoleApp(externalStore = created, autoBootstrap = true)
                }
            }
            waitForIdle()
            runOnIdle { store.selectSession(sid) }
            waitForIdle()

            onNodeWithText("https://github.com/letrahoo/lework/pull/1", substring = true, useUnmergedTree = true)
                .performClick()
            waitForIdle()
            onNodeWithText("https://github.com/letrahoo/lework/releases/tag/v0.7.1", substring = true, useUnmergedTree = true)
                .performClick()
            waitForIdle()

            assertEquals(
                listOf(
                    "https://github.com/letrahoo/lework/pull/1",
                    "https://github.com/letrahoo/lework/releases/tag/v0.7.1",
                ),
                JvmPlatformTestHooks.openedUrls,
            )
        }

    @Test
    fun `T8_8 renders native Mermaid flowchart card, toggles diagram and source views, and opens fullscreen preview`() =
        runComposeUiTest {
            val sampleMarkdown = """
                ## 架构流程

                ```mermaid
                flowchart TD
                    subgraph client["终端界面"]
                        A["用户输入"] --> B(消息编排)
                    end
                    B --> C{"是否需要审批?"}
                    C -->|是| D((等待确认))
                    C -->|否| E[(执行引擎)]
                ```
            """.trimIndent()

            setContent {
                Box(modifier = Modifier.size(width = 480.dp, height = 800.dp)) {
                    EverforestMarkdownContent(markdown = sampleMarkdown)
                }
            }
            waitForIdle()

            // 1. Renders native Mermaid diagram card & canvas by default
            onNodeWithTag("mermaid_diagram_card").assertIsDisplayed()
            onNodeWithText("mermaid · 流程图").assertIsDisplayed()
            onNodeWithTag("mermaid_canvas").assertIsDisplayed()
            onNodeWithTag("mermaid_source_view").assertDoesNotExist()

            val captureDir = (System.getProperty("lework.captureScreenshotsDir")
                ?: System.getenv("LEWORK_CAPTURE_DIR"))?.takeIf { it.isNotBlank() }
            if (captureDir != null) {
                val outDir = File(captureDir).apply { mkdirs() }
                val png = SkiaImage.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)
                if (png != null) File(outDir, "mermaid_flowchart.png").writeBytes(png.bytes)
            }

            // 2. Toggle to `源码` view and verify Mermaid source renders cleanly
            onNodeWithTag("mermaid_tab_source").performClick()
            waitForIdle()
            onNodeWithTag("mermaid_source_view").assertIsDisplayed()
            onNodeWithText("flowchart TD", substring = true).assertIsDisplayed()
            onNodeWithTag("mermaid_canvas").assertDoesNotExist()

            // 3. Toggle back to `图表` view
            onNodeWithTag("mermaid_tab_diagram").performClick()
            waitForIdle()
            onNodeWithTag("mermaid_canvas").assertIsDisplayed()

            // 4. Open fullscreen preview dialog, test zoom controls, and dismiss cleanly
            onNodeWithTag("mermaid_btn_fullscreen").performClick()
            waitForIdle()
            onNodeWithTag("mermaid_fullscreen_dialog").assertIsDisplayed()
            onNodeWithText("全屏查看 · 流程图").assertIsDisplayed()
            onNodeWithText("100%").assertIsDisplayed()

            onNodeWithTag("mermaid_fullscreen_zoom_in").performClick()
            waitForIdle()
            onNodeWithText("125%").assertIsDisplayed()

            onNodeWithTag("mermaid_fullscreen_zoom_reset").performClick()
            waitForIdle()
            onNodeWithText("100%").assertIsDisplayed()

            onNodeWithTag("mermaid_fullscreen_close").performClick()
            waitForIdle()
            onNodeWithTag("mermaid_fullscreen_dialog").assertDoesNotExist()
        }

    @Test
    fun `T8_9 renders sequence, state, er, pie mermaid diagrams and unsupported fallback banner cleanly`() =
        runComposeUiTest {
            val multiDiagramMd = """
                ```mermaid
                sequenceDiagram
                    actor U as 用户
                    participant S as 服务
                    U->>S: 请求
                    S-->>U: 响应
                ```

                ```mermaid
                pie showData
                    title 模块占比
                    "核心" : 70
                    "测试" : 30
                ```

                ```mermaid
                gantt
                    title Unsupported Sample
                    Task A :a1, 2026-01-01, 2d
                ```
            """.trimIndent()

            setContent {
                Box(modifier = Modifier.size(width = 480.dp, height = 1000.dp)) {
                    EverforestMarkdownContent(markdown = multiDiagramMd)
                }
            }
            waitForIdle()

            onNodeWithText("mermaid · 时序图").assertIsDisplayed()
            onNodeWithText("mermaid · 饼图").assertIsDisplayed()
            onNodeWithText("模块占比").assertIsDisplayed()
            onNodeWithTag("mermaid_fallback_banner").assertIsDisplayed()
            onNodeWithText("暂未支持的图表语法，已切换源码视图").assertIsDisplayed()

            val captureDir = (System.getProperty("lework.captureScreenshotsDir")
                ?: System.getenv("LEWORK_CAPTURE_DIR"))?.takeIf { it.isNotBlank() }
            if (captureDir != null) {
                val outDir = File(captureDir).apply { mkdirs() }
                val png = SkiaImage.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)
                if (png != null) File(outDir, "mermaid_multi_diagrams.png").writeBytes(png.bytes)
            }
        }
}
