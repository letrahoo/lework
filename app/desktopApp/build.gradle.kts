import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    sourceSets {
        val jvmMain by getting {
            dependencies {
                implementation(project(":shared"))
                implementation(compose.desktop.currentOs)
            }
        }
    }
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
