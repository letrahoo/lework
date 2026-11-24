package dev.lework.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.lework.platform.JvmDesktopRuntime
import dev.lework.store.ConsoleStore
import dev.lework.ui.ConsoleApp
import dev.lework.ui.rememberLeworkBrandPainter
import java.awt.Dimension
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener

fun main(args: Array<String>) {
    System.setProperty("apple.awt.application.name", "Lework")
    System.setProperty("apple.awt.application.appearance", "NSAppearanceNameDarkAqua")

    JvmDesktopRuntime.initialize()
    args.firstOrNull { it.startsWith("lework://", ignoreCase = true) }?.let { rawUri ->
        JvmDesktopRuntime.emitIncomingPairingUri(rawUri)
    }

    application {
        val appScope = rememberCoroutineScope()
        val store = remember { ConsoleStore(appScope) }
        val uiState by store.state.collectAsState()
        var openSettingsSignal by remember { mutableIntStateOf(0) }
        var toggleSidebarSignal by remember { mutableIntStateOf(0) }
        var openGlobalSearchSignal by remember { mutableIntStateOf(0) }
        var openInSessionFindSignal by remember { mutableIntStateOf(0) }
        val hostTitle = uiState.hub?.let {
            uiState.activeHostDisplayName.takeIf { name -> name.isNotBlank() }
        }?.let { "Lework — $it" } ?: "Lework"

        val windowState = rememberWindowState(
            position = WindowPosition.Aligned(Alignment.Center),
            size = DpSize(1320.dp, 860.dp),
        )
        val appIcon = rememberLeworkBrandPainter()

        Window(
            onCloseRequest = {
                JvmDesktopRuntime.shutdown()
                exitApplication()
            },
            state = windowState,
            title = hostTitle,
            icon = appIcon,
        ) {
            DisposableEffect(window) {
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
            }

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
