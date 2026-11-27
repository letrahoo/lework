package dev.lework

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import dev.lework.platform.JvmPlatformTestHooks
import dev.lework.platform.PairedHost
import dev.lework.protocol.*
import dev.lework.store.ConsoleUiState
import dev.lework.ui.*
import dev.lework.ui.desktop.DesktopComposer
import dev.lework.ui.desktop.DesktopSettingsDialog
import dev.lework.ui.desktop.DesktopWorkspaceHeader
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SlashCommandAndUxPolishTest {

    @BeforeTest
    fun setUp() {
        JvmPlatformTestHooks.reset()
    }

    @AfterTest
    fun tearDown() {
        JvmPlatformTestHooks.reset()
    }

    @Test
    fun slashQueryExtractionAndContextAwareCommandCatalog() {
        assertEquals("", extractSlashCommandQuery("/"))
        assertEquals("pl", extractSlashCommandQuery("/pl"))
        assertEquals("grill-me", extractSlashCommandQuery("  /grill-me"))
        assertNull(extractSlashCommandQuery("hello /plan"))
        assertNull(extractSlashCommandQuery("/plan refactor auth"))
        assertNull(extractSlashCommandQuery("/plan\nnext"))

        val caps = EngineCapabilities(
            permissions = true,
            images = true,
            modelSwitch = true,
            modes = true,
            history = true,
            cancel = true,
        )
        val agyEngine = EngineInfo(
            id = "agy",
            displayName = "Antigravity",
            kind = EngineKind.CLI,
            status = EngineStatus(installed = true, ready = true),
            capabilities = caps,
            models = listOf(
                EngineModelSpec(
                    id = "claude-opus-4-6-thinking",
                    name = "Claude Opus 4.6",
                    efforts = listOf(ReasoningEffort.HIGH),
                ),
            ),
            slashCommands = listOf(
                SlashCommandInfo("goal", "围绕目标持续推进", "目标描述", SlashCommandSource.BUILTIN),
                SlashCommandInfo("my-skill", "自定义代码审计技能", null, SlashCommandSource.SKILL),
            ),
        )
        val draftState = ConsoleUiState(
            engines = listOf(agyEngine),
            selectedEngineId = "agy",
            selectedModelId = "claude-opus-4-6-thinking",
            activeSessionId = null,
        )
        val draftCmds = buildAvailableSlashCommands(draftState, includeDesktopOnly = true)
        val draftNames = draftCmds.map { it.name }.toSet()
        assertTrue("goal" in draftNames)
        assertTrue("my-skill" in draftNames)
        assertTrue("new" in draftNames)
        assertTrue("diff" in draftNames)
        // Context-aware hiding: no active session -> no /rename or /artifacts; single effort -> no /effort
        assertTrue("rename" !in draftNames)
        assertTrue("artifacts" !in draftNames)
        assertTrue("effort" !in draftNames)

        val activeSess = SessionMeta(
            id = "sess_1",
            engineId = "agy",
            cwd = "/workspace/lework",
            title = "AGY 会话",
            state = SessionState.IDLE,
            createdAt = 1000L,
            updatedAt = 2000L,
            lastSeq = 1,
            slashCommands = listOf(
                SlashCommandInfo("swarm", "多智能体协同", "分工", SlashCommandSource.WORKFLOW),
            ),
        )
        val activeState = draftState.copy(
            sessions = listOf(activeSess),
            activeSessionId = "sess_1",
        )
        val activeNames = buildAvailableSlashCommands(activeState, includeDesktopOnly = true).map { it.name }.toSet()
        assertTrue("swarm" in activeNames)
        assertTrue("rename" in activeNames)
        assertTrue("artifacts" in activeNames)

        val filtered = filterSlashCommands(draftCmds, "my")
        assertEquals("my-skill", filtered.first().name)

        // Local vs engine command routing
        assertEquals(LocalSlashAction.NewSession, parseLocalSlashAction("/new", activeState))
        assertEquals(LocalSlashAction.NewSession, parseLocalSlashAction("/clear", activeState))
        assertEquals(LocalSlashAction.SwitchMode(SessionMode.PLAN), parseLocalSlashAction("/mode plan", activeState))
        assertEquals(LocalSlashAction.RenameSession("新标题"), parseLocalSlashAction("/rename 新标题", activeState))
        assertEquals(LocalSlashAction.OpenDiff, parseLocalSlashAction("/diff", activeState))
        assertNull(parseLocalSlashAction("/plan implement oauth", activeState))
        assertNull(parseLocalSlashAction("/goal ship release", activeState))
        assertNull(parseLocalSlashAction("/rename test", draftState))
    }

    @Test
    fun desktopComposerSlashPopupOpensFiltersSelectsAndToggles() = runComposeUiTest {
        val state = ConsoleUiState(
            selectedEngineId = "agy",
            engines = listOf(
                EngineInfo(
                    id = "agy",
                    displayName = "Antigravity",
                    kind = EngineKind.CLI,
                    status = EngineStatus(installed = true, ready = true),
                    capabilities = EngineCapabilities(
                        permissions = true,
                        images = true,
                        modelSwitch = true,
                        modes = true,
                        history = true,
                        cancel = true,
                    ),
                    slashCommands = listOf(
                        SlashCommandInfo("goal", "目标持续推进", "目标", SlashCommandSource.BUILTIN),
                        SlashCommandInfo("swarm", "多智能体协作", null, SlashCommandSource.WORKFLOW),
                    ),
                ),
            ),
        )
        var selectedCmd: ResolvedSlashCommand? = null

        setContent {
            MaterialTheme {
                var draft by remember { mutableStateOf("") }
                Box(modifier = Modifier.width(720.dp)) {
                    DesktopComposer(
                        state = state,
                        text = draft,
                        onTextChange = { draft = it },
                        onOpenWorkspaceDialog = {},
                        onOpenModelPicker = {},
                        onCycleMode = {},
                        onSend = {},
                        onCancel = {},
                        onPickImage = {},
                        onRemoveImage = {},
                        onSelectMentionFile = {},
                        onDismissMention = {},
                        onSelectSlashCommand = { cmd ->
                            selectedCmd = cmd
                            draft = "/${cmd.name} "
                        },
                    )
                }
            }
        }

        // Initially hidden
        onNodeWithTag("composer_slash_popup").assertDoesNotExist()

        // Toggle open via `/` button
        onNodeWithTag("composer_slash_btn").performClick()
        waitForIdle()
        onNodeWithTag("composer_slash_popup").assertIsDisplayed()
        onNodeWithTag("slash_cmd_goal").assertIsDisplayed()
        onNodeWithTag("slash_cmd_swarm").assertIsDisplayed()

        // Clicking `/` button again closes the popup cleanly
        onNodeWithTag("composer_slash_btn").performClick()
        waitForIdle()
        onNodeWithTag("composer_slash_popup").assertDoesNotExist()

        // Re-open and click `/goal` -> selects command and closes popup once trailing space is inserted
        onNodeWithTag("composer_slash_btn").performClick()
        waitForIdle()
        onNodeWithTag("slash_cmd_goal").performClick()
        waitForIdle()
        assertNotNull(selectedCmd)
        assertEquals("goal", selectedCmd?.name)
        onNodeWithTag("composer_slash_popup").assertDoesNotExist()
    }

    @Test
    fun inlineApprovalBannerSupportsQuestionModeCustomInputAndLocalizedPermissions() = runComposeUiTest {
        var lastDecision: List<String?>? = null
        val questionEvent = AgentEvent.PermissionRequested(
            seq = 10,
            sessionId = "sess_q",
            ts = 1000L,
            requestId = "elicit_1",
            title = "选择数据库方案",
            interactionKind = InteractionKind.QUESTION,
            allowCustomInput = true,
            options = listOf(
                PermissionOption("sqlite", "SQLite（推荐）", PermissionOptionKind.ALLOW_ONCE),
                PermissionOption("mysql", "MySQL 8", PermissionOptionKind.ALLOW_ALWAYS),
            ),
        )

        setContent {
            MaterialTheme {
                Box(modifier = Modifier.width(640.dp)) {
                    InlineApprovalBanner(
                        permission = questionEvent,
                        onDecision = { reqId, optId, sessId ->
                            lastDecision = listOf(reqId, optId, sessId, null)
                        },
                        onCustomAnswer = { reqId, custom, sessId ->
                            lastDecision = listOf(reqId, "__custom__", sessId, custom)
                        },
                    )
                }
            }
        }

        onNodeWithTag("inline_question_banner").assertIsDisplayed()
        onNodeWithText("选择数据库方案").assertIsDisplayed()
        onNodeWithText("暂不回答").assertIsDisplayed()
        // Permission-only tags must NOT appear in question cards
        onNodeWithText("本次").assertDoesNotExist()
        onNodeWithText("始终").assertDoesNotExist()

        // Enter custom answer and submit
        onNodeWithTag("approval_custom_input").performTextInput("PostgreSQL 16")
        waitForIdle()
        onNodeWithTag("approval_custom_submit_btn").performClick()
        waitForIdle()
        assertEquals(listOf("elicit_1", "__custom__", "sess_q", "PostgreSQL 16"), lastDecision)

        // Verify permission localization helper
        assertEquals("仅本次允许", localizeApprovalOptionName("Allow once", PermissionOptionKind.ALLOW_ONCE))
        assertEquals("始终允许", localizeApprovalOptionName("Allow always", PermissionOptionKind.ALLOW_ALWAYS))
        assertEquals("拒绝", localizeApprovalOptionName("Reject once", PermissionOptionKind.REJECT_ONCE))
    }

    @Test
    fun settingsDestructiveActionsRequireSecondStepConfirmation() = runComposeUiTest {
        var logoutCount = 0
        val removedHosts = mutableListOf<String>()
        val removedProviders = mutableListOf<String>()
        val hub1 = HubInfo(
            id = "hub_1",
            name = "MacBook Pro",
            role = HubRole.STANDALONE,
            platform = PlatformType.DARWIN,
            version = "0.8.19",
        )
        val state = ConsoleUiState(
            hub = hub1,
            pairedHosts = listOf(
                PairedHost(hubId = "hub_1", name = "MacBook Pro"),
                PairedHost(hubId = "hub_2", name = "Remote Devbox"),
            ),
            providers = listOf(
                ProviderInfo(
                    id = "prov_1",
                    name = "OpenRouter",
                    type = ProviderType.ENDPOINT,
                    adapter = ProviderAdapterKind.GENERIC,
                    baseUrl = "https://openrouter.ai/api/v1",
                    enabled = true,
                    healthy = true,
                ),
            ),
        )

        setContent {
            MaterialTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    DesktopSettingsDialog(
                        state = state,
                        onDismiss = {},
                        onSwitchHost = {},
                        onRemoveHost = { removedHosts.add(it) },
                        onPairAnotherHost = {},
                        onRemoveProvider = { removedProviders.add(it) },
                        onLogout = { logoutCount++ },
                    )
                }
            }
        }

        // 1. Clicking logout opens confirmation dialog instead of logging out immediately
        onNodeWithTag("settings_btn_logout").performClick()
        waitForIdle()
        assertEquals(0, logoutCount)
        onNodeWithTag("settings_confirm_logout_btn").assertIsDisplayed()
        onNodeWithTag("settings_confirm_logout_btn").performClick()
        waitForIdle()
        assertEquals(1, logoutCount)

        // 2. Removing paired host requires confirmation
        onNodeWithTag("settings_btn_remove_host_hub_2").performScrollTo().performClick()
        waitForIdle()
        assertTrue(removedHosts.isEmpty())
        onNodeWithTag("settings_confirm_remove_host_btn").performClick()
        waitForIdle()
        assertEquals(listOf("hub_2"), removedHosts)

        // 3. Removing external model provider requires confirmation
        onNodeWithTag("settings_provider_remove_prov_1").performScrollTo().performClick()
        waitForIdle()
        assertTrue(removedProviders.isEmpty())
        onNodeWithTag("settings_confirm_remove_provider_btn").performClick()
        waitForIdle()
        assertEquals(listOf("prov_1"), removedProviders)
    }

    @Test
    fun workspaceHeaderHidesArtifactsButtonWhenNoActiveSession() = runComposeUiTest {
        val state = ConsoleUiState(selectedWorkspacePath = "/workspace/lework")
        val sess = SessionMeta(
            id = "sess_1",
            engineId = "agy",
            cwd = "/workspace/lework",
            title = "测试会话",
            state = SessionState.IDLE,
            createdAt = 1000L,
            updatedAt = 2000L,
            lastSeq = 1,
        )
        var currentSession by mutableStateOf<SessionMeta?>(null)

        setContent {
            MaterialTheme {
                Box(modifier = Modifier.width(800.dp)) {
                    DesktopWorkspaceHeader(
                        state = state,
                        activeSession = currentSession,
                        showExpandSidebarButton = false,
                        activeInspectorTab = null,
                        onExpandSidebar = {},
                        onOpenWorkspaceDialog = {},
                        onToggleDiffInspector = {},
                        onToggleFilesInspector = {},
                        onToggleArtifactsInspector = {},
                        onRenameSession = {},
                        onToggleArchiveSession = {},
                    )
                }
            }
        }

        onNodeWithTag("topbar_btn_artifacts").assertDoesNotExist()
        currentSession = sess
        waitForIdle()
        onNodeWithTag("topbar_btn_artifacts").assertIsDisplayed()
    }
}
