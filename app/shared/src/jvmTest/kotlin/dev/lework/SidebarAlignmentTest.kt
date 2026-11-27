package dev.lework

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import dev.lework.protocol.EngineCapabilities
import dev.lework.protocol.EngineInfo
import dev.lework.protocol.EngineKind
import dev.lework.protocol.EngineStatus
import dev.lework.protocol.HubInfo
import dev.lework.protocol.HubRole
import dev.lework.protocol.PlatformType
import dev.lework.protocol.SessionMeta
import dev.lework.protocol.SessionState
import dev.lework.store.ConnectionStatus
import dev.lework.store.ConsoleUiState
import dev.lework.store.FALLBACK_PROJECT_GROUP_KEY
import dev.lework.store.SidebarGroupingMode
import dev.lework.ui.Everforest
import dev.lework.ui.TasksSidebarContent
import dev.lework.ui.desktop.DesktopSidebar
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SidebarAlignmentTest {

    private fun assertDpClose(expected: Dp, actual: Dp, tolerance: Dp = 1.dp, label: String) {
        val diff = abs(expected.value - actual.value).dp
        assertTrue(
            diff <= tolerance,
            "$label expected ~$expected but was $actual (diff=$diff, tolerance=$tolerance)",
        )
    }

    private fun testCapabilities(): EngineCapabilities = EngineCapabilities(
        permissions = true,
        images = true,
        modelSwitch = true,
        modes = true,
        history = true,
        cancel = true,
    )

    private fun testEngines(): List<EngineInfo> = listOf(
        EngineInfo(
            id = "claude",
            displayName = "Claude",
            kind = EngineKind.CLI,
            capabilities = testCapabilities(),
            status = EngineStatus(installed = true, ready = true),
        ),
        EngineInfo(
            id = "pi",
            displayName = "Pi",
            kind = EngineKind.CLI,
            capabilities = testCapabilities(),
            status = EngineStatus(installed = true, ready = true),
        ),
        EngineInfo(
            id = "codex",
            displayName = "Codex",
            kind = EngineKind.CLI,
            capabilities = testCapabilities(),
            status = EngineStatus(installed = true, ready = false),
        ),
    )

    private fun session(
        id: String,
        cwd: String,
        updatedAt: Long,
        state: SessionState = SessionState.IDLE,
    ): SessionMeta = SessionMeta(
        id = id,
        engineId = "claude",
        cwd = cwd,
        title = "Session $id",
        state = state,
        createdAt = updatedAt - 100L,
        updatedAt = updatedAt,
        lastSeq = 1,
    )

    private fun sampleGroupedState(): ConsoleUiState {
        val sessions = buildList {
            add(session("lw_run", "/workspace/lework", 10_000L, state = SessionState.RUNNING))
            add(session("lw_2", "/workspace/lework", 9_900L))
            add(session("lw_3", "/workspace/lework", 9_800L))
            repeat(8) { idx ->
                add(session("levi_$idx", "/workspace/levi", 9_000L - idx))
            }
            add(session("dot_1", "/workspace/dotfiles", 8_000L))
            add(session("sub_1", "/workspace/sub-cost-view", 7_000L))
            repeat(27) { idx ->
                add(session("home_$idx", "/Users/letra", 6_000L - idx))
            }
        }
        return ConsoleUiState(
            hasEnteredWorkspace = true,
            connectionStatus = ConnectionStatus.CONNECTED,
            hub = HubInfo(
                id = "hub_test",
                name = "test.letra.xin",
                role = HubRole.MASTER,
                platform = PlatformType.DARWIN,
                version = "0.9.0",
            ),
            hubFingerprint = "E040-F5BA-217B-D079",
            authUserName = "Letra Hu",
            engines = testEngines(),
            sessions = sessions,
            sidebarGroupingMode = SidebarGroupingMode.PROJECT,
            collapsedProjectGroupKeys = setOf(
                "/workspace/lework",
                "/workspace/levi",
                "/workspace/dotfiles",
                "/workspace/sub-cost-view",
                FALLBACK_PROJECT_GROUP_KEY,
            ),
        )
    }

    @Test
    fun mobileDrawerAlignsProjectButtonsCountsSectionPillsAndSingleLineLogout() = runComposeUiTest {
        val state = sampleGroupedState()

        setContent {
            MaterialTheme {
                Box(
                    modifier = Modifier
                        .size(width = 316.dp, height = 740.dp)
                        .background(Everforest.BgDim),
                ) {
                    TasksSidebarContent(
                        state = state,
                        onNewSession = {},
                        onOpenUsage = {},
                        onSelectFilter = {},
                        onSelectSession = {},
                        onRefresh = {},
                        onLogout = {},
                    )
                }
            }
        }

        waitForIdle()

        val projectKeys = listOf(
            "/workspace/lework",
            "/workspace/levi",
            "/workspace/dotfiles",
            "/workspace/sub-cost-view",
        )
        val allGroupKeys = projectKeys + FALLBACK_PROJECT_GROUP_KEY

        val headerBounds = allGroupKeys.associateWith { key ->
            onNodeWithTag("project_group_header_$key", useUnmergedTree = true)
                .assertIsDisplayed()
                .getUnclippedBoundsInRoot()
        }
        val addBounds = projectKeys.associateWith { key ->
            onNodeWithTag("project_group_new_$key", useUnmergedTree = true)
                .assertIsDisplayed()
                .getUnclippedBoundsInRoot()
        }
        val countBounds = allGroupKeys.associateWith { key ->
            onNodeWithTag("project_group_count_$key", useUnmergedTree = true)
                .assertIsDisplayed()
                .getUnclippedBoundsInRoot()
        }

        val referenceHeader = headerBounds.getValue("/workspace/lework")
        val referenceAdd = addBounds.getValue("/workspace/lework")
        val referenceCount = countBounds.getValue("/workspace/lework")

        // Every project "+" button aligns vertically on the exact same right-pinned column
        for (key in projectKeys) {
            val header = headerBounds.getValue(key)
            val add = addBounds.getValue(key)
            assertDpClose(referenceAdd.left, add.left, label = "add.left($key)")
            assertDpClose(referenceAdd.right, add.right, label = "add.right($key)")
            assertDpClose(6.dp, header.right - add.right, label = "header.right - add.right($key)")
        }

        // Every project row (including fallback "通用 / 主目录") has identical height and aligned counts
        for (key in allGroupKeys) {
            val header = headerBounds.getValue(key)
            val count = countBounds.getValue(key)
            assertDpClose(referenceHeader.height, header.height, label = "header.height($key)")
            assertDpClose(referenceCount.right, count.right, label = "count.right($key)")
        }

        // Section header pills ("1 进行中" and "按项目") share identical height and flush right edge
        val runningPill = onNodeWithTag("drawer_btn_filter_running", useUnmergedTree = true)
            .assertIsDisplayed()
            .getUnclippedBoundsInRoot()
        val groupingPill = onNodeWithTag("drawer_btn_toggle_grouping", useUnmergedTree = true)
            .assertIsDisplayed()
            .getUnclippedBoundsInRoot()
        assertDpClose(runningPill.height, groupingPill.height, label = "section pills height")
        assertDpClose(referenceHeader.right, groupingPill.right, label = "grouping pill right edge")

        // Footer "断开连接" stays single-line (no 2-line wrap) even when host fingerprint is visible
        val logoutBounds = onNodeWithTag("drawer_btn_logout", useUnmergedTree = true)
            .assertIsDisplayed()
            .getUnclippedBoundsInRoot()
        assertTrue(
            logoutBounds.height <= 30.dp,
            "Expected single-line logout button height <= 30.dp, got ${logoutBounds.height}",
        )
        assertDpClose(referenceHeader.right, logoutBounds.right, tolerance = 2.dp, label = "logout right edge")
    }

    @Test
    fun desktopSidebarAlignsProjectCountsAndPreservesHeightOnHoverWithLongNames() = runComposeUiTest {
        val longPath = "/workspace/very-long-monorepo-service-name-that-must-ellipsize-cleanly"
        val base = sampleGroupedState()
        val state = base.copy(
            sessions = base.sessions + session("long_1", longPath, 11_000L),
            collapsedProjectGroupKeys = base.collapsedProjectGroupKeys + longPath,
        )

        setContent {
            MaterialTheme {
                Box(
                    modifier = Modifier
                        .size(width = 272.dp, height = 740.dp)
                        .background(Everforest.BgDim),
                ) {
                    DesktopSidebar(
                        state = state,
                        onNewSession = {},
                        onOpenUsage = {},
                        onOpenSettings = {},
                        onCollapseSidebar = {},
                        onSelectFilter = {},
                        onSelectSession = {},
                        onRefresh = {},
                        onSwitchHost = {},
                        onPairAnotherHost = {},
                        onSearchQueryChange = {},
                        onToggleArchivedView = {},
                        onTogglePinSession = {},
                        onRequestRenameSession = {},
                        onToggleArchiveSession = { _, _ -> },
                    )
                }
            }
        }

        waitForIdle()

        val keys = listOf(longPath, "/workspace/lework", "/workspace/levi", FALLBACK_PROJECT_GROUP_KEY)
        val headers = keys.associateWith { key ->
            onNodeWithTag("project_group_header_$key", useUnmergedTree = true)
                .assertIsDisplayed()
                .getUnclippedBoundsInRoot()
        }
        val counts = keys.associateWith { key ->
            onNodeWithTag("project_group_count_$key", useUnmergedTree = true)
                .assertIsDisplayed()
                .getUnclippedBoundsInRoot()
        }

        val refHeader = headers.getValue("/workspace/lework")
        val refCount = counts.getValue("/workspace/lework")
        for (key in keys) {
            assertDpClose(refHeader.height, headers.getValue(key).height, label = "desktop header.height($key)")
            assertDpClose(refCount.right, counts.getValue(key).right, label = "desktop count.right($key)")
        }

        // Hovering a project header swaps count for "+" without shifting row height
        onNodeWithTag("project_group_header_/workspace/levi").performMouseInput { enter(center) }
        waitForIdle()
        val hoveredHeader = onNodeWithTag("project_group_header_/workspace/levi", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val hoveredAdd = onNodeWithTag("project_group_new_/workspace/levi", useUnmergedTree = true)
            .assertIsDisplayed()
            .getUnclippedBoundsInRoot()
        assertDpClose(refHeader.height, hoveredHeader.height, label = "desktop hovered header.height")
        assertDpClose(4.dp, hoveredHeader.right - hoveredAdd.right, label = "desktop hovered add right edge")
    }
}
