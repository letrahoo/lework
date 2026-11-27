package dev.lework.platform

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopCliInstallerTest {
    private fun createTempRoot(): File =
        Files.createTempDirectory("lework-cli-installer-test").toFile()

    private fun createFakeRepo(root: File): File {
        val repo = File(root, "personal/lework")
        val launcher = File(repo, "hub/bin/hub.js")
        launcher.parentFile.mkdirs()
        launcher.writeText("#!/usr/bin/env node\nconsole.log('lework')\n")
        return repo
    }

    @Test
    fun installsSymlinkIntoWritableSystemBinAndUserLocalBin() {
        val root = createTempRoot()
        try {
            val home = File(root, "home").apply { mkdirs() }
            val brewBin = File(root, "opt/homebrew/bin").apply { mkdirs() }
            val repo = createFakeRepo(home)
            val env = CliInstallerEnv(
                osName = "Mac OS X",
                userHome = home,
                userDir = File(root, "app-sandbox").apply { mkdirs() },
                env = mapOf("PATH" to "${brewBin.absolutePath}:/usr/bin:/bin"),
                systemBinDirs = listOf(brewBin),
                etcPathsFile = File(root, "etc/paths"),
                etcPathsDir = File(root, "etc/paths.d"),
            )

            val before = DesktopCliInstaller.inspectStatus(env)
            assertTrue(before.supported)
            assertFalse(before.installed)
            assertEquals(File(repo, "hub/bin/hub.js").canonicalPath, before.launcherTargetPath)

            val installed = DesktopCliInstaller.installSync(env).getOrThrow()
            assertTrue(installed.installed)
            assertTrue(installed.inShellPath)
            assertEquals(File(brewBin, "lework").absolutePath, installed.installedPath)
            assertTrue(Files.isSymbolicLink(File(brewBin, "lework").toPath()))
            assertTrue(Files.isSymbolicLink(File(home, ".local/bin/lework").toPath()))

            val after = DesktopCliInstaller.inspectStatus(env)
            assertTrue(after.installed)
            assertTrue(after.inShellPath)
            assertEquals(File(brewBin, "lework").absolutePath, after.installedPath)

            val removed = DesktopCliInstaller.uninstallSync(env).getOrThrow()
            assertFalse(removed.installed)
            assertFalse(Files.exists(File(brewBin, "lework").toPath()))
            assertFalse(Files.exists(File(home, ".local/bin/lework").toPath()))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun discoversLauncherFromConfiguredWorkspaceRootsAndNeverClobbersUnrelatedBinary() {
        val root = createTempRoot()
        try {
            val home = File(root, "home").apply { mkdirs() }
            val leworkHome = File(home, ".lework").apply { mkdirs() }
            val customWorkspace = File(root, "custom-ws").apply { mkdirs() }
            val repo = File(customWorkspace, "lework")
            val launcher = File(repo, "hub/bin/hub.js")
            launcher.parentFile.mkdirs()
            launcher.writeText("#!/usr/bin/env node\n")

            File(leworkHome, "config.json").writeText(
                """{"workspaceRoots":["${customWorkspace.absolutePath.replace("\\", "\\\\")}"]}""",
            )

            // Pre-populate /opt/homebrew/bin/lework with an unrelated non-symlink file; installer must not overwrite it.
            val brewBin = File(root, "opt/homebrew/bin").apply { mkdirs() }
            val unrelatedBinary = File(brewBin, "lework").apply { writeText("foreign-binary") }

            val env = CliInstallerEnv(
                osName = "Mac OS X",
                userHome = home,
                userDir = File(root, "elsewhere").apply { mkdirs() },
                env = mapOf(
                    "PATH" to "/usr/bin:/bin",
                    "LEWORK_HOME" to leworkHome.absolutePath,
                ),
                systemBinDirs = listOf(brewBin),
                etcPathsFile = File(root, "etc/paths"),
                etcPathsDir = File(root, "etc/paths.d"),
            )

            val result = DesktopCliInstaller.installSync(env).getOrThrow()
            assertTrue(result.installed)
            assertFalse(result.inShellPath)
            assertEquals(File(home, ".local/bin/lework").absolutePath, result.installedPath)
            assertEquals("foreign-binary", unrelatedBinary.readText())

            // Adding ~/.local/bin to ~/.zshrc makes shell PATH detection report true.
            File(home, ".zshrc").writeText("export PATH=\"\$HOME/.local/bin:\$PATH\"\n")
            val inspected = DesktopCliInstaller.inspectStatus(env)
            assertTrue(inspected.installed)
            assertTrue(inspected.inShellPath)
            assertNotNull(inspected.launcherTargetPath)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun discoversLauncherFromLaunchAgentPlistWhenRepoLivesOutsideCommonDirs() {
        val root = createTempRoot()
        try {
            val home = File(root, "home").apply { mkdirs() }
            val agentsDir = File(home, "Library/LaunchAgents").apply { mkdirs() }
            val customRepo = File(root, "deep/nested/repo")
            val launcher = File(customRepo, "hub/bin/hub.js")
            launcher.parentFile.mkdirs()
            launcher.writeText("#!/usr/bin/env node\n")

            File(agentsDir, "dev.lework.hub-slave.plist").writeText(
                """
                <plist version="1.0"><dict>
                  <key>WorkingDirectory</key>
                  <string>${customRepo.absolutePath}</string>
                </dict></plist>
                """.trimIndent(),
            )

            val env = CliInstallerEnv(
                osName = "Mac OS X",
                userHome = home,
                userDir = File(root, "other").apply { mkdirs() },
                env = emptyMap(),
                systemBinDirs = emptyList(),
                etcPathsFile = File(root, "etc/paths"),
                etcPathsDir = File(root, "etc/paths.d"),
            )

            val resolved = DesktopCliInstaller.resolveCliLauncherTarget(env)
            assertEquals(launcher.canonicalPath, resolved?.canonicalPath)
        } finally {
            root.deleteRecursively()
        }
    }
}
