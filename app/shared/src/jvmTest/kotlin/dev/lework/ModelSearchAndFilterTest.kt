package dev.lework

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import dev.lework.protocol.*
import dev.lework.store.ConnectionStatus
import dev.lework.store.ConsoleUiState
import dev.lework.ui.desktop.DesktopModelPickerDialog
import dev.lework.ui.desktop.DesktopSettingsDialog
import dev.lework.ui.desktop.matchesModelSearchQuery
import dev.lework.ui.desktop.modelFamilyLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelSearchAndFilterTest {

    @Test
    fun `modelFamilyLabel strips namespace prefixes before classifying model family`() {
        assertEquals("Gemini", modelFamilyLabel("gapi/gemini-4-neon"))
        assertEquals("Gemini", modelFamilyLabel("gapi/gemini-4-carbon"))
        assertEquals("Gemini", modelFamilyLabel("google/gemini-3.8-flash"))
        assertEquals("Gemini", modelFamilyLabel("gemini-4-neon"))
        assertEquals("Claude", modelFamilyLabel("openrouter/anthropic/claude-opus-4-6"))
        assertEquals("Claude", modelFamilyLabel("gapi/claude-sonnet-4-6"))
        assertEquals("GPT / Codex", modelFamilyLabel("openai/gpt-6.2-astra"))
        assertEquals("GPT / Codex", modelFamilyLabel("gapi/codex-auto-review"))
        assertEquals("Kimi", modelFamilyLabel("moonshot/kimi-k3.1-pro"))
        assertEquals("DeepSeek", modelFamilyLabel("volc/deepseek-r1"))
        assertEquals("其他", modelFamilyLabel("custom/unknown-model"))
    }

    @Test
    fun `matchesModelSearchQuery supports slash and whitespace multi-term queries with strict prefix handling`() {
        assertTrue(matchesModelSearchQuery("", "gapi/gemini-4-neon"))
        assertTrue(matchesModelSearchQuery("gapi/neon", "gapi/gemini-4-neon"))
        assertFalse(matchesModelSearchQuery("gapi/neon", "gemini-4-neon"))
        assertTrue(matchesModelSearchQuery("gapi neon", "gapi/gemini-4-neon"))
        assertFalse(matchesModelSearchQuery("gapi neon", "gemini-4-neon"))
        assertTrue(matchesModelSearchQuery("gapi/", "gapi/gemini-4-neon"))
        assertFalse(matchesModelSearchQuery("gapi/", "my-gapi-model"))
        assertTrue(matchesModelSearchQuery("/", "gapi/gemini-4-neon"))
        assertFalse(matchesModelSearchQuery("/", "gemini-4-neon"))
        assertTrue(matchesModelSearchQuery("neon", "gapi/gemini-4-neon"))
        assertTrue(matchesModelSearchQuery("neon", "gemini-4-neon"))
        assertTrue(matchesModelSearchQuery("cpa neon", "gapi/gemini-4-neon", "CPA", "cpa_local"))
        assertFalse(matchesModelSearchQuery("cpa neon", "gemini-4-neon", "官方", "sub_agy"))
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `providerFilterDialog auto-refreshes, groups gapi gemini under Gemini chip, and matches gapi slash neon`() = runComposeUiTest {
        val refreshedIds = mutableListOf<String?>()
        var savedInput: ProviderConfigInput? = null

        val initialProvider = ProviderInfo(
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
            latencyMs = 9,
            selectedModels = listOf("gapi/gemini-4-carbon", "gpt-6.2-astra"),
            models = listOf("gapi/gemini-4-carbon", "gemini-4-neon", "gpt-6.2-astra", "kimi-k3"),
        )
        var uiState by mutableStateOf(
            ConsoleUiState(
                connectionStatus = ConnectionStatus.CONNECTED,
                hub = HubInfo("hub_mac", "MacBook Pro", HubRole.SLAVE, PlatformType.DARWIN, "0.8.10"),
                providers = listOf(initialProvider),
            ),
        )

        setContent {
            MaterialTheme {
                DesktopSettingsDialog(
                    state = uiState,
                    onDismiss = {},
                    onSwitchHost = {},
                    onRemoveHost = {},
                    onPairAnotherHost = {},
                    onRefreshProviders = { targetId ->
                        refreshedIds.add(targetId)
                        // Simulate CPA returning a newly added model `gapi/gemini-4-neon`
                        if (targetId == "cpa_local") {
                            uiState = uiState.copy(
                                providers = listOf(
                                    initialProvider.copy(
                                        models = listOf(
                                            "gapi/gemini-4-carbon",
                                            "gapi/gemini-4-neon",
                                            "gemini-4-neon",
                                            "gpt-6.2-astra",
                                            "kimi-k3",
                                        ),
                                    ),
                                ),
                            )
                        }
                    },
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
        assertEquals(listOf<String?>("cpa_local"), refreshedIds)

        // All 3 Gemini models (including gapi/*) must be grouped under the "Gemini (3)" chip
        onNodeWithText("Gemini (3)", useUnmergedTree = true).assertIsDisplayed().performClick()
        onNodeWithTag("provider_filter_item_gapi/gemini-4-neon", useUnmergedTree = true).assertIsDisplayed()

        // Multi-term slash query `gapi/neon` matches `gapi/gemini-4-neon` while excluding non-gapi `gemini-4-neon`
        onNodeWithTag("provider_model_filter_search", useUnmergedTree = true).performTextInput("gapi/neon")
        onNodeWithTag("provider_filter_item_gapi/gemini-4-neon", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("provider_filter_item_gemini-4-neon", useUnmergedTree = true).assertDoesNotExist()

        // Check `gapi/gemini-4-neon` and save; existing selectedModels must stay preserved
        onNodeWithTag("provider_filter_item_gapi/gemini-4-neon", useUnmergedTree = true).performClick()
        onNodeWithTag("provider_filter_btn_save", useUnmergedTree = true).performClick()

        assertEquals(
            listOf("gapi/gemini-4-carbon", "gapi/gemini-4-neon", "gpt-6.2-astra"),
            savedInput?.selectedModels,
        )
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `modelPickerDialog matches gapi slash neon across engine models`() = runComposeUiTest {
        val cpaNeon = EngineModelSpec(
            id = "gapi/gemini-4-neon",
            name = "gapi/gemini-4-neon",
            providerId = "cpa_local",
            providerName = "CPA",
        )
        val agyNeon = EngineModelSpec(
            id = "gemini-4-neon",
            name = "Gemini 4 Neon",
            providerId = "sub_agy",
            providerName = "官方",
        )
        val claudeEngine = EngineInfo(
            id = "claude",
            displayName = "Claude Code",
            kind = EngineKind.CLI,
            status = EngineStatus(installed = true, ready = true, authStatus = "authenticated"),
            capabilities = EngineCapabilities(
                permissions = true,
                images = true,
                modelSwitch = true,
                modes = true,
                history = true,
                cancel = true,
            ),
            models = listOf(cpaNeon),
            defaultModel = cpaNeon.id,
        )
        val agyEngine = EngineInfo(
            id = "agy",
            displayName = "Google Antigravity (AGY)",
            kind = EngineKind.CLI,
            status = EngineStatus(installed = true, ready = true, authStatus = "authenticated"),
            capabilities = EngineCapabilities(
                permissions = true,
                images = true,
                modelSwitch = true,
                modes = true,
                history = true,
                cancel = true,
            ),
            models = listOf(agyNeon),
            defaultModel = agyNeon.id,
        )
        val state = ConsoleUiState(
            connectionStatus = ConnectionStatus.CONNECTED,
            engines = listOf(claudeEngine, agyEngine),
            selectedEngineId = "claude",
            selectedProviderId = "cpa_local",
            selectedModelId = cpaNeon.id,
        )

        setContent {
            MaterialTheme {
                DesktopModelPickerDialog(
                    state = state,
                    onSelectEngineAndModel = { _, _, _ -> },
                    onSelectEffort = {},
                    onSelectMode = {},
                    onToggleFavoriteModel = { _, _, _ -> },
                    onDismiss = {},
                )
            }
        }

        onNodeWithTag("model_picker_search_input", useUnmergedTree = true).performTextInput("gapi/neon")
        onNodeWithTag("picker_model_claude_gapi/gemini-4-neon", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("picker_model_agy_gemini-4-neon", useUnmergedTree = true).assertDoesNotExist()
    }
}
