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

compose.desktop {
    application {
        mainClass = "dev.lework.desktop.MainKt"
        jvmArgs(
            "-Dapple.awt.application.name=Lework",
            "-Dapple.awt.application.appearance=NSAppearanceNameDarkAqua",
        )
        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            modules("java.naming", "jdk.crypto.ec", "jdk.unsupported", "java.management")
            packageName = "Lework"
            packageVersion = "1.6.9"
            description = "Lework Desktop Workstation — Multi-Engine & Remote Devbox Console"
            vendor = "Lework"
            macOS {
                bundleID = "dev.lework.desktop"
                dockName = "Lework"
            }
        }
    }
}
