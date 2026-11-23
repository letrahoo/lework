@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package dev.lework.platform

import dev.lework.protocol.ProtocolJson
import dev.lework.security.toHex
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.io.encoding.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface as SkiaSurface

@Serializable
private data class PersistedDesktopState(
    val lastGoodUrl: String? = null,
    val savedRelayUrls: List<String> = emptyList(),
    val savedToken: String? = null,
    val savedUserName: String? = "Owner",
    val savedPrefs: SavedSessionPrefs = SavedSessionPrefs(mode = "auto"),
    val pinnedFingerprints: Set<String> = emptySet(),
    val tsnetEnabled: Boolean = false,
    val tsnetControlUrl: String? = null,
    val pairedHosts: List<PairedHost> = emptyList(),
    val activeHostId: String? = null,
    val hostTokens: Map<String, String> = emptyMap(),
)

/**
 * Real macOS / JVM Desktop platform runtime used by `:desktopApp`.
 * Remains disabled (`isEnabled == false`) during `:shared:jvmTest` so `JvmPlatformTestHooks` stays 100% deterministic.
 */
object JvmDesktopRuntime {
    @Volatile
    var isEnabled: Boolean = false
        private set

    private val stateLock = Any()
    private var stateFile: File = File(System.getProperty("user.home"), ".lework/desktop/client-state.json")
    private var persisted = PersistedDesktopState()
    @Volatile
    private var localMacLoopbackUrl: String? = null

    private val _foreground = MutableStateFlow(true)
    val foreground: StateFlow<Boolean> = _foreground.asStateFlow()

    private val _networkEpoch = MutableStateFlow(0L)
    val networkEpoch: StateFlow<Long> = _networkEpoch.asStateFlow()

    private val _combinedUrls = MutableStateFlow<List<String>>(emptyList())
    val combinedUrls: StateFlow<List<String>> = _combinedUrls.asStateFlow()

    private val _incomingPairingUri = MutableStateFlow<String?>(null)
    val incomingPairingUri: StateFlow<String?> = _incomingPairingUri.asStateFlow()

    @Volatile
    private var bonjourUrls: List<String> = emptyList()
    @Volatile
    internal var tsnetProxyUrls: List<String> = emptyList()
    @Volatile
    internal var tsnetProxyPorts: Set<Int> = emptySet()
    @Volatile
    internal var tsnetForwarderSecret: String = ""

    private var dnsSdBrowseProc: Process? = null
    private val sshTunnelProcesses = ConcurrentHashMap<String, Pair<Int, Process>>()
    private val SSH_TARGET_REGEX = Regex("^[A-Za-z0-9._@-]+(?::\\d{1,5})?$")
    private val FORGE_HOST_PATTERNS = listOf(
        "github.com",
        "gitlab.com",
        "gitee.com",
        "bitbucket.org",
        "ssh.dev.azure.com",
    )

    fun initialize(customStateDir: File? = null) {
        synchronized(stateLock) {
            val baseDir = customStateDir ?: File(
                System.getenv("LEWORK_DESKTOP_HOME")?.takeIf { it.isNotBlank() }
                    ?: File(System.getProperty("user.home"), ".lework/desktop").absolutePath,
            )
            ensurePrivateDir(baseDir)
            stateFile = File(baseDir, "client-state.json")
            persisted = loadStateFromDisk(stateFile)
            isEnabled = true
            seedLocalMacHubIfAvailable()
        }
        registerDesktopUriHandler()
        Runtime.getRuntime().addShutdownHook(
            Thread {
                shutdown()
            },
        )
    }

    fun shutdown() {
        runCatching { dnsSdBrowseProc?.destroy() }
        dnsSdBrowseProc = null
        DesktopTsnetDaemon.stopSync()
        for ((_, entry) in sshTunnelProcesses) {
            runCatching { entry.second.destroy() }
        }
        sshTunnelProcesses.clear()
    }

    fun onWindowFocusChanged(focused: Boolean) {
        _foreground.value = focused
    }

    fun emitIncomingPairingUri(uri: String?) {
        val clean = uri?.trim()?.takeIf { it.isNotEmpty() }
        _incomingPairingUri.value = clean
    }

    private fun registerDesktopUriHandler() {
        runCatching {
            if (Desktop.isDesktopSupported()) {
                val desktop = Desktop.getDesktop()
                if (desktop.isSupported(Desktop.Action.APP_OPEN_URI)) {
                    desktop.setOpenURIHandler { event ->
                        val raw = event.uri?.toString()?.trim().orEmpty()
                        if (raw.startsWith("lework://", ignoreCase = true)) {
                            emitIncomingPairingUri(raw)
                        }
                    }
                }
            }
        }
    }

    private fun ensurePrivateDir(dir: File) {
        dir.mkdirs()
        runCatching {
            Files.setPosixFilePermissions(
                dir.toPath(),
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                ),
            )
        }
    }

    private fun loadStateFromDisk(file: File): PersistedDesktopState {
        if (!file.isFile) return PersistedDesktopState()
        return runCatching {
            val raw = file.readText()
            ProtocolJson.decodeFromString(PersistedDesktopState.serializer(), raw)
        }.getOrDefault(PersistedDesktopState())
    }

    private fun persistLocked() {
        runCatching {
            ensurePrivateDir(stateFile.parentFile)
            val tmp = File(stateFile.parentFile, "${stateFile.name}.tmp")
            val json = ProtocolJson.encodeToString(PersistedDesktopState.serializer(), persisted)
            tmp.writeText(json + "\n")
            runCatching {
                Files.setPosixFilePermissions(
                    tmp.toPath(),
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                )
            }
            Files.move(tmp.toPath(), stateFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.onFailure {
            runCatching {
                val json = ProtocolJson.encodeToString(PersistedDesktopState.serializer(), persisted)
                stateFile.writeText(json + "\n")
            }
        }
    }

    /**
     * Automatically imports the local Mac's `~/.lework/config.json` & `~/.lework/tls/cert.pem`
     * when running under the same macOS user account so first launch connects to localhost with zero setup.
     */
    private fun seedLocalMacHubIfAvailable() {
        runCatching {
            val leworkHome = File(
                System.getenv("LEWORK_HOME")?.takeIf { it.isNotBlank() }
                    ?: File(System.getProperty("user.home"), ".lework").absolutePath,
            )
            val cfgFile = File(leworkHome, "config.json")
            val certFile = File(leworkHome, "tls/cert.pem")
            if (!cfgFile.isFile || !certFile.isFile) return@runCatching

            val cfgObj = ProtocolJson.parseToJsonElement(cfgFile.readText()).jsonObject
            val hubId = cfgObj["hubId"]?.jsonPrimitive?.content?.trim().orEmpty()
            val name = cfgObj["name"]?.jsonPrimitive?.content?.trim().orEmpty().ifBlank { "本机 Mac" }
            val port = cfgObj["port"]?.jsonPrimitive?.intOrNull ?: 64450
            val token = cfgObj["token"]?.jsonPrimitive?.content?.trim().orEmpty()
            if (hubId.isEmpty() || token.isEmpty()) return@runCatching

            val cf = CertificateFactory.getInstance("X.509")
            val x509 = ByteArrayInputStream(certFile.readBytes()).use { cf.generateCertificate(it) as X509Certificate }
            val fp = MessageDigest.getInstance("SHA-256").digest(x509.encoded).toHex().lowercase()
            val loopback = "https://127.0.0.1:$port"
            localMacLoopbackUrl = loopback

            val nextPins = persisted.pinnedFingerprints + fp
            val nextHostTokens = persisted.hostTokens + (hubId to token)
            val existing = persisted.pairedHosts.firstOrNull { it.hubId == hubId }
            val mergedEndpoints = (listOf(loopback) + existing?.endpoints.orEmpty()).distinct()
            val seededHost = PairedHost(
                hubId = hubId,
                name = name,
                fingerprint = fp,
                endpoints = mergedEndpoints,
                userName = existing?.userName ?: "Owner",
                lastConnectedAt = existing?.lastConnectedAt ?: System.currentTimeMillis(),
            )
            val nextHosts = listOf(seededHost) + persisted.pairedHosts.filterNot { it.hubId == hubId }
            val nextActiveId = persisted.activeHostId ?: hubId
            val nextSavedToken = if (nextActiveId == hubId) token else (persisted.savedToken ?: token)
            persisted = persisted.copy(
                pinnedFingerprints = nextPins,
                hostTokens = nextHostTokens,
                pairedHosts = nextHosts,
                activeHostId = nextActiveId,
                savedToken = nextSavedToken,
                savedUserName = persisted.savedUserName ?: "Owner",
            )
            persistLocked()
        }
    }

    internal fun updateTsnetProxies(urls: List<String>) {
        tsnetProxyUrls = urls
        tsnetProxyPorts = urls.mapNotNull { u ->
            u.substringAfter("127.0.0.1:", "").substringBefore('/').toIntOrNull()
        }.toSet()
        _combinedUrls.value = (bonjourUrls + tsnetProxyUrls).distinct()
    }

    private fun updateBonjourUrls(urls: List<String>) {
        bonjourUrls = urls
        _combinedUrls.value = (bonjourUrls + tsnetProxyUrls).distinct()
    }

    fun candidateUrls(): List<String> = synchronized(stateLock) {
        val activeHostEndpoints = persisted.activeHostId?.let { id ->
            persisted.pairedHosts.firstOrNull { it.hubId == id }?.endpoints
        }.orEmpty()
        val tunnelUrls = sshTunnelProcesses.values.map { "https://127.0.0.1:${it.first}" }
        (activeHostEndpoints +
            listOfNotNull(localMacLoopbackUrl) +
            tunnelUrls +
            _combinedUrls.value +
            listOfNotNull(persisted.lastGoodUrl) +
            persisted.savedRelayUrls +
            listOf("https://127.0.0.1:64450")).distinct()
    }

    fun startBonjourDiscovery() {
        val dnsSd = File("/usr/bin/dns-sd")
        if (!dnsSd.canExecute()) return
        if (dnsSdBrowseProc?.isAlive == true) return
        Thread {
            runCatching {
                val proc = ProcessBuilder(dnsSd.absolutePath, "-B", "_lework._tcp", "local.")
                    .redirectErrorStream(true)
                    .start()
                dnsSdBrowseProc = proc
                val seenInstances = mutableSetOf<String>()
                proc.inputStream.bufferedReader().forEachLine { line ->
                    // Format: Timestamp  A/R Flags if domain serviceType instanceName
                    if (line.contains("_lework._tcp.") && line.contains(" Add ")) {
                        val instance = line.substringAfter("_lework._tcp.").trim()
                        if (instance.isNotEmpty() && seenInstances.add(instance)) {
                            resolveDnsSdInstance(dnsSd.absolutePath, instance)
                        }
                    }
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun resolveDnsSdInstance(dnsSdBin: String, instance: String) {
        Thread {
            runCatching {
                val p = ProcessBuilder(dnsSdBin, "-L", instance, "_lework._tcp", "local.")
                    .redirectErrorStream(true)
                    .start()
                val found = mutableListOf<String>()
                var port = 64450
                val readerThread = Thread {
                    runCatching {
                        p.inputStream.bufferedReader().forEachLine { l ->
                            val reachMatch = Regex("can be reached at ([^:\\s]+):(\\d+)").find(l)
                            if (reachMatch != null) {
                                val host = reachMatch.groupValues[1].trimEnd('.')
                                val parsedPort = reachMatch.groupValues[2].toIntOrNull() ?: 64450
                                port = parsedPort
                                if (host.isNotEmpty()) {
                                    found.add("https://$host:$parsedPort")
                                }
                            }
                            val ipsMatch = Regex("\\bips=([^\\s]+)").find(l)
                            if (ipsMatch != null) {
                                for (ip in ipsMatch.groupValues[1].split(',')) {
                                    val clean = ip.trim()
                                    if (clean.isNotEmpty()) {
                                        found.add("https://$clean:$port")
                                    }
                                }
                            }
                        }
                    }
                }.apply { isDaemon = true; start() }
                p.waitFor(1_800, TimeUnit.MILLISECONDS)
                runCatching { p.destroy() }
                readerThread.join(300)
                if (found.isNotEmpty()) {
                    updateBonjourUrls((found + bonjourUrls).distinct())
                }
            }
        }.apply { isDaemon = true }.start()
    }

    // --- Persistence accessors ---
    fun loadLastGoodUrl(): String? = synchronized(stateLock) { persisted.lastGoodUrl }
    fun saveLastGoodUrl(url: String?) = synchronized(stateLock) {
        persisted = persisted.copy(lastGoodUrl = url?.takeIf { it.isNotBlank() })
        persistLocked()
    }

    fun loadRelayUrls(): List<String> = synchronized(stateLock) { persisted.savedRelayUrls }
    fun saveRelayUrls(urls: List<String>) = synchronized(stateLock) {
        persisted = persisted.copy(
            savedRelayUrls = urls.map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() }.distinct(),
        )
        persistLocked()
    }

    fun loadAccessToken(): String? = synchronized(stateLock) { persisted.savedToken }
    fun loadAuthUserName(): String? = synchronized(stateLock) { persisted.savedUserName }
    fun saveAuthSession(token: String?, userName: String?) = synchronized(stateLock) {
        persisted = if (token.isNullOrBlank()) {
            persisted.copy(savedToken = null, savedUserName = null)
        } else {
            persisted.copy(savedToken = token, savedUserName = userName ?: "Owner")
        }
        persistLocked()
    }

    fun loadPrefs(): SavedSessionPrefs = synchronized(stateLock) { persisted.savedPrefs }
    fun savePrefs(prefs: SavedSessionPrefs) = synchronized(stateLock) {
        persisted = persisted.copy(savedPrefs = prefs)
        persistLocked()
    }

    fun loadPins(): Set<String> = synchronized(stateLock) { persisted.pinnedFingerprints }
    fun savePins(pins: Set<String>) = synchronized(stateLock) {
        persisted = persisted.copy(pinnedFingerprints = pins)
        persistLocked()
    }

    fun loadTsnetEnabled(): Boolean = synchronized(stateLock) { persisted.tsnetEnabled }
    fun saveTsnetEnabled(enabled: Boolean, controlUrl: String?) = synchronized(stateLock) {
        persisted = persisted.copy(
            tsnetEnabled = enabled,
            tsnetControlUrl = if (controlUrl != null) controlUrl.ifBlank { null } else persisted.tsnetControlUrl,
        )
        persistLocked()
    }
    fun loadTsnetControlUrl(): String? = synchronized(stateLock) { persisted.tsnetControlUrl }

    fun loadPairedHosts(): List<PairedHost> = synchronized(stateLock) { persisted.pairedHosts }
    fun savePairedHosts(hosts: List<PairedHost>) = synchronized(stateLock) {
        persisted = persisted.copy(pairedHosts = hosts)
        persistLocked()
    }

    fun loadActiveHostId(): String? = synchronized(stateLock) { persisted.activeHostId }
    fun saveActiveHostId(hubId: String?) = synchronized(stateLock) {
        persisted = persisted.copy(activeHostId = hubId?.takeIf { it.isNotBlank() })
        persistLocked()
    }

    fun loadHostToken(hubId: String): String? = synchronized(stateLock) { persisted.hostTokens[hubId] }
    fun saveHostToken(hubId: String, token: String?) = synchronized(stateLock) {
        val next = persisted.hostTokens.toMutableMap()
        if (token.isNullOrBlank()) next.remove(hubId) else next[hubId] = token
        persisted = persisted.copy(hostTokens = next)
        persistLocked()
    }

    // --- OS Clipboard & Browser ---
    fun openExternalUrl(url: String) {
        runCatching {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(url))
                return
            }
        }
        runCatching {
            ProcessBuilder("open", url).start()
        }
    }

    fun copyToClipboard(text: String) {
        runCatching {
            val selection = StringSelection(text)
            Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
        }
    }

    fun readFromClipboard(): String? = runCatching {
        val cb = Toolkit.getDefaultToolkit().systemClipboard
        if (cb.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
            (cb.getData(DataFlavor.stringFlavor) as? String)?.takeIf { it.isNotBlank() }
        } else {
            null
        }
    }.getOrNull()

    // --- Native Image File Picker ---
    fun pickImages(maxCount: Int, onResult: (List<PickedImagePayload>) -> Unit) {
        Thread {
            val pickedFiles = runCatching {
                val dialog = FileDialog(null as Frame?, "选择要发送的图片", FileDialog.LOAD).apply {
                    isMultipleMode = maxCount > 1
                    setFilenameFilter { _, name ->
                        val lower = name.lowercase()
                        lower.endsWith(".png") ||
                            lower.endsWith(".jpg") ||
                            lower.endsWith(".jpeg") ||
                            lower.endsWith(".webp") ||
                            lower.endsWith(".gif")
                    }
                }
                dialog.isVisible = true
                dialog.files?.toList().orEmpty()
            }.getOrDefault(emptyList())

            val payloads = pickedFiles
                .take(maxCount.coerceIn(1, 4))
                .mapNotNull { file -> encodePickedImageFile(file) }
            onResult(payloads)
        }.apply { isDaemon = true }.start()
    }

    private fun encodePickedImageFile(file: File): PickedImagePayload? = runCatching {
        if (!file.isFile || file.length() <= 0 || file.length() > 15 * 1024 * 1024) return null
        val rawBytes = file.readBytes()
        val ext = file.extension.lowercase()
        val rawMime = when (ext) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            else -> "image/jpeg"
        }

        val skiaImg = SkiaImage.makeFromEncoded(rawBytes)
        val thumbB64 = runCatching {
            val maxThumb = 220
            val scale = minOf(maxThumb.toFloat() / skiaImg.width.toFloat(), maxThumb.toFloat() / skiaImg.height.toFloat(), 1f)
            val tw = (skiaImg.width * scale).toInt().coerceAtLeast(1)
            val th = (skiaImg.height * scale).toInt().coerceAtLeast(1)
            val surface = SkiaSurface.makeRaster(ImageInfo.makeN32Premul(tw, th))
            surface.canvas.drawImageRect(
                skiaImg,
                Rect.makeWH(skiaImg.width.toFloat(), skiaImg.height.toFloat()),
                Rect.makeWH(tw.toFloat(), th.toFloat()),
            )
            val snapshot = surface.makeImageSnapshot()
            val encoded = snapshot.encodeToData(EncodedImageFormat.JPEG, 72) ?: snapshot.encodeToData(EncodedImageFormat.PNG)
            encoded?.bytes?.let { Base64.encode(it) }
        }.getOrNull()

        // Downscale oversized images so base64 stays comfortably within protocol 1MB limit
        val (finalMime, finalBytes) = if (rawBytes.size <= 700_000) {
            rawMime to rawBytes
        } else {
            val maxDim = 1600
            val scale = minOf(maxDim.toFloat() / skiaImg.width.toFloat(), maxDim.toFloat() / skiaImg.height.toFloat(), 1f)
            val w = (skiaImg.width * scale).toInt().coerceAtLeast(1)
            val h = (skiaImg.height * scale).toInt().coerceAtLeast(1)
            val surface = SkiaSurface.makeRaster(ImageInfo.makeN32Premul(w, h))
            surface.canvas.drawImageRect(
                skiaImg,
                Rect.makeWH(skiaImg.width.toFloat(), skiaImg.height.toFloat()),
                Rect.makeWH(w.toFloat(), h.toFloat()),
            )
            val encoded = surface.makeImageSnapshot().encodeToData(EncodedImageFormat.JPEG, 82)
                ?: return null
            "image/jpeg" to encoded.bytes
        }

        PickedImagePayload(
            fileName = file.name.take(120),
            mimeType = finalMime,
            dataBase64 = Base64.encode(finalBytes),
            thumbnailBase64 = thumbB64,
        )
    }.getOrNull()

    // --- SSH Config Discovery & One-Click Remote Devbox Pairing ---
    fun listSshConfigHosts(): List<String> = runCatching {
        val sshCfg = File(System.getProperty("user.home"), ".ssh/config")
        if (!sshCfg.isFile) return@runCatching emptyList()
        val results = mutableListOf<String>()
        sshCfg.forEachLine { rawLine ->
            val trimmed = rawLine.substringBefore('#').trim()
            if (trimmed.startsWith("Host ", ignoreCase = true) || trimmed.startsWith("Host\t", ignoreCase = true)) {
                val aliases = trimmed.substring(4).trim().split(Regex("\\s+"))
                for (alias in aliases) {
                    if (alias.isEmpty() || alias.contains('*') || alias.contains('?') || alias.startsWith('!')) continue
                    val lower = alias.lowercase()
                    if (FORGE_HOST_PATTERNS.any { lower == it || lower.endsWith(".$it") }) continue
                    if (SSH_TARGET_REGEX.matches(alias) && alias !in results) {
                        results.add(alias)
                    }
                }
            }
        }
        results.take(8)
    }.getOrDefault(emptyList())

    suspend fun requestSshPairingLink(rawTarget: String): Result<String> = withContext(Dispatchers.IO) {
        val clean = rawTarget.trim()
        if (clean.isEmpty() || clean.startsWith("-") || !SSH_TARGET_REGEX.matches(clean)) {
            return@withContext Result.failure(IllegalArgumentException("SSH 主机格式无效，请输入如 devbox 或 user@192.168.10.20"))
        }

        val hostPart = clean.substringBefore(':')
        val customSshPort = clean.substringAfter(':', "").takeIf { it.isNotEmpty() }?.toIntOrNull()

        val sshCmd = mutableListOf(
            "ssh",
            "-o",
            "BatchMode=yes",
            "-o",
            "ConnectTimeout=8",
        )
        if (customSshPort != null) {
            sshCmd.add("-p")
            sshCmd.add(customSshPort.toString())
        }
        sshCmd.add(hostPart)
        sshCmd.add("export PATH=\"\$HOME/.local/bin:/opt/homebrew/bin:/usr/local/bin:\$PATH\"; lework pair")

        val proc = try {
            ProcessBuilder(sshCmd).start()
        } catch (t: Throwable) {
            return@withContext Result.failure(IllegalStateException("无法启动本机 ssh 命令: ${t.message}"))
        }

        var stdoutText = ""
        var stderrText = ""
        val outThread = Thread { stdoutText = runCatching { proc.inputStream.bufferedReader().readText() }.getOrDefault("") }
        val errThread = Thread { stderrText = runCatching { proc.errorStream.bufferedReader().readText() }.getOrDefault("") }
        outThread.start()
        errThread.start()

        val finished = proc.waitFor(15, TimeUnit.SECONDS)
        if (!finished) {
            runCatching { proc.destroyForcibly() }
            return@withContext Result.failure(IllegalStateException("SSH 连接 $hostPart 超时，请确认免密登录可用"))
        }
        outThread.join(1_000)
        errThread.join(1_000)

        val linkMatch = Regex("(lework://pair\\?d=[A-Za-z0-9_-]+)").find(stdoutText)
        if (linkMatch == null) {
            val detail = stderrText.trim().lineSequence().lastOrNull { it.isNotBlank() }
                ?: stdoutText.trim().lineSequence().lastOrNull { it.isNotBlank() }
                ?: "远端未找到 lework 命令，请先在开发机执行 pnpm setup && lework service install"
            return@withContext Result.failure(IllegalStateException("无法从 $hostPart 获取配对信息：$detail"))
        }

        val rawDeepLink = linkMatch.groupValues[1]
        val enriched = ensureDirectOrSshTunneledLink(hostPart, customSshPort, rawDeepLink)
        Result.success(enriched)
    }

    /**
     * Checks if at least one remote endpoint in `lework://pair?d=...` is directly TCP-reachable;
     * if firewalled (e.g. behind an SSH jump host), automatically opens a local `ssh -N -L` port forward
     * and prepends `https://127.0.0.1:<localPort>` into the signed fingerprint payload!
     */
    private fun ensureDirectOrSshTunneledLink(
        hostPart: String,
        customSshPort: Int?,
        deepLink: String,
    ): String = runCatching {
        val b64 = deepLink.substringAfter("lework://pair?d=", "")
        if (b64.isEmpty()) return@runCatching deepLink
        val padded = b64 + "=".repeat((4 - b64.length % 4) % 4)
        val decodedJson = Base64.UrlSafe.decode(padded).decodeToString()
        val obj = ProtocolJson.parseToJsonElement(decodedJson).jsonObject
        val urls = obj["urls"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content.takeIf { s -> s.isNotBlank() } }.orEmpty()
        val primaryUrl = obj["url"]?.jsonPrimitive?.content.orEmpty()
        val candidates = (urls + listOf(primaryUrl)).filter { it.isNotBlank() }.distinct()

        var remoteHubPort = 64450
        for (u in candidates) {
            val uri = runCatching { URI(u) }.getOrNull() ?: continue
            val h = uri.host ?: continue
            val p = if (uri.port > 0) uri.port else 64450
            remoteHubPort = p
            if (h != "127.0.0.1" && h != "localhost" && isTcpReachable(h, p, 1_400)) {
                return@runCatching deepLink
            }
        }

        // Direct TCP blocked -> open or reuse local SSH port forward `127.0.0.1:<localPort> -> 127.0.0.1:<remoteHubPort>`
        val tunnelKey = "$hostPart:$remoteHubPort"
        val existingTunnel = sshTunnelProcesses[tunnelKey]
        val localPort = if (existingTunnel != null && existingTunnel.second.isAlive) {
            existingTunnel.first
        } else {
            existingTunnel?.second?.let { runCatching { it.destroy() } }
            val freePort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
            val fwdCmd = mutableListOf(
                "ssh",
                "-o",
                "BatchMode=yes",
                "-o",
                "ExitOnForwardFailure=yes",
                "-o",
                "ServerAliveInterval=20",
                "-o",
                "ServerAliveCountMax=3",
                "-N",
                "-L",
                "127.0.0.1:$freePort:127.0.0.1:$remoteHubPort",
            )
            if (customSshPort != null) {
                fwdCmd.add("-p")
                fwdCmd.add(customSshPort.toString())
            }
            fwdCmd.add(hostPart)
            val tunnelProc = ProcessBuilder(fwdCmd).start()
            val deadline = System.currentTimeMillis() + 5_000L
            while (System.currentTimeMillis() < deadline && tunnelProc.isAlive) {
                if (isTcpReachable("127.0.0.1", freePort, 250)) break
                Thread.sleep(120L)
            }
            if (!tunnelProc.isAlive) return@runCatching deepLink
            sshTunnelProcesses[tunnelKey] = freePort to tunnelProc
            freePort
        }

        val tunnelEndpoint = "https://127.0.0.1:$localPort"
        val nextUrls = JsonArray((listOf(tunnelEndpoint) + candidates).distinct().map { JsonPrimitive(it) })
        val patchedObj = buildJsonObject {
            for ((k, v) in obj) {
                when (k) {
                    "url" -> put("url", tunnelEndpoint)
                    "urls" -> put("urls", nextUrls)
                    else -> put(k, v)
                }
            }
            if ("urls" !in obj) put("urls", nextUrls)
            if ("url" !in obj) put("url", tunnelEndpoint)
        }
        val reEncoded = Base64.UrlSafe.encode(patchedObj.toString().encodeToByteArray()).trimEnd('=')
        "lework://pair?d=$reEncoded"
    }.getOrDefault(deepLink)

    private fun isTcpReachable(host: String, port: Int, timeoutMs: Int): Boolean = runCatching {
        Socket().use { sock ->
            sock.connect(InetSocketAddress(host, port), timeoutMs)
            true
        }
    }.getOrDefault(false)
}

/**
 * Embedded `lework-tsnet --client-only` userspace Tailscale mesh manager for macOS Desktop.
 */
internal object DesktopTsnetDaemon {
    private val mutex = Mutex()
    private var proc: Process? = null
    private var controlPort: Int = 0
    private var secret: String = ""
    @Volatile
    private var lastStderr: String = ""

    @Serializable
    private data class ConfigurePayload(
        val controlUrl: String = "",
        val authKey: String = "",
    )

    fun resolveBinary(): File? {
        val envBin = System.getenv("LEWORK_TSNET_BIN")?.trim()?.takeIf { it.isNotEmpty() }
        if (envBin != null) {
            val f = File(envBin)
            if (f.isFile && f.canExecute()) return f
        }
        val appResDir = System.getProperty("compose.application.resources.dir")?.trim()
        if (!appResDir.isNullOrEmpty()) {
            val bundled = File(appResDir, "lework-tsnet")
            if (bundled.isFile && bundled.canExecute()) return bundled
        }
        val userDir = File(System.getProperty("user.dir"))
        val candidates = listOf(
            File(userDir, "tsnet-bridge/bin/lework-tsnet"),
            File(userDir.parentFile ?: userDir, "tsnet-bridge/bin/lework-tsnet"),
            File(System.getProperty("user.home"), ".lework/bin/lework-tsnet"),
            File("/opt/homebrew/bin/lework-tsnet"),
            File("/usr/local/bin/lework-tsnet"),
        )
        return candidates.firstOrNull { it.isFile && it.canExecute() }
    }

    fun isAvailable(): Boolean = resolveBinary() != null

    private fun sanitizedHostname(): String {
        val raw = runCatching { InetAddress.getLocalHost().hostName }.getOrNull()
            ?.removeSuffix(".local")
            ?.lowercase()
            ?.replace(Regex("[^a-z0-9-]+"), "-")
            ?.trim('-')
            ?: "mac"
        return "lework-desktop-${raw.ifEmpty { "mac" }}".take(48)
    }

    suspend fun ensureStarted(controlUrl: String?): Boolean = mutex.withLock {
        if (proc?.isAlive == true && controlPort > 0) return@withLock true
        val bin = resolveBinary() ?: return@withLock false
        withContext(Dispatchers.IO) {
            stopSync()
            val stateDir = File(System.getProperty("user.home"), ".lework/desktop/tsnet").apply { mkdirs() }
            val secBytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
            val newSecret = secBytes.joinToString("") { "%02x".format(it) }
            val fwdBytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
            val newForwarderSecret = fwdBytes.joinToString("") { "%02x".format(it) }

            val cmd = mutableListOf(
                bin.absolutePath,
                "--client-only",
                "--state-dir",
                stateDir.absolutePath,
                "--hostname",
                sanitizedHostname(),
                "--hub-port",
                "64450",
            )
            if (!controlUrl.isNullOrBlank()) {
                cmd.add("--control-url")
                cmd.add(controlUrl.trim())
            }

            lastStderr = ""
            val pb = ProcessBuilder(cmd).apply {
                environment()["LEWORK_TSNET_SECRET"] = newSecret
                environment()["LEWORK_FORWARDER_SECRET"] = newForwarderSecret
            }
            val started = runCatching { pb.start() }.getOrNull() ?: return@withContext false
            Thread {
                runCatching {
                    started.errorStream.bufferedReader().forEachLine { line ->
                        val t = line.trim()
                        if (t.isNotEmpty()) lastStderr = t.take(240)
                    }
                }
            }.apply { isDaemon = true }.start()

            val reader = BufferedReader(InputStreamReader(started.inputStream))
            var foundPort = 0
            val deadline = System.currentTimeMillis() + 8_000L
            while (System.currentTimeMillis() < deadline && started.isAlive) {
                val line = runCatching { reader.readLine() }.getOrNull() ?: break
                val trimmed = line.trim()
                if (trimmed.startsWith("{")) {
                    val obj = runCatching { ProtocolJson.parseToJsonElement(trimmed) as? JsonObject }.getOrNull()
                    val ready = obj?.get("ready")?.jsonPrimitive?.booleanOrNull == true
                    val port = obj?.get("controlPort")?.jsonPrimitive?.intOrNull ?: 0
                    if (ready && port > 0) {
                        foundPort = port
                        break
                    }
                }
            }
            if (foundPort <= 0) {
                runCatching { started.destroy() }
                return@withContext false
            }
            Thread {
                runCatching { reader.forEachLine { /* drain */ } }
            }.apply { isDaemon = true }.start()

            proc = started
            controlPort = foundPort
            secret = newSecret
            JvmDesktopRuntime.tsnetForwarderSecret = newForwarderSecret
            true
        }
    }

    fun stopSync() {
        runCatching { proc?.destroy() }
        proc = null
        controlPort = 0
        secret = ""
        JvmDesktopRuntime.tsnetForwarderSecret = ""
        JvmDesktopRuntime.updateTsnetProxies(emptyList())
    }

    suspend fun stopAndPurgeState() = mutex.withLock {
        withContext(Dispatchers.IO) {
            stopSync()
            runCatching { File(System.getProperty("user.home"), ".lework/desktop/tsnet").deleteRecursively() }
        }
    }

    suspend fun callControl(path: String, method: String = "GET", jsonBody: String? = null): MobileTsnetStatus {
        val avail = isAvailable()
        if (!avail || controlPort <= 0 || secret.isEmpty()) {
            return MobileTsnetStatus(available = avail, backendState = if (avail) "Stopped" else "Unavailable")
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val conn = (URL("http://127.0.0.1:$controlPort$path").openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
                    requestMethod = method
                    connectTimeout = 5_000
                    readTimeout = 14_000
                    setRequestProperty("Authorization", "Bearer $secret")
                    if (jsonBody != null) {
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    }
                }
                if (jsonBody != null) {
                    conn.outputStream.use { it.write(jsonBody.encodeToByteArray()) }
                }
                val stream = if (conn.responseCode in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
                val raw = stream.bufferedReader().use { it.readText() }
                val parsed = ProtocolJson.decodeFromString(MobileTsnetStatus.serializer(), raw)
                val withAvail = parsed.copy(available = true)
                val proxies = withAvail.peers.mapNotNull { it.localProxyUrl?.takeIf { u -> u.isNotBlank() } }
                JvmDesktopRuntime.updateTsnetProxies(proxies)
                withAvail
            }.getOrElse { err ->
                MobileTsnetStatus(
                    available = avail,
                    backendState = "Error",
                    error = err.message ?: "Desktop tsnet bridge error",
                )
            }
        }
    }

    suspend fun configureAndCall(controlUrl: String?, authKey: String?, nextPath: String): MobileTsnetStatus {
        if (!ensureStarted(controlUrl)) {
            return MobileTsnetStatus(
                available = isAvailable(),
                backendState = if (isAvailable()) "Error" else "Unavailable",
                error = if (isAvailable()) (lastStderr.takeIf { it.isNotBlank() } ?: "Failed to start desktop tsnet daemon") else null,
            )
        }
        if (!controlUrl.isNullOrBlank() || !authKey.isNullOrBlank()) {
            val body = ProtocolJson.encodeToString(
                ConfigurePayload.serializer(),
                ConfigurePayload(controlUrl = controlUrl?.trim().orEmpty(), authKey = authKey?.trim().orEmpty()),
            )
            callControl("/v1/configure", "POST", body)
        }
        return callControl(nextPath, if (nextPath == "/v1/status") "GET" else "POST", if (nextPath == "/v1/status") null else "{}")
    }
}
