package dev.lework.platform

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import dev.lework.protocol.ProtocolJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer

object AndroidPlatformRuntime {
    @Volatile
    internal var appContext: Context? = null
    internal val bonjourUrls = MutableStateFlow<List<String>>(emptyList())
    internal val foregroundFlow = MutableStateFlow(true)
    internal val incomingUriFlow = MutableStateFlow<String?>(null)
    private var activeDiscoveryListener: NsdManager.DiscoveryListener? = null

    fun attach(context: Context) {
        appContext = context.applicationContext
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
                                    bonjourUrls.value = (found + bonjourUrls.value).distinct()
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

actual fun appVersionLabel(): String = "v0.5.0 (20261119.2355)"

actual fun epochMillis(): Long = System.currentTimeMillis()

actual fun observeAppForeground(): StateFlow<Boolean> = AndroidPlatformRuntime.foregroundFlow.asStateFlow()

actual fun observeBonjourHubUrls(): StateFlow<List<String>> = AndroidPlatformRuntime.bonjourUrls.asStateFlow()

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
    addAll(AndroidPlatformRuntime.bonjourUrls.value)
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
    prefs()?.getString("auth_token", null)?.takeIf { it.isNotBlank() } ?: memToken

actual fun loadSavedAuthUserName(): String? =
    prefs()?.getString("auth_user", null)?.takeIf { it.isNotBlank() } ?: memUser

actual fun saveAuthSession(token: String?, userName: String?) {
    memToken = token?.takeIf { it.isNotBlank() }
    memUser = if (memToken == null) null else (userName ?: "Owner")
    prefs()?.edit()
        ?.putString("auth_token", memToken)
        ?.putString("auth_user", memUser)
        ?.apply()
}

actual fun loadSessionPrefs(): SavedSessionPrefs {
    val p = prefs() ?: return memPrefs
    return SavedSessionPrefs(
        engineId = p.getString("pref_engine", null)?.ifBlank { null } ?: memPrefs.engineId,
        providerId = p.getString("pref_provider", null)?.ifBlank { null },
        modelId = p.getString("pref_model", null)?.ifBlank { null },
        reasoningEffort = p.getString("pref_effort", null)?.ifBlank { null } ?: memPrefs.reasoningEffort,
        mode = p.getString("pref_mode", null)?.ifBlank { null } ?: memPrefs.mode,
        workspacePath = p.getString("pref_workspace", null) ?: memPrefs.workspacePath,
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
    val raw = prefs()?.getString("pinned_fps", null) ?: return memPins
    return raw.split(',').map { it.trim().lowercase() }.filter { it.length == 64 }.toSet()
}

actual fun savePinnedHubFingerprints(fingerprints: Set<String>) {
    memPins = fingerprints
    prefs()?.edit()?.putString("pinned_fps", fingerprints.joinToString(","))?.apply()
}

actual fun isMobileTsnetAvailable(): Boolean = false

actual suspend fun startMobileTsnet(controlUrl: String?): MobileTsnetStatus = MobileTsnetStatus()

actual suspend fun queryMobileTsnetStatus(): MobileTsnetStatus = MobileTsnetStatus()

actual suspend fun loginMobileTsnet(controlUrl: String?, authKey: String?): MobileTsnetStatus =
    MobileTsnetStatus(available = false, backendState = "Unavailable")

actual suspend fun logoutMobileTsnet(): MobileTsnetStatus = MobileTsnetStatus()

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

actual fun canScanQrCode(): Boolean = false

actual fun requestQrCodeScan(onResult: (String?) -> Unit) {
    onResult(null)
}

actual fun observeIncomingPairingUri(): StateFlow<String?> = AndroidPlatformRuntime.incomingUriFlow.asStateFlow()

actual fun consumeIncomingPairingUri() {
    AndroidPlatformRuntime.incomingUriFlow.value = null
}

actual fun loadPairedHosts(): List<PairedHost> {
    val raw = prefs()?.getString("paired_hosts_json", null) ?: return memPairedHosts
    return runCatching {
        ProtocolJson.decodeFromString(ListSerializer(PairedHost.serializer()), raw)
    }.getOrDefault(memPairedHosts)
}

actual fun savePairedHosts(hosts: List<PairedHost>) {
    memPairedHosts = hosts
    val raw = ProtocolJson.encodeToString(ListSerializer(PairedHost.serializer()), hosts)
    prefs()?.edit()?.putString("paired_hosts_json", raw)?.apply()
}

actual fun loadActiveHostId(): String? =
    prefs()?.getString("active_host_id", null)?.takeIf { it.isNotBlank() } ?: memActiveHostId

actual fun saveActiveHostId(hubId: String?) {
    memActiveHostId = hubId?.takeIf { it.isNotBlank() }
    prefs()?.edit()?.putString("active_host_id", memActiveHostId)?.apply()
}

actual fun loadHostAccessToken(hubId: String): String? =
    prefs()?.getString("host_tok_$hubId", null)?.takeIf { it.isNotBlank() } ?: memHostTokens[hubId]

actual fun saveHostAccessToken(hubId: String, token: String?) {
    val clean = token?.takeIf { it.isNotBlank() }
    if (clean == null) memHostTokens.remove(hubId) else memHostTokens[hubId] = clean
    prefs()?.edit()?.putString("host_tok_$hubId", clean)?.apply()
}
