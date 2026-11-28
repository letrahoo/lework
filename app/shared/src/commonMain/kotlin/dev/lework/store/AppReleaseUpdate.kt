package dev.lework.store

import dev.lework.platform.GitHubReleaseFetchPayload
import dev.lework.platform.appVersionLabel
import dev.lework.protocol.ProtocolJson
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal const val LEWORK_GITHUB_REPO = "letrahoo/lework"
internal const val LEWORK_RELEASES_API_URL = "https://api.github.com/repos/$LEWORK_GITHUB_REPO/releases/latest"
internal const val LEWORK_RELEASES_LATEST_WEB_URL = "https://github.com/$LEWORK_GITHUB_REPO/releases/latest"

private val SEMVER_REGEX = Regex("""(?<![0-9.])v?(\d+)\.(\d+)\.(\d+)(?![0-9.])""")
private val RELEASE_TAG_URL_REGEX = Regex("""/releases/tag/(v?\d+\.\d+\.\d+)(?:[/?#]|$)""")
private val SHA256_HEX_REGEX = Regex("""^[0-9a-f]{64}$""")
private val TRUSTED_GITHUB_HOSTS = setOf(
    "github.com",
    "api.github.com",
    "objects.githubusercontent.com",
    "release-assets.githubusercontent.com",
)

data class SemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<SemVer> {
    val label: String
        get() = "$major.$minor.$patch"

    val tagName: String
        get() = "v$label"

    override fun compareTo(other: SemVer): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        return patch.compareTo(other.patch)
    }

    override fun toString(): String = label
}

data class ReleaseAssetInfo(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long = 0L,
    val sha256: String? = null,
)

data class AppReleaseInfo(
    val tagName: String,
    val version: SemVer,
    val title: String,
    val htmlUrl: String,
    val publishedAt: String? = null,
    val releaseNotes: String = "",
    val macDmgAsset: ReleaseAssetInfo? = null,
    val androidApkAsset: ReleaseAssetInfo? = null,
    val checksumsUrl: String? = null,
)

data class AppUpdateState(
    val currentVersion: String = currentAppVersionString(),
    val checking: Boolean = false,
    val latestRelease: AppReleaseInfo? = null,
    val hasUpdate: Boolean = false,
    val dismissedTag: String? = null,
    val lastCheckedAtMs: Long = 0L,
    val checkError: String? = null,
    val installing: Boolean = false,
    val installProgressFraction: Float? = null,
    val installStatusText: String? = null,
    val installError: String? = null,
    val showUpdateDialog: Boolean = false,
) {
    val hasVisibleBanner: Boolean
        get() = hasUpdate && latestRelease != null && dismissedTag != latestRelease.tagName
}

internal fun parseSemVer(raw: String?): SemVer? {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return null
    val match = SEMVER_REGEX.find(text) ?: return null
    val major = match.groupValues[1].toIntOrNull() ?: return null
    val minor = match.groupValues[2].toIntOrNull() ?: return null
    val patch = match.groupValues[3].toIntOrNull() ?: return null
    return SemVer(major, minor, patch)
}

internal fun currentAppSemVer(): SemVer? = parseSemVer(appVersionLabel())

internal fun currentAppVersionString(): String = currentAppSemVer()?.tagName ?: appVersionLabel()

internal fun isTrustedGitHubReleaseUrl(url: String?): Boolean {
    val clean = url?.trim().orEmpty()
    if (!clean.startsWith("https://", ignoreCase = true)) return false
    val authority = clean.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
    if (authority.isEmpty() || authority.contains('@')) return false
    val host = authority.substringBefore(':').lowercase()
    return host in TRUSTED_GITHUB_HOSTS
}

internal fun normalizeSha256Digest(raw: String?): String? {
    val clean = raw?.trim()?.removePrefix("sha256:")?.removePrefix("SHA256:")?.trim()?.lowercase().orEmpty()
    return clean.takeIf { SHA256_HEX_REGEX.matches(it) }
}

internal fun parseSha256Sums(content: String, fileName: String): String? {
    val targetName = fileName.trim()
    if (targetName.isEmpty()) return null
    for (rawLine in content.lineSequence()) {
        val line = rawLine.trim()
        if (line.isEmpty() || line.startsWith("#")) continue
        val sep = line.indexOfFirst { it.isWhitespace() }
        if (sep <= 0) continue
        val hash = normalizeSha256Digest(line.substring(0, sep)) ?: continue
        val name = line.substring(sep).trim().removePrefix("*").substringAfterLast('/')
        if (name == targetName) {
            return hash
        }
    }
    return null
}

internal fun resolveApkCandidateFromSha256Sums(
    content: String,
    preferredFileName: String,
): Pair<String, String>? {
    val preferred = preferredFileName.trim()
    val entries = mutableListOf<Pair<String, String>>()
    for (rawLine in content.lineSequence()) {
        val line = rawLine.trim()
        if (line.isEmpty() || line.startsWith("#")) continue
        val sep = line.indexOfFirst { it.isWhitespace() }
        if (sep <= 0) continue
        val hash = normalizeSha256Digest(line.substring(0, sep)) ?: continue
        val name = line.substring(sep).trim().removePrefix("*").substringAfterLast('/')
        if (name.isEmpty()) continue
        if (preferred.isNotEmpty() && name == preferred) {
            return name to hash
        }
        if (name.lowercase().endsWith(".apk")) {
            entries.add(name to hash)
        }
    }
    return entries.firstOrNull { it.first.lowercase().contains("release") } ?: entries.firstOrNull()
}

internal fun formatReleaseSizeMb(sizeBytes: Long): String? {
    if (sizeBytes <= 0L) return null
    val tenths = ((sizeBytes * 10L) / (1024L * 1024L)).coerceAtLeast(1L)
    return "${tenths / 10L}.${tenths % 10L} MB"
}

internal fun parseGitHubLatestReleaseJson(jsonBody: String): AppReleaseInfo? {
    val root = runCatching { ProtocolJson.parseToJsonElement(jsonBody).jsonObject }.getOrNull() ?: return null
    if (root["draft"]?.jsonPrimitive?.booleanOrNull == true) return null
    if (root["prerelease"]?.jsonPrimitive?.booleanOrNull == true) return null

    val tagName = root["tag_name"]?.jsonPrimitive?.content?.trim().orEmpty()
    val semVer = parseSemVer(tagName) ?: return null
    val normalizedTag = semVer.tagName
    val htmlUrl = root["html_url"]?.jsonPrimitive?.content?.trim()
        ?.takeIf { isTrustedGitHubReleaseUrl(it) }
        ?: "https://github.com/$LEWORK_GITHUB_REPO/releases/tag/$normalizedTag"
    val title = root["name"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() } ?: "Lework $normalizedTag"
    val publishedAt = root["published_at"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
    val releaseNotes = root["body"]?.jsonPrimitive?.content?.trim().orEmpty()

    val assets = root["assets"]?.jsonArray.orEmpty().mapNotNull { el ->
        val obj = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
        val name = obj["name"]?.jsonPrimitive?.content?.trim().orEmpty()
        val downloadUrl = obj["browser_download_url"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (name.isEmpty() || !isTrustedGitHubReleaseUrl(downloadUrl)) return@mapNotNull null
        val sizeBytes = obj["size"]?.jsonPrimitive?.longOrNull ?: 0L
        val sha256 = normalizeSha256Digest(obj["digest"]?.jsonPrimitive?.content)
        ReleaseAssetInfo(
            name = name,
            downloadUrl = downloadUrl,
            sizeBytes = sizeBytes,
            sha256 = sha256,
        )
    }

    val macDmgAsset = assets.firstOrNull {
        val lower = it.name.lowercase()
        lower.endsWith(".dmg") && (lower.contains("arm64") || lower.contains("macos"))
    } ?: assets.firstOrNull { it.name.lowercase().endsWith(".dmg") }

    val androidApkAsset = assets.firstOrNull {
        val lower = it.name.lowercase()
        lower.endsWith(".apk") && lower.contains("release")
    } ?: assets.firstOrNull { it.name.lowercase().endsWith(".apk") }

    val checksumsUrl = assets.firstOrNull {
        it.name.equals("SHA256SUMS.txt", ignoreCase = true)
    }?.downloadUrl

    return AppReleaseInfo(
        tagName = normalizedTag,
        version = semVer,
        title = title,
        htmlUrl = htmlUrl,
        publishedAt = publishedAt,
        releaseNotes = releaseNotes,
        macDmgAsset = macDmgAsset,
        androidApkAsset = androidApkAsset,
        checksumsUrl = checksumsUrl,
    )
}

internal fun parseGitHubRedirectReleaseTag(finalUrl: String): AppReleaseInfo? {
    val cleanUrl = finalUrl.trim()
    if (!isTrustedGitHubReleaseUrl(cleanUrl)) return null
    val match = RELEASE_TAG_URL_REGEX.find(cleanUrl) ?: return null
    val semVer = parseSemVer(match.groupValues[1]) ?: return null
    val tag = semVer.tagName
    val dmgName = "Lework-$tag-macos-arm64.dmg"
    val apkName = "lework-$tag-android-release.apk"
    val baseDownload = "https://github.com/$LEWORK_GITHUB_REPO/releases/download/$tag"
    return AppReleaseInfo(
        tagName = tag,
        version = semVer,
        title = "Lework $tag",
        htmlUrl = "https://github.com/$LEWORK_GITHUB_REPO/releases/tag/$tag",
        publishedAt = null,
        releaseNotes = "已检测到最新正式版本 **$tag**。点击下方按钮可直接下载安装包或前往 GitHub 查看完整变更说明。",
        macDmgAsset = ReleaseAssetInfo(
            name = dmgName,
            downloadUrl = "$baseDownload/$dmgName",
        ),
        androidApkAsset = ReleaseAssetInfo(
            name = apkName,
            downloadUrl = "$baseDownload/$apkName",
        ),
        checksumsUrl = "$baseDownload/SHA256SUMS.txt",
    )
}

internal fun resolveGitHubReleasePayload(payload: GitHubReleaseFetchPayload): AppReleaseInfo? {
    payload.jsonBody?.takeIf { it.isNotBlank() }?.let { json ->
        parseGitHubLatestReleaseJson(json)?.let { return it }
    }
    payload.resolvedWebUrl?.takeIf { it.isNotBlank() }?.let { url ->
        parseGitHubRedirectReleaseTag(url)?.let { return it }
    }
    return null
}

internal fun formatReleasePublishedDate(publishedAt: String?): String? {
    val clean = publishedAt?.trim().orEmpty()
    if (clean.length < 10) return null
    return clean.substring(0, 10)
}

internal fun extractReleaseHighlightSummary(releaseNotes: String): String? {
    val lines = releaseNotes.lineSequence()
        .map { it.trim() }
        .filter { it.startsWith("* ") || it.startsWith("- ") }
        .map { line ->
            line.removePrefix("* ").removePrefix("- ")
                .substringBefore(" by @")
                .trim()
        }
        .filter { it.isNotBlank() }
        .toList()
    return lines.firstOrNull()?.take(96)
}
