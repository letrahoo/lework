import java.io.FileInputStream
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

val localReleaseSigningProps = Properties().apply {
    val propFile = File(System.getProperty("user.home"), ".lework/android-release.properties")
    if (propFile.isFile) {
        FileInputStream(propFile).use { load(it) }
    }
}

fun resolveSigningProp(envKey: String, propKey: String): String? =
    System.getenv(envKey)?.trim()?.takeIf { it.isNotEmpty() }
        ?: localReleaseSigningProps.getProperty(propKey)?.trim()?.takeIf { it.isNotEmpty() }

val releaseKeystorePath = resolveSigningProp("LEWORK_KEYSTORE_FILE", "storeFile")
    ?: File(System.getProperty("user.home"), ".lework/android-release.jks")
        .takeIf { it.isFile }
        ?.absolutePath
val releaseStorePassword = resolveSigningProp("LEWORK_KEYSTORE_PASSWORD", "storePassword")
val releaseKeyAlias = resolveSigningProp("LEWORK_KEY_ALIAS", "keyAlias")
val releaseKeyPassword = resolveSigningProp("LEWORK_KEY_PASSWORD", "keyPassword") ?: releaseStorePassword
val hasReleaseSigning =
    !releaseKeystorePath.isNullOrBlank() &&
        File(releaseKeystorePath).isFile &&
        !releaseStorePassword.isNullOrBlank() &&
        !releaseKeyAlias.isNullOrBlank()

android {
    namespace = "dev.lework.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.lework.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 7
        versionName = "0.6.1"
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = File(releaseKeystorePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
        }
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

val ensureTsnetJniLibs by tasks.registering(Exec::class) {
    val repoRoot = rootDir.parentFile
    val arm64So = file("src/main/jniLibs/arm64-v8a/libtsnetbridge.so")
    val x64So = file("src/main/jniLibs/x86_64/libtsnetbridge.so")
    onlyIf { !arm64So.isFile || !x64So.isFile }
    workingDir = repoRoot
    commandLine("bash", File(repoRoot, "scripts/build-tsnet.sh").absolutePath, "--android")
}

tasks.named("preBuild") {
    dependsOn(ensureTsnetJniLibs)
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.material3)
    implementation(compose.ui)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)
}
