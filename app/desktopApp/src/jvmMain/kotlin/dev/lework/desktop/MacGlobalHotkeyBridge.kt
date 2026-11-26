package dev.lework.desktop

import dev.lework.platform.logRuntimeEvent
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import javax.swing.SwingUtilities

/**
 * macOS native bridge backed by Carbon `RegisterEventHotKey` + AppKit `NSApp` (bundled as
 * `native/macos/libLeworkMacHotkey.dylib`).
 *
 * Uses official Carbon hotkey registration so `Ctrl + Cmd + E` works system-wide from any
 * application without requiring macOS Accessibility / Input Monitoring permissions.
 * Gracefully degrades to no-op on non-macOS JVMs (Linux / Windows CI & desktop runs).
 */
internal object MacGlobalHotkeyBridge {
    /** Carbon `kVK_ANSI_E` virtual keycode (physical ANSI E, input-method independent). */
    const val MAC_VK_ANSI_E: Int = 0x0E

    /** Carbon `cmdKey` (`1 << 8`). */
    const val MAC_MOD_CMD: Int = 0x0100

    /** Carbon `controlKey` (`1 << 12`). */
    const val MAC_MOD_CTRL: Int = 0x1000

    /** Combined `Ctrl + Cmd` modifier mask (`cmdKey | controlKey`). */
    const val TOGGLE_MODIFIERS: Int = MAC_MOD_CMD or MAC_MOD_CTRL

    private const val RESOURCE_PATH = "/native/macos/libLeworkMacHotkey.dylib"

    private val loadLock = Any()

    @Volatile
    private var libraryLoadAttempted: Boolean = false

    @Volatile
    private var libraryLoaded: Boolean = false

    @Volatile
    private var registered: Boolean = false

    @Volatile
    private var onHotKeyCallback: ((appIsActive: Boolean, appIsHidden: Boolean) -> Unit)? = null

    val isMacOs: Boolean
        get() = System.getProperty("os.name").orEmpty().contains("mac", ignoreCase = true)

    val isRegistered: Boolean
        get() = registered

    /**
     * Registers the system-wide toggle hotkey asynchronously on a dedicated background thread
     * (never blocking AWT EDT against AppKit Thread 0). Invokes [onResult] on the Swing EDT.
     */
    fun registerToggleHotKeyAsync(
        keyCode: Int = MAC_VK_ANSI_E,
        modifiers: Int = TOGGLE_MODIFIERS,
        onTriggered: (appIsActive: Boolean, appIsHidden: Boolean) -> Unit,
        onResult: (Boolean) -> Unit = {},
    ) {
        onHotKeyCallback = onTriggered
        if (!isMacOs) {
            SwingUtilities.invokeLater { onResult(false) }
            return
        }
        val worker = Thread(
            {
                val ok = registerBlocking(keyCode, modifiers)
                SwingUtilities.invokeLater { onResult(ok) }
            },
            "lework-mac-hotkey-init",
        )
        worker.isDaemon = true
        worker.start()
    }

    fun unregister() {
        onHotKeyCallback = null
        if (!libraryLoaded || !registered) {
            registered = false
            return
        }
        registered = false
        runCatching {
            nativeUnregisterToggleHotKey()
            logRuntimeEvent("HOTKEY", "unregistered macOS global hotkey Ctrl+Cmd+E")
        }.onFailure { err ->
            logRuntimeEvent("HOTKEY", "unregister failed: ${err.message.orEmpty()}")
        }
    }

    fun hideApp(): Boolean {
        if (!libraryLoaded) return false
        return runCatching {
            nativeHideApp()
            true
        }.getOrDefault(false)
    }

    fun activateApp(): Boolean {
        if (!libraryLoaded) return false
        return runCatching {
            nativeActivateApp()
            true
        }.getOrDefault(false)
    }

    /** Invoked from Objective-C on macOS AppKit Thread 0; immediately hops to Swing EDT. */
    @JvmStatic
    fun onNativeHotKeyPressed(appIsActive: Boolean, appIsHidden: Boolean) {
        val cb = onHotKeyCallback ?: return
        SwingUtilities.invokeLater {
            cb(appIsActive, appIsHidden)
        }
    }

    private fun registerBlocking(keyCode: Int, modifiers: Int): Boolean {
        if (!ensureLibraryLoaded()) return false
        return runCatching {
            val status = nativeRegisterToggleHotKey(keyCode, modifiers)
            if (status == 0) {
                registered = true
                logRuntimeEvent("HOTKEY", "registered macOS global hotkey Ctrl+Cmd+E (keyCode=0x${keyCode.toString(16)})")
                true
            } else {
                registered = false
                logRuntimeEvent(
                    "HOTKEY",
                    "failed to register macOS global hotkey Ctrl+Cmd+E osStatus=$status (may conflict with another app)",
                )
                false
            }
        }.getOrElse { err ->
            registered = false
            logRuntimeEvent("HOTKEY", "native hotkey registration error: ${err.message.orEmpty()}")
            false
        }
    }

    private fun ensureLibraryLoaded(): Boolean {
        if (libraryLoaded) return true
        if (!isMacOs) return false
        synchronized(loadLock) {
            if (libraryLoaded) return true
            if (libraryLoadAttempted) return false
            libraryLoadAttempted = true
            val bytes = MacGlobalHotkeyBridge::class.java.getResourceAsStream(RESOURCE_PATH)?.use { it.readBytes() }
            if (bytes == null || bytes.isEmpty()) {
                logRuntimeEvent("HOTKEY", "native dylib resource missing at $RESOURCE_PATH")
                return false
            }
            val loaded = runCatching {
                val targetFile = extractDylib(bytes)
                System.load(targetFile.absolutePath)
                true
            }.onFailure { err ->
                logRuntimeEvent("HOTKEY", "failed to load native hotkey dylib: ${err.message.orEmpty()}")
            }.getOrDefault(false)
            libraryLoaded = loaded
            return loaded
        }
    }

    private fun extractDylib(bytes: ByteArray): File {
        val digestHex = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .take(6)
            .joinToString("") { b -> "%02x".format(b.toInt() and 0xFF) }
        val fileName = "libLeworkMacHotkey-$digestHex.dylib"
        val cacheDir = File(System.getProperty("user.home"), ".lework/desktop/native")
        val target = runCatching {
            cacheDir.mkdirs()
            val candidate = File(cacheDir, fileName)
            if (!candidate.isFile || candidate.length() != bytes.size.toLong()) {
                val tmp = File(cacheDir, "$fileName.tmp.${ProcessHandle.current().pid()}")
                tmp.writeBytes(bytes)
                runCatching {
                    Files.setPosixFilePermissions(
                        tmp.toPath(),
                        setOf(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE,
                        ),
                    )
                }
                Files.move(
                    tmp.toPath(),
                    candidate.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            }
            candidate
        }.getOrElse {
            val fallback = Files.createTempFile("libLeworkMacHotkey-", ".dylib").toFile()
            fallback.writeBytes(bytes)
            fallback.setExecutable(true, true)
            fallback.deleteOnExit()
            fallback
        }
        return target
    }

    @JvmStatic
    private external fun nativeRegisterToggleHotKey(keyCode: Int, modifiers: Int): Int

    @JvmStatic
    private external fun nativeUnregisterToggleHotKey()

    @JvmStatic
    private external fun nativeHideApp()

    @JvmStatic
    private external fun nativeActivateApp()
}
