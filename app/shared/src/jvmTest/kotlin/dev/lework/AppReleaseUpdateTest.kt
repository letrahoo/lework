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
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.lework.platform.GitHubReleaseFetchPayload
import dev.lework.platform.JvmPlatformTestHooks
import dev.lework.platform.SavedSessionPrefs
import dev.lework.store.ConsoleStore
import dev.lework.store.SemVer
import dev.lework.store.extractReleaseHighlightSummary
import dev.lework.store.isTrustedGitHubReleaseUrl
import dev.lework.store.parseGitHubLatestReleaseJson
import dev.lework.store.parseGitHubRedirectReleaseTag
import dev.lework.store.parseSemVer
import dev.lework.store.parseSha256Sums
import dev.lework.ui.ConsoleApp
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

class AppReleaseUpdateTest {
    @Before
    fun setUp() {
        JvmPlatformTestHooks.reset()
    }

    private fun sampleReleaseJson(
        tag: String = "v0.8.20",
        draft: Boolean = false,
        prerelease: Boolean = false,
        dmgUrl: String = "https://github.com/letrahoo/lework/releases/download/$tag/Lework-$tag-macos-arm64.dmg",
        dmgSha: String = "sha256:3576139add0c0a28831237cec52431539015fadc4017b8a7f695830d5c0dae49",
    ): String =
        """
        {
          "tag_name": "$tag",
          "name": "Lework $tag",
          "draft": $draft,
          "prerelease": $prerelease,
          "html_url": "https://github.com/letrahoo/lework/releases/tag/$tag",
          "published_at": "2026-11-27T06:43:20Z",
          "body": "## What's Changed\n* feat(app): release update notifier and one-click DMG installer by @letrahoo in https://github.com/letrahoo/lework/pull/48\n* fix(ui): polish sidebar footer layout",
          "assets": [
            {
              "name": "Lework-$tag-macos-arm64.dmg",
              "browser_download_url": "$dmgUrl",
              "size": 115715693,
              "digest": "$dmgSha"
            },
            {
              "name": "lework-$tag-android-debug.apk",
              "browser_download_url": "https://github.com/letrahoo/lework/releases/download/$tag/lework-$tag-android-debug.apk",
              "size": 40702040
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
    fun semverParsingAndComparisonCoversDesktopAndroidAndIosLabels() {
        assertEquals(SemVer(0, 8, 19), parseSemVer("v0.8.19"))
        assertEquals(SemVer(0, 8, 19), parseSemVer("Lework Desktop v0.8.19"))
        assertEquals(SemVer(0, 8, 19), parseSemVer("v0.8.19 (81900)"))
        assertEquals(SemVer(0, 8, 19), parseSemVer("v0.8.19 (20261127.0643)"))
        assertNull(parseSemVer("dev-snapshot"))

        assertTrue(SemVer(0, 8, 20) > SemVer(0, 8, 19))
        assertTrue(SemVer(0, 9, 0) > SemVer(0, 8, 99))
        assertTrue(SemVer(1, 0, 0) > SemVer(0, 99, 99))
        assertEquals(0, SemVer(0, 8, 19).compareTo(SemVer(0, 8, 19)))
    }

    @Test
    fun parserExtractsAssetsValidatesHostsAndHandlesFallbackRedirect() {
        val parsed = parseGitHubLatestReleaseJson(sampleReleaseJson("v0.8.20"))
        assertNotNull(parsed)
        assertEquals("v0.8.20", parsed.tagName)
        assertEquals(SemVer(0, 8, 20), parsed.version)
        assertEquals("Lework-v0.8.20-macos-arm64.dmg", parsed.macDmgAsset?.name)
        assertEquals(
            "3576139add0c0a28831237cec52431539015fadc4017b8a7f695830d5c0dae49",
            parsed.macDmgAsset?.sha256,
        )
        assertEquals("lework-v0.8.20-android-debug.apk", parsed.androidApkAsset?.name)
        assertEquals(
            "https://github.com/letrahoo/lework/releases/download/v0.8.20/SHA256SUMS.txt",
            parsed.checksumsUrl,
        )
        assertEquals(
            "feat(app): release update notifier and one-click DMG installer",
            extractReleaseHighlightSummary(parsed.releaseNotes),
        )

        // Drafts and prereleases are ignored
        assertNull(parseGitHubLatestReleaseJson(sampleReleaseJson("v0.8.21", draft = true)))
        assertNull(parseGitHubLatestReleaseJson(sampleReleaseJson("v0.8.21", prerelease = true)))

        // Untrusted asset hosts are stripped
        val tampered = parseGitHubLatestReleaseJson(
            sampleReleaseJson(
                tag = "v0.8.22",
                dmgUrl = "https://evil.example/Lework-v0.8.22-macos-arm64.dmg",
            ),
        )
        assertNotNull(tampered)
        assertNull(tampered.macDmgAsset)
        assertFalse(isTrustedGitHubReleaseUrl("https://github.com@evil.example/releases/tag/v0.8.22"))
        assertFalse(isTrustedGitHubReleaseUrl("http://github.com/letrahoo/lework/releases/tag/v0.8.22"))

        // SHA256SUMS.txt parsing
        val sums = """
            45fb1eae74494488241af714fb2dfa4e4dede3f081f71aea4a23a8afcba601ff  lework-v0.8.19-android-debug.apk
            3576139add0c0a28831237cec52431539015fadc4017b8a7f695830d5c0dae49  Lework-v0.8.19-macos-arm64.dmg
        """.trimIndent()
        assertEquals(
            "3576139add0c0a28831237cec52431539015fadc4017b8a7f695830d5c0dae49",
            parseSha256Sums(sums, "Lework-v0.8.19-macos-arm64.dmg"),
        )
        assertNull(parseSha256Sums(sums, "missing.dmg"))

        // Web redirect fallback (when GitHub API rate-limits IP)
        val fallback = parseGitHubRedirectReleaseTag("https://github.com/letrahoo/lework/releases/tag/v0.8.25")
        assertNotNull(fallback)
        assertEquals("v0.8.25", fallback.tagName)
        assertEquals("Lework-v0.8.25-macos-arm64.dmg", fallback.macDmgAsset?.name)
    }

    @Test
    fun storeChecksUpdatesThrottlesAutoChecksPersistsDismissalAndReappearsOnNewerTag() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            JvmPlatformTestHooks.appVersionOverride = "Lework Desktop v0.8.19"
            var fetchCount = 0
            var currentTag = "v0.8.20"
            JvmPlatformTestHooks.onFetchLatestRelease = { _, _ ->
                fetchCount++
                Result.success(GitHubReleaseFetchPayload(jsonBody = sampleReleaseJson(currentTag)))
            }

            val store = ConsoleStore(scope, FakeHubTransport())
            store.checkForAppUpdates(manual = false)
            advanceUntilIdle()

            assertEquals(1, fetchCount)
            assertTrue(store.state.value.appUpdate.hasUpdate)
            assertTrue(store.state.value.appUpdate.hasVisibleBanner)
            assertEquals("v0.8.20", store.state.value.appUpdate.latestRelease?.tagName)

            // Automatic check within 2-hour cooldown is throttled
            store.checkForAppUpdates(manual = false)
            advanceUntilIdle()
            assertEquals(1, fetchCount)

            // Dismissing v0.8.20 hides banner and persists dismissedUpdateTag
            store.dismissAppUpdateReminder()
            assertFalse(store.state.value.appUpdate.hasVisibleBanner)
            assertEquals("v0.8.20", JvmPlatformTestHooks.savedPrefs.dismissedUpdateTag)

            // Manual re-check on same tag keeps dismissal; newer tag v0.8.21 re-surfaces banner!
            store.checkForAppUpdates(manual = true)
            advanceUntilIdle()
            assertEquals(2, fetchCount)
            assertFalse(store.state.value.appUpdate.hasVisibleBanner)

            currentTag = "v0.8.21"
            store.checkForAppUpdates(manual = true)
            advanceUntilIdle()
            assertEquals(3, fetchCount)
            assertTrue(store.state.value.appUpdate.hasVisibleBanner)
            assertEquals("v0.8.21", store.state.value.appUpdate.latestRelease?.tagName)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun storeSilencesAutoNetworkErrorsAndSurfacesManualErrors() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            JvmPlatformTestHooks.appVersionOverride = "Lework Desktop v0.8.19"
            JvmPlatformTestHooks.onFetchLatestRelease = { _, _ ->
                Result.failure(IllegalStateException("Network unreachable"))
            }

            val store = ConsoleStore(scope, FakeHubTransport())
            store.checkForAppUpdates(manual = false)
            advanceUntilIdle()
            assertNull(store.state.value.appUpdate.checkError)

            store.checkForAppUpdates(manual = true)
            advanceUntilIdle()
            val err = store.state.value.appUpdate.checkError
            assertNotNull(err)
            assertTrue(err.contains("Network unreachable"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun storeDesktopOneClickUpdateReportsProgressAndFallbackOpensReleaseUrl() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            JvmPlatformTestHooks.simulateDesktopLayout = true
            JvmPlatformTestHooks.appVersionOverride = "Lework Desktop v0.8.19"
            JvmPlatformTestHooks.canInstallDesktopUpdate = true
            JvmPlatformTestHooks.onFetchLatestRelease = { _, _ ->
                Result.success(GitHubReleaseFetchPayload(jsonBody = sampleReleaseJson("v0.8.20")))
            }

            var receivedUrl: String? = null
            var receivedSha: String? = null
            JvmPlatformTestHooks.onInstallDesktopUpdate = { url, fileName, sha, _, onProgress ->
                receivedUrl = url
                receivedSha = sha
                onProgress(0.5f, "正在下载 $fileName (50%)…")
                Result.success("校验通过，已完成安装")
            }

            val store = ConsoleStore(scope, FakeHubTransport())
            store.checkForAppUpdates(manual = true)
            advanceUntilIdle()

            store.startAppUpdateAction()
            advanceUntilIdle()

            assertEquals(
                "https://github.com/letrahoo/lework/releases/download/v0.8.20/Lework-v0.8.20-macos-arm64.dmg",
                receivedUrl,
            )
            assertEquals("3576139add0c0a28831237cec52431539015fadc4017b8a7f695830d5c0dae49", receivedSha)
            assertFalse(store.state.value.appUpdate.installing)
            assertEquals("校验通过，已完成安装", store.state.value.appUpdate.installStatusText)

            // Fallback when desktop direct installer is unavailable opens release URL
            JvmPlatformTestHooks.canInstallDesktopUpdate = false
            store.startAppUpdateAction()
            assertTrue(
                JvmPlatformTestHooks.openedUrls.contains("https://github.com/letrahoo/lework/releases/tag/v0.8.20"),
            )
        } finally {
            scope.cancel()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun desktopSidebarShowsUpdateBannerOpensDialogAndPersistsDismissal() = runComposeUiTest {
        JvmPlatformTestHooks.simulateDesktopLayout = true
        JvmPlatformTestHooks.appVersionOverride = "Lework Desktop v0.8.19"
        JvmPlatformTestHooks.canInstallDesktopUpdate = true
        JvmPlatformTestHooks.savedPrefs = SavedSessionPrefs(mode = "auto", dismissedUpdateTag = null)
        JvmPlatformTestHooks.onFetchLatestRelease = { _, _ ->
            Result.success(GitHubReleaseFetchPayload(jsonBody = sampleReleaseJson("v0.8.20")))
        }
        var installClicked = false
        JvmPlatformTestHooks.onInstallDesktopUpdate = { _, _, _, _, _ ->
            installClicked = true
            Result.success("已下载并校验安装包")
        }

        val fake = FakeHubTransport()
        lateinit var store: ConsoleStore

        setContent {
            val scope = rememberCoroutineScope()
            val created = remember { ConsoleStore(scope, fake) }
            store = created
            Box(modifier = Modifier.size(width = 1280.dp, height = 820.dp)) {
                ConsoleApp(externalStore = created, autoBootstrap = true)
            }
        }

        waitUntil(timeoutMillis = 5_000L) {
            store.state.value.hasEnteredWorkspace && store.state.value.appUpdate.hasVisibleBanner
        }

        // Sidebar banner is automatically surfaced once connected & newer release found
        onNodeWithTag("sidebar_update_banner").assertIsDisplayed()
        onNodeWithText("发现新版本 v0.8.20", substring = true).assertIsDisplayed()

        // Click banner -> opens update detail dialog with release notes & install button
        onNodeWithTag("sidebar_update_banner").performClick()
        waitUntil(timeoutMillis = 3_000L) {
            onAllNodesWithTag("app_update_dialog").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag("app_update_dialog").assertIsDisplayed()
        onNodeWithTag("app_update_primary_action_btn").assertIsDisplayed()

        // Trigger one-click install
        onNodeWithTag("app_update_primary_action_btn").performClick()
        waitUntil(timeoutMillis = 3_000L) { installClicked }
        onNodeWithTag("app_update_progress_box").assertIsDisplayed()

        // Dismiss this version -> closes dialog & hides sidebar banner
        onNodeWithTag("app_update_dismiss_version_btn").performClick()
        waitUntil(timeoutMillis = 3_000L) { !store.state.value.appUpdate.hasVisibleBanner }

        // Open Settings -> software update card is immediately surfaced at the top when an update is available
        onNodeWithTag("drawer_btn_settings").performClick()
        waitUntil(timeoutMillis = 3_000L) {
            onAllNodesWithTag("settings_app_update_section").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag("settings_app_update_section").performScrollTo().assertIsDisplayed()
        onNodeWithTag("settings_btn_check_update").performScrollTo().assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun mobileDrawerShowsUpdatePillAndOpensUpdateDialog() = runComposeUiTest {
        JvmPlatformTestHooks.simulateDesktopLayout = false
        JvmPlatformTestHooks.appVersionOverride = "v0.8.19 (81900)"
        JvmPlatformTestHooks.onFetchLatestRelease = { _, _ ->
            Result.success(GitHubReleaseFetchPayload(jsonBody = sampleReleaseJson("v0.8.20")))
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
            store.state.value.hasEnteredWorkspace && store.state.value.appUpdate.hasUpdate
        }

        onNodeWithTag("topbar_btn_menu").performClick()
        waitUntil(timeoutMillis = 3_000L) {
            onAllNodesWithTag("drawer_update_pill", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag("drawer_update_pill", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("drawer_update_pill", useUnmergedTree = true).performClick()

        waitUntil(timeoutMillis = 3_000L) {
            onAllNodesWithTag("app_update_dialog").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag("app_update_dialog").assertIsDisplayed()
    }
}
