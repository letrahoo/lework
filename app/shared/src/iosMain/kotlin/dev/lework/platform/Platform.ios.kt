@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.lework.platform

import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.get
import kotlinx.cinterop.pointed
import kotlinx.cinterop.reinterpret
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSNetService
import platform.Foundation.NSNetServiceBrowser
import platform.Foundation.NSNetServiceBrowserDelegateProtocol
import platform.Foundation.NSNetServiceDelegateProtocol
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDefaults
import platform.Foundation.create
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIDevice
import platform.UIKit.UIPasteboard
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.posix.AF_INET
import platform.posix.sockaddr

private const val KEY_FEISHU_TOKEN = "qprc.feishu.token"
private const val KEY_FEISHU_USER = "qprc.feishu.user"
private const val KEY_LAST_GOOD_URL = "qprc.last.good.url"
private const val KEY_RELAY_URLS = "qprc.relay.urls"
private const val KEY_PREF_ENGINE = "qprc.pref.engine"
private const val KEY_PREF_PROVIDER = "qprc.pref.provider"
private const val KEY_PREF_MODEL = "qprc.pref.model"
private const val KEY_PREF_EFFORT = "qprc.pref.effort"
private const val KEY_PREF_MODE = "qprc.pref.mode"
private const val KEY_PREF_WORKSPACE = "qprc.pref.workspace"

private object IosBonjourScanner {
    private val _urls = MutableStateFlow<List<String>>(emptyList())
    val urls: StateFlow<List<String>> = _urls.asStateFlow()

    private var browser: NSNetServiceBrowser? = null
    private val activeServices = mutableListOf<NSNetService>()

    private val serviceDelegate = object : NSObject(), NSNetServiceDelegateProtocol {
        override fun netServiceDidResolveAddress(sender: NSNetService) {
            val port = sender.port.toInt()
            if (port <= 0) return
            val discovered = mutableListOf<String>()

            // 1. Resolved IPv4 socket addresses (fastest direct LAN connection)
            val addrList = sender.addresses
            if (addrList != null) {
                for (item in addrList) {
                    val data = item as? NSData ?: continue
                    if (data.length.toInt() < 8) continue
                    val ptr = data.bytes ?: continue
                    val sa = ptr.reinterpret<sockaddr>().pointed
                    if (sa.sa_family.toInt() == AF_INET) {
                        val raw = ptr.reinterpret<UByteVar>()
                        val b0 = raw[4].toInt()
                        val b1 = raw[5].toInt()
                        val b2 = raw[6].toInt()
                        val b3 = raw[7].toInt()
                        if (b0 != 127 && b0 != 0) {
                            discovered.add("http://$b0.$b1.$b2.$b3:$port")
                        }
                    }
                }
            }

            // 2. TXT `ips=` fallback advertised by Mac Hub
            val txtData = sender.TXTRecordData()
            if (txtData != null) {
                val dict = NSNetService.dictionaryFromTXTRecordData(txtData)
                val ipsData = dict["ips"] as? NSData
                if (ipsData != null && ipsData.length.toInt() > 0) {
                    val csv = NSString.create(data = ipsData, encoding = NSUTF8StringEncoding)?.toString().orEmpty()
                    for (ip in csv.split(',')) {
                        val clean = ip.trim()
                        if (clean.isNotEmpty()) {
                            discovered.add("http://$clean:$port")
                        }
                    }
                }
            }

            // 3. Bonjour `.local` hostname fallback
            val host = sender.hostName?.trim()?.trimEnd('.')
            if (!host.isNullOrEmpty()) {
                discovered.add("http://$host:$port")
            }

            if (discovered.isNotEmpty()) {
                val merged = (discovered + _urls.value).distinct()
                _urls.value = merged
            }
        }
    }

    private val browserDelegate = object : NSObject(), NSNetServiceBrowserDelegateProtocol {
        override fun netServiceBrowser(
            browser: NSNetServiceBrowser,
            didFindService: NSNetService,
            moreComing: Boolean,
        ) {
            if (!activeServices.contains(didFindService)) {
                activeServices.add(didFindService)
            }
            didFindService.includesPeerToPeer = true
            didFindService.delegate = serviceDelegate
            didFindService.resolveWithTimeout(5.0)
        }
    }

    fun startOrRefresh() {
        dispatch_async(dispatch_get_main_queue()) {
            browser?.stop()
            activeServices.forEach { it.stop() }
            activeServices.clear()

            val nextBrowser = NSNetServiceBrowser()
            nextBrowser.includesPeerToPeer = true
            nextBrowser.delegate = browserDelegate
            browser = nextBrowser
            nextBrowser.searchForServicesOfType("_qprc._tcp.", inDomain = "local.")
        }
    }
}

private object IosAppLifecycle {
    private val _isForeground = MutableStateFlow(true)
    val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()
    private var registered = false
    private var activeObserver: Any? = null
    private var bgObserver: Any? = null

    fun ensureRegistered() {
        if (registered) return
        registered = true
        dispatch_async(dispatch_get_main_queue()) {
            val center = NSNotificationCenter.defaultCenter
            activeObserver = center.addObserverForName(
                name = UIApplicationDidBecomeActiveNotification,
                `object` = null,
                queue = NSOperationQueue.mainQueue,
            ) { _ ->
                _isForeground.value = true
            }
            bgObserver = center.addObserverForName(
                name = UIApplicationDidEnterBackgroundNotification,
                `object` = null,
                queue = NSOperationQueue.mainQueue,
            ) { _ ->
                _isForeground.value = false
            }
        }
    }
}

actual fun platformName(): String =
    "${UIDevice.currentDevice.systemName()} ${UIDevice.currentDevice.systemVersion}"

actual fun appVersionLabel(): String {
    val info = NSBundle.mainBundle.infoDictionary
    val shortVer = info?.get("CFBundleShortVersionString") as? String ?: "0.3.0"
    val buildVer = info?.get("CFBundleVersion") as? String ?: "1"
    return "v$shortVer ($buildVer)"
}

actual fun epochMillis(): Long =
    (NSDate().timeIntervalSince1970 * 1000.0).toLong()

actual fun observeAppForeground(): StateFlow<Boolean> {
    IosAppLifecycle.ensureRegistered()
    return IosAppLifecycle.isForeground
}

actual fun observeBonjourHubUrls(): StateFlow<List<String>> = IosBonjourScanner.urls

actual fun startBonjourDiscovery() {
    IosBonjourScanner.startOrRefresh()
}

actual fun loadLastGoodHubUrl(): String? =
    NSUserDefaults.standardUserDefaults.stringForKey(KEY_LAST_GOOD_URL)?.takeIf { it.isNotBlank() }

actual fun saveLastGoodHubUrl(url: String?) {
    val d = NSUserDefaults.standardUserDefaults
    if (url.isNullOrBlank()) {
        d.removeObjectForKey(KEY_LAST_GOOD_URL)
    } else {
        d.setObject(url, forKey = KEY_LAST_GOOD_URL)
    }
    d.synchronize()
}

actual fun loadSavedRelayUrls(): List<String> =
    NSUserDefaults.standardUserDefaults
        .stringForKey(KEY_RELAY_URLS)
        .orEmpty()
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }

actual fun saveRelayUrls(urls: List<String>) {
    val d = NSUserDefaults.standardUserDefaults
    val cleaned = urls.map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() }.distinct()
    if (cleaned.isEmpty()) {
        d.removeObjectForKey(KEY_RELAY_URLS)
    } else {
        d.setObject(cleaned.joinToString(","), forKey = KEY_RELAY_URLS)
    }
    d.synchronize()
}

actual fun candidateHubHttpUrls(): List<String> {
    val out = mutableListOf<String>()
    out.addAll(IosBonjourScanner.urls.value)
    loadLastGoodHubUrl()?.let { out.add(it) }
    out.addAll(loadSavedRelayUrls())
    out.add("http://127.0.0.1:64450")
    return out.distinct()
}

actual fun loadSavedFeishuToken(): String? =
    NSUserDefaults.standardUserDefaults.stringForKey(KEY_FEISHU_TOKEN)

actual fun loadSavedFeishuUserName(): String? =
    NSUserDefaults.standardUserDefaults.stringForKey(KEY_FEISHU_USER)

actual fun saveFeishuSession(token: String?, userName: String?) {
    val defaults = NSUserDefaults.standardUserDefaults
    if (token.isNullOrBlank()) {
        defaults.removeObjectForKey(KEY_FEISHU_TOKEN)
        defaults.removeObjectForKey(KEY_FEISHU_USER)
    } else {
        defaults.setObject(token, forKey = KEY_FEISHU_TOKEN)
        defaults.setObject(userName ?: "Owner", forKey = KEY_FEISHU_USER)
    }
    defaults.synchronize()
}

actual fun loadSessionPrefs(): SavedSessionPrefs {
    val d = NSUserDefaults.standardUserDefaults
    return SavedSessionPrefs(
        engineId = d.stringForKey(KEY_PREF_ENGINE)?.ifBlank { null } ?: "claude",
        providerId = d.stringForKey(KEY_PREF_PROVIDER)?.ifBlank { null },
        modelId = d.stringForKey(KEY_PREF_MODEL)?.ifBlank { null },
        reasoningEffort = d.stringForKey(KEY_PREF_EFFORT)?.ifBlank { null } ?: "medium",
        mode = d.stringForKey(KEY_PREF_MODE)?.ifBlank { null } ?: "ask",
        workspacePath = d.stringForKey(KEY_PREF_WORKSPACE).orEmpty(),
    )
}

actual fun saveSessionPrefs(prefs: SavedSessionPrefs) {
    val d = NSUserDefaults.standardUserDefaults
    d.setObject(prefs.engineId, forKey = KEY_PREF_ENGINE)
    if (prefs.providerId != null) d.setObject(prefs.providerId, forKey = KEY_PREF_PROVIDER)
    else d.removeObjectForKey(KEY_PREF_PROVIDER)
    if (prefs.modelId != null) d.setObject(prefs.modelId, forKey = KEY_PREF_MODEL)
    else d.removeObjectForKey(KEY_PREF_MODEL)
    d.setObject(prefs.reasoningEffort, forKey = KEY_PREF_EFFORT)
    d.setObject(prefs.mode, forKey = KEY_PREF_MODE)
    d.setObject(prefs.workspacePath, forKey = KEY_PREF_WORKSPACE)
    d.synchronize()
}

actual fun openExternalUrl(url: String) {
    val nsUrl = NSURL.URLWithString(url) ?: return
    UIApplication.sharedApplication.openURL(
        nsUrl,
        options = emptyMap<Any?, Any>(),
        completionHandler = null,
    )
}

actual fun copyTextToClipboard(text: String) {
    UIPasteboard.generalPasteboard.string = text
}
