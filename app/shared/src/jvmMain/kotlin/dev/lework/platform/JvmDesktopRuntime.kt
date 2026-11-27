@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package dev.lework.platform

import dev.lework.protocol.ProtocolJson
import dev.lework.security.toHex
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Image as AwtImage
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.awt.image.MultiResolutionImage
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import javax.imageio.ImageIO
import javax.swing.ImageIcon
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
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
internal data class PersistedDesktopState(
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

internal data class LocalMacHubSeed(
    val hubId: String,
    val name: String,
    val port: Int,
    val token: String,
    val fingerprint: String,
    val loopbackUrl: String,
    val certFileName: String,
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
    private val logLock = Any()
    private var stateFile: File = File(System.getProperty("user.home"), ".lework/desktop/client-state.json")
    private var runtimeLogFile: File = File(System.getProperty("user.home"), ".lework/desktop/runtime.log")
    private const val MAX_RUNTIME_LOG_BYTES = 2L * 1024L * 1024L
    private var persisted = PersistedDesktopState()
    @Volatile
    private var localMacHubId: String? = null
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
    private val FORGE_HOST_PREFIXES = listOf(
        "git.",
        "gitlab.",
        "code.",
        "gerrit.",
        "gitea.",
        "bitbucket.",
    )

    fun initialize(customStateDir: File? = null) {
        synchronized(stateLock) {
            val baseDir = customStateDir ?: File(
                System.getenv("LEWORK_DESKTOP_HOME")?.takeIf { it.isNotBlank() }
                    ?: File(System.getProperty("user.home"), ".lework/desktop").absolutePath,
            )
            ensurePrivateDir(baseDir)
            stateFile = File(baseDir, "client-state.json")
            synchronized(logLock) {
                runtimeLogFile = File(baseDir, "runtime.log")
            }
            persisted = loadStateFromDisk(stateFile)
            isEnabled = true
            seedLocalMacHubIfAvailable()
        }
        logRuntimeEvent("DESKTOP", "runtime initialized stateDir=${stateFile.parentFile?.absolutePath.orEmpty()}")
        registerDesktopUriHandler()
        Runtime.getRuntime().addShutdownHook(
            Thread {
                shutdown()
            },
        )
    }

    fun runtimeLogPath(): String = synchronized(logLock) { runtimeLogFile.absolutePath }

    fun logRuntimeEvent(tag: String, message: String) {
        val line = formatRuntimeLogLine(System.currentTimeMillis(), tag, message)
        if (System.getenv("LEWORK_DEBUG_LOG") == "1" || System.getProperty("lework.debug.log") == "true") {
            System.err.println(line)
        }
        synchronized(logLock) {
            runCatching {
                val target = runtimeLogFile
                target.parentFile?.let { ensurePrivateDir(it) }
                if (target.isFile && target.length() >= MAX_RUNTIME_LOG_BYTES) {
                    val rotated = File(target.parentFile, "${target.name}.1")
                    runCatching {
                        Files.move(
                            target.toPath(),
                            rotated.toPath(),
                            StandardCopyOption.REPLACE_EXISTING,
                        )
                    }.onFailure {
                        runCatching { rotated.delete() }
                        runCatching { target.renameTo(rotated) }
                    }
                }
                val isNew = !target.exists()
                target.appendText(line + "\n")
                if (isNew) {
                    runCatching {
                        Files.setPosixFilePermissions(
                            target.toPath(),
                            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                        )
                    }
                }
            }
        }
    }

    fun shutdown() {
        logRuntimeEvent("DESKTOP", "runtime shutdown")
        runCatching { dnsSdBrowseProc?.destroy() }
        dnsSdBrowseProc = null
        DesktopTsnetDaemon.stopSync()
        for ((_, entry) in sshTunnelProcesses) {
            runCatching { entry.second.destroy() }
        }
        sshTunnelProcesses.clear()
    }

    fun onWindowFocusChanged(focused: Boolean) {
        val prev = _foreground.value
        _foreground.value = focused
        if (prev != focused) {
            logRuntimeEvent("WINDOW", "focus changed $prev->$focused")
        }
    }

    fun emitIncomingPairingUri(uri: String?) {
        val clean = uri?.trim()?.takeIf { it.isNotEmpty() }
        if (clean != null) {
            logRuntimeEvent("DEEPLINK", "incoming uri=${redactUrlForLog(clean)}")
        }
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

    internal fun resolveLocalHubCertFile(leworkHome: File): File? =
        listOf(
            File(leworkHome, "tls/hub-cert.pem"),
            File(leworkHome, "tls/cert.pem"),
        ).firstOrNull { it.isFile }

    internal fun readLocalMacHubSeed(leworkHome: File): LocalMacHubSeed? {
        val cfgFile = File(leworkHome, "config.json")
        val certFile = resolveLocalHubCertFile(leworkHome) ?: return null
        if (!cfgFile.isFile) return null

        val cfgObj = ProtocolJson.parseToJsonElement(cfgFile.readText()).jsonObject
        val hubId = cfgObj["hubId"]?.jsonPrimitive?.content?.trim().orEmpty()
        val name = cfgObj["name"]?.jsonPrimitive?.content?.trim().orEmpty().ifBlank { "本机 Mac" }
        val port = cfgObj["port"]?.jsonPrimitive?.intOrNull ?: 64450
        val token = cfgObj["token"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (hubId.isEmpty() || token.isEmpty()) return null

        val cf = CertificateFactory.getInstance("X.509")
        val x509 = ByteArrayInputStream(certFile.readBytes()).use { cf.generateCertificate(it) as X509Certificate }
        val fp = MessageDigest.getInstance("SHA-256").digest(x509.encoded).toHex().lowercase()
        return LocalMacHubSeed(
            hubId = hubId,
            name = name,
            port = port,
            token = token,
            fingerprint = fp,
            loopbackUrl = "https://127.0.0.1:$port",
            certFileName = certFile.name,
        )
    }

    internal fun applyLocalMacHubSeed(
        current: PersistedDesktopState,
        seed: LocalMacHubSeed,
        nowMs: Long = System.currentTimeMillis(),
    ): PersistedDesktopState {
        val nextPins = current.pinnedFingerprints + seed.fingerprint
        val nextHostTokens = current.hostTokens + (seed.hubId to seed.token)
        val existing = current.pairedHosts.firstOrNull { it.hubId == seed.hubId }
        val mergedEndpoints = (listOf(seed.loopbackUrl) + existing?.endpoints.orEmpty()).distinct().take(8)
        val seededHost = PairedHost(
            hubId = seed.hubId,
            name = seed.name,
            fingerprint = seed.fingerprint,
            endpoints = mergedEndpoints,
            userName = existing?.userName ?: "Owner",
            lastConnectedAt = existing?.lastConnectedAt ?: nowMs,
            customName = existing?.customName,
        )
        val nextHosts = prioritizeLocalHostEndpoints(
            hosts = listOf(seededHost) + current.pairedHosts.filterNot { it.hubId == seed.hubId },
            localHubId = seed.hubId,
            localLoopbackUrl = seed.loopbackUrl,
        )
        val nextActiveId = current.activeHostId ?: seed.hubId
        val nextSavedToken = if (nextActiveId == seed.hubId) seed.token else (current.savedToken ?: seed.token)
        return current.copy(
            pinnedFingerprints = nextPins,
            hostTokens = nextHostTokens,
            pairedHosts = nextHosts,
            activeHostId = nextActiveId,
            savedToken = nextSavedToken,
            savedUserName = current.savedUserName ?: "Owner",
        )
    }

    /**
     * Automatically imports the local Mac's `~/.lework/config.json` & `~/.lework/tls/hub-cert.pem`
     * (with legacy `tls/cert.pem` fallback) when running under the same macOS user account so first
     * launch connects to localhost with zero setup.
     */
    private fun seedLocalMacHubIfAvailable() {
        runCatching {
            val leworkHome = File(
                System.getenv("LEWORK_HOME")?.takeIf { it.isNotBlank() }
                    ?: File(System.getProperty("user.home"), ".lework").absolutePath,
            )
            val seed = readLocalMacHubSeed(leworkHome) ?: run {
                val cfgExists = File(leworkHome, "config.json").isFile
                val certExists = resolveLocalHubCertFile(leworkHome) != null
                if (cfgExists || certExists) {
                    logRuntimeEvent(
                        "DESKTOP",
                        "local hub auto-seed skipped cfgExists=$cfgExists certExists=$certExists",
                    )
                }
                return@runCatching
            }
            localMacHubId = seed.hubId
            localMacLoopbackUrl = seed.loopbackUrl
            persisted = applyLocalMacHubSeed(persisted, seed)
            persistLocked()
            logRuntimeEvent(
                "DESKTOP",
                "seeded local hub hubId=${seed.hubId} endpoint=${seed.loopbackUrl} cert=${seed.certFileName}",
            )
        }.onFailure { err ->
            logRuntimeEvent(
                "DESKTOP",
                "local hub auto-seed failed err=${err::class.simpleName}:${err.message.orEmpty()}",
            )
        }
    }

    internal fun updateTsnetProxies(urls: List<String>) {
        tsnetProxyUrls = urls
        tsnetProxyPorts = urls.mapNotNull { u ->
            u.substringAfter("127.0.0.1:", "").substringBefore('/').toIntOrNull()
        }.toSet()
        val next = (bonjourUrls + tsnetProxyUrls).distinct()
        if (next != _combinedUrls.value) {
            logRuntimeEvent("DISCOVERY", "tsnet proxies updated count=${urls.size} combined=${next.size}")
        }
        _combinedUrls.value = next
    }

    private fun updateBonjourUrls(urls: List<String>) {
        bonjourUrls = urls
        val next = (bonjourUrls + tsnetProxyUrls).distinct()
        if (next != _combinedUrls.value) {
            logRuntimeEvent("DISCOVERY", "bonjour urls updated count=${urls.size} urls=${urls.take(4).joinToString(",") { redactUrlForLog(it) }}")
        }
        _combinedUrls.value = next
    }

    internal fun prioritizeLocalHostEndpoints(
        hosts: List<PairedHost>,
        localHubId: String?,
        localLoopbackUrl: String?,
    ): List<PairedHost> {
        if (localHubId.isNullOrBlank() || localLoopbackUrl.isNullOrBlank()) return hosts
        return hosts.map { host ->
            if (host.hubId == localHubId) {
                host.copy(endpoints = (listOf(localLoopbackUrl) + host.endpoints).distinct().take(8))
            } else {
                host
            }
        }
    }

    internal fun buildCandidateUrls(
        activeHostId: String?,
        pairedHosts: List<PairedHost>,
        localHubId: String?,
        localLoopbackUrl: String?,
        tunnelUrls: List<String>,
        combinedDiscoveredUrls: List<String>,
        lastGoodUrl: String?,
        savedRelayUrls: List<String>,
    ): List<String> {
        val activeHostEndpoints = activeHostId?.let { id ->
            pairedHosts.firstOrNull { it.hubId == id }?.endpoints
        }.orEmpty()
        val loopback = localLoopbackUrl?.takeIf { it.isNotBlank() }
        val ordered = if (loopback != null && (activeHostId == null || activeHostId == localHubId)) {
            listOf(loopback) +
                activeHostEndpoints +
                tunnelUrls +
                combinedDiscoveredUrls +
                listOfNotNull(lastGoodUrl) +
                savedRelayUrls +
                listOf("https://127.0.0.1:64450")
        } else {
            activeHostEndpoints +
                listOfNotNull(localLoopbackUrl) +
                tunnelUrls +
                combinedDiscoveredUrls +
                listOfNotNull(lastGoodUrl) +
                savedRelayUrls +
                listOf("https://127.0.0.1:64450")
        }
        return ordered.distinct()
    }

    fun candidateUrls(): List<String> = synchronized(stateLock) {
        val tunnelUrls = sshTunnelProcesses.values.map { "https://127.0.0.1:${it.first}" }
        buildCandidateUrls(
            activeHostId = persisted.activeHostId,
            pairedHosts = persisted.pairedHosts,
            localHubId = localMacHubId,
            localLoopbackUrl = localMacLoopbackUrl,
            tunnelUrls = tunnelUrls,
            combinedDiscoveredUrls = _combinedUrls.value,
            lastGoodUrl = persisted.lastGoodUrl,
            savedRelayUrls = persisted.savedRelayUrls,
        )
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
        persisted = persisted.copy(
            pairedHosts = prioritizeLocalHostEndpoints(hosts, localMacHubId, localMacLoopbackUrl),
        )
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
        logRuntimeEvent("EXTERNAL_URL", "open ${redactUrlForLog(url)}")
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

    fun copyToClipboard(text: String): Boolean =
        runCatching {
            val selection = StringSelection(text)
            Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
        }.isSuccess

    fun readFromClipboard(): String? = runCatching {
        val cb = Toolkit.getDefaultToolkit().systemClipboard
        if (cb.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
            (cb.getData(DataFlavor.stringFlavor) as? String)?.takeIf { it.isNotBlank() }
        } else {
            null
        }
    }.getOrNull()

    private const val MAX_RAW_IMAGE_BYTES = 15 * 1024 * 1024
    private val CLIPBOARD_IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "tiff", "tif")
    private val WEB_SAFE_IMAGE_MIMES = setOf("image/png", "image/jpeg", "image/webp", "image/gif")
    private val PREFERRED_CLIPBOARD_IMAGE_MIMES = listOf(
        "image/png",
        "image/webp",
        "image/jpeg",
        "image/gif",
        "image/tiff",
    )

    fun readImagesFromClipboard(maxCount: Int = 4): List<PickedImagePayload> = runCatching {
        val limit = maxCount.coerceIn(1, 4)
        val transferable = Toolkit.getDefaultToolkit().systemClipboard.getContents(null) ?: return emptyList()
        extractImagesFromTransferable(transferable, limit)
    }.getOrDefault(emptyList())

    internal fun extractImagesFromTransferable(
        transferable: Transferable,
        maxCount: Int = 4,
    ): List<PickedImagePayload> {
        val limit = maxCount.coerceIn(1, 4)

        // Priority 1: Copied image file(s) from Finder / file manager
        if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            val rawFiles = runCatching {
                transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>
            }.getOrNull().orEmpty()
            val imageFiles = rawFiles
                .filterIsInstance<File>()
                .filter { file -> file.isFile && file.extension.lowercase() in CLIPBOARD_IMAGE_EXTENSIONS }
            if (imageFiles.isNotEmpty()) {
                val encoded = mutableListOf<PickedImagePayload>()
                for (file in imageFiles) {
                    if (encoded.size >= limit) break
                    encodePickedImageFile(file)?.let { encoded.add(it) }
                }
                if (encoded.isNotEmpty()) return encoded
            }
        }

        // Priority 2: In-memory bitmap / macOS screenshot / browser "Copy Image"
        if (transferable.isDataFlavorSupported(DataFlavor.imageFlavor)) {
            val awtImg = runCatching {
                transferable.getTransferData(DataFlavor.imageFlavor) as? AwtImage
            }.getOrNull()
            if (awtImg != null) {
                val pngBytes = awtImageToPngBytes(awtImg)
                if (pngBytes != null) {
                    val payload = encodePickedImageBytes(
                        rawBytes = pngBytes,
                        fileName = "clipboard-${System.currentTimeMillis()}.png",
                        rawMime = "image/png",
                    )
                    if (payload != null) return listOf(payload)
                }
            }
        }

        // Priority 3: Direct image/* MIME stream / byte-array flavors (Electron, Chromium, native apps)
        val imageFlavors = runCatching { transferable.transferDataFlavors.toList() }
            .getOrDefault(emptyList())
            .filter { flavor ->
                val primary = runCatching { flavor.primaryType }.getOrNull()
                primary.equals("image", ignoreCase = true) &&
                    (InputStream::class.java.isAssignableFrom(flavor.representationClass) ||
                        flavor.representationClass == ByteArray::class.java)
            }
            .sortedBy { flavor ->
                val mime = "${flavor.primaryType}/${flavor.subType}".lowercase()
                val idx = PREFERRED_CLIPBOARD_IMAGE_MIMES.indexOf(mime)
                if (idx >= 0) idx else PREFERRED_CLIPBOARD_IMAGE_MIMES.size
            }

        for (flavor in imageFlavors) {
            val mime = "${flavor.primaryType}/${flavor.subType}".lowercase()
            val rawBytes = readTransferFlavorBytes(transferable, flavor, MAX_RAW_IMAGE_BYTES) ?: continue
            val ext = when (mime) {
                "image/png" -> "png"
                "image/webp" -> "webp"
                "image/gif" -> "gif"
                "image/jpeg", "image/jpg" -> "jpg"
                else -> "png"
            }
            val payload = encodePickedImageBytes(
                rawBytes = rawBytes,
                fileName = "clipboard-${System.currentTimeMillis()}.$ext",
                rawMime = mime,
            )
            if (payload != null) return listOf(payload)
        }

        return emptyList()
    }

    private fun resolveAwtImageDimensions(img: AwtImage): Pair<Int, Int>? {
        if (img is BufferedImage) {
            return if (img.width > 0 && img.height > 0) img.width to img.height else null
        }
        var w = img.getWidth(null)
        var h = img.getHeight(null)
        if (w <= 0 || h <= 0) {
            val icon = runCatching { ImageIcon(img) }.getOrNull()
            if (icon != null) {
                w = icon.iconWidth
                h = icon.iconHeight
            }
        }
        return if (w > 0 && h > 0) w to h else null
    }

    private fun awtImageToPngBytes(image: AwtImage): ByteArray? = runCatching {
        val resolved: AwtImage = if (image is MultiResolutionImage) {
            val variants = runCatching { image.resolutionVariants }.getOrNull().orEmpty()
            variants.maxByOrNull { variant ->
                val dims = resolveAwtImageDimensions(variant)
                if (dims != null) dims.first.toLong() * dims.second.toLong() else 0L
            } ?: image
        } else {
            image
        }

        val (w, h) = resolveAwtImageDimensions(resolved) ?: return@runCatching null
        if (w > 16_384 || h > 16_384) return@runCatching null

        val buffered = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val graphics = buffered.createGraphics()
        try {
            graphics.drawImage(resolved, 0, 0, w, h, null)
        } finally {
            graphics.dispose()
        }

        val baos = ByteArrayOutputStream()
        if (!ImageIO.write(buffered, "png", baos)) return@runCatching null
        baos.toByteArray().takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun readTransferFlavorBytes(
        transferable: Transferable,
        flavor: DataFlavor,
        maxBytes: Int,
    ): ByteArray? = runCatching {
        when (val data = transferable.getTransferData(flavor)) {
            is ByteArray -> data.takeIf { it.isNotEmpty() && it.size <= maxBytes }
            is InputStream -> data.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) return@runCatching null
                    out.write(buf, 0, n)
                }
                out.toByteArray().takeIf { it.isNotEmpty() }
            }
            else -> null
        }
    }.getOrNull()

    private fun convertToPngWithImageIo(rawBytes: ByteArray): ByteArray? = runCatching {
        val buffered = ImageIO.read(ByteArrayInputStream(rawBytes)) ?: return@runCatching null
        val baos = ByteArrayOutputStream()
        if (!ImageIO.write(buffered, "png", baos)) return@runCatching null
        baos.toByteArray().takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun convertHeicViaSips(file: File): ByteArray? {
        val sips = File("/usr/bin/sips")
        if (!sips.canExecute()) return null
        val tmpOut = runCatching { File.createTempFile("lework-clip-", ".png") }.getOrNull() ?: return null
        return try {
            val proc = ProcessBuilder(
                sips.absolutePath,
                "-s",
                "format",
                "png",
                file.absolutePath,
                "--out",
                tmpOut.absolutePath,
            ).redirectErrorStream(true).start()
            val finished = proc.waitFor(4, TimeUnit.SECONDS)
            if (!finished) {
                runCatching { proc.destroyForcibly() }
                return null
            }
            if (proc.exitValue() != 0 || !tmpOut.isFile || tmpOut.length() <= 0 || tmpOut.length() > MAX_RAW_IMAGE_BYTES) {
                return null
            }
            tmpOut.readBytes().takeIf { it.isNotEmpty() }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { tmpOut.delete() }
        }
    }

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
        if (!file.isFile || file.length() <= 0 || file.length() > MAX_RAW_IMAGE_BYTES) return null
        val rawBytes = file.readBytes()
        val ext = file.extension.lowercase()
        val rawMime = when (ext) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "tiff", "tif" -> "image/tiff"
            "heic" -> "image/heic"
            else -> "image/jpeg"
        }
        encodePickedImageBytes(rawBytes, file.name, rawMime) ?: if (ext == "heic") {
            val pngBytes = convertHeicViaSips(file) ?: return null
            encodePickedImageBytes(pngBytes, "${file.nameWithoutExtension}.png", "image/png")
        } else {
            null
        }
    }.getOrNull()

    internal fun encodePickedImageBytes(
        rawBytes: ByteArray,
        fileName: String,
        rawMime: String,
    ): PickedImagePayload? = runCatching {
        if (rawBytes.isEmpty() || rawBytes.size > MAX_RAW_IMAGE_BYTES) return null

        var effectiveBytes = rawBytes
        var effectiveMime = rawMime.lowercase().trim().let {
            if (it == "image/jpg") "image/jpeg" else it
        }
        val skiaImg = runCatching { SkiaImage.makeFromEncoded(effectiveBytes) }.getOrNull() ?: run {
            val pngFallback = convertToPngWithImageIo(effectiveBytes) ?: return null
            effectiveBytes = pngFallback
            effectiveMime = "image/png"
            SkiaImage.makeFromEncoded(effectiveBytes)
        }

        try {
            val thumbB64 = runCatching {
                val maxThumb = 220
                val scale = minOf(maxThumb.toFloat() / skiaImg.width.toFloat(), maxThumb.toFloat() / skiaImg.height.toFloat(), 1f)
                val tw = (skiaImg.width * scale).toInt().coerceAtLeast(1)
                val th = (skiaImg.height * scale).toInt().coerceAtLeast(1)
                val surface = SkiaSurface.makeRaster(ImageInfo.makeN32Premul(tw, th))
                try {
                    surface.canvas.drawImageRect(
                        skiaImg,
                        Rect.makeWH(skiaImg.width.toFloat(), skiaImg.height.toFloat()),
                        Rect.makeWH(tw.toFloat(), th.toFloat()),
                    )
                    val snapshot = surface.makeImageSnapshot()
                    try {
                        val encoded = snapshot.encodeToData(EncodedImageFormat.JPEG, 72)
                            ?: snapshot.encodeToData(EncodedImageFormat.PNG)
                        if (encoded != null) {
                            try {
                                Base64.encode(encoded.bytes)
                            } finally {
                                encoded.close()
                            }
                        } else {
                            null
                        }
                    } finally {
                        snapshot.close()
                    }
                } finally {
                    surface.close()
                }
            }.getOrNull()

            // Downscale oversized images (or normalize non-web MIME formats) so base64 stays comfortably within protocol 1MB limit
            val isWebMime = effectiveMime in WEB_SAFE_IMAGE_MIMES
            fun renderScaledSnapshot(maxDim: Int): SkiaImage {
                val scale = minOf(maxDim.toFloat() / skiaImg.width.toFloat(), maxDim.toFloat() / skiaImg.height.toFloat(), 1f)
                val w = (skiaImg.width * scale).toInt().coerceAtLeast(1)
                val h = (skiaImg.height * scale).toInt().coerceAtLeast(1)
                val surface = SkiaSurface.makeRaster(ImageInfo.makeN32Premul(w, h))
                return try {
                    surface.canvas.drawImageRect(
                        skiaImg,
                        Rect.makeWH(skiaImg.width.toFloat(), skiaImg.height.toFloat()),
                        Rect.makeWH(w.toFloat(), h.toFloat()),
                    )
                    surface.makeImageSnapshot()
                } finally {
                    surface.close()
                }
            }

            val (finalMime, finalBytes) = if (isWebMime && effectiveBytes.size <= 700_000) {
                effectiveMime to effectiveBytes
            } else if (!isWebMime && effectiveBytes.size <= 700_000) {
                val snapshot = renderScaledSnapshot(1600)
                try {
                    val pngData = snapshot.encodeToData(EncodedImageFormat.PNG)
                    val pngBytes = if (pngData != null) {
                        try {
                            pngData.bytes
                        } finally {
                            pngData.close()
                        }
                    } else {
                        null
                    }
                    if (pngBytes != null && pngBytes.size <= 700_000) {
                        "image/png" to pngBytes
                    } else {
                        val jpgData = snapshot.encodeToData(EncodedImageFormat.JPEG, 82) ?: return null
                        val jpgBytes = try {
                            jpgData.bytes
                        } finally {
                            jpgData.close()
                        }
                        "image/jpeg" to jpgBytes
                    }
                } finally {
                    snapshot.close()
                }
            } else {
                var encodedBytes: ByteArray? = null
                var lastDim = -1
                var cachedSnapshot: SkiaImage? = null
                try {
                    for ((dim, quality) in listOf(1600 to 82, 1600 to 65, 1280 to 58, 1024 to 48)) {
                        val snap = if (dim == lastDim && cachedSnapshot != null) {
                            cachedSnapshot
                        } else {
                            cachedSnapshot?.close()
                            cachedSnapshot = null
                            lastDim = dim
                            renderScaledSnapshot(dim).also { cachedSnapshot = it }
                        }
                        val dataObj = snap.encodeToData(EncodedImageFormat.JPEG, quality) ?: continue
                        val candidate = try {
                            dataObj.bytes
                        } finally {
                            dataObj.close()
                        }
                        encodedBytes = candidate
                        if (candidate.size <= 700_000) break
                    }
                } finally {
                    cachedSnapshot?.close()
                }
                val finalJpg = encodedBytes ?: return null
                "image/jpeg" to finalJpg
            }

            val cleanName = fileName.trim().ifEmpty { "clipboard.png" }.take(120)
            PickedImagePayload(
                fileName = cleanName,
                mimeType = finalMime,
                dataBase64 = Base64.encode(finalBytes),
                thumbnailBase64 = thumbB64,
            )
        } finally {
            skiaImg.close()
        }
    }.getOrNull()

    // --- SSH Config Discovery & One-Click Remote Devbox Pairing ---
    fun listSshConfigHosts(): List<String> = runCatching {
        val sshCfg = File(System.getProperty("user.home"), ".ssh/config")
        if (!sshCfg.isFile) return@runCatching emptyList()
        parseSshConfigHosts(sshCfg.readText())
    }.getOrDefault(emptyList())

    internal fun parseSshConfigHosts(configText: String): List<String> {
        val results = mutableListOf<String>()
        var currentAliases = emptyList<String>()
        var currentUser: String? = null
        var currentHostName: String? = null

        fun isGitForgeHost(raw: String): Boolean {
            val lower = raw.trim().lowercase()
            if (lower.isEmpty()) return false
            if (FORGE_HOST_PATTERNS.any { lower == it || lower.endsWith(".$it") }) return true
            if (FORGE_HOST_PREFIXES.any { lower.startsWith(it) && lower.length > it.length }) return true
            return false
        }

        fun flushBlock() {
            if (currentAliases.isEmpty()) return
            val isGitUser = currentUser?.equals("git", ignoreCase = true) == true
            val isForgeHostName = currentHostName?.let(::isGitForgeHost) == true
            if (!isGitUser && !isForgeHostName) {
                for (alias in currentAliases) {
                    if (isGitForgeHost(alias)) continue
                    if (SSH_TARGET_REGEX.matches(alias) && alias !in results) {
                        results.add(alias)
                    }
                }
            }
            currentAliases = emptyList()
            currentUser = null
            currentHostName = null
        }

        configText.lineSequence().forEach { rawLine ->
            val trimmed = rawLine.substringBefore('#').trim()
            if (trimmed.isEmpty()) return@forEach
            val parts = trimmed.split(Regex("[\\s=]+"), limit = 2)
            if (parts.size < 2) return@forEach
            val key = parts[0].lowercase()
            val value = parts[1].trim()
            when (key) {
                "host" -> {
                    flushBlock()
                    currentAliases = value
                        .split(Regex("\\s+"))
                        .filter { it.isNotEmpty() && !it.contains('*') && !it.contains('?') && !it.startsWith('!') }
                }
                "match" -> flushBlock()
                "user" -> if (currentUser == null) currentUser = value
                "hostname" -> if (currentHostName == null) currentHostName = value
            }
        }
        flushBlock()
        return results.take(8)
    }

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
        } catch (ce: CancellationException) {
            throw ce
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
    private suspend fun ensureDirectOrSshTunneledLink(
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
                delay(120L)
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
