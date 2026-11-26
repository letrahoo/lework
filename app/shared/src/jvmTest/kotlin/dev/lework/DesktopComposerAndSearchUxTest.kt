package dev.lework

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.lework.platform.JvmPlatformTestHooks
import dev.lework.protocol.AgentEvent
import dev.lework.protocol.SessionMeta
import dev.lework.protocol.SessionState
import dev.lework.store.ChatTimelineItem
import dev.lework.store.ConsoleStore
import dev.lework.store.ConsoleUiState
import dev.lework.store.SessionTranscript
import dev.lework.ui.ConsoleApp
import dev.lework.ui.ConversationStreamView
import dev.lework.ui.Everforest
import dev.lework.ui.EverforestColorScheme
import dev.lework.ui.EverforestMarkdownContent
import dev.lework.ui.LocalSearchHighlightPrimary
import dev.lework.ui.LocalSearchHighlightQuery
import dev.lework.ui.applySearchTermHighlights
import dev.lework.ui.desktop.DesktopComposer
import dev.lework.ui.desktop.DesktopSearchDialog
import dev.lework.ui.desktop.insertNewlineAtSelection
import dev.lework.ui.parseEverforestInline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DesktopComposerAndSearchUxTest {

    @BeforeTest
    fun setUp() {
        JvmPlatformTestHooks.reset()
        JvmPlatformTestHooks.simulateDesktopLayout = true
    }

    @Test
    fun `insertNewlineAtSelection inserts newline at cursor and replaces active selection`() {
        val mid = insertNewlineAtSelection(TextFieldValue("hello world", TextRange(5)))
        assertEquals("hello\n world", mid.text)
        assertEquals(TextRange(6), mid.selection)

        val replaced = insertNewlineAtSelection(TextFieldValue("abcdef", TextRange(1, 4)))
        assertEquals("a\nef", replaced.text)
        assertEquals(TextRange(2), replaced.selection)
    }

    @Test
    fun `DesktopComposer Shift+Enter inserts newline without submitting while Enter submits`() = runComposeUiTest {
        var latestDraft = ""
        var sentCount = 0

        setContent {
            MaterialTheme(colorScheme = EverforestColorScheme) {
                var draft by remember { mutableStateOf("第一行") }
                latestDraft = draft
                Box(modifier = Modifier.size(680.dp, 260.dp)) {
                    DesktopComposer(
                        state = ConsoleUiState(hasEnteredWorkspace = true),
                        text = draft,
                        onTextChange = {
                            draft = it
                            latestDraft = it
                        },
                        onOpenWorkspaceDialog = {},
                        onOpenModelPicker = {},
                        onCycleMode = {},
                        onSend = {
                            sentCount++
                            draft = ""
                            latestDraft = ""
                        },
                        onCancel = {},
                        onPickImage = {},
                        onRemoveImage = {},
                        onSelectMentionFile = {},
                        onDismissMention = {},
                    )
                }
            }
        }
        waitForIdle()

        val input = onNodeWithTag("composer_input")
        input.performClick()
        waitForIdle()

        // Shift + Enter inserts a real newline instead of submitting
        input.performKeyInput {
            withKeyDown(Key.ShiftLeft) {
                pressKey(Key.Enter)
            }
        }
        waitForIdle()
        assertEquals("第一行\n", latestDraft)
        assertEquals(0, sentCount)

        // Continue typing on line 2
        input.performTextInput("第二行")
        waitForIdle()
        assertEquals("第一行\n第二行", latestDraft)
        assertEquals(0, sentCount)

        // Plain Enter submits the multiline prompt
        input.performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(1, sentCount)
        assertEquals("", latestDraft)
    }

    @Test
    fun `applySearchTermHighlights layers gold match spans over rich Markdown without losing formatting`() {
        val rich = parseEverforestInline("使用 **WAL** `Changeset` 与 Harmony 预审批链路")
        val highlighted = applySearchTermHighlights(rich, "harmony changeset", primary = true)

        assertEquals(rich.text, highlighted.text)
        val goldSpans = highlighted.spanStyles.filter { it.item.background == Everforest.BoldGold }
        assertEquals(2, goldSpans.size)
        val matchedSubstrings = goldSpans.map { highlighted.text.substring(it.start, it.end).lowercase() }.toSet()
        assertEquals(setOf("changeset", "harmony"), matchedSubstrings)
    }

    @Test
    fun `global search jump highlights search terms directly inside jumped timeline message`() = runComposeUiTest {
        val sid = "host:agy:conv_highlight"
        val session = SessionMeta(
            id = sid,
            engineId = "agy",
            cwd = "/workspace/lework",
            title = "高亮测试",
            state = SessionState.IDLE,
            createdAt = 1_000L,
            updatedAt = 3_000L,
            lastSeq = 2,
        )
        val transcript = SessionTranscript(
            earliestSeq = 1,
            lastSeq = 2,
            items = listOf(
                ChatTimelineItem.User(id = "u1", text = "检查 Spanner 同步逻辑", ts = 1_000L, seq = 1),
                ChatTimelineItem.Assistant(
                    id = "a1",
                    text = "采用 **WAL** 与 Spanner 元数据双写保证一致性。",
                    ts = 2_000L,
                    seq = 2,
                ),
            ),
        )

        setContent {
            MaterialTheme(colorScheme = EverforestColorScheme) {
                Box(modifier = Modifier.size(680.dp, 460.dp)) {
                    ConversationStreamView(
                        sessionKey = sid,
                        activeSession = session,
                        transcript = transcript,
                        isSessionRunning = false,
                        onLoadOlder = {},
                        highlightedSeq = 2,
                        highlightedQuery = "Spanner WAL",
                        keepHighlightPersistent = true,
                    )
                }
            }
        }
        waitForIdle()

        onNodeWithTag("timeline_highlighted_segment_2", useUnmergedTree = true).assertIsDisplayed()
        val assistantNode = onNodeWithText("元数据双写", substring = true, useUnmergedTree = true)
            .fetchSemanticsNode()
        val textList = assistantNode.config[SemanticsProperties.Text]
        assertTrue(
            textList.any { annotated ->
                annotated.spanStyles.count { it.item.background == Everforest.BoldGold } == 2
            },
            "Expected primary gold inline highlight spans for both WAL and Spanner on jumped message",
        )
        val userNode = onNodeWithText("同步逻辑", substring = true, useUnmergedTree = true)
            .fetchSemanticsNode()
        assertTrue(
            userNode.config[SemanticsProperties.Text].any { annotated ->
                annotated.spanStyles.any { it.item.background == Everforest.BoldGold.copy(alpha = 0.24f) }
            },
            "Expected secondary inline highlight span on surrounding matching user message",
        )
    }

    @Test
    fun `EverforestMarkdownContent highlights search terms inside table cells via CompositionLocal`() = runComposeUiTest {
        setContent {
            MaterialTheme(colorScheme = EverforestColorScheme) {
                CompositionLocalProvider(
                    LocalSearchHighlightQuery provides "Spanner",
                    LocalSearchHighlightPrimary provides true,
                ) {
                    EverforestMarkdownContent(markdown = "| 列 |\n| --- |\n| Spanner 节点 |")
                }
            }
        }
        waitForIdle()
        val cellNode = onNodeWithTag("md_table_cell_1_0", useUnmergedTree = true).fetchSemanticsNode()
        assertTrue(
            cellNode.config[SemanticsProperties.Text].any { annotated ->
                annotated.spanStyles.any { it.item.background == Everforest.BoldGold }
            },
            "Expected search term highlighting inside Markdown table cell",
        )
    }

    @Test
    fun `reopening global search auto-selects previous query so typing replaces it immediately`() = runComposeUiTest {
        var latestQuery = "Harmony"
        setContent {
            MaterialTheme(colorScheme = EverforestColorScheme) {
                DesktopSearchDialog(
                    state = ConsoleUiState(
                        hasEnteredWorkspace = true,
                        searchQuery = latestQuery,
                    ),
                    openNonce = 1,
                    onSearch = { q, _, _, _, _, _ -> latestQuery = q },
                    onJumpToHit = { _, _ -> },
                    onDismiss = {},
                )
            }
        }
        waitForIdle()

        // Previous query "Harmony" must be fully selected (0..7) upon opening
        val searchInput = onNodeWithTag("desktop_global_search_input")
        val selection = searchInput.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]
        assertEquals(TextRange(0, "Harmony".length), selection)

        // Typing immediately replaces the entire previous query instead of appending
        searchInput.performTextInput("Spanner")
        waitForIdle()
        assertEquals("Spanner", latestQuery)
    }

    @Test
    fun `jumpToSearchHit populates highlightedQuery and highlightedSeq for timeline highlighting`() = runComposeUiTest {
        val transport = FakeHubTransport()
        val sid = "host:agy:conv_reopen"
        transport.sessions.add(
            SessionMeta(
                id = sid,
                engineId = "agy",
                cwd = "/workspace/lework",
                title = "搜索全选测试",
                state = SessionState.IDLE,
                createdAt = 1_000L,
                updatedAt = 3_000L,
                lastSeq = 1,
            ),
        )
        transport.sessionEventsMap[sid] = mutableListOf(
            AgentEvent.UserMessage(0, sid, 1_000L, "u0", "旧关键词 Harmony 链路"),
            AgentEvent.AssistantDelta(1, sid, 2_000L, "a0", "新关键词 Spanner 存储"),
        )

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = ConsoleStore(scope, transport)
        store.bootstrapOrConnect()

        try {
            setContent {
                ConsoleApp(externalStore = store, autoBootstrap = false)
            }
            waitForIdle()
            store.searchConversations(query = "Spanner", immediate = true)
            waitForIdle()
            store.jumpToSearchHit(sid, 1)
            waitForIdle()
            assertEquals("Spanner", store.state.value.highlightedQuery)
            assertEquals(1, store.state.value.highlightedSeq)
        } finally {
            scope.cancel()
        }
    }
}
