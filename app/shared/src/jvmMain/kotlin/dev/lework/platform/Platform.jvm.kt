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
    var nextPickedImage: PickedImagePayload? = null
    var signingExpiryHours: Int? = null

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
        nextPickedImage = null
        signingExpiryHours = null
    }
}

actual fun platformName(): String = "JVM-Test"

actual fun appVersionLabel(): String = "v0.5.0 (test)"

actual fun epochMillis(): Long = System.currentTimeMillis()

actual fun observeAppForeground(): StateFlow<Boolean> = JvmPlatformTestHooks.foreground

actual fun observeNetworkEpoch(): StateFlow<Long> = JvmPlatformTestHooks.networkEpoch

actual fun candidateHubHttpUrls(): List<String> =
    (JvmPlatformTestHooks.bonjourUrls.value +
        JvmPlatformTestHooks.tsnetStatus.peers.mapNotNull { it.localProxyUrl } +
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

actual fun isMobileTsnetAvailable(): Boolean = JvmPlatformTestHooks.tsnetAvailable

actual suspend fun startMobileTsnet(controlUrl: String?): MobileTsnetStatus = JvmPlatformTestHooks.tsnetStatus

actual suspend fun queryMobileTsnetStatus(): MobileTsnetStatus = JvmPlatformTestHooks.tsnetStatus

actual suspend fun loginMobileTsnet(controlUrl: String?, authKey: String?): MobileTsnetStatus =
    JvmPlatformTestHooks.tsnetStatus

actual suspend fun logoutMobileTsnet(): MobileTsnetStatus {
    JvmPlatformTestHooks.tsnetStatus = MobileTsnetStatus(available = JvmPlatformTestHooks.tsnetAvailable, backendState = "NeedsLogin")
    return JvmPlatformTestHooks.tsnetStatus
}

actual fun loadSavedTsnetEnabled(): Boolean = JvmPlatformTestHooks.tsnetEnabled

actual fun saveTsnetEnabled(enabled: Boolean, controlUrl: String?) {
    JvmPlatformTestHooks.tsnetEnabled = enabled
    if (controlUrl != null) JvmPlatformTestHooks.tsnetControlUrl = controlUrl.ifBlank { null }
}

actual fun loadSavedTsnetControlUrl(): String? = JvmPlatformTestHooks.tsnetControlUrl

actual fun canScanQrCode(): Boolean = JvmPlatformTestHooks.qrScanSupported

actual fun requestQrCodeScan(onResult: (String?) -> Unit) {
    onResult(JvmPlatformTestHooks.nextScannedQrResult)
}

actual fun observeIncomingPairingUri(): StateFlow<String?> = JvmPlatformTestHooks.incomingPairingUri

actual fun consumeIncomingPairingUri() {
    JvmPlatformTestHooks.emitIncomingPairingUri(null)
}

actual fun loadPairedHosts(): List<PairedHost> = JvmPlatformTestHooks.pairedHosts

actual fun savePairedHosts(hosts: List<PairedHost>) {
    JvmPlatformTestHooks.pairedHosts = hosts
}

actual fun loadActiveHostId(): String? = JvmPlatformTestHooks.activeHostId

actual fun saveActiveHostId(hubId: String?) {
    JvmPlatformTestHooks.activeHostId = hubId?.takeIf { it.isNotBlank() }
}

actual fun loadHostAccessToken(hubId: String): String? = JvmPlatformTestHooks.hostTokens[hubId]

actual fun saveHostAccessToken(hubId: String, token: String?) {
    if (token.isNullOrBlank()) {
        JvmPlatformTestHooks.hostTokens.remove(hubId)
    } else {
        JvmPlatformTestHooks.hostTokens[hubId] = token
    }
}

actual fun canPickImages(): Boolean = JvmPlatformTestHooks.imagePickSupported

actual fun requestImagePick(onResult: (PickedImagePayload?) -> Unit) {
    onResult(JvmPlatformTestHooks.nextPickedImage)
}

actual fun signingExpiryRemainingHours(): Int? = JvmPlatformTestHooks.signingExpiryHours
