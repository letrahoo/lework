package dev.lework

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import dev.lework.platform.JvmPlatformTestHooks
import dev.lework.protocol.*
import dev.lework.store.*
import dev.lework.ui.ConsoleApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
class PerSessionDraftAndFilePreviewTest {

    private val tinyPngB64 =
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="

    @BeforeTest
    fun setUp() {
        JvmPlatformTestHooks.reset()
    }

    @AfterTest
    fun tearDown() {
        JvmPlatformTestHooks.reset()
    }

    private fun twoTestSessions(): List<SessionMeta> = listOf(
        SessionMeta(
            id = "sess_alpha",
            engineId = "claude",
            cwd = "/Users/dev/lework",
            title = "会话 Alpha",
            state = SessionState.IDLE,
            createdAt = 1_000L,
            updatedAt = 2_000L,
            lastSeq = 2,
        ),
        SessionMeta(
            id = "sess_beta",
            engineId = "claude",
            cwd = "/Users/dev/lework",
            title = "会话 Beta",
            state = SessionState.IDLE,
            createdAt = 1_000L,
            updatedAt = 1_500L,
            lastSeq = 1,
        ),
    )

    @Test
    fun `parseLocalFileLink handles file URIs, UTF-8 percent encoding, home paths, relative paths, and line ranges`() {
        val rgo = parseLocalFileLink("file:///Users/bytedance/.agent-memory/concepts/entries/internal-infra/rgo.md")
        assertEquals(
            ParsedLocalFileLink("/Users/bytedance/.agent-memory/concepts/entries/internal-infra/rgo.md"),
            rgo,
        )

        val encodedZh = parseLocalFileLink("file://localhost/Users/bytedance/%E6%80%BB%E8%A7%88%E7%9C%8B%E6%9D%BF.html")
        assertEquals(
            ParsedLocalFileLink("/Users/bytedance/总览看板.html"),
            encodedZh,
        )

        val relRange = parseLocalFileLink("app/shared/src/commonMain/kotlin/dev/lework/store/ConsoleStore.kt#L25-L40")
        assertEquals(
            ParsedLocalFileLink(
                path = "app/shared/src/commonMain/kotlin/dev/lework/store/ConsoleStore.kt",
                startLine = 25,
                endLine = 40,
            ),
            relRange,
        )

        val colonRange = parseLocalFileLink("/Users/dev/lework/README.md:12-18")
        assertEquals(
            ParsedLocalFileLink(path = "/Users/dev/lework/README.md", startLine = 12, endLine = 18),
            colonRange,
        )

        val homePath = parseLocalFileLink("~/.agent-memory/concepts/OVERVIEW.md")
        assertEquals(ParsedLocalFileLink("~/.agent-memory/concepts/OVERVIEW.md"), homePath)

        // External / unsafe / pure anchor links must never match as local files
        assertNull(parseLocalFileLink("https://github.com/letrahoo/lework"))
        assertNull(parseLocalFileLink("http://127.0.0.1:64450"))
        assertNull(parseLocalFileLink("mailto:dev@example.com"))
        assertNull(parseLocalFileLink("javascript:alert(1)"))
        assertNull(parseLocalFileLink("file://evil-host.example/share/secret.txt"))
        assertNull(parseLocalFileLink("#section-only"))
    }

    @Test
    fun `resolveLocalFilePath normalizes relative traversal and preserves home shortcuts`() {
        assertEquals(
            "/Users/dev/lework/docs/notes.md",
            resolveLocalFilePath("./docs/notes.md", "/Users/dev/lework"),
        )
        assertEquals(
            "/Users/bytedance/.agent-memory/concepts/OVERVIEW.md",
            resolveLocalFilePath(
                "../../OVERVIEW.md",
                "/Users/bytedance/.agent-memory/concepts/entries/internal-infra",
            ),
        )
        assertEquals(
            "~/.agent-memory/concepts/entries/rgo.md",
            resolveLocalFilePath("~/.agent-memory/concepts/./entries/rgo.md", "/Users/dev/lework"),
        )
    }

    @Test
    fun `extractReadablePreviewFromHtml strips scripts and renders headings and links cleanly`() {
        val html = """
            <!doctype html>
            <html>
              <head><title>概念记忆看板</title><style>body{color:red}</style></head>
              <body>
                <script>window.evil = true;</script>
                <h1>总览看板</h1>
                <p>查看概念卡片 <a href="entries/rgo.md">rgo.md</a></p>
              </body>
            </html>
        """.trimIndent()
        val preview = extractReadablePreviewFromHtml(html)
        assertTrue(preview.contains("# 概念记忆看板"))
        assertTrue(preview.contains("# 总览看板"))
        assertTrue(preview.contains("[rgo.md](entries/rgo.md)"))
        assertFalse(preview.contains("window.evil"))
        assertFalse(preview.contains("color:red"))
    }

    @Test
    fun `per-session drafts and attached images remain completely isolated across sessions and new draft`() = runTest {
        val transport = FakeHubTransport().apply {
            sessions.clear()
            sessions.addAll(twoTestSessions())
        }
        val storeScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val store = ConsoleStore(storeScope, transport)
            store.bootstrapOrConnect()
            runCurrent()

            // Draft on New Session (activeSessionId == null)
            store.startNewDraftSession()
            store.updateSessionDraft("新会话草稿内容")
            assertEquals("新会话草稿内容", store.state.value.activeDraftText)
            assertTrue(store.state.value.hasDraft(null))

            // Switch to Session Alpha -> starts empty, does NOT leak new session draft
            store.selectSession("sess_alpha")
            runCurrent()
            assertEquals("", store.state.value.activeDraftText)
            store.updateSessionDraft("Alpha 待发消息")
            store.attachImage(
                PromptImageAttachment(
                    mimeType = "image/png",
                    dataBase64 = tinyPngB64,
                    fileName = "alpha_only.png",
                ),
            )
            assertEquals("Alpha 待发消息", store.state.value.draftTextFor("sess_alpha"))
            assertEquals(1, store.state.value.composerAttachedImages.size)

            // Switch to Session Beta -> starts empty, does NOT see Alpha's text or image
            store.selectSession("sess_beta")
            runCurrent()
            assertEquals("", store.state.value.activeDraftText)
            assertTrue(store.state.value.composerAttachedImages.isEmpty())
            store.updateSessionDraft("Beta 自己的草稿")

            // Switch back to Alpha -> restores Alpha's text & image intact
            store.selectSession("sess_alpha")
            runCurrent()
            assertEquals("Alpha 待发消息", store.state.value.activeDraftText)
            assertEquals(1, store.state.value.composerAttachedImages.size)
            assertEquals("alpha_only.png", store.state.value.composerAttachedImages.first().fileName)

            // Sending in Alpha clears ONLY Alpha's draft & image, leaving Beta & New Session intact
            store.sendPrompt(store.state.value.activeDraftText)
            runCurrent()
            assertEquals("", store.state.value.draftTextFor("sess_alpha"))
            assertFalse(store.state.value.hasDraft("sess_alpha"))
            assertEquals("Beta 自己的草稿", store.state.value.draftTextFor("sess_beta"))
            assertEquals("新会话草稿内容", store.state.value.draftTextFor(null))
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun `desktop sidebar displays independent per-session draft badges and new-session draft row`() =
        runComposeUiTest {
            JvmPlatformTestHooks.simulateDesktopLayout = true
            val transport = FakeHubTransport().apply {
                sessions.clear()
                sessions.addAll(twoTestSessions())
            }
            lateinit var store: ConsoleStore

            setContent {
                val scope = rememberCoroutineScope()
                val created = androidx.compose.runtime.remember { ConsoleStore(scope, transport) }
                store = created
                Box(modifier = Modifier.size(width = 1280.dp, height = 820.dp)) {
                    ConsoleApp(externalStore = created, autoBootstrap = true)
                }
            }

            waitUntil(timeoutMillis = 5_000L) { store.state.value.hasEnteredWorkspace }
            runOnIdle {
                store.selectSession("sess_alpha")
            }
            waitUntil(timeoutMillis = 5_000L) { store.state.value.activeSessionId == "sess_alpha" }

            // Type unsent text into Session Alpha
            onNodeWithTag("composer_input").performTextInput("Alpha 未发送想法")
            waitUntil(timeoutMillis = 3_000L) {
                store.state.value.draftTextFor("sess_alpha") == "Alpha 未发送想法"
            }

            // Switch to Session Beta -> composer must be empty, and Alpha row shows [草稿] badge & snippet
            onNodeWithTag("session_row_sess_beta").performClick()
            waitUntil(timeoutMillis = 5_000L) { store.state.value.activeSessionId == "sess_beta" }

            onNodeWithTag("session_draft_badge_sess_alpha", useUnmergedTree = true).assertIsDisplayed()
            onNodeWithTag("session_draft_preview_sess_alpha", useUnmergedTree = true)
                .assertTextContains("Alpha 未发送想法", substring = true)
            assertEquals("", store.state.value.activeDraftText)

            // Type unsent text into New Session, switch back to Beta -> top new-session draft row appears
            onNodeWithTag("drawer_btn_new_session").performClick()
            waitUntil(timeoutMillis = 3_000L) { store.state.value.activeSessionId == null }
            onNodeWithTag("composer_input").performTextInput("全新任务草稿")
            waitUntil(timeoutMillis = 3_000L) { store.state.value.draftTextFor(null) == "全新任务草稿" }

            onNodeWithTag("session_row_sess_beta").performClick()
            waitUntil(timeoutMillis = 3_000L) { store.state.value.activeSessionId == "sess_beta" }
            onNodeWithTag("session_row_new_draft").assertIsDisplayed()

            // Click back to Session Alpha -> exact unsent draft restored
            onNodeWithTag("session_row_sess_alpha").performClick()
            waitUntil(timeoutMillis = 3_000L) { store.state.value.activeSessionId == "sess_alpha" }
            assertEquals("Alpha 未发送想法", store.state.value.activeDraftText)
        }

    @Test
    fun `desktop previews concept memory markdown, html dashboard with companion OVERVIEW md, nested link stack, and code line highlights`() =
        runComposeUiTest {
            JvmPlatformTestHooks.simulateDesktopLayout = true
            JvmPlatformTestHooks.canOpenLocalFiles = true

            val rgoPath = "/Users/bytedance/.agent-memory/concepts/entries/internal-infra/rgo.md"
            val overviewHtmlPath = "/Users/bytedance/.agent-memory/concepts/overview.html"
            val overviewMdPath = "/Users/bytedance/.agent-memory/concepts/OVERVIEW.md"
            val codePath = "/Users/dev/lework/app/Sample.kt"

            val transport = FakeHubTransport().apply {
                sessions.clear()
                sessions.addAll(twoTestSessions())
                sessionEventsMap["sess_alpha"] = mutableListOf(
                    AgentEvent.UserMessage(
                        seq = 1,
                        sessionId = "sess_alpha",
                        ts = 1_000L,
                        messageId = "u_1",
                        text = "帮我查一下 RGO",
                    ),
                    AgentEvent.AssistantDelta(
                        seq = 2,
                        sessionId = "sess_alpha",
                        ts = 1_100L,
                        messageId = "a_1",
                        text = "这是 RGO 概念总结。\n\n" +
                            "*已自动沉淀至 Concept Memory (公司内部 · 工程基建)：" +
                            "[rgo.md](file://$rgoPath) · [总览看板](file://$overviewHtmlPath)*",
                    ),
                )
                readFileHandler = { reqPath ->
                    when (reqPath) {
                        rgoPath -> FsReadResult(
                            path = rgoPath,
                            content = "# RGO 资源治理编排\n\n这里是完整的 Concept Memory 卡片详情。",
                            truncated = false,
                        )
                        overviewHtmlPath -> FsReadResult(
                            path = overviewHtmlPath,
                            content = "<!doctype html><html><body><h1>Interactive Dashboard</h1></body></html>",
                            truncated = false,
                        )
                        overviewMdPath -> FsReadResult(
                            path = overviewMdPath,
                            content = "# Concept Memory 总览\n\n- [查看 rgo.md 卡片](entries/internal-infra/rgo.md)",
                            truncated = false,
                        )
                        codePath -> FsReadResult(
                            path = codePath,
                            content = (1..40).joinToString("\n") { lineNo -> "val line$lineNo = $lineNo" },
                            truncated = false,
                        )
                        else -> error("File not found: $reqPath")
                    }
                }
            }
            lateinit var store: ConsoleStore

            setContent {
                val scope = rememberCoroutineScope()
                val created = androidx.compose.runtime.remember { ConsoleStore(scope, transport) }
                store = created
                Box(modifier = Modifier.size(width = 1280.dp, height = 820.dp)) {
                    ConsoleApp(externalStore = created, autoBootstrap = true)
                }
            }

            waitUntil(timeoutMillis = 5_000L) { store.state.value.hasEnteredWorkspace }
            runOnIdle {
                store.selectSession("sess_alpha")
            }
            waitUntil(timeoutMillis = 5_000L) {
                store.state.value.activeTranscript.items.any { it is ChatTimelineItem.Assistant }
            }

            // 1. Verify italic Concept Memory Markdown attaches clickable local file LinkAnnotations
            val receiptMarkdown = "*已自动沉淀至 Concept Memory (公司内部 · 工程基建)：" +
                "[rgo.md](file://$rgoPath) · [总览看板](file://$overviewHtmlPath)*"
            val clickedTargets = mutableListOf<String>()
            val parsedInline = dev.lework.ui.parseEverforestInline(
                text = receiptMarkdown,
                onFileLinkClick = { clickedTargets.add(it) },
            )
            val linkAnnotations = parsedInline.getLinkAnnotations(0, parsedInline.length)
            assertEquals(2, linkAnnotations.size)
            linkAnnotations.forEach { range ->
                val clickable = range.item as androidx.compose.ui.text.LinkAnnotation.Clickable
                clickable.linkInteractionListener?.onClick(clickable)
            }
            assertEquals(listOf("file://$rgoPath", "file://$overviewHtmlPath"), clickedTargets)

            // 2. Open Concept Memory `rgo.md` link -> renders in Desktop preview modal
            runOnIdle { store.openLinkedFilePreview("file://$rgoPath") }
            waitUntil(timeoutMillis = 5_000L) {
                store.state.value.activeFilePreview?.loading == false
            }
            onNodeWithTag("desktop_file_preview_dialog").assertIsDisplayed()
            onNodeWithTag("file_preview_title", useUnmergedTree = true).assertTextEquals("rgo.md")
            onNodeWithText("RGO 资源治理编排", substring = true, useUnmergedTree = true).assertIsDisplayed()

            // 3. Open `overview.html` -> loads companion `OVERVIEW.md` in 看板预览 mode & supports external browser open
            runOnIdle { store.openLinkedFilePreview("file://$overviewHtmlPath") }
            waitUntil(timeoutMillis = 5_000L) {
                val p = store.state.value.activeFilePreview
                p != null && !p.loading && p.resolvedPath == overviewHtmlPath
            }
            onNodeWithTag("file_preview_tab_rendered", useUnmergedTree = true).assertIsDisplayed()
            onNodeWithTag("file_preview_tab_source", useUnmergedTree = true).assertIsDisplayed()
            onNodeWithText("Concept Memory 总览", substring = true, useUnmergedTree = true).assertIsDisplayed()

            // External open button opens local HTML file cleanly
            onNodeWithTag("file_preview_open_external_btn", useUnmergedTree = true).performClick()
            assertEquals(listOf(overviewHtmlPath), JvmPlatformTestHooks.openedLocalFiles)

            // Switch to HTML source tab
            onNodeWithTag("file_preview_tab_source", useUnmergedTree = true).performClick()
            onNodeWithTag("file_preview_code_list", useUnmergedTree = true).assertIsDisplayed()

            // 4. Nested link navigation from OVERVIEW.md -> rgo.md and Back button pops stack
            runOnIdle {
                store.openLinkedFilePreview(
                    rawTarget = "entries/internal-infra/rgo.md",
                    baseDirectory = "/Users/bytedance/.agent-memory/concepts",
                    pushToStack = true,
                )
            }
            waitUntil(timeoutMillis = 5_000L) {
                val p = store.state.value.activeFilePreview
                p != null && !p.loading && p.resolvedPath == rgoPath
            }
            assertTrue(store.state.value.canNavigateBackFilePreview)
            onNodeWithTag("file_preview_back_btn", useUnmergedTree = true).performClick()
            waitUntil(timeoutMillis = 3_000L) {
                store.state.value.activeFilePreview?.resolvedPath == overviewHtmlPath
            }

            // 5. Open source file with line anchor #L25-L28 -> shows line range badge & code preview
            runOnIdle { store.openLinkedFilePreview("app/Sample.kt#L25-L28") }
            waitUntil(timeoutMillis = 5_000L) {
                val p = store.state.value.activeFilePreview
                p != null && !p.loading && p.resolvedPath == codePath
            }
            val codeEntry = store.state.value.activeFilePreview
            assertNotNull(codeEntry)
            assertEquals(25, codeEntry.startLine)
            assertEquals(28, codeEntry.endLine)
            onNodeWithText("L25–L28", useUnmergedTree = true).assertIsDisplayed()

            // 6. Close preview modal
            onNodeWithTag("file_preview_close_btn", useUnmergedTree = true).performClick()
            waitUntil(timeoutMillis = 3_000L) { store.state.value.activeFilePreview == null }
        }

    @Test
    fun `mobile composer isolates drafts per session and shows draft badge in session drawer`() =
        runComposeUiTest {
            JvmPlatformTestHooks.simulateDesktopLayout = false
            val transport = FakeHubTransport().apply {
                sessions.clear()
                sessions.addAll(twoTestSessions())
            }
            lateinit var store: ConsoleStore

            setContent {
                val scope = rememberCoroutineScope()
                val created = androidx.compose.runtime.remember { ConsoleStore(scope, transport) }
                store = created
                Box(modifier = Modifier.size(width = 402.dp, height = 874.dp)) {
                    ConsoleApp(externalStore = created, autoBootstrap = true)
                }
            }

            waitUntil(timeoutMillis = 5_000L) { store.state.value.hasEnteredWorkspace }
            runOnIdle { store.selectSession("sess_alpha") }
            waitUntil(timeoutMillis = 3_000L) { store.state.value.activeSessionId == "sess_alpha" }

            onNodeWithTag("composer_input").performTextInput("移动端 Alpha 草稿")
            waitUntil(timeoutMillis = 3_000L) {
                store.state.value.draftTextFor("sess_alpha") == "移动端 Alpha 草稿"
            }

            runOnIdle { store.selectSession("sess_beta") }
            waitUntil(timeoutMillis = 3_000L) { store.state.value.activeSessionId == "sess_beta" }
            assertEquals("", store.state.value.activeDraftText)
            assertEquals("移动端 Alpha 草稿", store.state.value.draftTextFor("sess_alpha"))

            // Open drawer and verify draft badge + preview appear on Alpha row
            onNodeWithTag("topbar_btn_menu").performClick()
            waitUntil(timeoutMillis = 3_000L) {
                onAllNodesWithTag("session_draft_badge_sess_alpha", useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithTag("session_draft_badge_sess_alpha", useUnmergedTree = true).assertIsDisplayed()
            onNodeWithTag("session_draft_preview_sess_alpha", useUnmergedTree = true)
                .assertTextContains("移动端 Alpha 草稿", substring = true)
        }

    @Test
    fun `desktop auto-focuses composer on session switch and supports Option+UpDown, Cmd+1-9, and Cmd+Bracket shortcuts`() =
        runComposeUiTest {
            JvmPlatformTestHooks.simulateDesktopLayout = true
            val gamma = SessionMeta(
                id = "sess_gamma",
                engineId = "claude",
                cwd = "/Users/dev/lework",
                title = "会话 Gamma",
                state = SessionState.IDLE,
                createdAt = 1_000L,
                updatedAt = 1_200L,
                lastSeq = 1,
            )
            val transport = FakeHubTransport().apply {
                sessions.clear()
                sessions.addAll(twoTestSessions() + gamma)
            }
            lateinit var store: ConsoleStore

            setContent {
                val scope = rememberCoroutineScope()
                val created = androidx.compose.runtime.remember { ConsoleStore(scope, transport) }
                store = created
                Box(modifier = Modifier.size(width = 1280.dp, height = 820.dp)) {
                    ConsoleApp(externalStore = created, autoBootstrap = true)
                }
            }

            waitUntil(timeoutMillis = 5_000L) { store.state.value.hasEnteredWorkspace }
            // Initial workspace entry auto-focuses the composer input
            waitUntil(timeoutMillis = 3_000L) {
                runCatching { onNodeWithTag("composer_input").assertIsFocused(); true }.getOrDefault(false)
            }

            // Clicking a session row switches to it and auto-focuses the composer immediately for typing
            onNodeWithTag("session_row_sess_alpha").performClick()
            waitUntil(timeoutMillis = 3_000L) { store.state.value.activeSessionId == "sess_alpha" }
            waitUntil(timeoutMillis = 3_000L) {
                runCatching { onNodeWithTag("composer_input").assertIsFocused(); true }.getOrDefault(false)
            }

            // Option + ↓ moves down to sess_beta and keeps composer focused
            onNodeWithTag("composer_input").performKeyInput {
                withKeyDown(androidx.compose.ui.input.key.Key.AltLeft) {
                    pressKey(androidx.compose.ui.input.key.Key.DirectionDown)
                }
            }
            waitUntil(timeoutMillis = 3_000L) { store.state.value.activeSessionId == "sess_beta" }
            onNodeWithTag("composer_input").assertIsFocused()

            // Option + ↓ again moves to sess_gamma
            onNodeWithTag("composer_input").performKeyInput {
                withKeyDown(androidx.compose.ui.input.key.Key.AltLeft) {
                    pressKey(androidx.compose.ui.input.key.Key.DirectionDown)
                }
            }
            waitUntil(timeoutMillis = 3_000L) { store.state.value.activeSessionId == "sess_gamma" }

            // Option + ↑ moves back up to sess_beta
            onNodeWithTag("composer_input").performKeyInput {
                withKeyDown(androidx.compose.ui.input.key.Key.AltLeft) {
                    pressKey(androidx.compose.ui.input.key.Key.DirectionUp)
                }
            }
            waitUntil(timeoutMillis = 3_000L) { store.state.value.activeSessionId == "sess_beta" }

            // Cmd + 1 jumps straight to 1st session (sess_alpha)
            onNodeWithTag("composer_input").performKeyInput {
                withKeyDown(androidx.compose.ui.input.key.Key.MetaLeft) {
                    pressKey(androidx.compose.ui.input.key.Key.One)
                }
            }
            waitUntil(timeoutMillis = 3_000L) { store.state.value.activeSessionId == "sess_alpha" }
            onNodeWithTag("composer_input").assertIsFocused()

            // Cmd + 3 jumps straight to 3rd session (sess_gamma)
            onNodeWithTag("composer_input").performKeyInput {
                withKeyDown(androidx.compose.ui.input.key.Key.MetaLeft) {
                    pressKey(androidx.compose.ui.input.key.Key.Three)
                }
            }
            waitUntil(timeoutMillis = 3_000L) { store.state.value.activeSessionId == "sess_gamma" }

            // Cmd + [ navigates back in recent visit history (sess_gamma -> sess_alpha)
            onNodeWithTag("composer_input").performKeyInput {
                withKeyDown(androidx.compose.ui.input.key.Key.MetaLeft) {
                    pressKey(androidx.compose.ui.input.key.Key.LeftBracket)
                }
            }
            waitUntil(timeoutMillis = 3_000L) { store.state.value.activeSessionId == "sess_alpha" }
            onNodeWithTag("composer_input").assertIsFocused()

            // Cmd + ] navigates forward in recent visit history (sess_alpha -> sess_gamma)
            onNodeWithTag("composer_input").performKeyInput {
                withKeyDown(androidx.compose.ui.input.key.Key.MetaLeft) {
                    pressKey(androidx.compose.ui.input.key.Key.RightBracket)
                }
            }
            waitUntil(timeoutMillis = 3_000L) { store.state.value.activeSessionId == "sess_gamma" }
            onNodeWithTag("composer_input").assertIsFocused()
        }
}
