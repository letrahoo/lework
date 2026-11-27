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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.lework.platform.JvmPlatformTestHooks
import dev.lework.platform.SavedSessionPrefs
import dev.lework.protocol.SessionMeta
import dev.lework.protocol.SessionState
import dev.lework.protocol.SessionWorktreeInfo
import dev.lework.protocol.Workspace
import dev.lework.store.ConsoleStore
import dev.lework.store.FALLBACK_PROJECT_GROUP_KEY
import dev.lework.store.SidebarGroupingMode
import dev.lework.store.buildSessionProjectGroups
import dev.lework.store.splitMatchingScopePrefix
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutoTitleAndProjectGroupingTest {

    @BeforeTest
    fun setup() {
        JvmPlatformTestHooks.reset()
        JvmPlatformTestHooks.simulateDesktopLayout = true
        JvmPlatformTestHooks.savedToken = "test_token_grouping"
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

    private fun sampleSession(
        id: String,
        title: String,
        cwd: String,
        updatedAt: Long,
        state: SessionState = SessionState.IDLE,
        worktree: SessionWorktreeInfo? = null,
        autoTitle: String? = null,
        customTitle: String? = null,
    ): SessionMeta = SessionMeta(
        id = id,
        engineId = "claude",
        cwd = cwd,
        workspaces = listOf(cwd),
        worktree = worktree,
        title = title,
        autoTitle = autoTitle,
        customTitle = customTitle,
        state = state,
        createdAt = updatedAt - 1_000L,
        updatedAt = updatedAt,
        lastSeq = 4,
    )

    @Test
    fun groupsByProjectRollsUpSubdirsWorktreesDisambiguatesCollisionsAndOrdersByRecency() {
        val sessions = listOf(
            sampleSession(
                id = "s_lework_root",
                title = "lework-sidebar-项目分组",
                cwd = "/Users/dev/personal/lework",
                updatedAt = 5_000L,
            ),
            sampleSession(
                id = "s_lework_sub",
                title = "lework-hub-自动标题",
                cwd = "/Users/dev/personal/lework/hub",
                updatedAt = 4_000L,
            ),
            sampleSession(
                id = "s_lework_wt",
                title = "lework-ui-分支隔离",
                cwd = "/Users/dev/.lework-worktrees/lework/feat-x",
                updatedAt = 6_000L,
                worktree = SessionWorktreeInfo(
                    baseRepoPath = "/Users/dev/personal/lework",
                    worktreePath = "/Users/dev/.lework-worktrees/lework/feat-x",
                    branch = "lework/feat-x",
                ),
            ),
            sampleSession(
                id = "s_work_sdk",
                title = "sdk-缓存优化",
                cwd = "/Users/dev/work/sdk",
                updatedAt = 3_000L,
            ),
            sampleSession(
                id = "s_personal_sdk",
                title = "sdk-本地脚本",
                cwd = "/Users/dev/personal/sdk",
                updatedAt = 2_000L,
            ),
            sampleSession(
                id = "s_home",
                title = "日常问答",
                cwd = "/Users/dev",
                updatedAt = 9_000L,
            ),
        )
        val workspaces = listOf(
            Workspace(id = "ws_1", name = "lework", path = "/Users/dev/personal/lework"),
            Workspace(id = "ws_2", name = "personal", path = "/Users/dev/personal"),
        )

        val groups = buildSessionProjectGroups(sessions, workspaces)
        assertEquals(4, groups.size)

        // Most recently active named project comes first (lework has s_lework_wt at 6000L)
        assertEquals("/Users/dev/personal/lework", groups[0].key)
        assertEquals("lework", groups[0].displayName)
        assertEquals(
            listOf("s_lework_wt", "s_lework_root", "s_lework_sub"),
            groups[0].sessions.map { it.id },
        )

        // Colliding basenames (`work/sdk` vs `personal/sdk`) are automatically disambiguated
        assertEquals("work/sdk", groups[1].displayName)
        assertEquals("personal/sdk", groups[2].displayName)

        // Home/unassigned fallback group sorts after named projects
        assertEquals(FALLBACK_PROJECT_GROUP_KEY, groups[3].key)
        assertTrue(groups[3].isFallback)
        assertEquals(listOf("s_home"), groups[3].sessions.map { it.id })
    }

    @Test
    fun splitsMatchingScopePrefixCleanlyInsideProjectGroup() {
        assertEquals(
            "lework-" to "sidebar-项目分组",
            splitMatchingScopePrefix("lework-sidebar-项目分组", "lework"),
        )
        assertEquals(
            "sdk-" to "cache-优化",
            splitMatchingScopePrefix("sdk-cache-优化", "work/sdk"),
        )
        assertNull(splitMatchingScopePrefix("dotfiles-zsh-配色", "lework"))
        assertNull(splitMatchingScopePrefix("lework-", "lework"))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun storePersistsGroupingStateAutoExpandsOnSearchAndSelectionAndRegeneratesTitle() = runTest {
        val transport = FakeHubTransport()
        transport.sessions.addAll(
            listOf(
                sampleSession(
                    id = "s_lework_1",
                    title = "lework-sidebar-项目分组",
                    cwd = "/workspace/lework",
                    updatedAt = 4_000L,
                    customTitle = "旧手动标题",
                ),
                sampleSession(
                    id = "s_dot_1",
                    title = "dotfiles-zsh-主题同步",
                    cwd = "/workspace/dotfiles",
                    updatedAt = 2_000L,
                ),
            ),
        )
        transport.regenerateTitleHandler = { "lework-sidebar-智能重命名" }

        val storeScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val store = ConsoleStore(storeScope, transport)
            store.bootstrapOrConnect()
            runCurrent()

            assertEquals(SidebarGroupingMode.PROJECT, store.state.value.sidebarGroupingMode)
            assertEquals(
                listOf("s_lework_1", "s_dot_1"),
                store.state.value.visibleSidebarSessions.map { it.id },
            )

            // Collapsing dotfiles hides it from visibleSidebarSessions and persists pref
            store.toggleProjectGroupCollapsed("/workspace/dotfiles")
            assertTrue("/workspace/dotfiles" in store.state.value.collapsedProjectGroupKeys)
            assertEquals(listOf("s_lework_1"), store.state.value.visibleSidebarSessions.map { it.id })
            assertTrue("/workspace/dotfiles" in JvmPlatformTestHooks.savedPrefs.collapsedProjectGroupKeys)

            // Searching temporarily reveals sessions inside collapsed groups
            store.setDrawerSearchQuery("主题同步")
            assertEquals(listOf("s_dot_1"), store.state.value.visibleSidebarSessions.map { it.id })
            store.setDrawerSearchQuery("")
            assertEquals(listOf("s_lework_1"), store.state.value.visibleSidebarSessions.map { it.id })

            // Selecting a session inside a collapsed group automatically uncollapses its group
            store.selectSession("s_dot_1")
            runCurrent()
            assertFalse("/workspace/dotfiles" in store.state.value.collapsedProjectGroupKeys)
            assertEquals(
                listOf("s_lework_1", "s_dot_1"),
                store.state.value.visibleSidebarSessions.map { it.id },
            )

            // Toggling grouping mode switches to TIME and persists
            store.toggleSidebarGroupingMode()
            assertEquals(SidebarGroupingMode.TIME, store.state.value.sidebarGroupingMode)
            assertFalse(JvmPlatformTestHooks.savedPrefs.sidebarGroupByProject)

            // Regenerate title RPC updates session title and clears customTitle
            store.regenerateSessionTitle("s_lework_1")
            runCurrent()
            assertEquals(listOf("s_lework_1"), transport.regeneratedTitleSessionIds)
            val refreshed = store.state.value.sessions.first { it.id == "s_lework_1" }
            assertEquals("lework-sidebar-智能重命名", refreshed.title)
            assertEquals("lework-sidebar-智能重命名", refreshed.autoTitle)
            assertNull(refreshed.customTitle)
        } finally {
            storeScope.cancel()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun desktopSidebarRendersCollapsibleProjectGroupsQuickNewAndTitleRegeneration() {
        val transport = FakeHubTransport()
        transport.sessions.addAll(
            listOf(
                sampleSession(
                    id = "s_lework_1",
                    title = "lework-sidebar-项目聚合",
                    cwd = "/workspace/lework",
                    updatedAt = 5_000L,
                ),
                sampleSession(
                    id = "s_lework_2",
                    title = "lework-hub-自动标题",
                    cwd = "/workspace/lework/.worktrees/wt1",
                    updatedAt = 4_500L,
                    worktree = SessionWorktreeInfo(
                        baseRepoPath = "/workspace/lework",
                        worktreePath = "/workspace/lework/.worktrees/wt1",
                        branch = "lework/auto-title",
                    ),
                ),
                sampleSession(
                    id = "s_dot_1",
                    title = "dotfiles-zsh-快捷键",
                    cwd = "/workspace/dotfiles",
                    updatedAt = 3_000L,
                ),
            ),
        )
        transport.regenerateTitleHandler = { "lework-sidebar-自动标题已刷新" }

        runComposeUiTest {
            lateinit var store: ConsoleStore
            setContent {
                val scope = rememberCoroutineScope()
                val created = remember { ConsoleStore(scope, transport) }
                store = created
                Box(modifier = Modifier.size(width = 1280.dp, height = 820.dp)) {
                    ConsoleApp(externalStore = created, autoBootstrap = true)
                }
            }
            waitUntil(timeoutMillis = 5_000L) { store.state.value.hasEnteredWorkspace }
            waitForIdle()

            // Project headers render cleanly by directory (worktree folded under /workspace/lework)
            onNodeWithTag("project_group_header_/workspace/lework").assertIsDisplayed()
            onNodeWithTag("project_group_header_/workspace/dotfiles").assertIsDisplayed()
            onNodeWithTag("session_row_s_lework_1").assertIsDisplayed()
            onNodeWithTag("session_row_s_lework_2").assertIsDisplayed()
            onNodeWithTag("session_row_s_dot_1").assertIsDisplayed()

            // Click dotfiles project header -> collapses its session row
            onNodeWithTag("project_group_header_/workspace/dotfiles").performClick()
            waitForIdle()
            assertEquals(0, onAllNodesWithTag("session_row_s_dot_1").fetchSemanticsNodes().size)

            // Search query auto-expands collapsed group so matching session is immediately visible
            onNodeWithTag("drawer_search_input").performTextInput("快捷键")
            waitForIdle()
            onNodeWithTag("session_row_s_dot_1").assertIsDisplayed()
            runOnIdle { store.setDrawerSearchQuery("") }
            waitForIdle()
            assertEquals(0, onAllNodesWithTag("session_row_s_dot_1").fetchSemanticsNodes().size)

            // Click dotfiles header again -> expands back
            onNodeWithTag("project_group_header_/workspace/dotfiles").performClick()
            waitForIdle()
            onNodeWithTag("session_row_s_dot_1").assertIsDisplayed()

            // Hover project header -> click "+" quick-new starts draft targeted at that project
            onNodeWithTag("project_group_header_/workspace/dotfiles").performMouseInput { enter(center) }
            waitForIdle()
            onNodeWithTag("project_group_new_/workspace/dotfiles").assertIsDisplayed().performClick()
            waitForIdle()
            onNodeWithTag("project_group_header_/workspace/dotfiles").performMouseInput { exit() }
            waitForIdle()
            assertEquals("/workspace/dotfiles", store.state.value.selectedWorkspacePath)
            assertNull(store.state.value.activeSessionId)

            // Toggle grouping mode to flat time list and back to project grouping
            onNodeWithTag("drawer_btn_toggle_grouping").assertIsDisplayed().performClick()
            waitForIdle()
            assertEquals(SidebarGroupingMode.TIME, store.state.value.sidebarGroupingMode)
            assertEquals(
                0,
                onAllNodesWithTag("project_group_header_/workspace/lework").fetchSemanticsNodes().size,
            )
            onNodeWithTag("drawer_btn_toggle_grouping").performClick()
            waitForIdle()
            assertEquals(SidebarGroupingMode.PROJECT, store.state.value.sidebarGroupingMode)
            onNodeWithTag("project_group_header_/workspace/lework").assertIsDisplayed()

            // Open Rename dialog on s_lework_1 and click "智能生成标题"
            onNodeWithTag("session_row_s_lework_1").performMouseInput { enter(center) }
            waitForIdle()
            onNodeWithTag("session_rename_s_lework_1").assertIsDisplayed().performClick()
            waitForIdle()
            onNodeWithTag("rename_session_regenerate").assertIsDisplayed().performClick()
            waitForIdle()
            assertEquals(listOf("s_lework_1"), transport.regeneratedTitleSessionIds)
            assertEquals(
                "lework-sidebar-自动标题已刷新",
                store.state.value.sessions.first { it.id == "s_lework_1" }.title,
            )
            onNodeWithText("自动标题已刷新", substring = true).assertIsDisplayed()
        }
    }
}
