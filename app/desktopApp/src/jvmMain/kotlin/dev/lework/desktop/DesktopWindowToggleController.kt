package dev.lework.desktop

internal enum class WindowToggleAction {
    SHOW,
    HIDE,
}

internal data class DesktopWindowSnapshot(
    val appIsActive: Boolean,
    val appIsHidden: Boolean,
    val windowIsVisible: Boolean,
    val windowIsMinimized: Boolean,
)

/**
 * Pure decision policy mirroring macOS WeChat (`Ctrl + Cmd + W`) / Spotlight toggle behavior:
 * - Only hides when Lework is currently active, unhidden, visible, and not minimized.
 * - In every other state (hidden, unfocused behind another app, minimized in Dock, or closed
 *   to background), brings the window immediately to front and focuses it.
 */
internal object DesktopWindowTogglePolicy {
    fun decide(snapshot: DesktopWindowSnapshot): WindowToggleAction {
        val isForegroundVisible =
            !snapshot.appIsHidden &&
                snapshot.appIsActive &&
                snapshot.windowIsVisible &&
                !snapshot.windowIsMinimized
        return if (isForegroundVisible) WindowToggleAction.HIDE else WindowToggleAction.SHOW
    }
}

/**
 * Guards against OS key-repeat or accidental double-delivery when toggling window visibility.
 */
internal class ToggleHotkeyDebouncer(
    private val minIntervalMs: Long = 220L,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    private var lastAcceptedAtMs: Long = Long.MIN_VALUE

    fun shouldAccept(): Boolean {
        val now = clockMs()
        if (lastAcceptedAtMs != Long.MIN_VALUE && now - lastAcceptedAtMs < minIntervalMs) {
            return false
        }
        lastAcceptedAtMs = now
        return true
    }
}
