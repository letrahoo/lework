package dev.lework.platform

import kotlinx.coroutines.flow.StateFlow

data class SavedSessionPrefs(
    val engineId: String = "claude",
    val providerId: String? = null,
    val modelId: String? = null,
    val reasoningEffort: String = "medium",
    val mode: String = "ask",
    val workspacePath: String = "",
)

expect fun platformName(): String
expect fun appVersionLabel(): String
expect fun epochMillis(): Long
expect fun observeAppForeground(): StateFlow<Boolean>
expect fun candidateHubHttpUrls(): List<String>
expect fun observeBonjourHubUrls(): StateFlow<List<String>>
expect fun startBonjourDiscovery()
expect fun loadLastGoodHubUrl(): String?
expect fun saveLastGoodHubUrl(url: String?)
expect fun loadSavedRelayUrls(): List<String>
expect fun saveRelayUrls(urls: List<String>)
expect fun loadSavedFeishuToken(): String?
expect fun loadSavedFeishuUserName(): String?
expect fun saveFeishuSession(token: String?, userName: String?)

/** Pinned Hub TLS certificate fingerprints (SHA-256 hex). Stored next to the token (iOS Keychain). */
expect fun loadPinnedHubFingerprints(): Set<String>
expect fun savePinnedHubFingerprints(fingerprints: Set<String>)
expect fun loadSessionPrefs(): SavedSessionPrefs
expect fun saveSessionPrefs(prefs: SavedSessionPrefs)
expect fun openExternalUrl(url: String)
expect fun copyTextToClipboard(text: String)
