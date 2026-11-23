package dev.lework.platform

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.provider.OpenableColumns
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.lework.protocol.ProtocolJson
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

interface AndroidNativeDelegate {
    fun presentQrScanner(onResult: (String?) -> Unit)
    fun presentImagePicker(onResult: (PickedImagePayload?) -> Unit)
    fun presentMultiImagePicker(
        source: ImagePickSource,
        maxCount: Int,
        onResult: (List<PickedImagePayload>) -> Unit,
    ) {
        presentImagePicker { single -> onResult(listOfNotNull(single)) }
    }
}

object AndroidPlatformRuntime {
    @Volatile
    internal var appContext: Context? = null
    @Volatile
    internal var nativeDelegate: AndroidNativeDelegate? = null

    private val _combinedUrls = MutableStateFlow<List<String>>(emptyList())
    internal val combinedUrls: StateFlow<List<String>> = _combinedUrls.asStateFlow()
    private var nsdUrls: List<String> = emptyList()
    @Volatile
    internal var tsnetProxyUrls: List<String> = emptyList()
    @Volatile
    internal var tsnetProxyPorts: Set<Int> = emptySet()
    @Volatile
    internal var tsnetForwarderSecret: String = ""

    internal val foregroundFlow = MutableStateFlow(true)
    internal val networkEpochFlow = MutableStateFlow(0L)
    internal val incomingUriFlow = MutableStateFlow<String?>(null)
    private var activeDiscoveryListener: NsdManager.DiscoveryListener? = null
    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile
    private var networkCallbackRegistered = false
    @Volatile
    private var lastNetworkSignature: String? = null

    fun attach(context: Context) {
        val appCtx = context.applicationContext
        appContext = appCtx
        registerNetworkMonitorIfNeeded(appCtx)
    }

    private fun registerNetworkMonitorIfNeeded(ctx: Context) {
        if (networkCallbackRegistered) return
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        networkCallbackRegistered = true
        runCatching {
            val initialNet = cm.activeNetwork
            val initialIf = if (initialNet != null) cm.getLinkProperties(initialNet)?.interfaceName.orEmpty() else ""
            lastNetworkSignature = if (initialNet != null) "${initialNet.networkHandle}:$initialIf" else "none"

            cm.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    private fun onRouteUpdated(sig: String, clearLanNsd: Boolean = false) {
                        val prev = lastNetworkSignature
                        if (prev == sig) return
                        lastNetworkSignature = sig
                        if (clearLanNsd) {
                            updateNsdUrls(emptyList())
                        } else {
                            startNsdDiscovery()
                        }
                        bgScope.launch {
                            AndroidTsnetDaemon.notifyNetworkChanged()
                        }
                        if (prev != null) {
                            networkEpochFlow.value = networkEpochFlow.value + 1L
                        }
                    }

                    override fun onAvailable(network: Network) {
                        val ifName = runCatching { cm.getLinkProperties(network)?.interfaceName.orEmpty() }.getOrDefault("")
                        onRouteUpdated("${network.networkHandle}:$ifName")
                    }

                    override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                        val ifName = linkProperties.interfaceName.orEmpty()
                        onRouteUpdated("${network.networkHandle}:$ifName")
                    }

                    override fun onLost(network: Network) {
                        val active = runCatching { cm.activeNetwork }.getOrNull()
                        if (active == null || active.networkHandle == network.networkHandle) {
                            onRouteUpdated("none", clearLanNsd = true)
                        }
                    }
                },
            )
        }
    }

    fun registerDelegate(delegate: AndroidNativeDelegate?) {
        nativeDelegate = delegate
    }

    internal fun updateNsdUrls(urls: List<String>) {
        nsdUrls = urls
        _combinedUrls.value = (nsdUrls + tsnetProxyUrls).distinct()
    }

    internal fun updateTsnetProxies(urls: List<String>) {
        tsnetProxyUrls = urls
        tsnetProxyPorts = urls.mapNotNull { u ->
            u.substringAfter("127.0.0.1:", "").substringBefore('/').toIntOrNull()
        }.toSet()
        _combinedUrls.value = (nsdUrls + tsnetProxyUrls).distinct()
    }

    fun onForegroundChanged(isForeground: Boolean) {
        foregroundFlow.value = isForeground
    }

    fun onIncomingUri(rawUri: String?) {
        val trimmed = rawUri?.trim().orEmpty()
        if (trimmed.startsWith("lework://", ignoreCase = true)) {
            incomingUriFlow.value = trimmed
        }
    }

    fun startNsdDiscovery() {
        val ctx = appContext ?: return
        val nsd = ctx.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        activeDiscoveryListener?.let { prev ->
            runCatching { nsd.stopServiceDiscovery(prev) }
            activeDiscoveryListener = null
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                runCatching {
                    @Suppress("DEPRECATION")
                    nsd.resolveService(
                        serviceInfo,
                        object : NsdManager.ResolveListener {
                            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}

                            override fun onServiceResolved(info: NsdServiceInfo) {
                                val port = info.port
                                if (port <= 0) return
                                val found = mutableListOf<String>()
                                @Suppress("DEPRECATION")
                                val hostIp = info.host?.hostAddress?.trim()
                                if (!hostIp.isNullOrEmpty() && !hostIp.startsWith("127.")) {
                                    found.add("https://$hostIp:$port")
                                }
                                val ipsAttr = info.attributes["ips"]?.decodeToString().orEmpty()
                                for (ip in ipsAttr.split(',')) {
                                    val clean = ip.trim()
                                    if (clean.isNotEmpty()) {
                                        found.add("https://$clean:$port")
                                    }
                                }
                                if (found.isNotEmpty()) {
                                    updateNsdUrls((found + nsdUrls).distinct())
                                }
                            }
                        },
                    )
                }
            }
        }
        activeDiscoveryListener = listener
        runCatching {
            nsd.discoverServices("_lework._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
        }
    }
}

/**
 * Hardware-backed Android Keystore AES-256-GCM vault for access tokens & pinned TLS fingerprints.
 * Automatically migrates pre-v0.6.0 plaintext SharedPreferences entries on first read.
 */
private object AndroidKeyStoreVault {
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "dev.lework.vault.aes256"
    private const val PREFIX_V1 = "enc:v1:"
    private const val PREFIX_V2 = "enc:v2:"

    private fun deleteSecretKey() {
        runCatching {
            val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            if (ks.containsAlias(KEY_ALIAS)) ks.deleteEntry(KEY_ALIAS)
        }
    }

    private fun getOrCreateSecretKey(): SecretKey? = runCatching {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return@runCatching it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        gen.init(spec)
        gen.generateKey()
    }.getOrNull()

    fun get(prefKey: String): String? {
        val p = prefs() ?: return null
        val raw = p.getString(prefKey, null)?.takeIf { it.isNotBlank() } ?: return null
        if (!raw.startsWith(PREFIX_V2) && !raw.startsWith(PREFIX_V1)) {
            // Migrate legacy plaintext credential; fail closed (delete plaintext) if Keystore is unavailable
            set(prefKey, raw)
            return if (p.getString(prefKey, null)?.startsWith(PREFIX_V2) == true) raw else null
        }
        val isV2 = raw.startsWith(PREFIX_V2)
        val payload = if (isV2) raw.removePrefix(PREFIX_V2) else raw.removePrefix(PREFIX_V1)
        val secretKey = getOrCreateSecretKey() ?: return null
        return runCatching {
            val parts = payload.split(':', limit = 2)
            if (parts.size != 2) return null
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val cipherBytes = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
            if (isV2) {
                cipher.updateAAD(prefKey.encodeToByteArray())
            }
            cipher.doFinal(cipherBytes).decodeToString()
        }.getOrNull()
    }

    fun set(prefKey: String, plaintext: String?) {
        val p = prefs() ?: return
        if (plaintext.isNullOrBlank()) {
            p.edit().remove(prefKey).apply()
            return
        }
        fun tryEncryptAndSave(key: SecretKey): Boolean = runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(prefKey.encodeToByteArray())
            val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
            val encrypted = Base64.encodeToString(cipher.doFinal(plaintext.encodeToByteArray()), Base64.NO_WRAP)
            p.edit().putString(prefKey, "$PREFIX_V2$iv:$encrypted").apply()
            true
        }.getOrElse { err ->
            if (err is KeyPermanentlyInvalidatedException) {
                deleteSecretKey()
            }
            false
        }

        val key = getOrCreateSecretKey()
        if (key != null && tryEncryptAndSave(key)) return
        val rotatedKey = getOrCreateSecretKey()
        if (rotatedKey != null && tryEncryptAndSave(rotatedKey)) return
        // Fail closed: never persist raw bearer tokens or pins in plaintext SharedPreferences
        p.edit().remove(prefKey).apply()
    }
}

/**
 * Manages the embedded Go `libtsnetbridge.so` userspace Tailscale mesh child process on Android.
 */
private object AndroidTsnetDaemon {
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

    @Serializable
    private data class NetChangePayload(
        val dns: String = "",
        val interfaces: String = "",
    )

    private fun resolveBinary(): File? {
        val ctx = AndroidPlatformRuntime.appContext ?: return null
        val f = File(ctx.applicationInfo.nativeLibraryDir, "libtsnetbridge.so")
        return if (f.exists() && f.canExecute()) f else null
    }

    fun isAvailable(): Boolean = resolveBinary() != null

    private fun detectActiveDnsServers(ctx: Context): String {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val servers = runCatching {
            val net = cm?.activeNetwork
            val props = if (net != null) cm.getLinkProperties(net) else null
            props?.dnsServers?.mapNotNull { it.hostAddress?.takeIf { a -> !a.contains('%') } }.orEmpty()
        }.getOrDefault(emptyList())
        return (servers + listOf("223.5.5.5", "1.1.1.1", "8.8.8.8")).distinct().take(5).joinToString(",")
    }

    private fun detectActiveInterfaces(ctx: Context): String {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        return runCatching {
            val net = cm?.activeNetwork ?: return@runCatching ""
            val props = cm.getLinkProperties(net) ?: return@runCatching ""
            val ifName = props.interfaceName?.trim()?.takeIf { it.isNotEmpty() } ?: "wlan0"
            props.linkAddresses
                .mapNotNull { la ->
                    val host = la.address?.hostAddress?.substringBefore('%')?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    "$ifName=$host/${la.prefixLength}"
                }
                .take(6)
                .joinToString(",")
        }.getOrDefault("")
    }

    private fun exportSystemCaBundle(ctx: Context): File? = runCatching {
        val outFile = File(ctx.filesDir, "tsnet-cacerts.pem")
        if (outFile.isFile && outFile.length() > 16_384L) return@runCatching outFile
        val ks = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        val sb = StringBuilder(131_072)
        val aliases = ks.aliases()
        var count = 0
        while (aliases.hasMoreElements()) {
            val alias = aliases.nextElement() ?: continue
            val cert = ks.getCertificate(alias) as? X509Certificate ?: continue
            val b64 = Base64.encodeToString(cert.encoded, Base64.DEFAULT).trim()
            if (b64.isNotEmpty()) {
                sb.append("-----BEGIN CERTIFICATE-----\n")
                sb.append(b64).append('\n')
                sb.append("-----END CERTIFICATE-----\n")
                count++
            }
        }
        if (count == 0) return@runCatching null
        outFile.writeText(sb.toString())
        outFile
    }.getOrNull()

    private fun sanitizedHostname(): String {
        val raw = Build.MODEL?.lowercase()?.replace(Regex("[^a-z0-9-]+"), "-")?.trim('-') ?: "android"
        return "lework-android-${raw.ifEmpty { "phone" }}".take(48)
    }

    suspend fun ensureStarted(controlUrl: String?): Boolean = mutex.withLock {
        val alive = proc?.isAlive == true && controlPort > 0
        if (alive) return@withLock true
        val ctx = AndroidPlatformRuntime.appContext ?: return@withLock false
        val bin = resolveBinary() ?: return@withLock false

        withContext(Dispatchers.IO) {
            runCatching { proc?.destroy() }
            proc = null
            controlPort = 0

            val stateDir = File(ctx.filesDir, "tsnet").apply { mkdirs() }
            val secBytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
            val newSecret = secBytes.joinToString("") { "%02x".format(it) }
            val fwdBytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
            val newForwarderSecret = fwdBytes.joinToString("") { "%02x".format(it) }
            val ifSpec = detectActiveInterfaces(ctx)
            val cmd = mutableListOf(
                bin.absolutePath,
                "--client-only",
                "--state-dir",
                stateDir.absolutePath,
                "--hostname",
                sanitizedHostname(),
                "--hub-port",
                "64450",
                "--dns",
                detectActiveDnsServers(ctx),
            )
            if (ifSpec.isNotEmpty()) {
                cmd.add("--interfaces")
                cmd.add(ifSpec)
            }
            if (!controlUrl.isNullOrBlank()) {
                cmd.add("--control-url")
                cmd.add(controlUrl.trim())
            }

            val caBundle = exportSystemCaBundle(ctx)
            lastStderr = ""
            val pb = ProcessBuilder(cmd).apply {
                environment()["LEWORK_TSNET_SECRET"] = newSecret
                environment()["LEWORK_FORWARDER_SECRET"] = newForwarderSecret
                environment()["HOME"] = ctx.filesDir.absolutePath
                environment()["SSL_CERT_DIR"] = "/apex/com.android.conscrypt/cacerts:/system/etc/security/cacerts"
                if (caBundle != null) {
                    environment()["SSL_CERT_FILE"] = caBundle.absolutePath
                }
            }

            val started = runCatching { pb.start() }.getOrNull() ?: return@withContext false
            // Capture stderr lines asynchronously for diagnostics
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
                    val obj = runCatching {
                        ProtocolJson.parseToJsonElement(trimmed) as? JsonObject
                    }.getOrNull()
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
            // Keep draining stdout in background
            Thread {
                runCatching { reader.forEachLine { /* drain */ } }
            }.apply { isDaemon = true }.start()

            proc = started
            controlPort = foundPort
            secret = newSecret
            AndroidPlatformRuntime.tsnetForwarderSecret = newForwarderSecret
            true
        }
    }

    fun lastErrorDetail(): String? = lastStderr.takeIf { it.isNotBlank() }

    suspend fun stopAndPurgeState() = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching { proc?.destroy() }
            proc = null
            controlPort = 0
            secret = ""
            AndroidPlatformRuntime.tsnetForwarderSecret = ""
            AndroidPlatformRuntime.updateTsnetProxies(emptyList())
            AndroidPlatformRuntime.appContext?.let { ctx ->
                runCatching { File(ctx.filesDir, "tsnet").deleteRecursively() }
            }
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
                val stream = if (conn.responseCode in 200..299) {
                    conn.inputStream
                } else {
                    conn.errorStream ?: conn.inputStream
                }
                val raw = stream.bufferedReader().use { it.readText() }
                val parsed = ProtocolJson.decodeFromString(MobileTsnetStatus.serializer(), raw)
                val withAvail = parsed.copy(available = true)
                val proxies = withAvail.peers.mapNotNull { it.localProxyUrl?.takeIf { u -> u.isNotBlank() } }
                AndroidPlatformRuntime.updateTsnetProxies(proxies)
                withAvail
            }.getOrElse { err ->
                MobileTsnetStatus(
                    available = avail,
                    backendState = "Error",
                    error = err.message ?: "Android tsnet bridge error",
                )
            }
        }
    }

    suspend fun configureAndCall(controlUrl: String?, authKey: String?, nextPath: String): MobileTsnetStatus {
        if (!ensureStarted(controlUrl)) {
            return MobileTsnetStatus(
                available = isAvailable(),
                backendState = if (isAvailable()) "Error" else "Unavailable",
                error = if (isAvailable()) (lastErrorDetail() ?: "Failed to start embedded tsnet daemon") else null,
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

    suspend fun notifyNetworkChanged() {
        val ctx = AndroidPlatformRuntime.appContext ?: return
        if (proc?.isAlive != true || controlPort <= 0 || secret.isEmpty()) return
        val body = ProtocolJson.encodeToString(
            NetChangePayload.serializer(),
            NetChangePayload(
                dns = detectActiveDnsServers(ctx),
                interfaces = detectActiveInterfaces(ctx),
            ),
        )
        runCatching {
            callControl("/v1/netchange", "POST", body)
        }
    }
}

private var memToken: String? = null
private var memUser: String? = null
private var memLastGoodUrl: String? = null
private var memRelayUrls: List<String> = emptyList()
private var memPrefs = SavedSessionPrefs()
private var memPins: Set<String> = emptySet()
private var memTsnetEnabled: Boolean = false
private var memTsnetControlUrl: String? = null
private var memPairedHosts: List<PairedHost> = emptyList()
private var memActiveHostId: String? = null
private val memHostTokens = mutableMapOf<String, String>()

private fun prefs() =
    AndroidPlatformRuntime.appContext?.getSharedPreferences("lework_prefs", Context.MODE_PRIVATE)

actual fun platformName(): String = "Android ${Build.VERSION.RELEASE}"

actual fun appVersionLabel(): String {
    val ctx = AndroidPlatformRuntime.appContext
    if (ctx != null) {
        runCatching {
            val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            val vName = info.versionName?.trim().orEmpty().ifEmpty { "0.7.0" }
            val vCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            return "v$vName ($vCode)"
        }
    }
    return "v0.7.0"
}

actual fun epochMillis(): Long = System.currentTimeMillis()

actual fun localDateTimeParts(epochMs: Long): LocalDateTimeParts {
    val cal = java.util.Calendar.getInstance()
    cal.timeInMillis = epochMs
    return LocalDateTimeParts(
        year = cal.get(java.util.Calendar.YEAR),
        month = cal.get(java.util.Calendar.MONTH) + 1,
        day = cal.get(java.util.Calendar.DAY_OF_MONTH),
        hour = cal.get(java.util.Calendar.HOUR_OF_DAY),
        minute = cal.get(java.util.Calendar.MINUTE),
        second = cal.get(java.util.Calendar.SECOND),
    )
}

actual fun observeAppForeground(): StateFlow<Boolean> = AndroidPlatformRuntime.foregroundFlow.asStateFlow()

actual fun observeNetworkEpoch(): StateFlow<Long> = AndroidPlatformRuntime.networkEpochFlow.asStateFlow()

actual fun observeBonjourHubUrls(): StateFlow<List<String>> = AndroidPlatformRuntime.combinedUrls

actual fun startBonjourDiscovery() {
    AndroidPlatformRuntime.startNsdDiscovery()
}

actual fun loadLastGoodHubUrl(): String? =
    prefs()?.getString("last_good_url", null)?.takeIf { it.isNotBlank() } ?: memLastGoodUrl

actual fun saveLastGoodHubUrl(url: String?) {
    memLastGoodUrl = url?.takeIf { it.isNotBlank() }
    prefs()?.edit()?.putString("last_good_url", memLastGoodUrl)?.apply()
}

actual fun loadSavedRelayUrls(): List<String> {
    val raw = prefs()?.getString("relay_urls", null)
    if (raw != null) {
        return raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }
    return memRelayUrls
}

actual fun saveRelayUrls(urls: List<String>) {
    val cleaned = urls.map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() }.distinct()
    memRelayUrls = cleaned
    prefs()?.edit()?.putString("relay_urls", cleaned.joinToString(","))?.apply()
}

actual fun candidateHubHttpUrls(): List<String> = buildList {
    addAll(AndroidPlatformRuntime.combinedUrls.value)
    addAll(AndroidPlatformRuntime.tsnetProxyUrls)
    val activeId = loadActiveHostId()
    if (activeId != null) {
        loadPairedHosts().firstOrNull { it.hubId == activeId }?.let { addAll(it.endpoints) }
    }
    loadLastGoodHubUrl()?.let { add(it) }
    addAll(loadSavedRelayUrls())
    for (h in loadPairedHosts()) addAll(h.endpoints)
    add("https://10.0.2.2:64450")
}.distinct()

actual fun loadSavedAccessToken(): String? =
    AndroidKeyStoreVault.get("auth_token")?.takeIf { it.isNotBlank() } ?: memToken

actual fun loadSavedAuthUserName(): String? =
    prefs()?.getString("auth_user", null)?.takeIf { it.isNotBlank() } ?: memUser

actual fun saveAuthSession(token: String?, userName: String?) {
    memToken = token?.takeIf { it.isNotBlank() }
    memUser = if (memToken == null) null else (userName ?: "Owner")
    AndroidKeyStoreVault.set("auth_token", memToken)
    prefs()?.edit()?.putString("auth_user", memUser)?.apply()
}

actual fun loadSessionPrefs(): SavedSessionPrefs {
    val p = prefs() ?: return memPrefs
    val favs = p.getString("pref_fav_models", null)
        ?.split('|')
        ?.map { it.trim() }
        ?.filter { it.contains(':') }
        ?: memPrefs.favoriteModelKeys
    val pinned = p.getString("pref_pinned_sessions", null)
        ?.split('|')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?: memPrefs.pinnedSessionIds
    return SavedSessionPrefs(
        engineId = p.getString("pref_engine", null)?.ifBlank { null } ?: memPrefs.engineId,
        providerId = p.getString("pref_provider", null)?.ifBlank { null },
        modelId = p.getString("pref_model", null)?.ifBlank { null },
        reasoningEffort = p.getString("pref_effort", null)?.ifBlank { null } ?: memPrefs.reasoningEffort,
        mode = p.getString("pref_mode", null)?.ifBlank { null } ?: memPrefs.mode,
        workspacePath = p.getString("pref_workspace", null) ?: memPrefs.workspacePath,
        favoriteModelKeys = favs,
        pinnedSessionIds = pinned,
    )
}

actual fun saveSessionPrefs(prefs: SavedSessionPrefs) {
    memPrefs = prefs
    prefs()?.edit()
        ?.putString("pref_engine", prefs.engineId)
        ?.putString("pref_provider", prefs.providerId)
        ?.putString("pref_model", prefs.modelId)
        ?.putString("pref_effort", prefs.reasoningEffort)
        ?.putString("pref_mode", prefs.mode)
        ?.putString("pref_workspace", prefs.workspacePath)
        ?.putString("pref_fav_models", prefs.favoriteModelKeys.joinToString("|"))
        ?.putString("pref_pinned_sessions", prefs.pinnedSessionIds.joinToString("|"))
        ?.apply()
}

actual fun openExternalUrl(url: String) {
    val ctx = AndroidPlatformRuntime.appContext ?: return
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
    }
}

actual fun copyTextToClipboard(text: String) {
    val ctx = AndroidPlatformRuntime.appContext ?: return
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText("Lework", text))
}

actual fun loadPinnedHubFingerprints(): Set<String> {
    val raw = AndroidKeyStoreVault.get("pinned_fps") ?: return memPins
    return raw.split(',').map { it.trim().lowercase() }.filter { it.length == 64 }.toSet()
}

actual fun savePinnedHubFingerprints(fingerprints: Set<String>) {
    memPins = fingerprints
    AndroidKeyStoreVault.set("pinned_fps", fingerprints.joinToString(","))
}

actual fun isMobileTsnetAvailable(): Boolean = AndroidTsnetDaemon.isAvailable()

actual suspend fun startMobileTsnet(controlUrl: String?): MobileTsnetStatus {
    val ctrl = controlUrl ?: loadSavedTsnetControlUrl()
    if (!AndroidTsnetDaemon.ensureStarted(ctrl)) {
        return MobileTsnetStatus(available = AndroidTsnetDaemon.isAvailable(), backendState = "Unavailable")
    }
    return AndroidTsnetDaemon.callControl("/v1/status")
}

actual suspend fun queryMobileTsnetStatus(): MobileTsnetStatus {
    if (!AndroidTsnetDaemon.isAvailable()) return MobileTsnetStatus()
    return AndroidTsnetDaemon.callControl("/v1/status")
}

actual suspend fun loginMobileTsnet(controlUrl: String?, authKey: String?): MobileTsnetStatus =
    AndroidTsnetDaemon.configureAndCall(controlUrl ?: loadSavedTsnetControlUrl(), authKey, "/v1/login")

actual suspend fun logoutMobileTsnet(): MobileTsnetStatus {
    runCatching { AndroidTsnetDaemon.callControl("/v1/logout", "POST", "{}") }
    AndroidTsnetDaemon.stopAndPurgeState()
    return MobileTsnetStatus(available = AndroidTsnetDaemon.isAvailable(), backendState = "NeedsLogin")
}

actual fun loadSavedTsnetEnabled(): Boolean = prefs()?.getBoolean("tsnet_enabled", memTsnetEnabled) ?: memTsnetEnabled

actual fun saveTsnetEnabled(enabled: Boolean, controlUrl: String?) {
    memTsnetEnabled = enabled
    if (controlUrl != null) memTsnetControlUrl = controlUrl.ifBlank { null }
    prefs()?.edit()
        ?.putBoolean("tsnet_enabled", enabled)
        ?.apply {
            if (controlUrl != null) putString("tsnet_control_url", controlUrl.ifBlank { null })
        }
        ?.apply()
}

actual fun loadSavedTsnetControlUrl(): String? =
    prefs()?.getString("tsnet_control_url", null)?.takeIf { it.isNotBlank() } ?: memTsnetControlUrl

actual fun canScanQrCode(): Boolean = AndroidPlatformRuntime.nativeDelegate != null

actual fun requestQrCodeScan(onResult: (String?) -> Unit) {
    val d = AndroidPlatformRuntime.nativeDelegate
    if (d == null) onResult(null) else d.presentQrScanner(onResult)
}

actual fun observeIncomingPairingUri(): StateFlow<String?> = AndroidPlatformRuntime.incomingUriFlow.asStateFlow()

actual fun consumeIncomingPairingUri() {
    AndroidPlatformRuntime.incomingUriFlow.value = null
}

actual fun loadPairedHosts(): List<PairedHost> {
    val raw = prefs()?.getString("paired_hosts_json", null) ?: return memPairedHosts
    return runCatching {
        ProtocolJson.decodeFromString(ListSerializer(PairedHost.serializer()), raw)
    }.getOrDefault(emptyList())
}

actual fun savePairedHosts(hosts: List<PairedHost>) {
    memPairedHosts = hosts
    val p = prefs() ?: return
    if (hosts.isEmpty()) {
        p.edit().remove("paired_hosts_json").apply()
    } else {
        val raw = ProtocolJson.encodeToString(ListSerializer(PairedHost.serializer()), hosts)
        p.edit().putString("paired_hosts_json", raw).apply()
    }
}

actual fun loadActiveHostId(): String? =
    prefs()?.getString("active_host_id", null)?.takeIf { it.isNotBlank() } ?: memActiveHostId

actual fun saveActiveHostId(hubId: String?) {
    memActiveHostId = hubId?.takeIf { it.isNotBlank() }
    prefs()?.edit()?.putString("active_host_id", memActiveHostId)?.apply()
}

actual fun loadHostAccessToken(hubId: String): String? =
    AndroidKeyStoreVault.get("host_token_$hubId")?.takeIf { it.isNotBlank() } ?: memHostTokens[hubId]

actual fun saveHostAccessToken(hubId: String, token: String?) {
    val clean = token?.takeIf { it.isNotBlank() }
    if (clean == null) memHostTokens.remove(hubId) else memHostTokens[hubId] = clean
    AndroidKeyStoreVault.set("host_token_$hubId", clean)
}

actual fun canPickImages(): Boolean = AndroidPlatformRuntime.nativeDelegate != null

actual fun canCaptureCameraImage(): Boolean = AndroidPlatformRuntime.nativeDelegate != null

actual fun requestImagePick(onResult: (PickedImagePayload?) -> Unit) {
    val d = AndroidPlatformRuntime.nativeDelegate
    if (d == null) onResult(null) else d.presentImagePicker(onResult)
}

actual fun requestMultiImagePick(
    source: ImagePickSource,
    maxCount: Int,
    onResult: (List<PickedImagePayload>) -> Unit,
) {
    val d = AndroidPlatformRuntime.nativeDelegate
    if (d == null) {
        onResult(emptyList())
    } else {
        d.presentMultiImagePicker(source, maxCount.coerceIn(1, 4), onResult)
    }
}

actual fun decodeImageBytes(bytes: ByteArray): ImageBitmap? =
    runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()

actual fun signingExpiryRemainingHours(): Int? = null

actual fun dismissNativeKeyboard() = Unit

actual fun isDesktopPlatform(): Boolean = false

actual fun readTextFromClipboard(): String? = runCatching {
    val ctx = AndroidPlatformRuntime.appContext ?: return@runCatching null
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return@runCatching null
    val item = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
    item?.coerceToText(ctx)?.toString()?.takeIf { it.isNotBlank() }
}.getOrNull()

actual fun readImagesFromClipboard(maxCount: Int): List<PickedImagePayload> = runCatching {
    val ctx = AndroidPlatformRuntime.appContext ?: return@runCatching emptyList()
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return@runCatching emptyList()
    val clip = cm.primaryClip ?: return@runCatching emptyList()
    val limit = maxCount.coerceIn(1, 4)
    val resolver = ctx.contentResolver
    val out = mutableListOf<PickedImagePayload>()
    for (i in 0 until clip.itemCount) {
        if (out.size >= limit) break
        val uri = clip.getItemAt(i)?.uri ?: continue
        val mime = runCatching { resolver.getType(uri) }.getOrNull().orEmpty()
        if (!mime.startsWith("image/", ignoreCase = true)) continue
        decodeAndroidClipboardImage(ctx, uri, out.size + 1)?.let { out.add(it) }
    }
    out
}.getOrDefault(emptyList())

private fun decodeAndroidClipboardImage(
    ctx: Context,
    uri: Uri,
    index: Int,
): PickedImagePayload? = runCatching {
    val resolver = ctx.contentResolver
    val displayName = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: "clipboard_$index.jpg"

    val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, boundsOpts) }
    if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) return@runCatching null

    val targetMaxDim = 1280
    var sampleSize = 1
    while (boundsOpts.outWidth / (sampleSize * 2) >= targetMaxDim ||
        boundsOpts.outHeight / (sampleSize * 2) >= targetMaxDim
    ) {
        sampleSize *= 2
    }
    val decodeOpts = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val decoded = resolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, decodeOpts)
    } ?: return@runCatching null

    try {
        fun encodeWithinLimit(src: Bitmap, maxDim: Int, quality: Int): String {
            val longest = maxOf(src.width, src.height)
            val working = if (longest > maxDim && longest > 0) {
                val ratio = maxDim.toFloat() / longest.toFloat()
                val w = (src.width * ratio).toInt().coerceAtLeast(1)
                val h = (src.height * ratio).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(src, w, h, true)
            } else {
                src
            }
            val baos = ByteArrayOutputStream()
            working.compress(Bitmap.CompressFormat.JPEG, quality, baos)
            if (working !== src) working.recycle()
            return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        }

        var b64 = ""
        for ((dim, q) in listOf(1280 to 78, 1280 to 60, 1024 to 52, 800 to 45)) {
            b64 = encodeWithinLimit(decoded, dim, q)
            if (b64.length <= 900_000) break
        }
        val thumbB64 = encodeWithinLimit(decoded, 520, 68).takeIf { it.length <= 240_000 }
        if (b64.isEmpty() || b64.length > 990_000) return@runCatching null

        val finalName = if (displayName.contains('.')) displayName else "$displayName.jpg"
        PickedImagePayload(
            fileName = finalName.take(120),
            mimeType = "image/jpeg",
            dataBase64 = b64,
            thumbnailBase64 = thumbB64,
        )
    } finally {
        decoded.recycle()
    }
}.getOrNull()

actual fun canConnectViaSsh(): Boolean = false

actual fun listSshConfigHosts(): List<String> = emptyList()

actual suspend fun requestSshHostPairingLink(sshTarget: String): Result<String> =
    Result.failure(UnsupportedOperationException("仅桌面端支持通过本机 SSH 直连远程开发机"))
