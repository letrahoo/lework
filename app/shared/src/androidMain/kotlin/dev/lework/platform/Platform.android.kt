package dev.lework.platform

import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private var memToken: String? = null
private var memUser: String? = null
private var memLastGoodUrl: String? = null
private var memRelayUrls: List<String> = emptyList()
private var memPrefs = SavedSessionPrefs()
// Android build keeps credentials in process memory only (nothing persisted to disk yet).
private var memPins: Set<String> = emptySet()
private val emptyBonjourFlow = MutableStateFlow<List<String>>(emptyList())
private val androidForegroundFlow = MutableStateFlow(true)

actual fun platformName(): String = "Android ${Build.VERSION.RELEASE}"

actual fun appVersionLabel(): String = "v0.3.6 (20261119.0225)"

actual fun epochMillis(): Long = System.currentTimeMillis()

actual fun observeAppForeground(): StateFlow<Boolean> = androidForegroundFlow.asStateFlow()

actual fun observeBonjourHubUrls(): StateFlow<List<String>> = emptyBonjourFlow.asStateFlow()

actual fun startBonjourDiscovery() {
    // Android NSD hook placeholder
}

actual fun loadLastGoodHubUrl(): String? = memLastGoodUrl

actual fun saveLastGoodHubUrl(url: String?) {
    memLastGoodUrl = url
}

actual fun loadSavedRelayUrls(): List<String> = memRelayUrls

actual fun saveRelayUrls(urls: List<String>) {
    memRelayUrls = urls.map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() }.distinct()
}

actual fun candidateHubHttpUrls(): List<String> = buildList {
    memLastGoodUrl?.let { add(it) }
    addAll(memRelayUrls)
    add("https://10.0.2.2:64450")
}.distinct()

actual fun loadSavedFeishuToken(): String? = memToken

actual fun loadSavedFeishuUserName(): String? = memUser

actual fun saveFeishuSession(token: String?, userName: String?) {
    memToken = token
    memUser = userName
}

actual fun loadSessionPrefs(): SavedSessionPrefs = memPrefs

actual fun saveSessionPrefs(prefs: SavedSessionPrefs) {
    memPrefs = prefs
}

actual fun openExternalUrl(url: String) {
    // Handled via Android Intent when running on Android
}

actual fun copyTextToClipboard(text: String) {
    // Handled via Android ClipboardManager when running on Android
}

actual fun loadPinnedHubFingerprints(): Set<String> = memPins

actual fun savePinnedHubFingerprints(fingerprints: Set<String>) {
    memPins = fingerprints
}
