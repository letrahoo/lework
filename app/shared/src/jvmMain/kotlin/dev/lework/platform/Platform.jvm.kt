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
    var clipboardWriteSucceeds: Boolean = true
    val openedUrls = mutableListOf<String>()
    val openedLocalFiles = mutableListOf<String>()
    var canOpenLocalFiles: Boolean = false

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
    var nextClipboardImages: List<PickedImagePayload> = emptyList()
    var lastImagePickSource: ImagePickSource? = null
    var signingExpiryHours: Int? = null
    var keyboardDismissCount: Int = 0
    var simulateDesktopLayout: Boolean = false
    var sshConnectSupported: Boolean = false
    var sshConfigHosts: List<String> = emptyList()
    var nextSshPairingResult: Result<String> = Result.failure(IllegalStateException("No mock SSH pairing configured"))
    var globalCliStatus: GlobalCliInstallStatus = GlobalCliInstallStatus(supported = false)
    var onInstallGlobalCli: (() -> Result<GlobalCliInstallStatus>)? = null
    var onUninstallGlobalCli: (() -> Result<GlobalCliInstallStatus>)? = null
    val runtimeLogs = mutableListOf<String>()
    var fakeRuntimeLogPath: String? = null
    var appVersionOverride: String? = null
    var onFetchLatestRelease: (suspend (apiUrl: String, fallbackWebUrl: String) -> Result<GitHubReleaseFetchPayload>)? = null
    var canInstallDesktopUpdate: Boolean = false
    var onInstallDesktopUpdate: (
        suspend (
            downloadUrl: String,
            expectedFileName: String,
            expectedSha256: String?,
            checksumsUrl: String?,
            onProgress: (Float?, String) -> Unit,
        ) -> Result<String>
    )? = null

    fun recordRuntimeLog(line: String) {
        synchronized(runtimeLogs) {
            if (runtimeLogs.size >= 500) {
                runtimeLogs.removeAt(0)
            }
            runtimeLogs.add(line)
        }
    }

    fun snapshotRuntimeLogs(): List<String> = synchronized(runtimeLogs) { runtimeLogs.toList() }

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
        clipboardWriteSucceeds = true
        openedUrls.clear()
        openedLocalFiles.clear()
        canOpenLocalFiles = false
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
        nextClipboardImages = emptyList()
        lastImagePickSource = null
        signingExpiryHours = null
        keyboardDismissCount = 0
        simulateDesktopLayout = false
        sshConnectSupported = false
        sshConfigHosts = emptyList()
        nextSshPairingResult = Result.failure(IllegalStateException("No mock SSH pairing configured"))
        globalCliStatus = GlobalCliInstallStatus(supported = false)
        onInstallGlobalCli = null
        onUninstallGlobalCli = null
        synchronized(runtimeLogs) { runtimeLogs.clear() }
        fakeRuntimeLogPath = null
        appVersionOverride = null
        onFetchLatestRelease = null
        canInstallDesktopUpdate = false
        onInstallDesktopUpdate = null
    }
}

actual fun platformName(): String =
    if (JvmDesktopRuntime.isEnabled) "macOS Desktop" else "JVM-Test"

actual fun isDesktopPlatform(): Boolean =
    JvmDesktopRuntime.isEnabled || JvmPlatformTestHooks.simulateDesktopLayout

actual fun appVersionLabel(): String {
    if (!JvmDesktopRuntime.isEnabled) {
        return JvmPlatformTestHooks.appVersionOverride?.trim()?.takeIf { it.isNotEmpty() } ?: "v0.5.0 (test)"
    }
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

actual fun canOpenLocalFileExternally(path: String): Boolean =
    if (JvmDesktopRuntime.isEnabled) {
        JvmDesktopRuntime.canOpenLocalFileExternally(path)
    } else {
        JvmPlatformTestHooks.canOpenLocalFiles
    }

actual fun openLocalFileExternally(path: String): Boolean =
    if (JvmDesktopRuntime.isEnabled) {
        JvmDesktopRuntime.openLocalFileExternally(path)
    } else if (JvmPlatformTestHooks.canOpenLocalFiles) {
        JvmPlatformTestHooks.openedLocalFiles.add(path)
        true
    } else {
        false
    }

actual fun copyTextToClipboard(text: String): Boolean =
    if (JvmDesktopRuntime.isEnabled) {
        JvmDesktopRuntime.copyToClipboard(text)
    } else if (JvmPlatformTestHooks.clipboardWriteSucceeds) {
        JvmPlatformTestHooks.clipboardText = text
        true
    } else {
        false
    }

actual fun readTextFromClipboard(): String? =
    if (JvmDesktopRuntime.isEnabled) {
        JvmDesktopRuntime.readFromClipboard()
    } else {
        JvmPlatformTestHooks.clipboardText.takeIf { it.isNotBlank() }
    }

actual fun readImagesFromClipboard(maxCount: Int): List<PickedImagePayload> =
    if (JvmDesktopRuntime.isEnabled) {
        JvmDesktopRuntime.readImagesFromClipboard(maxCount.coerceIn(1, 4))
    } else {
        JvmPlatformTestHooks.nextClipboardImages.take(maxCount.coerceIn(1, 4))
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

actual fun inspectGlobalCliStatus(): GlobalCliInstallStatus =
    if (JvmDesktopRuntime.isEnabled) {
        DesktopCliInstaller.inspectStatus()
    } else {
        JvmPlatformTestHooks.globalCliStatus
    }

actual suspend fun installGlobalCliCommand(): Result<GlobalCliInstallStatus> =
    if (JvmDesktopRuntime.isEnabled) {
        DesktopCliInstaller.install()
    } else {
        JvmPlatformTestHooks.onInstallGlobalCli?.invoke()
            ?: Result.success(JvmPlatformTestHooks.globalCliStatus)
    }

actual suspend fun uninstallGlobalCliCommand(): Result<GlobalCliInstallStatus> =
    if (JvmDesktopRuntime.isEnabled) {
        DesktopCliInstaller.uninstall()
    } else {
        JvmPlatformTestHooks.onUninstallGlobalCli?.invoke()
            ?: Result.success(JvmPlatformTestHooks.globalCliStatus.copy(installed = false, installedPath = null))
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

actual fun decodeImageBytes(bytes: ByteArray): ImageBitmap? = runCatching {
    val img = SkiaImage.makeFromEncoded(bytes)
    try {
        img.toComposeImageBitmap()
    } finally {
        img.close()
    }
}.getOrNull()

actual fun signingExpiryRemainingHours(): Int? =
    if (JvmDesktopRuntime.isEnabled) null else JvmPlatformTestHooks.signingExpiryHours

actual fun dismissNativeKeyboard() {
    if (!JvmDesktopRuntime.isEnabled) {
        JvmPlatformTestHooks.keyboardDismissCount++
    }
}

actual fun logRuntimeEvent(tag: String, message: String) {
    if (JvmDesktopRuntime.isEnabled) {
        JvmDesktopRuntime.logRuntimeEvent(tag, message)
    } else {
        JvmPlatformTestHooks.recordRuntimeLog(formatRuntimeLogLine(epochMillis(), tag, message))
    }
}

actual fun runtimeLogFilePath(): String? =
    if (JvmDesktopRuntime.isEnabled) JvmDesktopRuntime.runtimeLogPath() else JvmPlatformTestHooks.fakeRuntimeLogPath

actual suspend fun fetchLatestGitHubReleasePayload(
    apiUrl: String,
    fallbackWebUrl: String,
): Result<GitHubReleaseFetchPayload> =
    if (JvmDesktopRuntime.isEnabled) {
        DesktopReleaseUpdater.fetchLatestReleasePayload(apiUrl, fallbackWebUrl)
    } else {
        JvmPlatformTestHooks.onFetchLatestRelease?.invoke(apiUrl, fallbackWebUrl)
            ?: Result.failure(UnsupportedOperationException("Release check hook not configured in test mode"))
    }

actual fun canInstallDesktopDmgUpdate(): Boolean =
    if (JvmDesktopRuntime.isEnabled) {
        DesktopReleaseUpdater.canInstallMacDmg()
    } else {
        JvmPlatformTestHooks.canInstallDesktopUpdate
    }

actual suspend fun installDesktopDmgUpdate(
    downloadUrl: String,
    expectedFileName: String,
    expectedSha256: String?,
    checksumsUrl: String?,
    onProgress: (fraction: Float?, statusText: String) -> Unit,
): Result<String> =
    if (JvmDesktopRuntime.isEnabled) {
        DesktopReleaseUpdater.installMacDmgUpdate(
            downloadUrl = downloadUrl,
            expectedFileName = expectedFileName,
            expectedSha256 = expectedSha256,
            checksumsUrl = checksumsUrl,
            onProgress = onProgress,
        )
    } else {
        JvmPlatformTestHooks.onInstallDesktopUpdate?.invoke(
            downloadUrl,
            expectedFileName,
            expectedSha256,
            checksumsUrl,
            onProgress,
        ) ?: Result.failure(UnsupportedOperationException("Desktop updater hook not configured in test mode"))
    }
