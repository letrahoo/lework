package dev.lework

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import dev.lework.platform.GlobalCliInstallStatus
import dev.lework.platform.JvmPlatformTestHooks
import dev.lework.store.ConsoleUiState
import dev.lework.ui.desktop.DesktopSettingsDialog
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DesktopGlobalCliSettingsTest {
    @BeforeTest
    fun setUp() {
        JvmPlatformTestHooks.reset()
    }

    @AfterTest
    fun tearDown() {
        JvmPlatformTestHooks.reset()
    }

    @Test
    fun settingsDialogRegistersAndUninstallsGlobalLeworkCommand() = runComposeUiTest {
        var installCalls = 0
        var uninstallCalls = 0
        JvmPlatformTestHooks.globalCliStatus = GlobalCliInstallStatus(
            supported = true,
            installed = false,
            installDirectory = "/opt/homebrew/bin",
            detail = "点击按钮将 lework 注册到全局命令行目录",
        )
        JvmPlatformTestHooks.onInstallGlobalCli = {
            installCalls++
            val next = GlobalCliInstallStatus(
                supported = true,
                installed = true,
                installedPath = "/opt/homebrew/bin/lework",
                launcherTargetPath = "/Users/dev/lework/hub/bin/hub.js",
                inShellPath = true,
                installDirectory = "/opt/homebrew/bin",
                detail = "已注册到 /opt/homebrew/bin/lework，重开或当前终端可直接运行 lework",
            )
            JvmPlatformTestHooks.globalCliStatus = next
            Result.success(next)
        }
        JvmPlatformTestHooks.onUninstallGlobalCli = {
            uninstallCalls++
            val next = GlobalCliInstallStatus(
                supported = true,
                installed = false,
                installDirectory = "/opt/homebrew/bin",
            )
            JvmPlatformTestHooks.globalCliStatus = next
            Result.success(next)
        }

        setContent {
            MaterialTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    DesktopSettingsDialog(
                        state = ConsoleUiState(hasEnteredWorkspace = true),
                        onDismiss = {},
                        onSwitchHost = {},
                        onRemoveHost = {},
                        onPairAnotherHost = {},
                        onLogout = {},
                    )
                }
            }
        }

        waitForIdle()
        onNodeWithTag("settings_global_cli_section").performScrollTo()
        onNodeWithTag("settings_global_cli_badge").assertTextContains("未注册")

        onNodeWithTag("settings_btn_install_global_cli").performScrollTo().performClick()
        waitForIdle()

        assertEquals(1, installCalls)
        onNodeWithTag("settings_global_cli_badge").assertTextContains("已注册")
        onNodeWithTag("settings_global_cli_path").assertTextContains("/opt/homebrew/bin/lework", substring = true)

        onNodeWithTag("settings_btn_uninstall_global_cli").performScrollTo().performClick()
        waitForIdle()

        assertEquals(1, uninstallCalls)
        onNodeWithTag("settings_global_cli_badge").assertTextContains("未注册")
    }

    @Test
    fun settingsDialogShowsPathExportCopyActionWhenDirectoryNotInShellPath() = runComposeUiTest {
        JvmPlatformTestHooks.globalCliStatus = GlobalCliInstallStatus(
            supported = true,
            installed = true,
            installedPath = "/Users/dev/.local/bin/lework",
            inShellPath = false,
            installDirectory = "/Users/dev/.local/bin",
            detail = "已写入 ~/.local/bin/lework，但尚未加入 PATH",
        )

        setContent {
            MaterialTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    DesktopSettingsDialog(
                        state = ConsoleUiState(hasEnteredWorkspace = true),
                        onDismiss = {},
                        onSwitchHost = {},
                        onRemoveHost = {},
                        onPairAnotherHost = {},
                        onLogout = {},
                    )
                }
            }
        }

        waitForIdle()
        onNodeWithTag("settings_global_cli_section").performScrollTo()
        onNodeWithTag("settings_global_cli_badge").assertTextContains("需配置 PATH")
        onNodeWithTag("settings_btn_copy_cli_path_export").performScrollTo().performClick()
        waitForIdle()

        assertTrue(JvmPlatformTestHooks.clipboardText.contains("/Users/dev/.local/bin"))
    }

    @Test
    fun settingsDialogHidesGlobalCliSectionWhenUnsupported() = runComposeUiTest {
        JvmPlatformTestHooks.globalCliStatus = GlobalCliInstallStatus(supported = false)

        setContent {
            MaterialTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    DesktopSettingsDialog(
                        state = ConsoleUiState(hasEnteredWorkspace = true),
                        onDismiss = {},
                        onSwitchHost = {},
                        onRemoveHost = {},
                        onPairAnotherHost = {},
                        onLogout = {},
                    )
                }
            }
        }

        waitForIdle()
        onAllNodesWithTag("settings_global_cli_section").assertCountEquals(0)
    }
}
