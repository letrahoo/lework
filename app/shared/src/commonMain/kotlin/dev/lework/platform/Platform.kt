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
    val pinnedSessionIds: List<String> = emptyList(),
    val dismissedUpdateTag: String? = null,
    val sidebarGroupByProject: Boolean = true,
    val collapsedProjectGroupKeys: List<String> = emptyList(),
)

@Serializable
data class MobileTsnetPeer(
    val hostName: String = "",
    val dnsName: String = "",
    val tailscaleIp: String = "",
    val os: String = "",
    val online: Boolean = false,
    val isLeworkNode: Boolean = false,
    val localProxyUrl: String? = null,
)

fun MobileTsnetPeer.isMobileClientNode(): Boolean {
    val cleanOs = os.trim().lowercase()
    if (cleanOs == "ios" || cleanOs == "android") return true
    val cleanHost = hostName.trim().lowercase()
    val cleanDns = dnsName.trim().lowercase()
    return cleanHost.startsWith("lework-ios") ||
        cleanHost.startsWith("lework-android") ||
        cleanHost.startsWith("levi-remote-") ||
        cleanDns.startsWith("lework-ios") ||
        cleanDns.startsWith("lework-android") ||
        cleanDns.startsWith("levi-remote-")
}

fun prioritizeTsnetPeers(
    peers: List<MobileTsnetPeer>,
    pairedHosts: List<PairedHost> = emptyList(),
    activeHostId: String? = null,
): List<MobileTsnetPeer> {
    if (peers.size <= 1) return peers
    fun extractHostsFromEndpoints(endpoints: List<String>): Set<String> =
        endpoints.mapNotNull { raw ->
            val afterScheme = raw.substringAfter("://", raw).substringBefore('/')
            val host = if (afterScheme.startsWith("[")) {
                afterScheme.substringAfter('[').substringBefore(']')
            } else {
                afterScheme.substringBefore(':')
            }.trim().lowercase()
            host.takeIf { it.isNotEmpty() && it != "127.0.0.1" && it != "localhost" }
        }.toSet()

    val activeHost = activeHostId?.let { id -> pairedHosts.firstOrNull { it.hubId == id } }
    val activeKnownIpsOrHosts = activeHost?.let { h ->
        extractHostsFromEndpoints(h.endpoints) + setOfNotNull(h.name.trim().lowercase().takeIf { it.isNotEmpty() })
    }.orEmpty()

    val pairedKnownIpsOrHosts = pairedHosts.flatMap { h ->
        extractHostsFromEndpoints(h.endpoints) + listOfNotNull(h.name.trim().lowercase().takeIf { it.isNotEmpty() })
    }.toSet()

    fun matchesSet(peer: MobileTsnetPeer, known: Set<String>): Boolean {
        if (known.isEmpty()) return false
        val ip = peer.tailscaleIp.trim().lowercase()
        val host = peer.hostName.trim().lowercase()
        val dns = peer.dnsName.trim().trimEnd('.').lowercase()
        val shortDns = dns.substringBefore('.')
        return (ip.isNotEmpty() && ip in known) ||
            (host.isNotEmpty() && host in known) ||
            (dns.isNotEmpty() && dns in known) ||
            (shortDns.isNotEmpty() && shortDns in known)
    }

    fun rank(peer: MobileTsnetPeer): Int {
        if (matchesSet(peer, activeKnownIpsOrHosts)) return 0
        if (matchesSet(peer, pairedKnownIpsOrHosts)) return 1
        val isMobile = peer.isMobileClientNode()
        if (!isMobile && peer.isLeworkNode && peer.online) return 2
        if (!isMobile && peer.online) return 3
        if (!isMobile && peer.isLeworkNode) return 4
        if (!isMobile) return 5
        if (peer.online) return 6
        return 7
    }

    return peers.withIndex()
        .sortedWith(compareBy<IndexedValue<MobileTsnetPeer>> { rank(it.value) }.thenBy { it.index })
        .map { it.value }
}

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
    val customName: String? = null,
) {
    val displayName: String
        get() = customName?.trim()?.takeIf { it.isNotEmpty() } ?: name
}

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

const val MAX_ATTACHED_FILE_BYTES: Long = 5L * 1024L * 1024L
const val MAX_ATTACHED_FILES: Int = 4

@Serializable
data class PickedFilePayload(
    val fileName: String,
    val mimeType: String,
    val dataBase64: String,
    val sizeBytes: Long = 0L,
    val thumbnailBase64: String? = null,
    val isImage: Boolean = false,
)

@Serializable
data class FilePickOutcome(
    val files: List<PickedFilePayload> = emptyList(),
    val skippedOversizedCount: Int = 0,
    val skippedUnreadableCount: Int = 0,
)

fun inferMimeTypeFromFileName(fileName: String): String {
    val ext = fileName.substringAfterLast('.', "").lowercase().trim()
    return when (ext) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "svg" -> "image/svg+xml"
        "pdf" -> "application/pdf"
        "txt", "log" -> "text/plain"
        "md", "markdown" -> "text/markdown"
        "json", "jsonl" -> "application/json"
        "yaml", "yml" -> "application/yaml"
        "xml" -> "application/xml"
        "html", "htm" -> "text/html"
        "css" -> "text/css"
        "csv" -> "text/csv"
        "tsv" -> "text/tab-separated-values"
        "js", "mjs", "cjs" -> "text/javascript"
        "ts", "mts", "cts", "tsx", "jsx" -> "text/typescript"
        "py", "kt", "kts", "java", "swift", "go", "rs", "c", "h", "cpp", "hpp", "rb", "php", "sql", "sh", "zsh", "bash" -> "text/plain"
        "zip" -> "application/zip"
        "gz", "tgz" -> "application/gzip"
        "tar" -> "application/x-tar"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "ppt" -> "application/vnd.ms-powerpoint"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "m4a" -> "audio/mp4"
        else -> "application/octet-stream"
    }
}

data class LocalDateTimeParts(
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
    val second: Int,
)

expect fun platformName(): String
expect fun isDesktopPlatform(): Boolean
expect fun appVersionLabel(): String
expect fun epochMillis(): Long
expect fun localDateTimeParts(epochMs: Long): LocalDateTimeParts
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
expect fun canOpenLocalFileExternally(path: String): Boolean
expect fun openLocalFileExternally(path: String): Boolean
expect fun copyTextToClipboard(text: String): Boolean
expect fun readTextFromClipboard(): String?
expect fun readImagesFromClipboard(maxCount: Int = 4): List<PickedImagePayload>

@Serializable
data class GlobalCliInstallStatus(
    val supported: Boolean = false,
    val installed: Boolean = false,
    val installedPath: String? = null,
    val launcherTargetPath: String? = null,
    val inShellPath: Boolean = false,
    val installDirectory: String? = null,
    val detail: String? = null,
)

// Desktop SSH direct-pairing helpers for remote Linux Devboxes
expect fun canConnectViaSsh(): Boolean
expect fun listSshConfigHosts(): List<String>
expect suspend fun requestSshHostPairingLink(sshTarget: String): Result<String>

// Desktop global `lework` CLI registration helpers
expect fun inspectGlobalCliStatus(): GlobalCliInstallStatus
expect suspend fun installGlobalCliCommand(): Result<GlobalCliInstallStatus>
expect suspend fun uninstallGlobalCliCommand(): Result<GlobalCliInstallStatus>

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

// Native image/file picker, camera capture, bitmap decoding & iOS provisioning profile expiration inspector
expect fun canPickImages(): Boolean
expect fun canPickFiles(): Boolean
expect fun canCaptureCameraImage(): Boolean
expect fun requestImagePick(onResult: (PickedImagePayload?) -> Unit)
expect fun requestMultiImagePick(
    source: ImagePickSource = ImagePickSource.LIBRARY,
    maxCount: Int = 4,
    onResult: (List<PickedImagePayload>) -> Unit,
)
expect fun requestFilePick(
    maxCount: Int = MAX_ATTACHED_FILES,
    onResult: (FilePickOutcome) -> Unit,
)
expect fun decodeImageBytes(bytes: ByteArray): ImageBitmap?
expect fun signingExpiryRemainingHours(): Int?
expect fun dismissNativeKeyboard()

// Cross-platform rolling runtime diagnostic log (secret-free)
expect fun logRuntimeEvent(tag: String, message: String)
expect fun runtimeLogFilePath(): String?

@Serializable
data class GitHubReleaseFetchPayload(
    val jsonBody: String? = null,
    val resolvedWebUrl: String? = null,
)

// GitHub Releases update checker & Desktop/Android one-click in-app updaters
expect suspend fun fetchLatestGitHubReleasePayload(
    apiUrl: String,
    fallbackWebUrl: String,
): Result<GitHubReleaseFetchPayload>

expect fun canInstallDesktopDmgUpdate(): Boolean
expect suspend fun installDesktopDmgUpdate(
    downloadUrl: String,
    expectedFileName: String,
    expectedSha256: String?,
    checksumsUrl: String?,
    onProgress: (fraction: Float?, statusText: String) -> Unit,
): Result<String>

expect fun canInstallAndroidApkUpdate(): Boolean
expect suspend fun installAndroidApkUpdate(
    downloadUrl: String,
    expectedFileName: String,
    expectedSha256: String?,
    checksumsUrl: String?,
    onProgress: (fraction: Float?, statusText: String) -> Unit,
): Result<String>
