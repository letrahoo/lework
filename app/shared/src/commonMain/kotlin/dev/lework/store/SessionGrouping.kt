package dev.lework.store

import dev.lework.protocol.SessionMeta
import dev.lework.protocol.SessionState
import dev.lework.protocol.Workspace

enum class SidebarGroupingMode {
    PROJECT,
    TIME,
}

const val FALLBACK_PROJECT_GROUP_KEY = "__other_workspace__"
private const val FALLBACK_PROJECT_GROUP_NAME = "通用 / 主目录"

private val GENERIC_CONTAINER_NAMES = setOf(
    "users",
    "home",
    "root",
    "work",
    "personal",
    "projects",
    "project",
    "repos",
    "repo",
    "code",
    "codes",
    "my-projects",
    "developer",
    "workspace",
    "workspaces",
    "desktop",
    "documents",
    "downloads",
)

data class SessionProjectGroup(
    val key: String,
    val displayName: String,
    val fullPath: String,
    val compactPath: String,
    val isFallback: Boolean,
    val sessions: List<SessionMeta>,
    val latestUpdatedAt: Long,
    val runningCount: Int,
    val waitingPermissionCount: Int,
    val draftCount: Int,
)

internal fun normalizeProjectPath(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ""
    val unified = trimmed.replace('\\', '/').replace(Regex("/+"), "/")
    if (unified == "/") return "/"
    return unified.trimEnd('/')
}

internal fun isGenericOrHomeProjectPath(normalizedPath: String): Boolean {
    if (normalizedPath.isEmpty() || normalizedPath == "/" || normalizedPath == "~") return true
    val parts = normalizedPath.trimStart('/').split('/').filter { it.isNotEmpty() }
    if (parts.isEmpty()) return true
    val first = parts[0].lowercase()
    // `/Users/<name>` or `/home/<name>`
    if ((first == "users" || first == "home") && parts.size <= 2) return true
    // `/Users/<name>/<genericBucket>` or `/home/<name>/<genericBucket>`
    val last = parts.last().lowercase()
    if ((first == "users" || first == "home") && parts.size == 3 && last in GENERIC_CONTAINER_NAMES) {
        return true
    }
    if (parts.size == 1 && last in GENERIC_CONTAINER_NAMES) return true
    return false
}

internal fun compactProjectDisplayPath(normalizedPath: String): String {
    if (normalizedPath.isEmpty()) return ""
    val match = Regex("^/(?:Users|home)/[^/]+(/.*)?$").matchEntire(normalizedPath)
    if (match != null) {
        val rest = match.groupValues.getOrNull(1).orEmpty()
        return if (rest.isEmpty()) "~" else "~$rest"
    }
    return normalizedPath
}

internal fun resolveSessionPrimaryProjectPath(session: SessionMeta): String {
    val raw = session.worktree?.baseRepoPath?.trim()?.takeIf { it.isNotEmpty() }
        ?: session.workspaces?.firstOrNull { it.isNotBlank() }?.trim()
        ?: session.cwd.trim()
    return normalizeProjectPath(raw)
}

internal fun resolveSessionProjectGroupKey(
    session: SessionMeta,
    knownProjectRoots: Set<String>,
): String {
    val normalized = resolveSessionPrimaryProjectPath(session)
    if (isGenericOrHomeProjectPath(normalized)) {
        return FALLBACK_PROJECT_GROUP_KEY
    }
    // Roll subdirectories (e.g. `.../lework/hub`) up into the shortest specific known project root (`.../lework`)
    val ancestor = knownProjectRoots
        .asSequence()
        .filter { root ->
            root.length < normalized.length &&
                normalized.startsWith("$root/") &&
                !isGenericOrHomeProjectPath(root)
        }
        .minByOrNull { it.length }
    return ancestor ?: normalized
}

internal fun collectKnownProjectRoots(
    sessions: List<SessionMeta>,
    workspaces: List<Workspace>,
): Set<String> {
    val out = LinkedHashSet<String>()
    for (ws in workspaces) {
        val norm = normalizeProjectPath(ws.path)
        if (!isGenericOrHomeProjectPath(norm)) {
            out.add(norm)
        }
    }
    for (sess in sessions) {
        val norm = resolveSessionPrimaryProjectPath(sess)
        if (!isGenericOrHomeProjectPath(norm)) {
            out.add(norm)
        }
    }
    return out
}

internal fun buildSessionProjectGroups(
    sessions: List<SessionMeta>,
    workspaces: List<Workspace> = emptyList(),
    hasDraftForSession: (String) -> Boolean = { false },
    prioritizeActiveProjects: Boolean = true,
): List<SessionProjectGroup> {
    if (sessions.isEmpty()) return emptyList()
    val knownRoots = collectKnownProjectRoots(sessions, workspaces)
    val buckets = LinkedHashMap<String, MutableList<SessionMeta>>()
    for (sess in sessions) {
        val key = resolveSessionProjectGroupKey(sess, knownRoots)
        buckets.getOrPut(key) { mutableListOf() }.add(sess)
    }

    // Disambiguate colliding basenames across distinct project paths (e.g. `work/app` vs `personal/app`)
    val baseNameCounts = mutableMapOf<String, Int>()
    for (key in buckets.keys) {
        if (key == FALLBACK_PROJECT_GROUP_KEY) continue
        val base = key.substringAfterLast('/').lowercase()
        baseNameCounts[base] = (baseNameCounts[base] ?: 0) + 1
    }

    return buckets.entries.map { (key, list) ->
        val isFallback = key == FALLBACK_PROJECT_GROUP_KEY
        val sortedSessions = list.sortedWith(
            compareBy<SessionMeta> { sidebarSessionStatePriority(it.state) }
                .thenBy { if (hasDraftForSession(it.id)) 0 else 1 }
                .thenByDescending { it.updatedAt },
        )
        val displayName = if (isFallback) {
            FALLBACK_PROJECT_GROUP_NAME
        } else {
            val base = key.substringAfterLast('/').ifBlank { key }
            val count = baseNameCounts[base.lowercase()] ?: 0
            if (count > 1) {
                val parent = key.substringBeforeLast('/', "").substringAfterLast('/')
                if (parent.isNotBlank()) "$parent/$base" else base
            } else {
                base
            }
        }
        val runningCount = sortedSessions.count { it.state == SessionState.RUNNING }
        val waitingCount = sortedSessions.count { it.state == SessionState.WAITING_PERMISSION }
        val draftCount = sortedSessions.count { hasDraftForSession(it.id) }
        SessionProjectGroup(
            key = key,
            displayName = displayName,
            fullPath = if (isFallback) "" else key,
            compactPath = if (isFallback) "" else compactProjectDisplayPath(key),
            isFallback = isFallback,
            sessions = sortedSessions,
            latestUpdatedAt = sortedSessions.maxOfOrNull { it.updatedAt } ?: 0L,
            runningCount = runningCount,
            waitingPermissionCount = waitingCount,
            draftCount = draftCount,
        )
    }.sortedWith(
        compareBy<SessionProjectGroup> { if (it.isFallback) 1 else 0 }
            .thenBy { group ->
                if (!prioritizeActiveProjects) {
                    0
                } else {
                    when {
                        group.waitingPermissionCount > 0 -> 0
                        group.runningCount > 0 -> 1
                        else -> 2
                    }
                }
            }
            .thenByDescending { it.latestUpdatedAt }
            .thenBy { it.displayName.lowercase() },
    )
}

internal fun sidebarSessionStatePriority(state: SessionState): Int = when (state) {
    SessionState.WAITING_PERMISSION -> 0
    SessionState.RUNNING -> 1
    else -> 2
}

/**
 * Splits a leading `<Scope>-` prefix from a structured title when the session is rendered inside
 * the matching project group, allowing the UI to subtly deemphasize the repeated scope segment
 * while preserving the complete `<Scope>-<Module>-<Feature>` string.
 */
internal fun splitMatchingScopePrefix(
    title: String,
    groupDisplayName: String?,
): Pair<String, String>? {
    if (groupDisplayName.isNullOrBlank()) return null
    val scopeLeaf = groupDisplayName.substringAfterLast('/').trim()
    if (scopeLeaf.isEmpty() || title.length <= scopeLeaf.length + 1) return null
    val prefix = title.substring(0, scopeLeaf.length)
    val sep = title[scopeLeaf.length]
    if (!prefix.equals(scopeLeaf, ignoreCase = true)) return null
    if (sep != '-' && sep != '·' && sep != '/') return null
    val rest = title.substring(scopeLeaf.length + 1).trimStart()
    if (rest.isEmpty()) return null
    return title.substring(0, scopeLeaf.length + 1) to rest
}
