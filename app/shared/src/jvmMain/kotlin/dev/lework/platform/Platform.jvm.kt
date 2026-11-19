package dev.lework.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Controllable JVM host platform hooks used by headless unit & Compose UI stress tests.
 */
object JvmPlatformTestHooks {
    private val _foreground = MutableStateFlow(true)
    val foreground: StateFlow<Boolean> = _foreground.asStateFlow()

    private val _bonjourUrls = MutableStateFlow<List<String>>(emptyList())
    val bonjourUrls: StateFlow<List<String>> = _bonjourUrls.asStateFlow()

    var candidateUrls: List<String> = listOf("https://127.0.0.1:64450")
    var lastGoodUrl: String? = null
    var savedRelayUrls: List<String> = emptyList()
    var savedToken: String? = "test-feishu-token"
    var savedUserName: String? = "Owner"
    var savedPrefs: SavedSessionPrefs = SavedSessionPrefs(mode = "auto")
    var pinnedFingerprints: Set<String> = emptySet()
    var bonjourStartedCount: Int = 0
    var clipboardText: String = ""
    val openedUrls = mutableListOf<String>()

    fun setForeground(isForeground: Boolean) {
        _foreground.value = isForeground
    }

    fun setBonjourUrls(urls: List<String>) {
        _bonjourUrls.value = urls
    }

    fun reset() {
        _foreground.value = true
        _bonjourUrls.value = emptyList()
        candidateUrls = listOf("https://127.0.0.1:64450")
        lastGoodUrl = null
        savedRelayUrls = emptyList()
        savedToken = "test-feishu-token"
        savedUserName = "Owner"
        savedPrefs = SavedSessionPrefs(mode = "auto")
        pinnedFingerprints = emptySet()
        bonjourStartedCount = 0
        clipboardText = ""
        openedUrls.clear()
    }
}

actual fun platformName(): String = "JVM-Test"

actual fun appVersionLabel(): String = "v0.3.9 (test)"

actual fun epochMillis(): Long = System.currentTimeMillis()

actual fun observeAppForeground(): StateFlow<Boolean> = JvmPlatformTestHooks.foreground

actual fun candidateHubHttpUrls(): List<String> =
    (JvmPlatformTestHooks.bonjourUrls.value +
        listOfNotNull(JvmPlatformTestHooks.lastGoodUrl) +
        JvmPlatformTestHooks.savedRelayUrls +
        JvmPlatformTestHooks.candidateUrls).distinct()

actual fun observeBonjourHubUrls(): StateFlow<List<String>> = JvmPlatformTestHooks.bonjourUrls

actual fun startBonjourDiscovery() {
    JvmPlatformTestHooks.bonjourStartedCount++
}

actual fun loadLastGoodHubUrl(): String? = JvmPlatformTestHooks.lastGoodUrl

actual fun saveLastGoodHubUrl(url: String?) {
    JvmPlatformTestHooks.lastGoodUrl = url?.takeIf { it.isNotBlank() }
}

actual fun loadSavedRelayUrls(): List<String> = JvmPlatformTestHooks.savedRelayUrls

actual fun saveRelayUrls(urls: List<String>) {
    JvmPlatformTestHooks.savedRelayUrls = urls.map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() }.distinct()
}

actual fun loadSavedAccessToken(): String? = JvmPlatformTestHooks.savedToken

actual fun loadSavedAuthUserName(): String? = JvmPlatformTestHooks.savedUserName

actual fun saveAuthSession(token: String?, userName: String?) {
    if (token.isNullOrBlank()) {
        JvmPlatformTestHooks.savedToken = null
        JvmPlatformTestHooks.savedUserName = null
    } else {
        JvmPlatformTestHooks.savedToken = token
        JvmPlatformTestHooks.savedUserName = userName ?: "Owner"
    }
}

actual fun loadSessionPrefs(): SavedSessionPrefs = JvmPlatformTestHooks.savedPrefs

actual fun saveSessionPrefs(prefs: SavedSessionPrefs) {
    JvmPlatformTestHooks.savedPrefs = prefs
}

actual fun openExternalUrl(url: String) {
    JvmPlatformTestHooks.openedUrls.add(url)
}

actual fun copyTextToClipboard(text: String) {
    JvmPlatformTestHooks.clipboardText = text
}

actual fun loadPinnedHubFingerprints(): Set<String> = JvmPlatformTestHooks.pinnedFingerprints

actual fun savePinnedHubFingerprints(fingerprints: Set<String>) {
    JvmPlatformTestHooks.pinnedFingerprints = fingerprints
}
