package dev.lework.platform

import dev.lework.store.isTrustedGitHubReleaseUrl
import dev.lework.store.normalizeSha256Digest
import dev.lework.store.parseSha256Sums
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Fetches GitHub Releases over standard system TLS/proxy (not the self-signed Hub pinning client)
 * and provides SHA-256 verified one-click DMG download & in-place macOS update installation.
 */
internal object DesktopReleaseUpdater {
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val DOWNLOAD_READ_TIMEOUT_MS = 60_000
    private const val MAX_REDIRECTS = 6
    private const val MAX_DMG_BYTES = 512L * 1024L * 1024L
    private const val STREAM_BUFFER_BYTES = 32 * 1024

    private fun userAgent(): String = "Lework-Desktop/${appVersionLabel()}"

    private fun isMacOs(): Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("mac")

    fun canInstallMacDmg(): Boolean = isMacOs()

    suspend fun fetchLatestReleasePayload(
        apiUrl: String,
        fallbackWebUrl: String,
    ): Result<GitHubReleaseFetchPayload> = withContext(Dispatchers.IO) {
        try {
            if (!isTrustedGitHubReleaseUrl(apiUrl) || !isTrustedGitHubReleaseUrl(fallbackWebUrl)) {
                return@withContext Result.failure(IllegalArgumentException("更新源地址不受信任"))
            }
            val apiResult = runCatching { fetchTextHttps(apiUrl, accept = "application/vnd.github+json") }
            apiResult.getOrNull()?.let { (code, body, _) ->
                if (code in 200..299 && body.isNotBlank()) {
                    return@withContext Result.success(GitHubReleaseFetchPayload(jsonBody = body))
                }
            }

            // Fallback to GitHub web redirect (`/releases/latest` -> `/releases/tag/vX.Y.Z`)
            // which is immune to unauthenticated GitHub API IP rate limits (403/429).
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

    suspend fun installMacDmgUpdate(
        downloadUrl: String,
        expectedFileName: String,
        expectedSha256: String?,
        checksumsUrl: String?,
        onProgress: (fraction: Float?, statusText: String) -> Unit,
    ): Result<String> {
        if (!isMacOs()) {
            return Result.failure(UnsupportedOperationException("仅 macOS 桌面端支持自动下载安装 DMG"))
        }
        if (!isTrustedGitHubReleaseUrl(downloadUrl)) {
            return Result.failure(IllegalArgumentException("安装包下载地址不受信任"))
        }
        val safeName = sanitizeDmgFileName(expectedFileName)
        val stageResult = withContext(Dispatchers.IO) {
            try {
                stageAndApplyMacDmg(
                    downloadUrl = downloadUrl,
                    safeFileName = safeName,
                    expectedSha256 = normalizeSha256Digest(expectedSha256),
                    checksumsUrl = checksumsUrl?.takeIf { isTrustedGitHubReleaseUrl(it) },
                    onProgress = onProgress,
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
        return stageResult.map { outcome ->
            if (outcome.shouldRestartApp) {
                onProgress(1f, outcome.message)
                delay(450L)
                runCatching { JvmDesktopRuntime.shutdown() }
                exitProcess(0)
            }
            outcome.message
        }
    }

    private data class InstallOutcome(
        val message: String,
        val shouldRestartApp: Boolean,
    )

    private fun stageAndApplyMacDmg(
        downloadUrl: String,
        safeFileName: String,
        expectedSha256: String?,
        checksumsUrl: String?,
        onProgress: (fraction: Float?, statusText: String) -> Unit,
    ): Result<InstallOutcome> {
        var resolvedSha256 = expectedSha256
        if (resolvedSha256 == null && checksumsUrl != null) {
            onProgress(null, "正在获取安装包校验摘要…")
            val sumsRes = runCatching { fetchTextHttps(checksumsUrl, accept = "text/plain") }.getOrNull()
            if (sumsRes != null && sumsRes.code in 200..299) {
                resolvedSha256 = parseSha256Sums(sumsRes.body, safeFileName)
            }
        }

        val updatesBaseDir = File(System.getProperty("user.home"), ".lework/updates").apply { mkdirs() }
        val stageDir = File(updatesBaseDir, "stage-${System.currentTimeMillis()}").apply { mkdirs() }
        val dmgFile = File(stageDir, safeFileName)

        onProgress(0f, "正在下载 $safeFileName…")
        val actualSha256 = try {
            downloadFileHttps(downloadUrl, dmgFile) { downloaded, total ->
                val frac = if (total > 0L) (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f) else null
                val pct = frac?.let { "${(it * 100f).toInt()}%" }
                val mb = (downloaded / (1024L * 1024L)).coerceAtLeast(1L)
                val label = if (pct != null) {
                    "正在下载 $safeFileName ($pct)…"
                } else {
                    "正在下载 $safeFileName (${mb} MB)…"
                }
                onProgress(frac, label)
            }
        } catch (e: Exception) {
            stageDir.deleteRecursively()
            return Result.failure(e)
        }

        if (resolvedSha256 != null && !actualSha256.equals(resolvedSha256, ignoreCase = true)) {
            stageDir.deleteRecursively()
            return Result.failure(IllegalStateException("安装包 SHA-256 校验失败，已终止更新"))
        }

        val targetApp = File("/Applications/Lework.app")
        val canReplaceApplicationsApp =
            JvmDesktopRuntime.isEnabled &&
                isRunningFromApplicationsBundle() &&
                canWriteApplicationsTarget(targetApp)

        if (!canReplaceApplicationsApp) {
            val downloadsDir = File(System.getProperty("user.home"), "Downloads").apply { mkdirs() }
            val finalDmg = File(downloadsDir, safeFileName)
            runCatching {
                dmgFile.copyTo(finalDmg, overwrite = true)
            }.onFailure { err ->
                stageDir.deleteRecursively()
                return Result.failure(err)
            }
            stageDir.deleteRecursively()
            onProgress(1f, "校验通过，正在打开安装包…")
            runCatching { ProcessBuilder("open", finalDmg.absolutePath).start() }
            return Result.success(
                InstallOutcome(
                    message = "已完成校验并下载到「下载/$safeFileName」，请在弹出的窗口中将 Lework 拖入「应用程序」",
                    shouldRestartApp = false,
                ),
            )
        }

        onProgress(1f, "校验通过，正在挂载安装包…")
        val mountDir = File(stageDir, "mnt").apply { mkdirs() }
        val attachProc = ProcessBuilder(
            "hdiutil",
            "attach",
            "-nobrowse",
            "-readonly",
            "-mountpoint",
            mountDir.absolutePath,
            dmgFile.absolutePath,
        ).redirectErrorStream(true).start()
        val attachOut = attachProc.inputStream.bufferedReader().use { it.readText() }
        val attachOk = attachProc.waitFor(30, TimeUnit.SECONDS) && attachProc.exitValue() == 0
        if (!attachOk) {
            stageDir.deleteRecursively()
            return Result.failure(IllegalStateException("挂载安装包失败：${attachOut.take(180)}"))
        }

        val mountedApp = File(mountDir, "Lework.app")
        val mountedBin = File(mountedApp, "Contents/MacOS/Lework")
        if (!mountedBin.isFile) {
            runCatching { ProcessBuilder("hdiutil", "detach", mountDir.absolutePath, "-quiet").start().waitFor(10, TimeUnit.SECONDS) }
            stageDir.deleteRecursively()
            return Result.failure(IllegalStateException("安装包内未找到有效的 Lework.app"))
        }

        val currentPid = ProcessHandle.current().pid()
        val helperScript = File(stageDir, "apply-update.sh")
        helperScript.writeText(
            """
            |#!/bin/sh
            |set -eu
            |OLD_PID="$currentPid"
            |MOUNT_DIR=${shellQuote(mountDir.absolutePath)}
            |STAGE_DIR=${shellQuote(stageDir.absolutePath)}
            |TARGET_APP="/Applications/Lework.app"
            |i=0
            |while kill -0 "${'$'}OLD_PID" 2>/dev/null && [ "${'$'}i" -lt 40 ]; do
            |  sleep 0.25
            |  i=${'$'}((i + 1))
            |done
            |rsync -a --delete "${'$'}MOUNT_DIR/Lework.app/" "${'$'}TARGET_APP/"
            |xattr -dr com.apple.quarantine "${'$'}TARGET_APP" 2>/dev/null || true
            |touch "${'$'}TARGET_APP"
            |hdiutil detach "${'$'}MOUNT_DIR" -quiet 2>/dev/null || true
            |rm -rf "${'$'}STAGE_DIR" 2>/dev/null || true
            |open "${'$'}TARGET_APP"
            """.trimMargin() + "\n",
        )
        helperScript.setExecutable(true)

        ProcessBuilder(
            "/bin/sh",
            "-c",
            "nohup /bin/sh ${shellQuote(helperScript.absolutePath)} >/dev/null 2>&1 &",
        ).start()

        return Result.success(
            InstallOutcome(
                message = "校验通过，正在自动安装并重启 Lework…",
                shouldRestartApp = true,
            ),
        )
    }

    private fun isRunningFromApplicationsBundle(): Boolean {
        val cmd = ProcessHandle.current().info().command().orElse("")
        val resDir = System.getProperty("compose.application.resources.dir").orEmpty()
        return cmd.startsWith("/Applications/Lework.app/") || resDir.startsWith("/Applications/Lework.app/")
    }

    private fun canWriteApplicationsTarget(targetApp: File): Boolean {
        val parent = targetApp.parentFile ?: return false
        return if (targetApp.exists()) {
            targetApp.canWrite() && parent.canWrite()
        } else {
            parent.canWrite()
        }
    }

    private fun sanitizeDmgFileName(raw: String): String {
        val base = raw.trim().substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("""[^A-Za-z0-9._-]"""), "_")
            .ifEmpty { "Lework-update.dmg" }
        return if (base.lowercase().endsWith(".dmg")) base else "$base.dmg"
    }

    private fun shellQuote(path: String): String = "'" + path.replace("'", "'\"'\"'") + "'"

    private data class HttpsResponse(
        val code: Int,
        val body: String,
        val finalUrl: String,
    )

    private fun fetchTextHttps(url: String, accept: String): HttpsResponse {
        val (conn, finalUrl) = openHttpsFollowingRedirects(
            startUrl = url,
            accept = accept,
            readTimeoutMs = READ_TIMEOUT_MS,
        )
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return HttpsResponse(code = code, body = text, finalUrl = finalUrl)
        } finally {
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
        onChunk: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): String {
        val (conn, _) = openHttpsFollowingRedirects(
            startUrl = url,
            accept = "application/octet-stream",
            readTimeoutMs = DOWNLOAD_READ_TIMEOUT_MS,
        )
        try {
            val code = conn.responseCode
            check(code in 200..299) { "下载安装包失败（HTTP $code）" }
            val total = conn.contentLengthLong
            check(total <= 0L || total <= MAX_DMG_BYTES) { "安装包体积异常，已取消下载" }
            val digest = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(STREAM_BUFFER_BYTES)
            var downloaded = 0L
            var lastReported = 0L
            conn.inputStream.use { input ->
                FileOutputStream(target).use { out ->
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        downloaded += read
                        check(downloaded <= MAX_DMG_BYTES) { "安装包体积超出安全限制，已终止下载" }
                        out.write(buf, 0, read)
                        digest.update(buf, 0, read)
                        if (downloaded - lastReported >= 256L * 1024L || (total > 0L && downloaded == total)) {
                            lastReported = downloaded
                            onChunk(downloaded, total)
                        }
                    }
                }
            }
            return digest.digest().joinToString("") { byte ->
                val v = byte.toInt() and 0xff
                val hi = "0123456789abcdef"[v ushr 4]
                val lo = "0123456789abcdef"[v and 0x0f]
                "$hi$lo"
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun openHttpsFollowingRedirects(
        startUrl: String,
        accept: String,
        readTimeoutMs: Int,
        method: String = "GET",
    ): Pair<HttpURLConnection, String> {
        var currentUrl = startUrl
        repeat(MAX_REDIRECTS) {
            check(currentUrl.startsWith("https://", ignoreCase = true)) { "仅允许通过 HTTPS 访问更新源" }
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                requestMethod = method
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = readTimeoutMs
                setRequestProperty("Accept", accept)
                setRequestProperty("User-Agent", userAgent())
            }
            val code = conn.responseCode
            if (code in setOf(301, 302, 303, 307, 308)) {
                val loc = conn.getHeaderField("Location")?.trim().orEmpty()
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
