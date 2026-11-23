package dev.lework.platform

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

@Serializable
data class SavedSessionPrefs(
    val engineId: String = "claude",
    val providerId: String? = null,
    val modelId: String? = null,
    val reasoningEffort: String = "medium",
    val mode: String = "auto",
    val workspacePath: String = "",
    val favoriteModelKeys: List<String> = emptyList(),
)

@Serializable
data class MobileTsnetPeer(
    val hostName: String = "",
    val dnsName: String = "",
    val tailscaleIp: String = "",
    val online: Boolean = false,
    val isLeworkNode: Boolean = false,
    val localProxyUrl: String? = null,
)

@Serializable
data class MobileTsnetStatus(
    val available: Boolean = false,
    val backendState: String = "Unavailable",
    val authUrl: String? = null,
    val controlUrl: String? = null,
    val selfHostName: String? = null,
    val selfDnsName: String? = null,
    val tailscaleIps: List<String> = emptyList(),
    val loginName: String? = null,
    val displayName: String? = null,
    val peers: List<MobileTsnetPeer> = emptyList(),
    val error: String? = null,
)

@Serializable
data class PairedHost(
    val hubId: String,
    val name: String,
    val fingerprint: String = "",
    val endpoints: List<String> = emptyList(),
    val userName: String = "Owner",
    val lastConnectedAt: Long = 0L,
)

enum class ImagePickSource {
    LIBRARY,
    CAMERA,
}

@Serializable
data class PickedImagePayload(
    val fileName: String,
    val mimeType: String,
    val dataBase64: String,
    val thumbnailBase64: String? = null,
)

expect fun platformName(): String
expect fun isDesktopPlatform(): Boolean
expect fun appVersionLabel(): String
expect fun epochMillis(): Long
expect fun observeAppForeground(): StateFlow<Boolean>
expect fun observeNetworkEpoch(): StateFlow<Long>
expect fun candidateHubHttpUrls(): List<String>
expect fun observeBonjourHubUrls(): StateFlow<List<String>>
expect fun startBonjourDiscovery()
expect fun loadLastGoodHubUrl(): String?
expect fun saveLastGoodHubUrl(url: String?)
expect fun loadSavedRelayUrls(): List<String>
expect fun saveRelayUrls(urls: List<String>)
expect fun loadSavedAccessToken(): String?
expect fun loadSavedAuthUserName(): String?
expect fun saveAuthSession(token: String?, userName: String?)

/** Pinned Hub TLS certificate fingerprints (SHA-256 hex). Stored next to the token (iOS Keychain). */
expect fun loadPinnedHubFingerprints(): Set<String>
expect fun savePinnedHubFingerprints(fingerprints: Set<String>)
expect fun loadSessionPrefs(): SavedSessionPrefs
expect fun saveSessionPrefs(prefs: SavedSessionPrefs)
expect fun openExternalUrl(url: String)
expect fun copyTextToClipboard(text: String)
expect fun readTextFromClipboard(): String?

// Desktop SSH direct-pairing helpers for remote Linux Devboxes
expect fun canConnectViaSsh(): Boolean
expect fun listSshConfigHosts(): List<String>
expect suspend fun requestSshHostPairingLink(sshTarget: String): Result<String>

// Embedded Tailscale `tsnet` client & same-account discovery
expect fun isMobileTsnetAvailable(): Boolean
expect suspend fun startMobileTsnet(controlUrl: String? = null): MobileTsnetStatus
expect suspend fun queryMobileTsnetStatus(): MobileTsnetStatus
expect suspend fun loginMobileTsnet(controlUrl: String? = null, authKey: String? = null): MobileTsnetStatus
expect suspend fun logoutMobileTsnet(): MobileTsnetStatus
expect fun loadSavedTsnetEnabled(): Boolean
expect fun saveTsnetEnabled(enabled: Boolean, controlUrl: String? = null)
expect fun loadSavedTsnetControlUrl(): String?

// Native QR scanner & `lework://pair` deep-link observer
expect fun canScanQrCode(): Boolean
expect fun requestQrCodeScan(onResult: (String?) -> Unit)
expect fun observeIncomingPairingUri(): StateFlow<String?>
expect fun consumeIncomingPairingUri()

// Multi-host asset book & per-host Keychain tokens
expect fun loadPairedHosts(): List<PairedHost>
expect fun savePairedHosts(hosts: List<PairedHost>)
expect fun loadActiveHostId(): String?
expect fun saveActiveHostId(hubId: String?)
expect fun loadHostAccessToken(hubId: String): String?
expect fun saveHostAccessToken(hubId: String, token: String?)

// Native image picker, camera capture, bitmap decoding & iOS provisioning profile expiration inspector
expect fun canPickImages(): Boolean
expect fun canCaptureCameraImage(): Boolean
expect fun requestImagePick(onResult: (PickedImagePayload?) -> Unit)
expect fun requestMultiImagePick(
    source: ImagePickSource = ImagePickSource.LIBRARY,
    maxCount: Int = 4,
    onResult: (List<PickedImagePayload>) -> Unit,
)
expect fun decodeImageBytes(bytes: ByteArray): ImageBitmap?
expect fun signingExpiryRemainingHours(): Int?
expect fun dismissNativeKeyboard()
