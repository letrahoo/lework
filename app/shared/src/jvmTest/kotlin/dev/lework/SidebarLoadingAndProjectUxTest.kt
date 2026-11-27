package dev.lework

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.lework.platform.JvmPlatformTestHooks
import dev.lework.platform.SavedSessionPrefs
import dev.lework.protocol.SessionMeta
import dev.lework.protocol.SessionState
import dev.lework.store.ConsoleStore
import dev.lework.ui.ConsoleApp
import dev.lework.ui.buildSidebarSessionSubtitle
import dev.lework.ui.formatSidebarElapsedDuration
import dev.lework.ui.formatSidebarRelativeTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SidebarLoadingAndProjectUxTest {

    @BeforeTest
    fun setup() {
        JvmPlatformTestHooks.reset()
        JvmPlatformTestHooks.simulateDesktopLayout = true
        JvmPlatformTestHooks.savedToken = "test_token_sidebar_ux"
        JvmPlatformTestHooks.savedUserName = "Tester"
        JvmPlatformTestHooks.savedPrefs = SavedSessionPrefs(
            mode = "auto",
            sidebarGroupByProject = true,
            collapsedProjectGroupKeys = emptyList(),
        )
    }

    @AfterTest
    fun tearDown() {
        JvmPlatformTestHooks.reset()
    }

    private fun makeSession(
        id: String,
        title: String,
        cwd: String,
        state: SessionState,
        updatedAt: Long,
        engineId: String = "agy",
        liveStatusText: String? = null,
        turnStartedAt: Long? = null,
    ): SessionMeta = SessionMeta(
        id = id,
        engineId = engineId,
        cwd = cwd,
        workspaces = listOf(cwd),
        title = title,
        state = state,
        liveStatusText = liveStatusText,
        turnStartedAt = turnStartedAt,
        createdAt = updatedAt - 10_000L,
        updatedAt = updatedAt,
        lastSeq = 3,
    )

    @Test
    fun formatsElapsedDurationRelativeTimeAndActiveSubtitles() {
        val nowMs = 1_795_767_000_000L
        assertEquals("45s", formatSidebarElapsedDuration(nowMs - 45_000L, nowMs))
        assertEquals("2m 05s", formatSidebarElapsedDuration(nowMs - 125_000L, nowMs))
        assertEquals("刚刚", formatSidebarRelativeTime(nowMs - 15_000L, nowMs))
        assertEquals("12分钟前", formatSidebarRelativeTime(nowMs - 12 * 60_000L, nowMs))

        val runningPinned = makeSession(
            id = "s_run_pin",
            title = "Claude-AI-Native-lework",
            cwd = "/workspace/lework",
            state = SessionState.RUNNING,
            updatedAt = nowMs - 65_000L,
            turnStartedAt = nowMs - 65_000L,
        )
        val subtitle = buildSidebarSessionSubtitle(
            session = runningPinned,
            selected = false,
            groupedProjectDisplayName = null,
            draftPreview = null,
            nowMs = nowMs,
        ).text
        assertTrue(subtitle.contains("运行中"))
        assertTrue(subtitle.contains("1m 05s"))
        assertTrue(subtitle.contains("lework"))
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun desktopSidebarShowsDistinctLoadingIndicatorsFloatingApprovalCollapsiblePinnedAndRunningFilter() {
        val nowMs = 1_795_767_000_000L
        val transport = FakeHubTransport()
        transport.sessions.addAll(
            listOf(
                makeSession(
                    id = "s_pinned_running",
                    title = "lework-acp-核心重构",
                    cwd = "/workspace/lework",
                    state = SessionState.RUNNING,
                    updatedAt = nowMs - 30_000L,
                    turnStartedAt = nowMs - 30_000L,
                ),
                makeSession(
                    id = "s_waiting_approval",
                    title = "dotfiles-zsh-需要确认",
                    cwd = "/workspace/dotfiles",
                    state = SessionState.WAITING_PERMISSION,
                    updatedAt = nowMs - 20_000L,
                ),
                makeSession(
                    id = "s_lework_running",
                    title = "lework-sidebar-loading",
                    cwd = "/workspace/lework",
                    state = SessionState.RUNNING,
                    updatedAt = nowMs - 10_000L,
                    turnStartedAt = nowMs - 10_000L,
                ),
                makeSession(
                    id = "s_lework_idle",
                    title = "lework-docs-架构图",
                    cwd = "/workspace/lework",
                    state = SessionState.IDLE,
                    updatedAt = nowMs - 120_000L,
                    engineId = "claude",
                ),
            ),
        )
        JvmPlatformTestHooks.savedPrefs = JvmPlatformTestHooks.savedPrefs.copy(
            pinnedSessionIds = listOf("s_pinned_running"),
        )

        runComposeUiTest {
            lateinit var store: ConsoleStore
            setContent {
                val scope = rememberCoroutineScope()
                val created = remember { ConsoleStore(scope, transport) }
                store = created
                Box(modifier = Modifier.size(width = 1280.dp, height = 840.dp)) {
                    ConsoleApp(externalStore = created, autoBootstrap = true)
                }
            }
            waitUntil(timeoutMillis = 5_000L) { store.state.value.hasEnteredWorkspace }
            waitForIdle()

            // Distinct leading indicators: loading spinner for running (including pinned), waiting ring, engine glyph for idle
            onNodeWithTag("session_status_loading_s_pinned_running", useUnmergedTree = true).assertIsDisplayed()
            onNodeWithTag("session_status_waiting_s_waiting_approval", useUnmergedTree = true).assertIsDisplayed()
            onNodeWithTag("session_status_loading_s_lework_running", useUnmergedTree = true).assertIsDisplayed()
            onNodeWithTag("session_status_engine_s_lework_idle", useUnmergedTree = true).assertIsDisplayed()

            // Floating waiting section and collapsible pinned header
            onNodeWithTag("drawer_hdr_waiting").assertIsDisplayed()
            onNodeWithTag("drawer_hdr_pinned").assertIsDisplayed().performClick()
            waitForIdle()
            assertTrue(store.state.value.pinnedSectionCollapsed)
            assertEquals(0, onAllNodesWithTag("session_row_s_pinned_running").fetchSemanticsNodes().size)

            // One-click running-only filter auto-expands pinned running sessions and hides idle rows
            onNodeWithTag("drawer_btn_filter_running").assertIsDisplayed().performClick()
            waitForIdle()
            assertTrue(store.state.value.drawerOnlyRunning)
            onNodeWithTag("session_row_s_pinned_running").assertIsDisplayed()
            onNodeWithTag("session_row_s_lework_running").assertIsDisplayed()
            assertEquals(0, onAllNodesWithTag("session_row_s_lework_idle").fetchSemanticsNodes().size)

            // Toggle off running-only filter restores idle rows
            onNodeWithTag("drawer_btn_filter_running").performClick()
            waitForIdle()
            assertFalse(store.state.value.drawerOnlyRunning)
            onNodeWithTag("session_row_s_lework_idle").assertIsDisplayed()
        }
    }

    @Test
    fun activeProjectsFloatAboveNewerIdleProjectsAndOrderMatchesVisibleSidebarSessions() {
        val nowMs = 1_795_767_000_000L
        val state = dev.lework.store.ConsoleUiState(
            pinnedSessionIds = listOf("s_pin"),
            sessions = listOf(
                makeSession(
                    id = "s_pin",
                    title = "置顶任务",
                    cwd = "/workspace/gecko",
                    state = SessionState.RUNNING,
                    updatedAt = nowMs - 60_000L,
                ),
                makeSession(
                    id = "s_idle_newer",
                    title = "较新的空闲项目",
                    cwd = "/workspace/alpha",
                    state = SessionState.IDLE,
                    updatedAt = nowMs - 5_000L,
                ),
                makeSession(
                    id = "s_active_older",
                    title = "运行中项目",
                    cwd = "/workspace/beta",
                    state = SessionState.RUNNING,
                    updatedAt = nowMs - 90_000L,
                ),
                makeSession(
                    id = "s_wait_float",
                    title = "待审批任务",
                    cwd = "/workspace/gamma",
                    state = SessionState.WAITING_PERMISSION,
                    updatedAt = nowMs - 120_000L,
                ),
            ),
        )

        val groups = state.sidebarProjectGroups(excludePinned = true, excludeFloatingWaiting = true)
        assertEquals(listOf("/workspace/beta", "/workspace/alpha"), groups.map { it.key })
        assertEquals(1, groups[0].runningCount)
        assertEquals(
            listOf("s_pin", "s_wait_float", "s_active_older", "s_idle_newer"),
            state.visibleSidebarSessions.map { it.id },
        )

        // Collapsed pinned section excludes pinned items from visibleSidebarSessions until running-only filter is enabled
        val collapsedState = state.copy(pinnedSectionCollapsed = true)
        assertEquals(
            listOf("s_wait_float", "s_active_older", "s_idle_newer"),
            collapsedState.visibleSidebarSessions.map { it.id },
        )
        val runningOnlyState = collapsedState.copy(drawerOnlyRunning = true)
        assertEquals(
            listOf("s_pin", "s_wait_float", "s_active_older"),
            runningOnlyState.visibleSidebarSessions.map { it.id },
        )
    }
}
