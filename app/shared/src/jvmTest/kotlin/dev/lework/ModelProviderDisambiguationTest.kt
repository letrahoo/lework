package dev.lework

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import dev.lework.protocol.*
import dev.lework.store.*
import dev.lework.ui.desktop.DesktopModelPickerDialog
import dev.lework.ui.desktop.DesktopSettingsDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelProviderDisambiguationTest {
    private val nativeOpus = EngineModelSpec(
        id = "claude-opus-4-6",
        name = "Claude Opus 4.6",
        providerId = "sub_claude",
        providerName = "官方",
        efforts = listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH),
        defaultEffort = ReasoningEffort.HIGH,
    )

    private val cpaOpus = EngineModelSpec(
        id = "claude-opus-4-6",
        name = "claude-opus-4-6",
        providerId = "cpa_local",
        providerName = "CPA",
        efforts = listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH),
        defaultEffort = ReasoningEffort.MEDIUM,
    )

    private val nativeUniqueSonnet = EngineModelSpec(
        id = "claude-sonnet-4-6",
        name = "Claude Sonnet 4.6",
        providerId = "sub_claude",
        providerName = "官方",
        efforts = listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH),
        defaultEffort = ReasoningEffort.MEDIUM,
    )

    private val openRouterGpt = EngineModelSpec(
        id = "gpt-5.4",
        name = "gpt-5.4",
        providerId = "prov_openrouter",
        providerName = "OpenRouter",
        efforts = listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH),
        defaultEffort = ReasoningEffort.MEDIUM,
    )

    private fun claudeEngineWithSameNameCollision(): EngineInfo = EngineInfo(
        id = "claude",
        displayName = "Claude Code",
        kind = EngineKind.CLI,
        status = EngineStatus(installed = true, ready = true, authStatus = "authenticated"),
        capabilities = EngineCapabilities(true, true, true, true, true, true),
        models = listOf(nativeOpus, nativeUniqueSonnet, cpaOpus, openRouterGpt),
        defaultModel = "claude-opus-4-6",
        defaultMode = SessionMode.AUTO,
        defaultEffort = ReasoningEffort.HIGH,
        supportedModes = listOf(SessionMode.AUTO, SessionMode.PLAN, SessionMode.DEFAULT),
    )

    @Test
    fun sourceBadgeShowsExternalAlwaysAndNativeOnlyOnSameNameCollision() {
        val models = claudeEngineWithSameNameCollision().models
        assertEquals("官方", nativeOpus.sourceBadgeLabel(models))
        assertEquals("CPA", cpaOpus.sourceBadgeLabel(models))
        assertEquals("OpenRouter", openRouterGpt.sourceBadgeLabel(models))
        // Unique native model stays clean without a redundant badge
        assertNull(nativeUniqueSonnet.sourceBadgeLabel(models))
    }

    @Test
    fun selectedModelSpecAndFavoritesDistinguishSameNameByProviderId() {
        val engine = claudeEngineWithSameNameCollision()
        val stateOfficial = ConsoleUiState(
            connectionStatus = ConnectionStatus.CONNECTED,
            engines = listOf(engine),
            selectedEngineId = "claude",
            selectedProviderId = "sub_claude",
            selectedModelId = "claude-opus-4-6",
        )
        assertEquals("sub_claude", stateOfficial.selectedModelSpec?.providerId)

        val stateCpa = stateOfficial.copy(selectedProviderId = "cpa_local")
        assertEquals("cpa_local", stateCpa.selectedModelSpec?.providerId)

        val favs = setOf(favoriteModelKey("claude", "cpa_local", "claude-opus-4-6"))
        assertTrue(isFavoriteModel(favs, "claude", "cpa_local", "claude-opus-4-6"))
        assertFalse(isFavoriteModel(favs, "claude", "sub_claude", "claude-opus-4-6"))
    }

    @Test
    fun storeSendsProviderIdOnSwitchAndManagesHostProviders() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val transport = FakeHubTransport().apply {
                customEngines = listOf(claudeEngineWithSameNameCollision())
                sessions.add(
                    SessionMeta(
                        id = "sess_claude_1",
                        engineId = "claude",
                        cwd = "/workspace/lework",
                        title = "Provider Switch Test",
                        state = SessionState.IDLE,
                        createdAt = 100L,
                        updatedAt = 200L,
                        lastSeq = 0,
                        providerId = "sub_claude",
                        model = "claude-opus-4-6",
                    ),
                )
            }
            val store = ConsoleStore(scope, transport)
            store.bootstrapOrConnect()
            repeat(40) {
                if (store.state.value.connectionStatus == ConnectionStatus.CONNECTED) return@repeat
                delay(20)
            }
            assertEquals(ConnectionStatus.CONNECTED, store.state.value.connectionStatus)

            store.selectSession("sess_claude_1")
            delay(60)
            assertEquals("sub_claude", store.state.value.selectedProviderId)

            // Switch to same-name model under CPA -> sends providerId="cpa_local" in setModel
            store.selectEngineAndModel("claude", cpaOpus)
            delay(80)
            assertEquals("cpa_local", store.state.value.selectedProviderId)
            assertEquals("cpa_local", transport.setModelCalls.lastOrNull()?.providerId)
            assertEquals("claude-opus-4-6", transport.setModelCalls.lastOrNull()?.modelId)

            // Sending prompt carries providerId="cpa_local"
            store.sendPrompt("请用 CPA 路由回复")
            delay(80)
            assertEquals("cpa_local", transport.sentPromptParams.lastOrNull()?.providerId)

            // Upsert a host-specific provider & remove it
            var savedCallbackFired = false
            store.upsertProvider(
                ProviderConfigInput(
                    name = "OpenRouter",
                    adapter = ProviderAdapterKind.GENERIC,
                    baseUrl = "https://openrouter.ai/api/v1",
                    apiKey = "or-demo-key",
                    wireApis = listOf(WireApi.ANTHROPIC_MESSAGES, WireApi.OPENAI_CHAT),
                    enabledEngines = listOf("claude", "codex", "pi"),
                ),
            ) { savedCallbackFired = true }
            delay(80)
            assertTrue(savedCallbackFired)
            assertTrue(store.state.value.externalProviders.any { it.name == "OpenRouter" })

            val orId = store.state.value.externalProviders.first { it.name == "OpenRouter" }.id
            store.removeProvider(orId)
            delay(80)
            assertFalse(store.state.value.externalProviders.any { it.id == orId })
        } finally {
            scope.cancel()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun modelPickerDisambiguatesSameNameCheckmarkAndSettingsAddsProvider() = runComposeUiTest {
        val engine = claudeEngineWithSameNameCollision()
        var pickedSpec: EngineModelSpec? = null
        val state = ConsoleUiState(
            connectionStatus = ConnectionStatus.CONNECTED,
            hub = HubInfo("hub_mac", "MacBook Pro", HubRole.SLAVE, PlatformType.DARWIN, "0.7.15"),
            engines = listOf(engine),
            providers = listOf(
                ProviderInfo(
                    id = "cpa_local",
                    name = "CPA",
                    type = ProviderType.ENDPOINT,
                    adapter = ProviderAdapterKind.CPA,
                    baseUrl = "http://127.0.0.1:8317",
                    wireApis = listOf(WireApi.ANTHROPIC_MESSAGES, WireApi.OPENAI_RESPONSES),
                    enabledEngines = listOf("claude", "codex", "pi"),
                    enabled = true,
                    builtIn = true,
                    hasApiKey = true,
                    healthy = true,
                    latencyMs = 9,
                    models = listOf("claude-opus-4-6"),
                ),
            ),
            selectedEngineId = "claude",
            selectedProviderId = "sub_claude",
            selectedModelId = "claude-opus-4-6",
        )

        setContent {
            MaterialTheme {
                DesktopModelPickerDialog(
                    state = state,
                    onSelectEngineAndModel = { _, spec, _ -> pickedSpec = spec },
                    onSelectEffort = {},
                    onSelectMode = {},
                    onToggleFavoriteModel = { _, _, _ -> },
                    onDismiss = {},
                )
            }
        }

        // Both same-name rows display with distinct badges; ONLY sub_claude shows the checkmark
        onNodeWithTag("picker_badge_claude_sub_claude_claude-opus-4-6", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("picker_badge_claude_cpa_local_claude-opus-4-6", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("picker_check_claude_sub_claude_claude-opus-4-6", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("picker_check_claude_cpa_local_claude-opus-4-6", useUnmergedTree = true).assertDoesNotExist()

        // Clicking the CPA row selects the CPA provider spec specifically
        onNodeWithTag("picker_model_claude_cpa_local_claude-opus-4-6", useUnmergedTree = true).performClick()
        assertEquals("cpa_local", pickedSpec?.providerId)
        assertEquals("claude-opus-4-6", pickedSpec?.id)

        // Source filter strip isolates official vs CPA models cleanly
        onNodeWithTag("picker_source_filter_cpa_local", useUnmergedTree = true).performClick()
        onNodeWithTag("picker_model_claude_cpa_local_claude-opus-4-6", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("picker_model_claude_sub_claude_claude-opus-4-6", useUnmergedTree = true).assertDoesNotExist()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun settingsDialogFiltersProviderModelsSubset() = runComposeUiTest {
        var savedInput: ProviderConfigInput? = null
        val state = ConsoleUiState(
            connectionStatus = ConnectionStatus.CONNECTED,
            hub = HubInfo("hub_mac", "MacBook Pro", HubRole.SLAVE, PlatformType.DARWIN, "0.7.15"),
            providers = listOf(
                ProviderInfo(
                    id = "cpa_local",
                    name = "CPA",
                    type = ProviderType.ENDPOINT,
                    adapter = ProviderAdapterKind.CPA,
                    baseUrl = "http://127.0.0.1:8317",
                    wireApis = listOf(WireApi.ANTHROPIC_MESSAGES, WireApi.OPENAI_RESPONSES),
                    enabledEngines = listOf("claude", "codex", "pi"),
                    enabled = true,
                    builtIn = true,
                    healthy = true,
                    latencyMs = 8,
                    models = listOf("claude-opus-4-6", "gpt-6.2-sol", "kimi-k2.6"),
                ),
            ),
        )

        setContent {
            MaterialTheme {
                DesktopSettingsDialog(
                    state = state,
                    onDismiss = {},
                    onSwitchHost = {},
                    onRemoveHost = {},
                    onPairAnotherHost = {},
                    onUpsertProvider = { input, onSuccess ->
                        savedInput = input
                        onSuccess()
                    },
                    onLogout = {},
                )
            }
        }

        onNodeWithTag("settings_provider_filter_cpa_local", useUnmergedTree = true).performClick()
        onNodeWithTag("provider_model_filter_dialog", useUnmergedTree = true).assertIsDisplayed()
        // Uncheck kimi-k2.6 so only claude-opus-4-6 and gpt-6.2-sol are kept
        onNodeWithTag("provider_filter_item_kimi-k2.6", useUnmergedTree = true).performClick()
        onNodeWithTag("provider_filter_btn_save", useUnmergedTree = true).performClick()

        assertEquals(listOf("claude-opus-4-6", "gpt-6.2-sol"), savedInput?.selectedModels)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun settingsDialogShowsHostScopedProvidersAndOpensEditor() = runComposeUiTest {
        var savedInput: ProviderConfigInput? = null
        val state = ConsoleUiState(
            connectionStatus = ConnectionStatus.CONNECTED,
            hub = HubInfo("hub_devbox", "Linux Devbox", HubRole.MASTER, PlatformType.LINUX, "0.7.15"),
            providers = listOf(
                ProviderInfo(
                    id = "cpa_local",
                    name = "CPA",
                    type = ProviderType.ENDPOINT,
                    adapter = ProviderAdapterKind.CPA,
                    baseUrl = "http://127.0.0.1:8317",
                    wireApis = listOf(WireApi.ANTHROPIC_MESSAGES, WireApi.OPENAI_RESPONSES),
                    enabledEngines = listOf("claude", "codex", "pi"),
                    enabled = true,
                    builtIn = true,
                    healthy = false,
                    error = "无法连接到服务地址",
                    models = emptyList(),
                ),
            ),
        )

        setContent {
            MaterialTheme {
                DesktopSettingsDialog(
                    state = state,
                    onDismiss = {},
                    onSwitchHost = {},
                    onRemoveHost = {},
                    onPairAnotherHost = {},
                    onUpsertProvider = { input, onSuccess ->
                        savedInput = input
                        onSuccess()
                    },
                    onLogout = {},
                )
            }
        }

        onNodeWithTag("settings_providers_section", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("settings_provider_card_cpa_local", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("settings_btn_add_provider", useUnmergedTree = true).performClick()
        onNodeWithTag("provider_editor_dialog", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("provider_field_name", useUnmergedTree = true).performTextInput("DevboxGateway")
        onNodeWithTag("provider_field_url", useUnmergedTree = true).performTextInput("https://gw.example.com/v1")
        onNodeWithTag("provider_btn_save", useUnmergedTree = true).performClick()

        assertEquals("DevboxGateway", savedInput?.name)
        assertEquals("https://gw.example.com/v1", savedInput?.baseUrl)
    }
}
