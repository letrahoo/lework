package dev.lework.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopWindowToggleControllerTest {
    @Test
    fun hidesOnlyWhenActiveUnhiddenVisibleAndNotMinimized() {
        assertEquals(
            WindowToggleAction.HIDE,
            DesktopWindowTogglePolicy.decide(
                DesktopWindowSnapshot(
                    appIsActive = true,
                    appIsHidden = false,
                    windowIsVisible = true,
                    windowIsMinimized = false,
                ),
            ),
        )
    }

    @Test
    fun showsWhenHiddenUnfocusedClosedOrMinimized() {
        val showCases = listOf(
            // Previously hidden via Ctrl+Cmd+E or Cmd+H
            DesktopWindowSnapshot(appIsActive = false, appIsHidden = true, windowIsVisible = true, windowIsMinimized = false),
            // Visible behind another application window
            DesktopWindowSnapshot(appIsActive = false, appIsHidden = false, windowIsVisible = true, windowIsMinimized = false),
            // Closed to background via red close button / Cmd+W
            DesktopWindowSnapshot(appIsActive = true, appIsHidden = false, windowIsVisible = false, windowIsMinimized = false),
            // Minimized in Dock
            DesktopWindowSnapshot(appIsActive = true, appIsHidden = false, windowIsVisible = true, windowIsMinimized = true),
            // App active yet hidden flag reported true
            DesktopWindowSnapshot(appIsActive = true, appIsHidden = true, windowIsVisible = true, windowIsMinimized = false),
        )
        for (snapshot in showCases) {
            assertEquals(WindowToggleAction.SHOW, DesktopWindowTogglePolicy.decide(snapshot), "Snapshot=$snapshot")
        }
    }

    @Test
    fun debouncerBlocksRapidKeyRepeatAndAllowsSubsequentToggle() {
        var nowMs = 1_000L
        val debouncer = ToggleHotkeyDebouncer(minIntervalMs = 220L, clockMs = { nowMs })

        assertTrue(debouncer.shouldAccept())
        nowMs += 50L
        assertFalse(debouncer.shouldAccept())
        nowMs += 169L
        assertFalse(debouncer.shouldAccept())
        nowMs += 1L
        assertTrue(debouncer.shouldAccept())
    }

    @Test
    fun macHotkeyConstantsMatchCarbonCtrlCmdE() {
        assertEquals(0x0E, MacGlobalHotkeyBridge.MAC_VK_ANSI_E)
        assertEquals(0x0100, MacGlobalHotkeyBridge.MAC_MOD_CMD)
        assertEquals(0x1000, MacGlobalHotkeyBridge.MAC_MOD_CTRL)
        assertEquals(0x1100, MacGlobalHotkeyBridge.TOGGLE_MODIFIERS)
    }
}
