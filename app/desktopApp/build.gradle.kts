import org.gradle.internal.jvm.Jvm
import org.gradle.internal.os.OperatingSystem
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

val macosNativeResourcesDir = layout.buildDirectory.dir("generated/macosNative")
val macosHotkeySource = layout.projectDirectory.file("src/native/macos/LeworkMacHotkey.m")
val macosHotkeyOutput = macosNativeResourcesDir.map { it.file("native/macos/libLeworkMacHotkey.dylib") }

val compileMacosHotkeyBridge by tasks.registering(Exec::class) {
    group = "build"
    description = "Compiles the macOS Carbon global hotkey & AppKit JNI bridge into a bundled dylib."
    onlyIf { OperatingSystem.current().isMacOsX }
    inputs.file(macosHotkeySource)
    outputs.file(macosHotkeyOutput)
    doFirst {
        val outputFile = macosHotkeyOutput.get().asFile
        outputFile.parentFile.mkdirs()
        val javaHome = Jvm.current().javaHome
        val jniInclude = File(javaHome, "include")
        val jniDarwinInclude = File(jniInclude, "darwin")
        commandLine(
            "clang",
            "-shared",
            "-fobjc-arc",
            "-O2",
            "-Wall",
            "-Wextra",
            "-Werror",
            "-Wno-deprecated-declarations",
            "-I${jniInclude.absolutePath}",
            "-I${jniDarwinInclude.absolutePath}",
            "-framework",
            "Carbon",
            "-framework",
            "Cocoa",
            "-o",
            outputFile.absolutePath,
            macosHotkeySource.asFile.absolutePath,
        )
    }
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    sourceSets {
        val jvmMain by getting {
            resources.srcDir(macosNativeResourcesDir)
            dependencies {
                implementation(project(":shared"))
                implementation(compose.desktop.currentOs)
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

tasks.named("jvmProcessResources") {
    dependsOn(compileMacosHotkeyBridge)
}

fun resolveRepoVersion(): String {
    val explicit = (findProperty("leworkVersion") as String?)?.trim()?.takeIf { it.isNotEmpty() }
        ?: System.getenv("LEWORK_VERSION")?.trim()?.takeIf { it.isNotEmpty() }
    if (explicit != null) return explicit.removePrefix("v")
    val rootPkg = File(rootDir.parentFile, "package.json")
    if (rootPkg.isFile) {
        val m = Regex(""""version"\s*:\s*"([^"]+)"""").find(rootPkg.readText())
        if (m != null) return m.groupValues[1].trim().removePrefix("v")
    }
    return "0.7.0"
}

fun computeDesktopPackageVersion(semver: String): String {
    val parts = semver.removePrefix("v").split(".")
    val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
    val minor = parts.getOrNull(1)?.toIntOrNull() ?: 7
    val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
    // macOS jpackage requires MAJOR >= 1; shifting major by +1 keeps bundle versions strictly monotonic across 0.x -> 1.0
    return "${major + 1}.$minor.$patch"
}

val leworkVersion = resolveRepoVersion()
val desktopPackageVersion = (findProperty("leworkDesktopPackageVersion") as String?)?.trim()?.takeIf { it.isNotEmpty() }
    ?: System.getenv("LEWORK_DESKTOP_PACKAGE_VERSION")?.trim()?.takeIf { it.isNotEmpty() }
    ?: computeDesktopPackageVersion(leworkVersion)

compose.desktop {
    application {
        mainClass = "dev.lework.desktop.MainKt"
        jvmArgs(
            "-Dapple.awt.application.name=Lework",
            "-Dapple.awt.application.appearance=NSAppearanceNameDarkAqua",
            "-Dlework.app.version=$leworkVersion",
        )
        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            modules("java.naming", "jdk.crypto.ec", "jdk.unsupported", "java.management")
            packageName = "Lework"
            packageVersion = desktopPackageVersion
            description = "Lework Desktop Workstation — Multi-Engine & Remote Devbox Console"
            vendor = "Lework"
            macOS {
                bundleID = "dev.lework.desktop"
                dockName = "Lework"
                iconFile.set(project.file("src/jvmMain/resources/Lework.icns"))
            }
        }
    }
}
