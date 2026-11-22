@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.lework.platform

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import dev.lework.protocol.ProtocolJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.builtins.ListSerializer
import kotlin.coroutines.resume
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSNetService
import platform.Foundation.NSNetServiceBrowser
import platform.Foundation.NSNetServiceBrowserDelegateProtocol
import platform.Foundation.NSNetServiceDelegateProtocol
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSSelectorFromString
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIDevice
import platform.UIKit.UIPasteboard
import platform.UIKit.endEditing
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.posix.AF_INET
import platform.posix.sockaddr

private const val KEY_LAST_GOOD_URL = "lework.last.good.url"
private const val KEY_RELAY_URLS = "lework.relay.urls"
private const val KEY_PREF_ENGINE = "lework.pref.engine"
private const val KEY_PREF_PROVIDER = "lework.pref.provider"
private const val KEY_PREF_MODEL = "lework.pref.model"
private const val KEY_PREF_EFFORT = "lework.pref.effort"
private const val KEY_PREF_MODE = "lework.pref.mode"
private const val KEY_PREF_WORKSPACE = "lework.pref.workspace"
private const val KEY_TSNET_ENABLED = "lework.tsnet.enabled"
private const val KEY_TSNET_CONTROL_URL = "lework.tsnet.control.url"
private const val KEY_PAIRED_HOSTS = "lework.paired.hosts.json"
private const val KEY_ACTIVE_HOST_ID = "lework.active.host.id"
private const val KC_ACCOUNT_TOKEN = "hub.access-token"
private const val KC_ACCOUNT_USER = "hub.user-name"
private const val KC_ACCOUNT_PINS = "hub.pinned-fingerprints"

interface IosNativeDelegate {
    fun tsnetStart(stateDir: String, hostname: String, controlUrl: String, authKey: String, reqId: Long)
    fun tsnetStatus(reqId: Long)
    fun tsnetLogin(controlUrl: String, authKey: String, reqId: Long)
    fun tsnetLogout(reqId: Long)
    fun presentQrScanner(reqId: Long)
    fun presentImagePicker(reqId: Long)
}

/**
 * Bridge registered by Swift (`NativeBridges.swift`) at app launch to connect:
 * 1. Embedded Go `libtsnetbridge.a` (`LeworkTsnet*` C functions)
 * 2. `AVCaptureSession` QR code scanner sheet
 * 3. `PHPickerViewController` image attachment sheet
 * 4. `.onOpenURL` (`lework://pair` & `lework://session`) deep-link forwarding
 */
object IosNativeBridge {
    internal var delegate: IosNativeDelegate? = null
    private var nextReqId: Long = 1L
    private val pendingTsnet = mutableMapOf<Long, (String) -> Unit>()
    private val pendingQr = mutableMapOf<Long, (String?) -> Unit>()
    private val pendingImage = mutableMapOf<Long, (PickedImagePayload?) -> Unit>()

    internal val incomingUriFlow = MutableStateFlow<String?>(null)
    internal val networkEpochFlow = MutableStateFlow(0L)
    internal var tsnetProxyUrls: List<String> = emptyList()

    fun registerDelegate(delegate: IosNativeDelegate) {
        this.delegate = delegate
    }

    fun notifyNetworkChanged(tsnetJson: String, isWifi: Boolean) {
        if (!isWifi) {
            IosDiscoveryBus.updateBonjour(emptyList())
        } else {
            IosBonjourScanner.startOrRefresh()
        }
        if (tsnetJson.isNotBlank() && tsnetJson != "{}") {
            parseMobileTsnetJson(tsnetJson)
        }
        networkEpochFlow.value = networkEpochFlow.value + 1L
    }

    internal fun allocTsnetCallback(cb: (String) -> Unit): Long {
        val id = nextReqId++
        pendingTsnet[id] = cb
        return id
    }

    internal fun allocQrCallback(cb: (String?) -> Unit): Long {
        val id = nextReqId++
        pendingQr[id] = cb
        return id
    }

    internal fun allocImageCallback(cb: (PickedImagePayload?) -> Unit): Long {
        val id = nextReqId++
        pendingImage[id] = cb
        return id
    }

    fun completeTsnetRequest(reqId: Long, json: String) {
        pendingTsnet.remove(reqId)?.invoke(json)
    }

    fun completeQrScan(reqId: Long, scannedText: String?) {
        pendingQr.remove(reqId)?.invoke(scannedText)
    }

    fun completeImagePick(reqId: Long, fileName: String?, mimeType: String?, dataBase64: String?) {
        val cb = pendingImage.remove(reqId) ?: return
        if (dataBase64.isNullOrBlank()) {
            cb.invoke(null)
        } else {
            cb.invoke(
                PickedImagePayload(
                    fileName = fileName?.ifBlank { null } ?: "image.jpg",
                    mimeType = mimeType?.ifBlank { null } ?: "image/jpeg",
                    dataBase64 = dataBase64,
                ),
            )
        }
    }

    fun handleIncomingUrl(url: String) {
        val trimmed = url.trim()
        if (trimmed.startsWith("lework://", ignoreCase = true)) {
            incomingUriFlow.value = trimmed
        }
    }
}

private object IosDiscoveryBus {
    private val _combined = MutableStateFlow<List<String>>(emptyList())
    val combined: StateFlow<List<String>> = _combined.asStateFlow()
    private var bonjourList: List<String> = emptyList()
    private var tsnetList: List<String> = emptyList()

    fun updateBonjour(urls: List<String>) {
        bonjourList = urls
        _combined.value = (bonjourList + tsnetList).distinct()
    }

    fun updateTsnetProxies(urls: List<String>) {
        IosNativeBridge.tsnetProxyUrls = urls
        tsnetList = urls
        _combined.value = (bonjourList + tsnetList).distinct()
    }
}

/**
 * Minimal Keychain wrapper (generic passwords, this-device-only, available after first unlock so
 * background reconnects work; never synced to iCloud / backups).
 */
private object IosKeychain {
    private const val SERVICE = "dev.lework.lework"

    private fun baseQuery(account: String): CFMutableDictionaryRef? {
        val dict = CFDictionaryCreateMutable(
            null,
            0,
            kCFTypeDictionaryKeyCallBacks.ptr,
            kCFTypeDictionaryValueCallBacks.ptr,
        )
        val svc = CFStringCreateWithCString(null, SERVICE, kCFStringEncodingUTF8)
        val acc = CFStringCreateWithCString(null, account, kCFStringEncodingUTF8)
        CFDictionaryAddValue(dict, kSecClass, kSecClassGenericPassword)
        CFDictionaryAddValue(dict, kSecAttrService, svc)
        CFDictionaryAddValue(dict, kSecAttrAccount, acc)
        CFRelease(svc)
        CFRelease(acc)
        return dict
    }

    fun get(account: String): String? {
        val query = baseQuery(account) ?: return null
        CFDictionaryAddValue(query, kSecReturnData, kCFBooleanTrue)
        CFDictionaryAddValue(query, kSecMatchLimit, kSecMatchLimitOne)
        return memScoped {
            val out = alloc<CFTypeRefVar>()
            val status = SecItemCopyMatching(query, out.ptr)
            CFRelease(query)
            val ref = out.value
            if (status != errSecSuccess || ref == null) return@memScoped null
            try {
                val data: CFDataRef = ref.reinterpret()
                val len = CFDataGetLength(data).toInt()
                val bytes = CFDataGetBytePtr(data)?.reinterpret<ByteVar>()?.readBytes(len) ?: return@memScoped null
                bytes.decodeToString()
            } finally {
                CFRelease(ref)
            }
        }
    }

    fun set(account: String, value: String?) {
        baseQuery(account)?.let { q ->
            SecItemDelete(q)
            CFRelease(q)
        }
        if (value.isNullOrEmpty()) return
        val bytes = value.encodeToByteArray()
        val data = bytes.usePinned { pin ->
            CFDataCreate(null, pin.addressOf(0).reinterpret(), bytes.size.convert())
        } ?: return
        val query = baseQuery(account)
        if (query == null) {
            CFRelease(data)
            return
        }
        CFDictionaryAddValue(query, kSecValueData, data)
        CFDictionaryAddValue(query, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
        SecItemAdd(query, null)
        CFRelease(data)
        CFRelease(query)
    }
}

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
                            discovered.add("https://$b0.$b1.$b2.$b3:$port")
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
                            discovered.add("https://$clean:$port")
                        }
                    }
                }
            }

            // 3. Bonjour `.local` hostname fallback
            val host = sender.hostName?.trim()?.trimEnd('.')
            if (!host.isNullOrEmpty()) {
                discovered.add("https://$host:$port")
            }

            if (discovered.isNotEmpty()) {
                val merged = (discovered + _urls.value).distinct()
                _urls.value = merged
                IosDiscoveryBus.updateBonjour(merged)
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
            nextBrowser.delegate = browserDelegate
            browser = nextBrowser
            nextBrowser.searchForServicesOfType("_lework._tcp.", inDomain = "local.")
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
    val shortVer = info?.get("CFBundleShortVersionString") as? String ?: "0.5.0"
    val buildVer = info?.get("CFBundleVersion") as? String ?: "1"
    return "v$shortVer ($buildVer)"
}

actual fun epochMillis(): Long =
    (NSDate().timeIntervalSince1970 * 1000.0).toLong()

actual fun observeAppForeground(): StateFlow<Boolean> {
    IosAppLifecycle.ensureRegistered()
    return IosAppLifecycle.isForeground
}

actual fun observeNetworkEpoch(): StateFlow<Long> = IosNativeBridge.networkEpochFlow.asStateFlow()

actual fun observeBonjourHubUrls(): StateFlow<List<String>> = IosDiscoveryBus.combined

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
    out.addAll(IosNativeBridge.tsnetProxyUrls)
    val activeId = loadActiveHostId()
    val hosts = loadPairedHosts()
    if (activeId != null) {
        hosts.firstOrNull { it.hubId == activeId }?.let { out.addAll(it.endpoints) }
    }
    loadLastGoodHubUrl()?.let { out.add(it) }
    out.addAll(loadSavedRelayUrls())
    for (h in hosts) {
        out.addAll(h.endpoints)
    }
    (NSBundle.mainBundle.objectForInfoDictionaryKey("LeworkBootstrapRelay") as? String)
        ?.trim()
        ?.takeIf { it.isNotEmpty() && !it.startsWith("$(") }
        ?.let { out.add(dev.lework.security.normalizeHubBaseUrl(it)) }
    out.add("https://127.0.0.1:64450")
    return out.distinct()
}

actual fun loadSavedAccessToken(): String? =
    IosKeychain.get(KC_ACCOUNT_TOKEN)?.takeIf { it.isNotBlank() }

actual fun loadSavedAuthUserName(): String? =
    IosKeychain.get(KC_ACCOUNT_USER)

actual fun saveAuthSession(token: String?, userName: String?) {
    if (token.isNullOrBlank()) {
        IosKeychain.set(KC_ACCOUNT_TOKEN, null)
        IosKeychain.set(KC_ACCOUNT_USER, null)
    } else {
        IosKeychain.set(KC_ACCOUNT_TOKEN, token)
        IosKeychain.set(KC_ACCOUNT_USER, userName ?: "Owner")
    }
}

actual fun loadPinnedHubFingerprints(): Set<String> =
    IosKeychain.get(KC_ACCOUNT_PINS).orEmpty()
        .split(',')
        .map { it.trim().lowercase() }
        .filter { it.length == 64 }
        .toSet()

actual fun savePinnedHubFingerprints(fingerprints: Set<String>) {
    IosKeychain.set(KC_ACCOUNT_PINS, fingerprints.joinToString(",").ifEmpty { null })
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

private fun iosTsnetStateDir(): String {
    val dirs = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true)
    val base = (dirs.firstOrNull() as? String)?.takeIf { it.isNotBlank() } ?: "/tmp"
    val target = "$base/lework-tsnet"
    runCatching {
        NSFileManager.defaultManager.createDirectoryAtPath(
            path = target,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
        // Exclude tsnet WireGuard/node keys from iCloud / iTunes backups
        NSURL.fileURLWithPath(target).setResourceValue(true, forKey = NSURLIsExcludedFromBackupKey, error = null)
    }
    return target
}

private fun iosTsnetHostname(): String {
    val raw = UIDevice.currentDevice.name
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .ifEmpty { "iphone" }
    return "lework-ios-${raw.take(24)}"
}

private fun parseMobileTsnetJson(rawJson: String): MobileTsnetStatus {
    val hasBridge = IosNativeBridge.delegate != null
    val parsed = runCatching {
        ProtocolJson.decodeFromString(MobileTsnetStatus.serializer(), rawJson)
    }.getOrElse { err ->
        MobileTsnetStatus(
            available = hasBridge,
            backendState = "Error",
            error = err.message ?: "Invalid tsnet JSON",
        )
    }
    val withAvail = parsed.copy(available = hasBridge)
    val liveProxies = withAvail.peers
        .mapNotNull { it.localProxyUrl?.takeIf { u -> u.isNotBlank() } }
    IosDiscoveryBus.updateTsnetProxies(liveProxies)
    return withAvail
}

actual fun isMobileTsnetAvailable(): Boolean = IosNativeBridge.delegate != null

actual suspend fun startMobileTsnet(controlUrl: String?): MobileTsnetStatus {
    val d = IosNativeBridge.delegate
        ?: return MobileTsnetStatus(available = false, backendState = "Unavailable")
    val stateDir = iosTsnetStateDir()
    val host = iosTsnetHostname()
    val ctrl = (controlUrl ?: loadSavedTsnetControlUrl()).orEmpty()
    val raw = suspendCancellableCoroutine<String> { cont ->
        dispatch_async(dispatch_get_main_queue()) {
            val id = IosNativeBridge.allocTsnetCallback { json ->
                if (cont.isActive) cont.resume(json)
            }
            d.tsnetStart(stateDir, host, ctrl, "", id)
        }
    }
    return parseMobileTsnetJson(raw)
}

actual suspend fun queryMobileTsnetStatus(): MobileTsnetStatus {
    val d = IosNativeBridge.delegate
        ?: return MobileTsnetStatus(available = false, backendState = "Unavailable")
    val raw = suspendCancellableCoroutine<String> { cont ->
        dispatch_async(dispatch_get_main_queue()) {
            val id = IosNativeBridge.allocTsnetCallback { json ->
                if (cont.isActive) cont.resume(json)
            }
            d.tsnetStatus(id)
        }
    }
    return parseMobileTsnetJson(raw)
}

actual suspend fun loginMobileTsnet(controlUrl: String?, authKey: String?): MobileTsnetStatus {
    val d = IosNativeBridge.delegate
        ?: return MobileTsnetStatus(available = false, backendState = "Unavailable")
    val stateDir = iosTsnetStateDir()
    val host = iosTsnetHostname()
    val ctrl = (controlUrl ?: loadSavedTsnetControlUrl()).orEmpty()
    val key = authKey.orEmpty()
    val raw = suspendCancellableCoroutine<String> { cont ->
        dispatch_async(dispatch_get_main_queue()) {
            val startId = IosNativeBridge.allocTsnetCallback { _ ->
                val loginId = IosNativeBridge.allocTsnetCallback { json ->
                    if (cont.isActive) cont.resume(json)
                }
                d.tsnetLogin(ctrl, key, loginId)
            }
            d.tsnetStart(stateDir, host, ctrl, key, startId)
        }
    }
    return parseMobileTsnetJson(raw)
}

actual suspend fun logoutMobileTsnet(): MobileTsnetStatus {
    val d = IosNativeBridge.delegate
        ?: return MobileTsnetStatus(available = false, backendState = "Unavailable")
    val raw = suspendCancellableCoroutine<String> { cont ->
        dispatch_async(dispatch_get_main_queue()) {
            val id = IosNativeBridge.allocTsnetCallback { json ->
                if (cont.isActive) cont.resume(json)
            }
            d.tsnetLogout(id)
        }
    }
    IosDiscoveryBus.updateTsnetProxies(emptyList())
    return parseMobileTsnetJson(raw)
}

actual fun loadSavedTsnetEnabled(): Boolean =
    NSUserDefaults.standardUserDefaults.boolForKey(KEY_TSNET_ENABLED)

actual fun saveTsnetEnabled(enabled: Boolean, controlUrl: String?) {
    val d = NSUserDefaults.standardUserDefaults
    d.setBool(enabled, forKey = KEY_TSNET_ENABLED)
    if (controlUrl != null) {
        val clean = controlUrl.trim()
        if (clean.isEmpty()) d.removeObjectForKey(KEY_TSNET_CONTROL_URL)
        else d.setObject(clean, forKey = KEY_TSNET_CONTROL_URL)
    }
    d.synchronize()
}

actual fun loadSavedTsnetControlUrl(): String? =
    NSUserDefaults.standardUserDefaults.stringForKey(KEY_TSNET_CONTROL_URL)?.takeIf { it.isNotBlank() }

actual fun canScanQrCode(): Boolean = IosNativeBridge.delegate != null

actual fun requestQrCodeScan(onResult: (String?) -> Unit) {
    val d = IosNativeBridge.delegate
    if (d == null) {
        onResult(null)
    } else {
        dispatch_async(dispatch_get_main_queue()) {
            val id = IosNativeBridge.allocQrCallback(onResult)
            d.presentQrScanner(id)
        }
    }
}

actual fun observeIncomingPairingUri(): StateFlow<String?> = IosNativeBridge.incomingUriFlow.asStateFlow()

actual fun consumeIncomingPairingUri() {
    IosNativeBridge.incomingUriFlow.value = null
}

actual fun loadPairedHosts(): List<PairedHost> {
    val raw = NSUserDefaults.standardUserDefaults.stringForKey(KEY_PAIRED_HOSTS) ?: return emptyList()
    return runCatching {
        ProtocolJson.decodeFromString(ListSerializer(PairedHost.serializer()), raw)
    }.getOrDefault(emptyList())
}

actual fun savePairedHosts(hosts: List<PairedHost>) {
    val d = NSUserDefaults.standardUserDefaults
    if (hosts.isEmpty()) {
        d.removeObjectForKey(KEY_PAIRED_HOSTS)
    } else {
        val raw = ProtocolJson.encodeToString(ListSerializer(PairedHost.serializer()), hosts)
        d.setObject(raw, forKey = KEY_PAIRED_HOSTS)
    }
    d.synchronize()
}

actual fun loadActiveHostId(): String? =
    NSUserDefaults.standardUserDefaults.stringForKey(KEY_ACTIVE_HOST_ID)?.takeIf { it.isNotBlank() }

actual fun saveActiveHostId(hubId: String?) {
    val d = NSUserDefaults.standardUserDefaults
    if (hubId.isNullOrBlank()) d.removeObjectForKey(KEY_ACTIVE_HOST_ID)
    else d.setObject(hubId, forKey = KEY_ACTIVE_HOST_ID)
    d.synchronize()
}

actual fun loadHostAccessToken(hubId: String): String? =
    IosKeychain.get("hub.token.$hubId")?.takeIf { it.isNotBlank() }

actual fun saveHostAccessToken(hubId: String, token: String?) {
    IosKeychain.set("hub.token.$hubId", token?.takeIf { it.isNotBlank() })
}

actual fun canPickImages(): Boolean = IosNativeBridge.delegate != null

actual fun requestImagePick(onResult: (PickedImagePayload?) -> Unit) {
    val d = IosNativeBridge.delegate
    if (d == null) {
        onResult(null)
    } else {
        dispatch_async(dispatch_get_main_queue()) {
            val id = IosNativeBridge.allocImageCallback(onResult)
            d.presentImagePicker(id)
        }
    }
}

actual fun signingExpiryRemainingHours(): Int? {
    val path = NSBundle.mainBundle.pathForResource("embedded", ofType = "mobileprovision") ?: return null
    val data = NSData.create(contentsOfFile = path) ?: return null
    val len = data.length.toInt()
    if (len <= 0 || len > 512 * 1024) return null
    val ptr = data.bytes?.reinterpret<ByteVar>() ?: return null
    val rawBytes = ptr.readBytes(len)
    val latin1 = buildString(rawBytes.size) {
        for (b in rawBytes) append((b.toInt() and 0xFF).toChar())
    }
    val match = Regex("<key>ExpirationDate</key>\\s*<date>([^<]+)</date>").find(latin1) ?: return null
    val iso = match.groupValues[1].trim()
    val expEpochSec = parseIso8601UtcEpochSeconds(iso) ?: return null
    val nowEpochSec = NSDate().timeIntervalSince1970.toLong()
    val remainingHours = ((expEpochSec - nowEpochSec) / 3600L).toInt()
    // Only surface reminder for short-lived personal provisioning profiles (<= 8 days horizon)
    return if (remainingHours <= 192) remainingHours else null
}

private fun parseIso8601UtcEpochSeconds(iso: String): Long? {
    val m = Regex("^(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})Z$").find(iso) ?: return null
    val year = m.groupValues[1].toIntOrNull() ?: return null
    val month = m.groupValues[2].toIntOrNull() ?: return null
    val day = m.groupValues[3].toIntOrNull() ?: return null
    val hour = m.groupValues[4].toIntOrNull() ?: return null
    val min = m.groupValues[5].toIntOrNull() ?: return null
    val sec = m.groupValues[6].toIntOrNull() ?: return null
    if (month !in 1..12 || day !in 1..31) return null

    fun isLeap(y: Int): Boolean = (y % 4 == 0 && y % 100 != 0) || (y % 400 == 0)
    var days = 0L
    for (y in 1970 until year) {
        days += if (isLeap(y)) 366L else 365L
    }
    val monthLengths = intArrayOf(31, if (isLeap(year)) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
    for (idx in 0 until (month - 1)) {
        days += monthLengths[idx]
    }
    days += (day - 1)
    return days * 86_400L + hour * 3_600L + min * 60L + sec
}

actual fun dismissNativeKeyboard() {
    dispatch_async(dispatch_get_main_queue()) {
        UIApplication.sharedApplication.sendAction(
            action = NSSelectorFromString("resignFirstResponder"),
            to = null,
            from = null,
            forEvent = null,
        )
        @Suppress("DEPRECATION")
        UIApplication.sharedApplication.keyWindow?.endEditing(true)
    }
}
