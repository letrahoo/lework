package dev.lework.platform

import dev.lework.protocol.ProtocolJson
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class CliInstallerEnv(
    val osName: String = System.getProperty("os.name").orEmpty(),
    val userHome: File = File(System.getProperty("user.home")),
    val userDir: File = File(System.getProperty("user.dir")),
    val env: Map<String, String> = System.getenv(),
    val appResourcesDir: String? = System.getProperty("compose.application.resources.dir"),
    val systemBinDirs: List<File> = listOf(File("/opt/homebrew/bin"), File("/usr/local/bin")),
    val etcPathsFile: File = File("/etc/paths"),
    val etcPathsDir: File = File("/etc/paths.d"),
)

/**
 * Installs and manages the global `lework` CLI symlink on macOS / Linux desktops so users can run
 * `lework tsnet status`, `lework pair`, `lework serve`, etc. from any terminal without `node ./hub/bin/hub.js`.
 */
internal object DesktopCliInstaller {
    private const val CLI_COMMAND_NAME = "lework"
    private const val LAUNCHER_RELATIVE_PATH = "hub/bin/hub.js"

    private val PLIST_WORKING_DIR_REGEX =
        Regex("""<key>WorkingDirectory</key>\s*<string>([^<]+)</string>""")

    fun isSupported(env: CliInstallerEnv = CliInstallerEnv()): Boolean {
        val os = env.osName.lowercase()
        return !os.contains("win")
    }

    fun inspectStatus(env: CliInstallerEnv = CliInstallerEnv()): GlobalCliInstallStatus {
        if (!isSupported(env)) {
            return GlobalCliInstallStatus(
                supported = false,
                detail = "当前系统暂不支持通过桌面端注册全局命令行",
            )
        }
        val launcher = resolveCliLauncherTarget(env)
        val userLocalBin = File(env.userHome, ".local/bin")
        val orderedDirs = (env.systemBinDirs + userLocalBin).distinctBy { it.absolutePath }

        var fallbackInstalled: Pair<File, File>? = null
        for (dir in orderedDirs) {
            val linkFile = File(dir, CLI_COMMAND_NAME)
            val resolved = resolveManagedLinkTarget(linkFile) ?: continue
            val inPath = isDirectoryInShellPath(dir, env)
            if (inPath) {
                return GlobalCliInstallStatus(
                    supported = true,
                    installed = true,
                    installedPath = linkFile.absolutePath,
                    launcherTargetPath = resolved.absolutePath,
                    inShellPath = true,
                    installDirectory = dir.absolutePath,
                    detail = "已注册全局命令，可在任意终端直接运行 lework",
                )
            }
            if (fallbackInstalled == null) {
                fallbackInstalled = linkFile to resolved
            }
        }

        if (fallbackInstalled != null) {
            val (linkFile, resolved) = fallbackInstalled
            val dir = linkFile.parentFile
            return GlobalCliInstallStatus(
                supported = true,
                installed = true,
                installedPath = linkFile.absolutePath,
                launcherTargetPath = resolved.absolutePath,
                inShellPath = false,
                installDirectory = dir.absolutePath,
                detail = "已写入 ${linkFile.absolutePath}，但所在目录尚未加入 shell PATH",
            )
        }

        return GlobalCliInstallStatus(
            supported = true,
            installed = false,
            launcherTargetPath = launcher?.absolutePath,
            inShellPath = false,
            installDirectory = choosePreferredTargetDir(env)?.absolutePath ?: userLocalBin.absolutePath,
            detail = if (launcher != null) {
                "点击按钮将 lework 注册到全局命令行目录"
            } else {
                "未自动定位到本机 Lework 仓库中的 $LAUNCHER_RELATIVE_PATH，请先在项目目录运行 pnpm setup"
            },
        )
    }

    suspend fun install(env: CliInstallerEnv = CliInstallerEnv()): Result<GlobalCliInstallStatus> {
        if (!isSupported(env)) {
            return Result.failure(UnsupportedOperationException("当前系统暂不支持通过桌面端注册全局命令行"))
        }
        return withContext(Dispatchers.IO) {
            try {
                installSync(env)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun uninstall(env: CliInstallerEnv = CliInstallerEnv()): Result<GlobalCliInstallStatus> {
        if (!isSupported(env)) {
            return Result.failure(UnsupportedOperationException("当前系统暂不支持通过桌面端移除全局命令行"))
        }
        return withContext(Dispatchers.IO) {
            try {
                uninstallSync(env)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    internal fun installSync(env: CliInstallerEnv = CliInstallerEnv()): Result<GlobalCliInstallStatus> {
        val launcher = resolveCliLauncherTarget(env)
            ?: return Result.failure(
                IllegalStateException(
                    "未找到本机 Lework 命令行入口（$LAUNCHER_RELATIVE_PATH）。请确认本机已克隆 Lework 仓库，或在环境变量 LEWORK_REPO_ROOT 中指定仓库目录。",
                ),
            )

        ensureExecutablePermissions(launcher)

        val userLocalBin = File(env.userHome, ".local/bin")
        val installedLinks = mutableListOf<File>()
        val targetSystemDir = env.systemBinDirs.firstOrNull { dir ->
            dir.isDirectory && isWritableDirectory(dir) && canSafelyManageLink(File(dir, CLI_COMMAND_NAME))
        }

        val dirsToInstall = buildList {
            if (targetSystemDir != null) add(targetSystemDir)
            if (targetSystemDir?.absolutePath != userLocalBin.absolutePath) add(userLocalBin)
        }

        var lastError: Throwable? = null
        for (dir in dirsToInstall) {
            val linkFile = File(dir, CLI_COMMAND_NAME)
            try {
                if (!dir.exists()) {
                    dir.mkdirs()
                }
                if (!dir.isDirectory || !canSafelyManageLink(linkFile)) {
                    continue
                }
                writeSymlink(linkFile.toPath(), launcher.toPath())
                installedLinks.add(linkFile)
            } catch (e: Exception) {
                lastError = e
            }
        }

        if (installedLinks.isEmpty()) {
            val reason = lastError?.message ?: "目标目录无写入权限或已存在非 Lework 同名文件"
            return Result.failure(IllegalStateException("注册全局 lework 命令失败：$reason"))
        }

        val primary = installedLinks.firstOrNull { isDirectoryInShellPath(it.parentFile, env) }
            ?: installedLinks.first()
        val inPath = isDirectoryInShellPath(primary.parentFile, env)
        val status = GlobalCliInstallStatus(
            supported = true,
            installed = true,
            installedPath = primary.absolutePath,
            launcherTargetPath = launcher.absolutePath,
            inShellPath = inPath,
            installDirectory = primary.parentFile.absolutePath,
            detail = if (inPath) {
                "已注册到 ${primary.absolutePath}，重开或当前终端可直接运行 lework"
            } else {
                "已注册到 ${primary.absolutePath}，将该目录加入 PATH 后即可全局运行 lework"
            },
        )
        logRuntimeEvent("CLI", "registered global lework path=${primary.absolutePath} target=${launcher.absolutePath} inPath=$inPath")
        return Result.success(status)
    }

    internal fun uninstallSync(env: CliInstallerEnv = CliInstallerEnv()): Result<GlobalCliInstallStatus> {
        val userLocalBin = File(env.userHome, ".local/bin")
        val allDirs = (env.systemBinDirs + userLocalBin).distinctBy { it.absolutePath }
        var removedCount = 0
        for (dir in allDirs) {
            val linkFile = File(dir, CLI_COMMAND_NAME)
            val linkPath = linkFile.toPath()
            if (Files.isSymbolicLink(linkPath) && isLeworkManagedSymlink(linkPath)) {
                Files.deleteIfExists(linkPath)
                removedCount++
            }
        }
        logRuntimeEvent("CLI", "uninstalled global lework symlinks count=$removedCount")
        return Result.success(inspectStatus(env))
    }

    internal fun resolveCliLauncherTarget(env: CliInstallerEnv = CliInstallerEnv()): File? {
        val candidates = mutableListOf<File>()

        env.env["LEWORK_REPO_ROOT"]?.trim()?.takeIf { it.isNotEmpty() }?.let { root ->
            candidates.add(File(root, LAUNCHER_RELATIVE_PATH))
        }

        val existingLinkDirs = (env.systemBinDirs + File(env.userHome, ".local/bin")).distinctBy { it.absolutePath }
        for (dir in existingLinkDirs) {
            resolveManagedLinkTarget(File(dir, CLI_COMMAND_NAME))?.let { candidates.add(it) }
        }

        candidates.add(File(env.userDir, LAUNCHER_RELATIVE_PATH))
        env.userDir.parentFile?.let { candidates.add(File(it, LAUNCHER_RELATIVE_PATH)) }

        env.appResourcesDir?.trim()?.takeIf { it.isNotEmpty() }?.let { resDir ->
            candidates.add(File(resDir, LAUNCHER_RELATIVE_PATH))
        }

        for (wd in discoverLaunchAgentRepoRoots(env.userHome)) {
            candidates.add(File(wd, LAUNCHER_RELATIVE_PATH))
        }

        for (root in discoverConfiguredWorkspaceRoots(env)) {
            candidates.add(File(root, LAUNCHER_RELATIVE_PATH))
            candidates.add(File(root, "lework/$LAUNCHER_RELATIVE_PATH"))
        }

        val commonRepoDirs = listOf(
            File(env.userHome, "lework"),
            File(env.userHome, "personal/lework"),
            File(env.userHome, "Projects/lework"),
            File(env.userHome, "Developer/lework"),
            File(env.userHome, "code/lework"),
            File(env.userHome, "work/lework"),
            File(env.userHome, "my-projects/lework"),
        )
        for (repo in commonRepoDirs) {
            candidates.add(File(repo, LAUNCHER_RELATIVE_PATH))
        }

        for (candidate in candidates) {
            if (candidate.isFile) {
                return runCatching { candidate.canonicalFile }.getOrDefault(candidate.absoluteFile)
            }
        }
        return null
    }

    internal fun isDirectoryInShellPath(dir: File, env: CliInstallerEnv = CliInstallerEnv()): Boolean {
        val targetNorm = normalizeDirPath(dir)
        val envPathEntries = env.env["PATH"].orEmpty()
            .split(File.pathSeparatorChar)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { normalizeDirPath(File(expandHome(it, env.userHome))) }
        if (targetNorm in envPathEntries) return true

        val systemPaths = mutableListOf<String>()
        if (env.etcPathsFile.isFile) {
            runCatching { systemPaths.addAll(env.etcPathsFile.readLines()) }
        }
        if (env.etcPathsDir.isDirectory) {
            runCatching {
                env.etcPathsDir.listFiles()?.filter { it.isFile }?.forEach { file ->
                    systemPaths.addAll(file.readLines())
                }
            }
        }
        val normalizedSysPaths = systemPaths
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { normalizeDirPath(File(it)) }
        if (targetNorm in normalizedSysPaths) return true

        val homeNorm = normalizeDirPath(env.userHome)
        val relHomePath = if (targetNorm.startsWith("$homeNorm/")) {
            targetNorm.removePrefix("$homeNorm/")
        } else {
            null
        }
        val rcFiles = listOf(".zprofile", ".zshrc", ".bash_profile", ".bashrc", ".profile")
            .map { File(env.userHome, it) }
            .filter { it.isFile }
        for (rc in rcFiles) {
            val content = runCatching { rc.readText() }.getOrNull() ?: continue
            if (targetNorm in content) return true
            if (targetNorm == "/opt/homebrew/bin" && ("/opt/homebrew/bin/brew" in content || "brew shellenv" in content)) {
                return true
            }
            if (relHomePath != null && (relHomePath in content)) {
                return true
            }
        }
        return false
    }

    private fun choosePreferredTargetDir(env: CliInstallerEnv): File? =
        env.systemBinDirs.firstOrNull { dir ->
            dir.isDirectory && isWritableDirectory(dir) && canSafelyManageLink(File(dir, CLI_COMMAND_NAME))
        }

    private fun discoverLaunchAgentRepoRoots(userHome: File): List<File> {
        val agentsDir = File(userHome, "Library/LaunchAgents")
        if (!agentsDir.isDirectory) return emptyList()
        val roots = mutableListOf<File>()
        val plistNames = listOf(
            "dev.lework.hub-slave.plist",
            "dev.lework.hub-master.plist",
            "dev.lework.hub-standalone.plist",
        )
        for (name in plistNames) {
            val file = File(agentsDir, name)
            if (!file.isFile) continue
            val text = runCatching { file.readText() }.getOrNull() ?: continue
            val match = PLIST_WORKING_DIR_REGEX.find(text) ?: continue
            val wd = match.groupValues[1].trim()
            if (wd.isNotEmpty()) {
                roots.add(File(wd))
            }
        }
        return roots
    }

    private fun discoverConfiguredWorkspaceRoots(env: CliInstallerEnv): List<File> {
        val leworkHome = env.env["LEWORK_HOME"]?.trim()?.takeIf { it.isNotEmpty() }?.let { File(it) }
            ?: File(env.userHome, ".lework")
        val cfgFile = File(leworkHome, "config.json")
        if (!cfgFile.isFile) return emptyList()
        return runCatching {
            val obj = ProtocolJson.parseToJsonElement(cfgFile.readText()).jsonObject
            obj["workspaceRoots"]?.jsonArray?.mapNotNull { el ->
                el.jsonPrimitive.content.trim().takeIf { it.isNotEmpty() }?.let { File(it) }
            }.orEmpty()
        }.getOrDefault(emptyList())
    }

    private fun resolveManagedLinkTarget(linkFile: File): File? {
        val path = linkFile.toPath()
        if (!Files.isSymbolicLink(path)) return null
        if (!isLeworkManagedSymlink(path)) return null
        return runCatching {
            val rawTarget = Files.readSymbolicLink(path)
            val resolved = if (rawTarget.isAbsolute) rawTarget else path.parent.resolve(rawTarget)
            val file = resolved.toFile().canonicalFile
            file.takeIf { it.isFile }
        }.getOrNull()
    }

    private fun isLeworkManagedSymlink(linkPath: Path): Boolean {
        val rawTarget = runCatching { Files.readSymbolicLink(linkPath) }.getOrNull() ?: return false
        val normalized = rawTarget.toString().replace('\\', '/')
        return normalized.endsWith("/$LAUNCHER_RELATIVE_PATH") || normalized == LAUNCHER_RELATIVE_PATH
    }

    private fun canSafelyManageLink(linkFile: File): Boolean {
        val path = linkFile.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return true
        if (!Files.isSymbolicLink(path)) return false
        if (isLeworkManagedSymlink(path)) return true
        // Replace broken/dangling symlinks only if they previously pointed to a lework path.
        val targetStr = runCatching { Files.readSymbolicLink(path).toString().lowercase() }.getOrDefault("")
        val resolvedExists = runCatching { Files.exists(path) }.getOrDefault(false)
        return !resolvedExists && "lework" in targetStr
    }

    private fun writeSymlink(linkPath: Path, targetPath: Path) {
        Files.deleteIfExists(linkPath)
        Files.createSymbolicLink(linkPath, targetPath)
    }

    private fun isWritableDirectory(dir: File): Boolean =
        runCatching { dir.isDirectory && Files.isWritable(dir.toPath()) }.getOrDefault(false)

    private fun ensureExecutablePermissions(file: File) {
        runCatching {
            val perms = Files.getPosixFilePermissions(file.toPath()).toMutableSet()
            perms.add(PosixFilePermission.OWNER_EXECUTE)
            perms.add(PosixFilePermission.GROUP_EXECUTE)
            perms.add(PosixFilePermission.OTHERS_EXECUTE)
            Files.setPosixFilePermissions(file.toPath(), perms)
        }.onFailure {
            file.setExecutable(true, false)
        }
    }

    private fun normalizeDirPath(file: File): String =
        runCatching { file.canonicalFile.absolutePath }.getOrDefault(file.absolutePath).trimEnd('/')

    private fun expandHome(raw: String, userHome: File): String =
        when {
            raw == "~" -> userHome.absolutePath
            raw.startsWith("~/") -> File(userHome, raw.removePrefix("~/")).absolutePath
            raw.startsWith("\$HOME/") -> File(userHome, raw.removePrefix("\$HOME/")).absolutePath
            else -> raw
        }
}
