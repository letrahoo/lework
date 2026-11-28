@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.lework

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.lework.platform.GitHubReleaseFetchPayload
import dev.lework.platform.JvmPlatformTestHooks
import dev.lework.platform.SavedSessionPrefs
import dev.lework.store.ConsoleStore
import dev.lework.ui.ConsoleApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidSelfUpdateTest {
    @Before
    fun setUp() {
        JvmPlatformTestHooks.reset()
    }

    private fun sampleAndroidReleaseJson(
        tag: String = "v0.9.3",
        apkName: String = "lework-$tag-android-release.apk",
        apkSha: String = "sha256:45fb1eae74494488241af714fb2dfa4e4dede3f081f71aea4a23a8afcba601ff",
        apkSizeBytes: Long = 41L * 1024L * 1024L,
    ): String =
        """
        {
          "tag_name": "$tag",
          "name": "Lework $tag",
          "draft": false,
          "prerelease": false,
          "html_url": "https://github.com/letrahoo/lework/releases/tag/$tag",
          "published_at": "2026-11-28T04:10:00Z",
          "body": "## What's Changed\n* feat(android): in-app APK self-update with SHA-256 verification",
          "assets": [
            {
              "name": "Lework-$tag-macos-arm64.dmg",
              "browser_download_url": "https://github.com/letrahoo/lework/releases/download/$tag/Lework-$tag-macos-arm64.dmg",
              "size": 115715693
            },
            {
              "name": "$apkName",
              "browser_download_url": "https://github.com/letrahoo/lework/releases/download/$tag/$apkName",
              "size": $apkSizeBytes,
              "digest": "$apkSha"
            },
            {
              "name": "SHA256SUMS.txt",
              "browser_download_url": "https://github.com/letrahoo/lework/releases/download/$tag/SHA256SUMS.txt",
              "size": 196
            }
          ]
        }
        """.trimIndent()

    @Test
    fun storeAndroidInAppUpdateDownloadsVerifiesReportsProgressAndFallsBackWhenUnsupported() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            JvmPlatformTestHooks.simulateDesktopLayout = false
            JvmPlatformTestHooks.platformNameOverride = "Android 15"
            JvmPlatformTestHooks.appVersionOverride = "v0.9.2 (90200)"
            JvmPlatformTestHooks.canInstallAndroidUpdate = true
            JvmPlatformTestHooks.onFetchLatestRelease = { _, _ ->
                Result.success(GitHubReleaseFetchPayload(jsonBody = sampleAndroidReleaseJson("v0.9.3")))
            }

            var receivedUrl: String? = null
            var receivedFile: String? = null
            var receivedSha: String? = null
            var receivedChecksumsUrl: String? = null
            JvmPlatformTestHooks.onInstallAndroidUpdate = { url, fileName, sha, checksumsUrl, onProgress ->
                receivedUrl = url
                receivedFile = fileName
                receivedSha = sha
                receivedChecksumsUrl = checksumsUrl
                onProgress(0.42f, "正在下载 $fileName (42% · 17.2 / 41.0 MB)…")
                Result.success("已校验安装包并打开系统安装界面，请点击「更新」完成升级")
            }

            val store = ConsoleStore(scope, FakeHubTransport())
            store.checkForAppUpdates(manual = true)
            advanceUntilIdle()

            assertTrue(store.state.value.appUpdate.hasUpdate)
            store.startAppUpdateAction()
            advanceUntilIdle()

            assertEquals(
                "https://github.com/letrahoo/lework/releases/download/v0.9.3/lework-v0.9.3-android-release.apk",
                receivedUrl,
            )
            assertEquals("lework-v0.9.3-android-release.apk", receivedFile)
            assertEquals("45fb1eae74494488241af714fb2dfa4e4dede3f081f71aea4a23a8afcba601ff", receivedSha)
            assertEquals(
                "https://github.com/letrahoo/lework/releases/download/v0.9.3/SHA256SUMS.txt",
                receivedChecksumsUrl,
            )
            assertTrue(JvmPlatformTestHooks.openedUrls.isEmpty())
            assertFalse(store.state.value.appUpdate.installing)
            assertEquals(1f, store.state.value.appUpdate.installProgressFraction)
            assertEquals(
                "已校验安装包并打开系统安装界面，请点击「更新」完成升级",
                store.state.value.appUpdate.installStatusText,
            )
            assertNull(store.state.value.appUpdate.installError)

            // Failure surfaces actionable inline error without opening browser
            JvmPlatformTestHooks.onInstallAndroidUpdate = { _, _, _, _, _ ->
                Result.failure(IllegalStateException("尚未开启「允许安装未知应用」权限"))
            }
            store.startAppUpdateAction()
            advanceUntilIdle()
            assertFalse(store.state.value.appUpdate.installing)
            val err = assertNotNull(store.state.value.appUpdate.installError)
            assertTrue(err.contains("允许安装未知应用"))
            assertTrue(JvmPlatformTestHooks.openedUrls.isEmpty())

            // When in-app installer is unavailable, falls back to external APK URL
            JvmPlatformTestHooks.canInstallAndroidUpdate = false
            store.startAppUpdateAction()
            assertTrue(
                JvmPlatformTestHooks.openedUrls.contains(
                    "https://github.com/letrahoo/lework/releases/download/v0.9.3/lework-v0.9.3-android-release.apk",
                ),
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun storeCancelAppUpdateInstallAbortsInFlightAndroidDownloadAndAllowsRetry() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            JvmPlatformTestHooks.simulateDesktopLayout = false
            JvmPlatformTestHooks.platformNameOverride = "Android 15"
            JvmPlatformTestHooks.appVersionOverride = "v0.9.2 (90200)"
            JvmPlatformTestHooks.canInstallAndroidUpdate = true
            JvmPlatformTestHooks.onFetchLatestRelease = { _, _ ->
                Result.success(GitHubReleaseFetchPayload(jsonBody = sampleAndroidReleaseJson("v0.9.3")))
            }

            val gate = CompletableDeferred<Unit>()
            var cancelled = false
            var attempt = 0
            JvmPlatformTestHooks.onInstallAndroidUpdate = { _, fileName, _, _, onProgress ->
                attempt++
                if (attempt == 1) {
                    onProgress(0.25f, "正在下载 $fileName (25%)…")
                    try {
                        gate.await()
                        Result.success("unexpected")
                    } catch (ce: CancellationException) {
                        cancelled = true
                        throw ce
                    }
                } else {
                    Result.success("已校验安装包并打开系统安装界面")
                }
            }

            val store = ConsoleStore(scope, FakeHubTransport())
            store.checkForAppUpdates(manual = true)
            advanceUntilIdle()

            store.startAppUpdateAction()
            testScheduler.runCurrent()
            assertTrue(store.state.value.appUpdate.installing)

            store.cancelAppUpdateInstall()
            advanceUntilIdle()
            assertTrue(cancelled)
            assertFalse(store.state.value.appUpdate.installing)
            assertEquals("已取消下载更新", store.state.value.appUpdate.installStatusText)
            assertNull(store.state.value.appUpdate.installError)

            // Retry works immediately after cancel
            store.startAppUpdateAction()
            advanceUntilIdle()
            assertEquals(2, attempt)
            assertFalse(store.state.value.appUpdate.installing)
            assertEquals("已校验安装包并打开系统安装界面", store.state.value.appUpdate.installStatusText)
        } finally {
            scope.cancel()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun mobileAndroidFlowShowsTopBarUpdateDotSupportsInAppInstallCancelAndDismissal() = runComposeUiTest {
        JvmPlatformTestHooks.simulateDesktopLayout = false
        JvmPlatformTestHooks.platformNameOverride = "Android 15"
        JvmPlatformTestHooks.appVersionOverride = "v0.9.2 (90200)"
        JvmPlatformTestHooks.canInstallAndroidUpdate = true
        JvmPlatformTestHooks.savedPrefs = SavedSessionPrefs(mode = "auto", dismissedUpdateTag = null)
        JvmPlatformTestHooks.onFetchLatestRelease = { _, _ ->
            Result.success(GitHubReleaseFetchPayload(jsonBody = sampleAndroidReleaseJson("v0.9.3")))
        }

        val firstAttemptGate = CompletableDeferred<Unit>()
        var attempts = 0
        JvmPlatformTestHooks.onInstallAndroidUpdate = { _, fileName, _, _, onProgress ->
            attempts++
            if (attempts == 1) {
                onProgress(0.35f, "正在下载 $fileName (35% · 14.3 / 41.0 MB)…")
                firstAttemptGate.await()
                Result.success("unreachable")
            } else {
                onProgress(1f, "校验通过，正在打开系统安装界面…")
                Result.success("已校验安装包并打开系统安装界面，请点击「更新」完成升级")
            }
        }

        val fake = FakeHubTransport()
        lateinit var store: ConsoleStore

        setContent {
            val scope = rememberCoroutineScope()
            val created = remember { ConsoleStore(scope, fake) }
            store = created
            Box(modifier = Modifier.size(width = 402.dp, height = 874.dp)) {
                ConsoleApp(externalStore = created, autoBootstrap = true)
            }
        }

        waitUntil(timeoutMillis = 5_000L) {
            store.state.value.hasEnteredWorkspace && store.state.value.appUpdate.hasVisibleBanner
        }

        // TopBar menu icon displays subtle non-intrusive green update indicator
        onNodeWithTag("topbar_update_badge_dot", useUnmergedTree = true).assertIsDisplayed()

        // Open drawer -> click update pill -> update dialog opens with direct-install CTA & package size
        onNodeWithTag("topbar_btn_menu").performClick()
        waitUntil(timeoutMillis = 3_000L) {
            onAllNodesWithTag("drawer_update_pill", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag("drawer_update_pill", useUnmergedTree = true).performClick()

        waitUntil(timeoutMillis = 3_000L) {
            onAllNodesWithTag("app_update_dialog").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag("app_update_dialog").assertIsDisplayed()
        onNodeWithText("41.0 MB", substring = true).assertIsDisplayed()
        onNodeWithText("下载并安装更新", substring = true).assertIsDisplayed()

        // Tap primary button -> starts in-app download & exposes cancel button
        onNodeWithTag("app_update_primary_action_btn").performClick()
        waitUntil(timeoutMillis = 3_000L) {
            onAllNodesWithTag("app_update_cancel_install_btn").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag("app_update_progress_box").assertIsDisplayed()
        onNodeWithTag("app_update_cancel_install_btn").performClick()

        waitUntil(timeoutMillis = 3_000L) { !store.state.value.appUpdate.installing }
        onNodeWithText("已取消下载更新", substring = true).assertIsDisplayed()

        // Retry completes in-app install without opening external browser
        onNodeWithTag("app_update_primary_action_btn").performClick()
        waitUntil(timeoutMillis = 3_000L) {
            attempts == 2 && !store.state.value.appUpdate.installing
        }
        assertTrue(JvmPlatformTestHooks.openedUrls.isEmpty())
        onNodeWithText("已校验安装包并打开系统安装界面", substring = true).assertIsDisplayed()

        // Dismiss version -> closes dialog and clears TopBar dot
        onNodeWithTag("app_update_dismiss_version_btn").performClick()
        waitUntil(timeoutMillis = 3_000L) {
            !store.state.value.appUpdate.showUpdateDialog && !store.state.value.appUpdate.hasVisibleBanner
        }
        assertTrue(onAllNodesWithTag("topbar_update_badge_dot", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }
}
