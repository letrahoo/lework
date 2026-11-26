package dev.lework.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.lework.platform.JvmDesktopRuntime
import dev.lework.platform.appVersionLabel
import dev.lework.platform.logRuntimeEvent
import dev.lework.platform.runtimeLogFilePath
import dev.lework.store.ConsoleStore
import dev.lework.ui.ConsoleApp
import dev.lework.ui.LocalModalEscapeDispatcher
import dev.lework.ui.ModalEscapeDispatcher
import dev.lework.ui.rememberLeworkBrandPainter
import java.awt.Desktop
import java.awt.Dimension
import java.awt.Frame
import java.awt.desktop.AppReopenedEvent
import java.awt.desktop.AppReopenedListener
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener
import javax.swing.SwingUtilities

fun main(args: Array<String>) {
    System.setProperty("apple.awt.application.name", "Lework")
    System.setProperty("apple.awt.application.appearance", "NSAppearanceNameDarkAqua")

    JvmDesktopRuntime.initialize()
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        val stack = throwable.stackTrace.take(12).joinToString(" <- ") { "${it.className}.${it.methodName}:${it.lineNumber}" }
        logRuntimeEvent(
            "CRASH",
            "uncaught thread=${thread.name} err=${throwable::class.simpleName}:${throwable.message} stack=$stack",
        )
    }
    logRuntimeEvent(
        "APP",
        "desktop start version=${appVersionLabel()} argsCount=${args.size} logPath=${runtimeLogFilePath().orEmpty()}",
    )
    args.firstOrNull { it.startsWith("lework://", ignoreCase = true) }?.let { rawUri ->
        JvmDesktopRuntime.emitIncomingPairingUri(rawUri)
    }

    application {
        val appScope = rememberCoroutineScope()
        val store = remember { ConsoleStore(appScope) }
        val uiState by store.state.collectAsState()
        val incomingPairingUri by JvmDesktopRuntime.incomingPairingUri.collectAsState()
        var isWindowVisible by remember { mutableStateOf(true) }
        var globalHotkeyActive by remember { mutableStateOf(false) }
        var openSettingsSignal by remember { mutableIntStateOf(0) }
        var toggleSidebarSignal by remember { mutableIntStateOf(0) }
        var openGlobalSearchSignal by remember { mutableIntStateOf(0) }
        var openInSessionFindSignal by remember { mutableIntStateOf(0) }
        val toggleDebouncer = remember { ToggleHotkeyDebouncer() }
        val modalEscapeDispatcher = remember { ModalEscapeDispatcher() }
        var windowRef by remember { mutableStateOf<ComposeWindow?>(null) }

        val hostTitle = uiState.hub?.let {
            uiState.activeHostDisplayName.takeIf { name -> name.isNotBlank() }
        }?.let { "Lework — $it" } ?: "Lework"

        val windowState = rememberWindowState(
            position = WindowPosition.Aligned(Alignment.Center),
            size = DpSize(1320.dp, 860.dp),
        )
        val appIcon = rememberLeworkBrandPainter()

        fun showAndFocusWindow(reason: String) {
            logRuntimeEvent("WINDOW", "show requested reason=$reason")
            isWindowVisible = true
            val awtWindow = windowRef
            if (awtWindow != null) {
                if ((awtWindow.extendedState and Frame.ICONIFIED) != 0) {
                    awtWindow.extendedState = awtWindow.extendedState and Frame.ICONIFIED.inv()
                }
                if (!awtWindow.isVisible) {
                    awtWindow.isVisible = true
                }
                awtWindow.toFront()
                awtWindow.requestFocus()
            }
            MacGlobalHotkeyBridge.activateApp()
            SwingUtilities.invokeLater {
                val win = windowRef ?: return@invokeLater
                if (win.isVisible) {
                    win.toFront()
                    win.requestFocus()
                }
            }
        }

        fun hideWindow(reason: String, keepComposeVisible: Boolean) {
            logRuntimeEvent("WINDOW", "hide requested reason=$reason keepComposeVisible=$keepComposeVisible")
            if (!keepComposeVisible) {
                isWindowVisible = false
            }
            JvmDesktopRuntime.onWindowFocusChanged(false)
            if (MacGlobalHotkeyBridge.isMacOs && MacGlobalHotkeyBridge.hideApp()) {
                return
            }
            val awtWindow = windowRef ?: return
            if (keepComposeVisible) {
                awtWindow.extendedState = awtWindow.extendedState or Frame.ICONIFIED
            } else {
                awtWindow.isVisible = false
            }
        }

        fun handleToggleWindow(appIsActive: Boolean, appIsHidden: Boolean, source: String) {
            if (!toggleDebouncer.shouldAccept()) return
            val awtWindow = windowRef
            val minimized = awtWindow != null && (awtWindow.extendedState and Frame.ICONIFIED) != 0
            val visible = isWindowVisible && (awtWindow?.isVisible != false)
            val action = DesktopWindowTogglePolicy.decide(
                DesktopWindowSnapshot(
                    appIsActive = appIsActive,
                    appIsHidden = appIsHidden,
                    windowIsVisible = visible,
                    windowIsMinimized = minimized,
                ),
            )
            when (action) {
                WindowToggleAction.HIDE -> hideWindow(reason = source, keepComposeVisible = true)
                WindowToggleAction.SHOW -> showAndFocusWindow(reason = source)
            }
        }

        // Bring window to foreground when a pairing deep link (`lework://...`) arrives while hidden.
        LaunchedEffect(incomingPairingUri) {
            if (!incomingPairingUri.isNullOrBlank()) {
                showAndFocusWindow(reason = "deep-link")
            }
        }

        DisposableEffect(Unit) {
            val isMac = MacGlobalHotkeyBridge.isMacOs
            if (isMac) {
                MacGlobalHotkeyBridge.registerToggleHotKeyAsync(
                    onTriggered = { appIsActive, appIsHidden ->
                        handleToggleWindow(
                            appIsActive = appIsActive,
                            appIsHidden = appIsHidden,
                            source = "hotkey-ctrl-cmd-e",
                        )
                    },
                    onResult = { ok ->
                        globalHotkeyActive = ok
                    },
                )
            }

            var reopenListener: AppReopenedListener? = null
            runCatching {
                if (Desktop.isDesktopSupported()) {
                    val desktop = Desktop.getDesktop()
                    if (isMac && desktop.isSupported(Desktop.Action.APP_EVENT_REOPENED)) {
                        val listener = object : AppReopenedListener {
                            override fun appReopened(e: AppReopenedEvent?) {
                                SwingUtilities.invokeLater {
                                    showAndFocusWindow(reason = "dock-reopen")
                                }
                            }
                        }
                        desktop.addAppEventListener(listener)
                        reopenListener = listener
                    }
                    if (isMac && desktop.isSupported(Desktop.Action.APP_QUIT_HANDLER)) {
                        desktop.setQuitHandler { _, response ->
                            logRuntimeEvent("APP", "macOS quit requested")
                            MacGlobalHotkeyBridge.unregister()
                            JvmDesktopRuntime.shutdown()
                            response.performQuit()
                        }
                    }
                }
            }

            onDispose {
                MacGlobalHotkeyBridge.unregister()
                reopenListener?.let { listener ->
                    runCatching {
                        if (Desktop.isDesktopSupported()) {
                            Desktop.getDesktop().removeAppEventListener(listener)
                        }
                    }
                }
            }
        }

        Window(
            onCloseRequest = {
                if (MacGlobalHotkeyBridge.isMacOs) {
                    // Match macOS WeChat/Slack convention: closing the window hides to Dock so
                    // `Ctrl + Cmd + E` or clicking the Dock icon brings Lework back immediately.
                    hideWindow(reason = "close-button", keepComposeVisible = false)
                } else {
                    logRuntimeEvent("APP", "window close requested")
                    MacGlobalHotkeyBridge.unregister()
                    JvmDesktopRuntime.shutdown()
                    exitApplication()
                }
            },
            visible = isWindowVisible,
            state = windowState,
            title = hostTitle,
            icon = appIcon,
            onPreviewKeyEvent = { ev ->
                if (ev.type == KeyEventType.KeyDown && ev.key == Key.Escape) {
                    modalEscapeDispatcher.dispatchEscape()
                } else {
                    false
                }
            },
        ) {
            DisposableEffect(window) {
                windowRef = window
                window.minimumSize = Dimension(760, 520)
                val listener = object : WindowFocusListener {
                    override fun windowGainedFocus(e: WindowEvent?) {
                        JvmDesktopRuntime.onWindowFocusChanged(true)
                    }

                    override fun windowLostFocus(e: WindowEvent?) {
                        JvmDesktopRuntime.onWindowFocusChanged(false)
                    }
                }
                window.addWindowFocusListener(listener)
                onDispose {
                    window.removeWindowFocusListener(listener)
                    if (windowRef === window) {
                        windowRef = null
                    }
                }
            }

            MenuBar {
                Menu("会话") {
                    Item(
                        text = "新建会话",
                        shortcut = KeyShortcut(Key.N, meta = true),
                        onClick = store::startNewDraftSession,
                    )
                    Item(
                        text = "刷新会话列表",
                        shortcut = KeyShortcut(Key.R, meta = true),
                        onClick = store::refreshHostSessions,
                    )
                    Item(
                        text = "收起 / 展开侧边栏",
                        shortcut = KeyShortcut(Key.B, meta = true),
                        onClick = { toggleSidebarSignal++ },
                    )
                    Separator()
                    Item(
                        text = "全文搜索历史对话…",
                        shortcut = KeyShortcut(Key.F, meta = true, shift = true),
                        onClick = { openGlobalSearchSignal++ },
                    )
                    Item(
                        text = "在当前会话中查找…",
                        shortcut = KeyShortcut(Key.F, meta = true),
                        onClick = { openInSessionFindSignal++ },
                    )
                    Separator()
                    Item(
                        text = "设置…",
                        shortcut = KeyShortcut(Key.Comma, meta = true),
                        onClick = { openSettingsSignal++ },
                    )
                    Separator()
                    Item(
                        text = "连接其他开发机…",
                        shortcut = KeyShortcut(Key.P, meta = true, shift = true),
                        onClick = store::preparePairAnotherHost,
                    )
                    Item(
                        text = "从剪贴板粘贴配对链接",
                        shortcut = KeyShortcut(Key.V, meta = true, shift = true),
                        onClick = store::pastePairingFromClipboard,
                    )
                    if (uiState.pairedHosts.size > 1) {
                        Separator()
                        Menu("切换已连接主机") {
                            for (host in uiState.pairedHosts) {
                                val label = if (host.hubId == uiState.hub?.id) "✓ ${host.displayName}" else host.displayName
                                Item(
                                    text = label,
                                    onClick = { store.switchPairedHost(host.hubId) },
                                )
                            }
                        }
                    }
                }
                Menu("窗口") {
                    // When the macOS Carbon global hotkey is active, omit the AWT menu accelerator
                    // to prevent double-firing; keep the visual shortcut hint in the label.
                    Item(
                        text = if (globalHotkeyActive) "显示 / 隐藏 Lework (⌃⌘E)" else "显示 / 隐藏 Lework",
                        shortcut = if (globalHotkeyActive) null else KeyShortcut(Key.E, meta = true, ctrl = true),
                        onClick = {
                            val focused = windowRef?.isFocused == true
                            handleToggleWindow(
                                appIsActive = focused,
                                appIsHidden = !isWindowVisible,
                                source = "menu-toggle",
                            )
                        },
                    )
                    if (MacGlobalHotkeyBridge.isMacOs) {
                        Item(
                            text = "关闭窗口到后台",
                            shortcut = KeyShortcut(Key.W, meta = true),
                            onClick = { hideWindow(reason = "menu-close-window", keepComposeVisible = false) },
                        )
                    }
                }
            }

            CompositionLocalProvider(LocalModalEscapeDispatcher provides modalEscapeDispatcher) {
                ConsoleApp(
                    externalStore = store,
                    openSettingsSignal = openSettingsSignal,
                    toggleSidebarSignal = toggleSidebarSignal,
                    openGlobalSearchSignal = openGlobalSearchSignal,
                    openInSessionFindSignal = openInSessionFindSignal,
                )
            }
        }
    }
}
