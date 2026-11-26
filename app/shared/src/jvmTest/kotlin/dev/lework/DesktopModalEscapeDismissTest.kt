package dev.lework

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import dev.lework.store.ConsoleUiState
import dev.lework.ui.LocalModalEscapeDispatcher
import dev.lework.ui.ModalEscapeDispatcher
import dev.lework.ui.desktop.DesktopModalFrame
import dev.lework.ui.desktop.DesktopSettingsDialog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DesktopModalEscapeDismissTest {
    @Test
    fun dispatcherHandlesLifoNestedDialogsAndEmptyStack() {
        val dispatcher = ModalEscapeDispatcher()
        assertFalse(dispatcher.hasActiveModals())
        assertFalse(dispatcher.dispatchEscape())

        val closed = mutableListOf<String>()
        val parentId = dispatcher.push { closed += "settings" }
        val childId = dispatcher.push { closed += "provider-editor" }

        assertTrue(dispatcher.hasActiveModals())
        assertTrue(dispatcher.dispatchEscape())
        assertEquals(listOf("provider-editor"), closed)

        dispatcher.remove(childId)
        assertTrue(dispatcher.dispatchEscape())
        assertEquals(listOf("provider-editor", "settings"), closed)

        dispatcher.remove(parentId)
        assertFalse(dispatcher.hasActiveModals())
        assertFalse(dispatcher.dispatchEscape())
    }

    @Test
    fun pressingEscapeImmediatelyDismissesSettingsDialogWithoutPriorClick() = runComposeUiTest {
        var showSettings by mutableStateOf(true)

        setContent {
            MaterialTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    if (showSettings) {
                        DesktopSettingsDialog(
                            state = ConsoleUiState(hasEnteredWorkspace = true),
                            onDismiss = { showSettings = false },
                            onSwitchHost = {},
                            onRemoveHost = {},
                            onPairAnotherHost = {},
                            onLogout = {},
                        )
                    }
                }
            }
        }

        waitForIdle()
        onAllNodesWithTag("desktop_settings_dialog").assertCountEquals(1)

        // Pressing Esc directly on the dialog surface (without clicking any input first) must close it.
        onNodeWithTag("desktop_settings_dialog").performKeyInput {
            pressKey(Key.Escape)
        }
        waitForIdle()

        assertFalse(showSettings)
        onAllNodesWithTag("desktop_settings_dialog").assertCountEquals(0)
    }

    @Test
    fun nestedModalsDismissTopmostFirstViaModalEscapeDispatcher() = runComposeUiTest {
        val dispatcher = ModalEscapeDispatcher()
        var showParent by mutableStateOf(true)
        var showChild by mutableStateOf(true)

        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalModalEscapeDispatcher provides dispatcher) {
                    Box(modifier = Modifier.fillMaxSize().testTag("host_root")) {
                        if (showParent) {
                            DesktopModalFrame(
                                onDismiss = { showParent = false },
                                testTag = "parent_modal",
                            ) {
                                Text("Parent")
                            }
                        }
                        if (showChild) {
                            DesktopModalFrame(
                                onDismiss = { showChild = false },
                                testTag = "child_modal",
                            ) {
                                Text("Child")
                            }
                        }
                    }
                }
            }
        }

        waitForIdle()
        assertTrue(dispatcher.hasActiveModals())

        // First Escape closes only the topmost child modal
        assertTrue(dispatcher.dispatchEscape())
        waitForIdle()
        assertFalse(showChild)
        assertTrue(showParent)

        // Second Escape closes the parent modal without needing to manually re-focus it
        assertTrue(dispatcher.dispatchEscape())
        waitForIdle()
        assertFalse(showParent)
        assertFalse(dispatcher.hasActiveModals())
    }
}
