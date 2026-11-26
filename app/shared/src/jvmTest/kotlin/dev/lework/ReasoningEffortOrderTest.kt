package dev.lework

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import dev.lework.protocol.*
import dev.lework.store.*
import dev.lework.ui.desktop.DesktopModelPickerDialog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReasoningEffortOrderTest {
    private fun agyEngineWithUnsortedEfforts(): EngineInfo = EngineInfo(
        id = "agy",
        displayName = "Google Antigravity (AGY)",
        kind = EngineKind.CLI,
        status = EngineStatus(installed = true, ready = true, authStatus = "authenticated"),
        capabilities = EngineCapabilities(true, true, true, true, true, true),
        models = listOf(
            EngineModelSpec(
                id = "gemini-4-neon",
                name = "Gemini 4 Neon",
                providerId = "sub_agy",
                providerName = "官方",
                efforts = listOf(
                    ReasoningEffort.HIGH,
                    ReasoningEffort.XHIGH,
                    ReasoningEffort.MEDIUM,
                    ReasoningEffort.LOW,
                ),
                defaultEffort = ReasoningEffort.HIGH,
            ),
            EngineModelSpec(
                id = "gemini-4-carbon",
                name = "Gemini 4 Carbon",
                providerId = "sub_agy",
                providerName = "官方",
                efforts = listOf(
                    ReasoningEffort.MEDIUM,
                    ReasoningEffort.HIGH,
                    ReasoningEffort.LOW,
                ),
                defaultEffort = ReasoningEffort.HIGH,
            ),
            EngineModelSpec(
                id = "gemini-max-model",
                name = "Gemini Max Model",
                providerId = "sub_agy",
                providerName = "官方",
                efforts = listOf(
                    ReasoningEffort.MAX,
                    ReasoningEffort.HIGH,
                    ReasoningEffort.LOW,
                    ReasoningEffort.XHIGH,
                    ReasoningEffort.MEDIUM,
                    ReasoningEffort.HIGH,
                ),
                defaultEffort = ReasoningEffort.HIGH,
            ),
        ),
        defaultModel = "gemini-4-neon",
        defaultMode = SessionMode.AUTO,
        defaultEffort = ReasoningEffort.HIGH,
        supportedModes = listOf(SessionMode.AUTO, SessionMode.PLAN, SessionMode.DEFAULT),
    )

    @Test
    fun availableEffortsSortsAndDeduplicatesFromLowToMax() {
        val engine = agyEngineWithUnsortedEfforts()
        val base = ConsoleUiState(
            connectionStatus = ConnectionStatus.CONNECTED,
            engines = listOf(engine),
            selectedEngineId = "agy",
            selectedProviderId = "sub_agy",
        )

        assertEquals(
            listOf(
                ReasoningEffort.LOW,
                ReasoningEffort.MEDIUM,
                ReasoningEffort.HIGH,
                ReasoningEffort.XHIGH,
            ),
            base.copy(selectedModelId = "gemini-4-neon").availableEfforts,
        )

        assertEquals(
            listOf(
                ReasoningEffort.LOW,
                ReasoningEffort.MEDIUM,
                ReasoningEffort.HIGH,
            ),
            base.copy(selectedModelId = "gemini-4-carbon").availableEfforts,
        )

        assertEquals(
            listOf(
                ReasoningEffort.LOW,
                ReasoningEffort.MEDIUM,
                ReasoningEffort.HIGH,
                ReasoningEffort.XHIGH,
                ReasoningEffort.MAX,
            ),
            base.copy(selectedModelId = "gemini-max-model").availableEfforts,
        )
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun desktopModelPickerRendersReasoningEffortsInAscendingOrder() = runComposeUiTest {
        val engine = agyEngineWithUnsortedEfforts()
        val state = ConsoleUiState(
            connectionStatus = ConnectionStatus.CONNECTED,
            hub = HubInfo("hub_mac", "MacBook Pro", HubRole.SLAVE, PlatformType.DARWIN, "0.8.10"),
            engines = listOf(engine),
            selectedEngineId = "agy",
            selectedProviderId = "sub_agy",
            selectedModelId = "gemini-4-neon",
            selectedEffort = ReasoningEffort.XHIGH,
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

        val lowNode = onNodeWithTag("picker_effort_low", useUnmergedTree = true).assertIsDisplayed()
        val mediumNode = onNodeWithTag("picker_effort_medium", useUnmergedTree = true).assertIsDisplayed()
        val highNode = onNodeWithTag("picker_effort_high", useUnmergedTree = true).assertIsDisplayed()
        val xhighNode = onNodeWithTag("picker_effort_xhigh", useUnmergedTree = true).assertIsDisplayed()

        val lowTop = lowNode.getUnclippedBoundsInRoot().top
        val mediumTop = mediumNode.getUnclippedBoundsInRoot().top
        val highTop = highNode.getUnclippedBoundsInRoot().top
        val xhighTop = xhighNode.getUnclippedBoundsInRoot().top

        assertTrue(lowTop < mediumTop, "Expected LOW ($lowTop) above MEDIUM ($mediumTop)")
        assertTrue(mediumTop < highTop, "Expected MEDIUM ($mediumTop) above HIGH ($highTop)")
        assertTrue(highTop < xhighTop, "Expected HIGH ($highTop) above XHIGH ($xhighTop)")
    }
}
