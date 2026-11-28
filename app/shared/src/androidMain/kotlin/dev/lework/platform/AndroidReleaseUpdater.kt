package dev.lework.platform

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import dev.lework.store.isTrustedGitHubReleaseUrl
import dev.lework.store.normalizeSha256Digest
import dev.lework.store.resolveApkCandidateFromSha256Sums
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

internal sealed interface ApkCompatibilityResult {
    data object Compatible : ApkCompatibilityResult
    data class Incompatible(val reason: String) : ApkCompatibilityResult
}

internal fun sanitizeApkFileName(raw: String): String {
    val base = raw.trim().substringAfterLast('/').substringAfterLast('\\')
        .replace(Regex("""[^A-Za-z0-9._-]"""), "_")
        .trimStart('.')
        .ifEmpty { "lework-update.apk" }
    return if (base.lowercase().endsWith(".apk")) base else "$base.apk"
}

internal fun rewriteReleaseAssetDownloadUrl(
    originalUrl: String,
    resolvedFileName: String,
): String? {
    val cleanFile = sanitizeApkFileName(resolvedFileName)
    val base = originalUrl.trim().substringBefore('?').substringBefore('#')
    val slashIdx = base.lastIndexOf('/')
    if (slashIdx <= "https://".length) return null
    val candidate = "${base.substring(0, slashIdx + 1)}$cleanFile"
    return candidate.takeIf { isTrustedGitHubReleaseUrl(it) }
}

internal fun formatTransferMegabytes(bytes: Long): String {
    if (bytes <= 0L) return "0.0"
    val tenths = ((bytes * 10L) / (1024L * 1024L)).coerceAtLeast(1L)
    return "${tenths / 10L}.${tenths % 10L}"
}

internal fun sha256HexOfBytes(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { byte ->
        val v = byte.toInt() and 0xff
        val hi = "0123456789abcdef"[v ushr 4]
        val lo = "0123456789abcdef"[v and 0x0f]
        "$hi$lo"
    }
}

internal fun sha256HexOfFile(
    file: File,
    checkCancelled: () -> Unit = {},
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buf = ByteArray(32 * 1024)
    FileInputStream(file).use { input ->
        while (true) {
            checkCancelled()
            val read = input.read(buf)
            if (read <= 0) break
            digest.update(buf, 0, read)
        }
    }
    return digest.digest().joinToString("") { byte ->
        val v = byte.toInt() and 0xff
        val hi = "0123456789abcdef"[v ushr 4]
        val lo = "0123456789abcdef"[v and 0x0f]
        "$hi$lo"
    }
}

internal fun evaluateApkCompatibility(
    expectedPackageName: String,
    currentVersionCode: Long,
    installedSignerCerts: Set<String>,
    archivePackageName: String?,
    archiveVersionCode: Long,
    archiveSignerCerts: Set<String>,
): ApkCompatibilityResult {
    val pkg = archivePackageName?.trim().orEmpty()
    if (pkg.isEmpty()) {
        return ApkCompatibilityResult.Incompatible("安装包解析失败，文件可能已损坏，请重试下载")
    }
    if (pkg != expectedPackageName) {
        return ApkCompatibilityResult.Incompatible("安装包应用标识不匹配（$pkg），已终止安装")
    }
    if (currentVersionCode > 0L && archiveVersionCode > 0L && archiveVersionCode < currentVersionCode) {
        return ApkCompatibilityResult.Incompatible(
            "安装包版本号（$archiveVersionCode）低于当前已安装版本（$currentVersionCode），无法覆盖安装",
        )
    }
    if (installedSignerCerts.isNotEmpty() &&
        archiveSignerCerts.isNotEmpty() &&
        installedSignerCerts.intersect(archiveSignerCerts).isEmpty()
    ) {
        return ApkCompatibilityResult.Incompatible(
            "新版本签名证书与当前安装版本不一致，无法直接覆盖更新。请点击「GitHub 发布页」下载并在卸载旧版后安装（首次切换到统一正式签名后即可无缝自更新）。",
        )
    }
    return ApkCompatibilityResult.Compatible
}

/**
 * Fetches GitHub Releases over Android's system TLS/proxy stack and provides SHA-256 & APK
 * archive verified one-click in-app self-update with unknown-sources permission handoff.
 */
object AndroidReleaseUpdater {
    private const val CONNECT_TIMEOUT_MS = 12_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val DOWNLOAD_READ_TIMEOUT_MS = 60_000
    private const val MAX_REDIRECTS = 6
    private const val MAX_APK_BYTES = 256L * 1024L * 1024L
    private const val STREAM_BUFFER_BYTES = 32 * 1024
    private const val PROGRESS_REPORT_STEP_BYTES = 128L * 1024L
    private const val UPDATES_CACHE_SUBDIR = "app_updates"
    private const val PREF_PENDING_APK_NAME = "pending_update_apk_name"
    private const val PREF_PENDING_APK_TS = "pending_update_apk_ts"
    private const val PENDING_INSTALL_TTL_MS = 10L * 60L * 1000L

    private fun userAgent(): String = "Lework-Android/${appVersionLabel()}"

    internal fun updatesDir(context: Context): File =
        File(context.cacheDir, UPDATES_CACHE_SUBDIR).apply { mkdirs() }

    internal fun canInstallAndroidApk(): Boolean = AndroidPlatformRuntime.appContext != null

    fun recordPendingInstall(context: Context, apkFile: File) {
        val safeName = sanitizeApkFileName(apkFile.name)
        context.applicationContext
            .getSharedPreferences("lework_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_PENDING_APK_NAME, safeName)
            .putLong(PREF_PENDING_APK_TS, System.currentTimeMillis())
            .apply()
    }

    fun clearPendingInstall(context: Context) {
        context.applicationContext
            .getSharedPreferences("lework_prefs", Context.MODE_PRIVATE)
            .edit()
            .remove(PREF_PENDING_APK_NAME)
            .remove(PREF_PENDING_APK_TS)
            .apply()
    }

    /**
     * Called from MainActivity.onResume() to resume installing a verified APK if Android 11+
     * restarted the app process immediately after the user enabled "Allow from this source".
     */
    fun consumeResumablePendingApk(context: Context): File? {
        val appCtx = context.applicationContext
        val prefs = appCtx.getSharedPreferences("lework_prefs", Context.MODE_PRIVATE)
        val pendingName = prefs.getString(PREF_PENDING_APK_NAME, null)?.trim().orEmpty()
        val recordedAt = prefs.getLong(PREF_PENDING_APK_TS, 0L)
        if (pendingName.isEmpty()) return null
        clearPendingInstall(appCtx)

        val ageMs = System.currentTimeMillis() - recordedAt
        if (ageMs < 0L || ageMs > PENDING_INSTALL_TTL_MS) return null

        val canInstall = Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            appCtx.packageManager.canRequestPackageInstalls()
        if (!canInstall) return null

        val candidate = File(updatesDir(appCtx), sanitizeApkFileName(pendingName))
        if (!candidate.isFile || candidate.length() <= 0L) return null

        val installedInfo = getInstalledPackageInfoCompat(appCtx.packageManager, appCtx.packageName)
        val currentVersionCode = packageVersionCode(installedInfo)
        val archiveInfo = readPackageArchiveInfo(appCtx.packageManager, candidate)
        val archiveVersionCode = packageVersionCode(archiveInfo)
        if (currentVersionCode > 0L && archiveVersionCode in 1L..currentVersionCode) {
            runCatching { candidate.delete() }
            return null
        }
        val compat = validateApkArchive(appCtx, candidate)
        if (compat is ApkCompatibilityResult.Incompatible) {
            runCatching { candidate.delete() }
            return null
        }
        logRuntimeEvent("UPDATE", "resuming pending apk install after permission restart file=${candidate.name}")
        return candidate
    }

    fun cleanupObsoleteApks(context: Context, keepFileName: String? = null) {
        val appCtx = context.applicationContext
        val dir = File(appCtx.cacheDir, UPDATES_CACHE_SUBDIR)
        if (!dir.isDirectory) return
        val prefs = appCtx.getSharedPreferences("lework_prefs", Context.MODE_PRIVATE)
        val pendingName = prefs.getString(PREF_PENDING_APK_NAME, null)?.trim()
        val pendingTs = prefs.getLong(PREF_PENDING_APK_TS, 0L)
        val pendingFresh = !pendingName.isNullOrEmpty() &&
            (System.currentTimeMillis() - pendingTs) in 0L..PENDING_INSTALL_TTL_MS
        val currentVersionCode = packageVersionCode(
            getInstalledPackageInfoCompat(appCtx.packageManager, appCtx.packageName),
        )
        val children = dir.listFiles().orEmpty()
        for (file in children) {
            if (!file.isFile) continue
            val name = file.name
            if (keepFileName != null && name == keepFileName) continue
            if (pendingFresh && name == pendingName) {
                val archiveCode = packageVersionCode(readPackageArchiveInfo(appCtx.packageManager, file))
                if (archiveCode <= 0L || currentVersionCode <= 0L || archiveCode > currentVersionCode) {
                    continue
                }
            }
            if (name.endsWith(".part") || name.endsWith(".apk")) {
                runCatching { file.delete() }
            }
        }
    }

    internal suspend fun fetchLatestReleasePayload(
        apiUrl: String,
        fallbackWebUrl: String,
    ): Result<GitHubReleaseFetchPayload> = withContext(Dispatchers.IO) {
        try {
            if (!isTrustedGitHubReleaseUrl(apiUrl) || !isTrustedGitHubReleaseUrl(fallbackWebUrl)) {
                return@withContext Result.failure(IllegalArgumentException("更新源地址不受信任"))
            }
            val apiResult = runCatching { fetchTextHttps(apiUrl, accept = "application/vnd.github+json") }
            apiResult.getOrNull()?.let { resp ->
                if (resp.code in 200..299 && resp.body.isNotBlank()) {
                    return@withContext Result.success(GitHubReleaseFetchPayload(jsonBody = resp.body))
                }
            }

            val redirectUrl = resolveHttpsRedirectTarget(fallbackWebUrl)
            if (redirectUrl != null && isTrustedGitHubReleaseUrl(redirectUrl)) {
                return@withContext Result.success(GitHubReleaseFetchPayload(resolvedWebUrl = redirectUrl))
            }

            val err = apiResult.exceptionOrNull()
            Result.failure(err ?: IllegalStateException("无法获取最新 Release 信息，请检查网络连接"))
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    internal suspend fun installApkUpdate(
        downloadUrl: String,
        expectedFileName: String,
        expectedSha256: String?,
        checksumsUrl: String?,
        onProgress: (fraction: Float?, statusText: String) -> Unit,
    ): Result<String> {
        val ctx = AndroidPlatformRuntime.appContext
            ?: return Result.failure(IllegalStateException("Android 应用上下文尚未就绪，请重试"))
        if (!isTrustedGitHubReleaseUrl(downloadUrl)) {
            return Result.failure(IllegalArgumentException("安装包下载地址不受信任"))
        }

        val activeConn = AtomicReference<HttpURLConnection?>(null)
        val parentJob = currentCoroutineContext()[Job]
        val cancelHandle = parentJob?.invokeOnCompletion { cause ->
            if (cause != null) {
                activeConn.getAndSet(null)?.disconnect()
            }
        }

        try {
            val stagedApk = withContext(Dispatchers.IO) {
                val ioContext = currentCoroutineContext()
                try {
                    stageAndVerifyApk(
                        context = ctx,
                        downloadUrl = downloadUrl,
                        expectedFileName = expectedFileName,
                        expectedSha256 = normalizeSha256Digest(expectedSha256),
                        checksumsUrl = checksumsUrl?.takeIf { isTrustedGitHubReleaseUrl(it) },
                        activeConn = activeConn,
                        checkCancelled = { ioContext.ensureActive() },
                        onProgress = onProgress,
                    )
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }

            val apkFile = stagedApk.getOrElse { return Result.failure(it) }
            val delegate = AndroidPlatformRuntime.nativeDelegate
                ?: return Result.failure(IllegalStateException("无法打开系统安装界面，请返回应用主界面后重试"))

            val needsUnknownSourcesGrant =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ctx.packageManager.canRequestPackageInstalls()
            if (needsUnknownSourcesGrant) {
                onProgress(1f, "请在系统设置中开启「允许安装未知应用」，返回后将自动继续安装…")
            } else {
                onProgress(1f, "校验通过，正在打开系统安装界面…")
            }

            val launchResult = suspendCancellableCoroutine<AndroidApkInstallLaunchResult> { cont ->
                cont.invokeOnCancellation {
                    delegate.cancelPendingUpdateInstall()
                }
                delegate.installUpdateApk(apkFile) { result ->
                    if (cont.isActive) {
                        cont.resume(result)
                    }
                }
            }

            return when (launchResult.status) {
                AndroidApkInstallStatus.LAUNCHED_INSTALLER -> {
                    logRuntimeEvent("UPDATE", "android installer launched file=${apkFile.name}")
                    Result.success("已校验安装包并打开系统安装界面，请点击「更新」完成升级")
                }
                AndroidApkInstallStatus.PERMISSION_DENIED -> {
                    Result.failure(
                        IllegalStateException(
                            "尚未开启「允许安装未知应用」权限。开启后再次点击按钮即可直接安装（安装包已缓存，无需重新下载）。",
                        ),
                    )
                }
                AndroidApkInstallStatus.FAILED -> {
                    Result.failure(
                        IllegalStateException(
                            launchResult.detail?.takeIf { it.isNotBlank() }
                                ?: "无法打开系统安装界面，可点击「GitHub 发布页」手动下载安装",
                        ),
                    )
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            return Result.failure(e)
        } finally {
            cancelHandle?.dispose()
            activeConn.getAndSet(null)?.disconnect()
        }
    }

    private fun stageAndVerifyApk(
        context: Context,
        downloadUrl: String,
        expectedFileName: String,
        expectedSha256: String?,
        checksumsUrl: String?,
        activeConn: AtomicReference<HttpURLConnection?>,
        checkCancelled: () -> Unit,
        onProgress: (fraction: Float?, statusText: String) -> Unit,
    ): Result<File> {
        checkCancelled()
        var effectiveFileName = sanitizeApkFileName(expectedFileName)
        var effectiveDownloadUrl = downloadUrl
        var resolvedSha256 = expectedSha256

        if (resolvedSha256 == null && checksumsUrl != null) {
            onProgress(null, "正在获取安装包校验摘要…")
            val sumsRes = runCatching {
                fetchTextHttps(checksumsUrl, accept = "text/plain", activeConn = activeConn)
            }.getOrNull()
            checkCancelled()
            if (sumsRes != null && sumsRes.code in 200..299) {
                val candidate = resolveApkCandidateFromSha256Sums(sumsRes.body, effectiveFileName)
                if (candidate != null) {
                    val (matchedName, matchedSha) = candidate
                    resolvedSha256 = matchedSha
                    val sanitizedMatch = sanitizeApkFileName(matchedName)
                    if (sanitizedMatch != effectiveFileName) {
                        val rewritten = rewriteReleaseAssetDownloadUrl(effectiveDownloadUrl, sanitizedMatch)
                        if (rewritten != null) {
                            effectiveFileName = sanitizedMatch
                            effectiveDownloadUrl = rewritten
                        }
                    }
                }
            }
        }

        val dir = updatesDir(context)
        val targetFile = File(dir, effectiveFileName)
        cleanupObsoleteApks(context, keepFileName = effectiveFileName)

        // Reuse an already-downloaded APK on disk when its SHA-256 matches the expected release digest.
        if (targetFile.isFile && targetFile.length() > 0L && resolvedSha256 != null) {
            onProgress(null, "正在核验已缓存安装包…")
            val cachedSha = runCatching { sha256HexOfFile(targetFile, checkCancelled) }.getOrNull()
            checkCancelled()
            if (cachedSha != null && cachedSha.equals(resolvedSha256, ignoreCase = true)) {
                val compat = validateApkArchive(context, targetFile)
                if (compat is ApkCompatibilityResult.Compatible) {
                    logRuntimeEvent("UPDATE", "reusing verified cached apk file=${targetFile.name}")
                    onProgress(1f, "已核验本地缓存安装包，正在准备安装…")
                    return Result.success(targetFile)
                }
                if (compat is ApkCompatibilityResult.Incompatible) {
                    runCatching { targetFile.delete() }
                    return Result.failure(IllegalStateException(compat.reason))
                }
            }
            runCatching { targetFile.delete() }
        }

        val tempFile = File(dir, "$effectiveFileName.${System.currentTimeMillis()}.part")
        onProgress(0f, "正在下载 $effectiveFileName…")
        val actualSha256 = try {
            downloadFileHttps(
                url = effectiveDownloadUrl,
                target = tempFile,
                activeConn = activeConn,
                checkCancelled = checkCancelled,
            ) { downloaded, total ->
                val frac = if (total > 0L) (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f) else null
                val label = if (frac != null) {
                    val pct = (frac * 100f).toInt()
                    "正在下载 $effectiveFileName ($pct% · ${formatTransferMegabytes(downloaded)} / ${formatTransferMegabytes(total)} MB)…"
                } else {
                    "正在下载 $effectiveFileName (${formatTransferMegabytes(downloaded)} MB)…"
                }
                onProgress(frac, label)
            }
        } catch (ce: CancellationException) {
            runCatching { tempFile.delete() }
            throw ce
        } catch (e: Exception) {
            runCatching { tempFile.delete() }
            return Result.failure(e)
        }

        checkCancelled()
        if (resolvedSha256 != null && !actualSha256.equals(resolvedSha256, ignoreCase = true)) {
            runCatching { tempFile.delete() }
            return Result.failure(IllegalStateException("安装包 SHA-256 校验失败，已终止更新"))
        }

        if (targetFile.exists()) {
            runCatching { targetFile.delete() }
        }
        val moved = tempFile.renameTo(targetFile)
        if (!moved) {
            try {
                tempFile.copyTo(targetFile, overwrite = true)
            } catch (e: Exception) {
                runCatching { tempFile.delete() }
                runCatching { targetFile.delete() }
                return Result.failure(e)
            }
            runCatching { tempFile.delete() }
        }

        onProgress(1f, "校验通过，正在检查安装包兼容性…")
        val compat = validateApkArchive(context, targetFile)
        if (compat is ApkCompatibilityResult.Incompatible) {
            runCatching { targetFile.delete() }
            return Result.failure(IllegalStateException(compat.reason))
        }
        return Result.success(targetFile)
    }

    private fun validateApkArchive(context: Context, apkFile: File): ApkCompatibilityResult {
        val pm = context.packageManager
        val installedInfo = getInstalledPackageInfoCompat(pm, context.packageName)
        val archiveInfo = readPackageArchiveInfo(pm, apkFile)
        return evaluateApkCompatibility(
            expectedPackageName = context.packageName,
            currentVersionCode = packageVersionCode(installedInfo),
            installedSignerCerts = extractSignerSha256Set(installedInfo),
            archivePackageName = archiveInfo?.packageName,
            archiveVersionCode = packageVersionCode(archiveInfo),
            archiveSignerCerts = extractSignerSha256Set(archiveInfo),
        )
    }

    private fun readPackageArchiveInfo(pm: PackageManager, apkFile: File): PackageInfo? =
        getPackageArchiveInfoCompat(pm, apkFile.absolutePath, includeSigners = true)
            ?: getPackageArchiveInfoCompat(pm, apkFile.absolutePath, includeSigners = false)

    private fun getInstalledPackageInfoCompat(pm: PackageManager, packageName: String): PackageInfo? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
        }
    }.getOrNull()

    private fun getPackageArchiveInfoCompat(
        pm: PackageManager,
        archivePath: String,
        includeSigners: Boolean,
    ): PackageInfo? = runCatching {
        val flags = if (!includeSigners) {
            0
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(archivePath, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(archivePath, flags)
        }
    }.getOrNull()

    private fun packageVersionCode(info: PackageInfo?): Long {
        if (info == null) return 0L
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }

    private fun extractSignerSha256Set(info: PackageInfo?): Set<String> {
        if (info == null) return emptySet()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo
            val signers = if (signingInfo != null) {
                if (signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners
                } else {
                    signingInfo.signingCertificateHistory ?: signingInfo.apkContentsSigners
                }
            } else {
                null
            }
            if (!signers.isNullOrEmpty()) {
                return signers.map { sha256HexOfBytes(it.toByteArray()) }.toSet()
            }
        }
        @Suppress("DEPRECATION")
        val legacySigners = info.signatures
        if (!legacySigners.isNullOrEmpty()) {
            return legacySigners.map { sha256HexOfBytes(it.toByteArray()) }.toSet()
        }
        return emptySet()
    }

    private data class HttpsResponse(
        val code: Int,
        val body: String,
        val finalUrl: String,
    )

    private fun fetchTextHttps(
        url: String,
        accept: String,
        activeConn: AtomicReference<HttpURLConnection?>? = null,
    ): HttpsResponse {
        val (conn, finalUrl) = openHttpsFollowingRedirects(
            startUrl = url,
            accept = accept,
            readTimeoutMs = READ_TIMEOUT_MS,
            activeConn = activeConn,
        )
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return HttpsResponse(code = code, body = text, finalUrl = finalUrl)
        } finally {
            activeConn?.compareAndSet(conn, null)
            conn.disconnect()
        }
    }

    private fun resolveHttpsRedirectTarget(url: String): String? {
        val (conn, finalUrl) = openHttpsFollowingRedirects(
            startUrl = url,
            accept = "text/html",
            readTimeoutMs = READ_TIMEOUT_MS,
            method = "HEAD",
        )
        return try {
            val code = conn.responseCode
            if (code in 200..399) finalUrl else null
        } finally {
            conn.disconnect()
        }
    }

    private fun downloadFileHttps(
        url: String,
        target: File,
        activeConn: AtomicReference<HttpURLConnection?>,
        checkCancelled: () -> Unit,
        onChunk: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): String {
        val (conn, _) = openHttpsFollowingRedirects(
            startUrl = url,
            accept = "application/octet-stream",
            readTimeoutMs = DOWNLOAD_READ_TIMEOUT_MS,
            activeConn = activeConn,
            checkCancelled = checkCancelled,
        )
        try {
            val code = conn.responseCode
            check(code in 200..299) { "下载安装包失败（HTTP $code）" }
            val total = conn.contentLengthLong
            check(total <= 0L || total <= MAX_APK_BYTES) { "安装包体积异常，已取消下载" }
            val digest = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(STREAM_BUFFER_BYTES)
            var downloaded = 0L
            var lastReported = 0L
            conn.inputStream.use { input ->
                FileOutputStream(target).use { out ->
                    while (true) {
                        checkCancelled()
                        val read = input.read(buf)
                        if (read <= 0) break
                        downloaded += read
                        check(downloaded <= MAX_APK_BYTES) { "安装包体积超出安全限制，已终止下载" }
                        out.write(buf, 0, read)
                        digest.update(buf, 0, read)
                        if (downloaded - lastReported >= PROGRESS_REPORT_STEP_BYTES ||
                            (total > 0L && downloaded == total)
                        ) {
                            lastReported = downloaded
                            onChunk(downloaded, total)
                        }
                    }
                }
            }
            check(downloaded > 0L) { "下载的安装包内容为空，请重试" }
            return digest.digest().joinToString("") { byte ->
                val v = byte.toInt() and 0xff
                val hi = "0123456789abcdef"[v ushr 4]
                val lo = "0123456789abcdef"[v and 0x0f]
                "$hi$lo"
            }
        } finally {
            activeConn.compareAndSet(conn, null)
            conn.disconnect()
        }
    }

    private fun openHttpsFollowingRedirects(
        startUrl: String,
        accept: String,
        readTimeoutMs: Int,
        method: String = "GET",
        activeConn: AtomicReference<HttpURLConnection?>? = null,
        checkCancelled: () -> Unit = {},
    ): Pair<HttpURLConnection, String> {
        var currentUrl = startUrl
        repeat(MAX_REDIRECTS) {
            checkCancelled()
            check(currentUrl.startsWith("https://", ignoreCase = true)) { "仅允许通过 HTTPS 访问更新源" }
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                requestMethod = method
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = readTimeoutMs
                setRequestProperty("Accept", accept)
                setRequestProperty("User-Agent", userAgent())
            }
            activeConn?.set(conn)
            val code = conn.responseCode
            if (code in setOf(301, 302, 303, 307, 308)) {
                val loc = conn.getHeaderField("Location")?.trim().orEmpty()
                activeConn?.compareAndSet(conn, null)
                conn.disconnect()
                check(loc.isNotEmpty()) { "更新源重定向缺少目标地址" }
                val resolved = URL(URL(currentUrl), loc).toString()
                check(isTrustedGitHubReleaseUrl(resolved)) { "更新源重定向目标不受信任" }
                currentUrl = resolved
            } else {
                return conn to currentUrl
            }
        }
        error("更新源重定向次数过多")
    }
}
