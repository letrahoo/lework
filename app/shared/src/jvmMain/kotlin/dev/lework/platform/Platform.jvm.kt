package dev.lework.platform

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jetbrains.skia.Image as SkiaImage

/**
 * Controllable JVM host platform hooks used by headless unit & Compose UI stress tests.
 */
object JvmPlatformTestHooks {
    private val _foreground = MutableStateFlow(true)
    val foreground: StateFlow<Boolean> = _foreground.asStateFlow()

    private val _networkEpoch = MutableStateFlow(0L)
    val networkEpoch: StateFlow<Long> = _networkEpoch.asStateFlow()

    private val _bonjourUrls = MutableStateFlow<List<String>>(emptyList())
    val bonjourUrls: StateFlow<List<String>> = _bonjourUrls.asStateFlow()

    private val _incomingPairingUri = MutableStateFlow<String?>(null)
    val incomingPairingUri: StateFlow<String?> = _incomingPairingUri.asStateFlow()

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

    var tsnetAvailable: Boolean = false
    var tsnetEnabled: Boolean = false
    var tsnetControlUrl: String? = null
    var tsnetStatus: MobileTsnetStatus = MobileTsnetStatus()
    var qrScanSupported: Boolean = false
    var nextScannedQrResult: String? = null
    var pairedHosts: List<PairedHost> = emptyList()
    var activeHostId: String? = null
    val hostTokens = mutableMapOf<String, String>()
    var imagePickSupported: Boolean = true
    var cameraCaptureSupported: Boolean = true
    var nextPickedImage: PickedImagePayload? = null
    var nextPickedImages: List<PickedImagePayload> = emptyList()
    var lastImagePickSource: ImagePickSource? = null
    var signingExpiryHours: Int? = null
    var keyboardDismissCount: Int = 0
    var simulateDesktopLayout: Boolean = false
    var sshConnectSupported: Boolean = false
    var sshConfigHosts: List<String> = emptyList()
    var nextSshPairingResult: Result<String> = Result.failure(IllegalStateException("No mock SSH pairing configured"))

    fun setForeground(isForeground: Boolean) {
        _foreground.value = isForeground
    }

    fun bumpNetworkEpoch() {
        _networkEpoch.value = _networkEpoch.value + 1L
    }

    fun setBonjourUrls(urls: List<String>) {
        _bonjourUrls.value = urls
    }

    fun emitIncomingPairingUri(uri: String?) {
        _incomingPairingUri.value = uri
    }

    fun reset() {
        _foreground.value = true
        _networkEpoch.value = 0L
        _bonjourUrls.value = emptyList()
        _incomingPairingUri.value = null
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
        tsnetAvailable = false
        tsnetEnabled = false
        tsnetControlUrl = null
        tsnetStatus = MobileTsnetStatus()
        qrScanSupported = false
        nextScannedQrResult = null
        pairedHosts = emptyList()
        activeHostId = null
        hostTokens.clear()
        imagePickSupported = true
        cameraCaptureSupported = true
        nextPickedImage = null
        nextPickedImages = emptyList()
        lastImagePickSource = null
        signingExpiryHours = null
        keyboardDismissCount = 0
        simulateDesktopLayout = false
        sshConnectSupported = false
        sshConfigHosts = emptyList()
        nextSshPairingResult = Result.failure(IllegalStateException("No mock SSH pairing configured"))
    }
}

actual fun platformName(): String =
    if (JvmDesktopRuntime.isEnabled) "macOS Desktop" else "JVM-Test"

actual fun isDesktopPlatform(): Boolean =
    JvmDesktopRuntime.isEnabled || JvmPlatformTestHooks.simulateDesktopLayout

actual fun appVersionLabel(): String {
    if (!JvmDesktopRuntime.isEnabled) return "v0.5.0 (test)"
    val ver = System.getProperty("lework.app.version")?.trim()?.takeIf { it.isNotEmpty() } ?: "0.7.0"
    return "Lework Desktop v$ver"
}

actual fun epochMillis(): Long = System.currentTimeMillis()

actual fun localDateTimeParts(epochMs: Long): LocalDateTimeParts {
    val zdt = java.time.Instant.ofEpochMilli(epochMs).atZone(java.time.ZoneId.systemDefault())
    return LocalDateTimeParts(
        year = zdt.year,
        month = zdt.monthValue,
        day = zdt.dayOfMonth,
        hour = zdt.hour,
        minute = zdt.minute,
        second = zdt.second,
    )
}

actual fun observeAppForeground(): StateFlow<Boolean> =
    if (JvmDesktopRuntime.isEnabled) JvmDesktopRuntime.foreground else JvmPlatformTestHooks.foreground

actual fun observeNetworkEpoch(): StateFlow<Long> =
    if (JvmDesktopRuntime.isEnabled) JvmDesktopRuntime.networkEpoch else JvmPlatformTestHooks.networkEpoch

actual fun candidateHubHttpUrls(): List<String> =
    if (JvmDesktopRuntime.isEnabled) {
        JvmDesktopRuntime.candidateUrls()
    } else {
        (JvmPlatformTestHooks.bonjourUrls.value +
            JvmPlatformTestHooks.tsnetStatus.peers.mapNotNull { it.localProxyUrl } +
            listOfNotNull(JvmPlatformTestHooks.lastGoodUrl) +
            JvmPlatformTestHooks.savedRelayUrls +
            JvmPlatformTestHooks.candidateUrls).distinct()
    }

actual fun observeBonjourHubUrls(): StateFlow<List<String>> =
    if (JvmDesktopRuntime.isEnabled) JvmDesktopRuntime.combinedUrls else JvmPlatformTestHooks.bonjourUrls

actual fun startBonjourDiscovery() {
    if (JvmDesktopRuntime.isEnabled) {
        JvmDesktopRuntime.startBonjourDiscovery()
    } else {
        JvmPlatformTestHooks.bonjourStartedCount++
    }
}

actual fun loadLastGoodHubUrl(): String? =
    if (JvmDesktopRuntime.isEnabled) JvmDesktopRuntime.loadLastGoodUrl() else JvmPlatformTestHooks.lastGoodUrl

actual fun saveLastGoodHubUrl(url: String?) {
    if (JvmDesktopRuntime.isEnabled) {
        JvmDesktopRuntime.saveLastGoodUrl(url)
    } else {
        JvmPlatformTestHooks.lastGoodUrl = url?.takeIf { it.isNotBlank() }
    }
}

actual fun loadSavedRelayUrls(): List<String> =
    if (JvmDesktopRuntime.isEnabled) JvmDesktopRuntime.loadRelayUrls() else JvmPlatformTestHooks.savedRelayUrls

actual fun saveRelayUrls(urls: List<String>) {
    if (JvmDesktopRuntime.isEnabled) {
        JvmDesktopRuntime.saveRelayUrls(urls)
    } else {
        JvmPlatformTestHooks.savedRelayUrls = urls.map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() }.distinct()
    }
}

actual fun loadSavedAccessToken(): String? =
    if (JvmDesktopRuntime.isEnabled) JvmDesktopRuntime.loadAccessToken() else JvmPlatformTestHooks.savedToken

actual fun loadSavedAuthUserName(): String? =
    if (JvmDesktopRuntime.isEnabled) JvmDesktopRuntime.loadAuthUserName() else JvmPlatformTestHooks.savedUserName

actual fun saveAuthSession(token: String?, userName: String?) {
    if (JvmDesktopRuntime.isEnabled) {
        JvmDesktopRuntime.saveAuthSession(token, userName)
    } else if (token.isNullOrBlank()) {
        JvmPlatformTestHooks.savedToken = null
        JvmPlatformTestHooks.savedUserName = null
    } else {
        JvmPlatformTestHooks.savedToken = token
        JvmPlatformTestHooks.savedUserName = userName ?: "Owner"
    }
}

actual fun loadSessionPrefs(): SavedSessionPrefs =
    if (JvmDesktopRuntime.isEnabled) JvmDesktopRuntime.loadPrefs() else JvmPlatformTestHooks.savedPrefs

actual fun saveSessionPrefs(prefs: SavedSessionPrefs) {
    if (JvmDesktopRuntime.isEnabled) {
        JvmDesktopRuntime.savePrefs(prefs)
    } else {
        JvmPlatformTestHooks.savedPrefs = prefs
    }
}

actual fun openExternalUrl(url: String) {
    if (JvmDesktopRuntime.isEnabled) {
        JvmDesktopRuntime.openExternalUrl(url)
    } else {
        JvmPlatformTestHooks.openedUrls.add(url)
    }
}

actual fun copyTextToClipboard(text: String) {
    if (JvmDesktopRuntime.isEnabled) {
        JvmDesktopRuntime.copyToClipboard(text)
    } else {
        JvmPlatformTestHooks.clipboardText = text
    }
}

actual fun readTextFromClipboard(): String? =
    if (JvmDesktopRuntime.isEnabled) {
        JvmDesktopRuntime.readFromClipboard()
    } else {
        JvmPlatformTestHooks.clipboardText.takeIf { it.isNotBlank() }
    }

actual fun canConnectViaSsh(): Boolean =
    if (JvmDesktopRuntime.isEnabled) true else JvmPlatformTestHooks.sshConnectSupported

actual fun listSshConfigHosts(): List<String> =
    if (JvmDesktopRuntime.isEnabled) JvmDesktopRuntime.listSshConfigHosts() else JvmPlatformTestHooks.sshConfigHosts

actual suspend fun requestSshHostPairingLink(sshTarget: String): Result<String> =
    if (JvmDesktopRuntime.isEnabled) {
        JvmDesktopRuntime.requestSshPairingLink(sshTarget)
    } else {
        JvmPlatformTestHooks.nextSshPairingResult
    }

actual fun loadPinnedHubFingerprints(): Set<String> =
    if (JvmDesktopRuntime.isEnabled) JvmDesktopRuntime.loadPins() else JvmPlatformTestHooks.pinnedFingerprints

actual fun savePinnedHubFingerprints(fingerprints: Set<String>) {
    if (JvmDesktopRuntime.isEnabled) {
        JvmDesktopRuntime.savePins(fingerprints)
    } else {
        JvmPlatformTestHooks.pinnedFingerprints = fingerprints
    }
}

actual fun isMobileTsnetAvailable(): Boolean =
    if (JvmDesktopRuntime.isEnabled) DesktopTsnetDaemon.isAvailable() else JvmPlatformTestHooks.tsnetAvailable

actual suspend fun startMobileTsnet(controlUrl: String?): MobileTsnetStatus =
    if (JvmDesktopRuntime.isEnabled) {
        DesktopTsnetDaemon.configureAndCall(controlUrl ?: loadSavedTsnetControlUrl(), null, "/v1/status")
    } else {
        JvmPlatformTestHooks.tsnetStatus
    }

actual suspend fun queryMobileTsnetStatus(): MobileTsnetStatus =
    if (JvmDesktopRuntime.isEnabled) {
        DesktopTsnetDaemon.callControl("/v1/status")
    } else {
        JvmPlatformTestHooks.tsnetStatus
    }

actual suspend fun loginMobileTsnet(controlUrl: String?, authKey: String?): MobileTsnetStatus =
    if (JvmDesktopRuntime.isEnabled) {
        DesktopTsnetDaemon.configureAndCall(controlUrl ?: loadSavedTsnetControlUrl(), authKey, "/v1/login")
    } else {
        JvmPlatformTestHooks.tsnetStatus
    }

actual suspend fun logoutMobileTsnet(): MobileTsnetStatus {
    if (JvmDesktopRuntime.isEnabled) {
        DesktopTsnetDaemon.stopAndPurgeState()
        return MobileTsnetStatus(available = DesktopTsnetDaemon.isAvailable(), backendState = "NeedsLogin")
    }
    JvmPlatformTestHooks.tsnetStatus = MobileTsnetStatus(available = JvmPlatformTestHooks.tsnetAvailable, backendState = "NeedsLogin")
    return JvmPlatformTestHooks.tsnetStatus
}

actual fun loadSavedTsnetEnabled(): Boolean =
    if (JvmDesktopRuntime.isEnabled) JvmDesktopRuntime.loadTsnetEnabled() else JvmPlatformTestHooks.tsnetEnabled

actual fun saveTsnetEnabled(enabled: Boolean, controlUrl: String?) {
    if (JvmDesktopRuntime.isEnabled) {
        JvmDesktopRuntime.saveTsnetEnabled(enabled, controlUrl)
    } else {
        JvmPlatformTestHooks.tsnetEnabled = enabled
        if (controlUrl != null) JvmPlatformTestHooks.tsnetControlUrl = controlUrl.ifBlank { null }
    }
}

actual fun loadSavedTsnetControlUrl(): String? =
    if (JvmDesktopRuntime.isEnabled) JvmDesktopRuntime.loadTsnetControlUrl() else JvmPlatformTestHooks.tsnetControlUrl

actual fun canScanQrCode(): Boolean =
    if (JvmDesktopRuntime.isEnabled) false else JvmPlatformTestHooks.qrScanSupported

actual fun requestQrCodeScan(onResult: (String?) -> Unit) {
    onResult(if (JvmDesktopRuntime.isEnabled) null else JvmPlatformTestHooks.nextScannedQrResult)
}

actual fun observeIncomingPairingUri(): StateFlow<String?> =
    if (JvmDesktopRuntime.isEnabled) JvmDesktopRuntime.incomingPairingUri else JvmPlatformTestHooks.incomingPairingUri

actual fun consumeIncomingPairingUri() {
    if (JvmDesktopRuntime.isEnabled) {
        JvmDesktopRuntime.emitIncomingPairingUri(null)
    } else {
        JvmPlatformTestHooks.emitIncomingPairingUri(null)
    }
}

actual fun loadPairedHosts(): List<PairedHost> =
    if (JvmDesktopRuntime.isEnabled) JvmDesktopRuntime.loadPairedHosts() else JvmPlatformTestHooks.pairedHosts

actual fun savePairedHosts(hosts: List<PairedHost>) {
    if (JvmDesktopRuntime.isEnabled) {
        JvmDesktopRuntime.savePairedHosts(hosts)
    } else {
        JvmPlatformTestHooks.pairedHosts = hosts
    }
}

actual fun loadActiveHostId(): String? =
    if (JvmDesktopRuntime.isEnabled) JvmDesktopRuntime.loadActiveHostId() else JvmPlatformTestHooks.activeHostId

actual fun saveActiveHostId(hubId: String?) {
    if (JvmDesktopRuntime.isEnabled) {
        JvmDesktopRuntime.saveActiveHostId(hubId)
    } else {
        JvmPlatformTestHooks.activeHostId = hubId?.takeIf { it.isNotBlank() }
    }
}

actual fun loadHostAccessToken(hubId: String): String? =
    if (JvmDesktopRuntime.isEnabled) JvmDesktopRuntime.loadHostToken(hubId) else JvmPlatformTestHooks.hostTokens[hubId]

actual fun saveHostAccessToken(hubId: String, token: String?) {
    if (JvmDesktopRuntime.isEnabled) {
        JvmDesktopRuntime.saveHostToken(hubId, token)
    } else if (token.isNullOrBlank()) {
        JvmPlatformTestHooks.hostTokens.remove(hubId)
    } else {
        JvmPlatformTestHooks.hostTokens[hubId] = token
    }
}

actual fun canPickImages(): Boolean =
    if (JvmDesktopRuntime.isEnabled) true else JvmPlatformTestHooks.imagePickSupported

actual fun canCaptureCameraImage(): Boolean =
    if (JvmDesktopRuntime.isEnabled) false else JvmPlatformTestHooks.cameraCaptureSupported

actual fun requestImagePick(onResult: (PickedImagePayload?) -> Unit) {
    if (JvmDesktopRuntime.isEnabled) {
        JvmDesktopRuntime.pickImages(1) { list -> onResult(list.firstOrNull()) }
    } else {
        JvmPlatformTestHooks.lastImagePickSource = ImagePickSource.LIBRARY
        onResult(JvmPlatformTestHooks.nextPickedImage ?: JvmPlatformTestHooks.nextPickedImages.firstOrNull())
    }
}

actual fun requestMultiImagePick(
    source: ImagePickSource,
    maxCount: Int,
    onResult: (List<PickedImagePayload>) -> Unit,
) {
    if (JvmDesktopRuntime.isEnabled) {
        JvmDesktopRuntime.pickImages(maxCount.coerceIn(1, 4), onResult)
    } else {
        JvmPlatformTestHooks.lastImagePickSource = source
        val items = if (JvmPlatformTestHooks.nextPickedImages.isNotEmpty()) {
            JvmPlatformTestHooks.nextPickedImages
        } else {
            listOfNotNull(JvmPlatformTestHooks.nextPickedImage)
        }
        onResult(items.take(maxCount.coerceIn(1, 4)))
    }
}

actual fun decodeImageBytes(bytes: ByteArray): ImageBitmap? =
    runCatching { SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()

actual fun signingExpiryRemainingHours(): Int? =
    if (JvmDesktopRuntime.isEnabled) null else JvmPlatformTestHooks.signingExpiryHours

actual fun dismissNativeKeyboard() {
    if (!JvmDesktopRuntime.isEnabled) {
        JvmPlatformTestHooks.keyboardDismissCount++
    }
}
