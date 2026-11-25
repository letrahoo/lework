import io.gitlab.arturbosch.detekt.Detekt

plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinAndroid) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.detekt)
}

dependencies {
    detektPlugins(project(":detekt-rules"))
}

detekt {
    toolVersion = libs.versions.detekt.get()
    buildUponDefaultConfig = false
    allRules = false
    parallel = true
    basePath = rootDir.absolutePath
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    source.setFrom(
        files(
            "$rootDir/shared/src",
            "$rootDir/androidApp/src",
            "$rootDir/desktopApp/src",
        ),
    )
}

tasks.named<Detekt>("detekt") {
    jvmTarget = "17"
    exclude("**/build/**")
    reports {
        html.required.set(false)
        xml.required.set(false)
        sarif.required.set(false)
        md.required.set(false)
        txt.required.set(true)
        txt.outputLocation.set(layout.buildDirectory.file("reports/detekt/detekt.txt"))
    }
}

val detektCommonMain by tasks.registering(Detekt::class) {
    description = "Run Detekt KMP purity checks on shared commonMain and commonTest sources."
    group = "verification"
    jvmTarget = "17"
    buildUponDefaultConfig = false
    allRules = false
    parallel = true
    basePath = rootDir.absolutePath
    config.setFrom(files("$rootDir/config/detekt/detekt-common.yml"))
    setSource(
        files(
            "$rootDir/shared/src/commonMain/kotlin",
            "$rootDir/shared/src/commonTest/kotlin",
        ),
    )
    exclude("**/build/**")
    reports {
        html.required.set(false)
        xml.required.set(false)
        sarif.required.set(false)
        md.required.set(false)
        txt.required.set(true)
        txt.outputLocation.set(layout.buildDirectory.file("reports/detekt/detekt-common.txt"))
    }
}

val detektAll by tasks.registering {
    description = "Run all Detekt lint suites and custom rule unit tests."
    group = "verification"
    dependsOn(
        tasks.named("detekt"),
        detektCommonMain,
        ":detekt-rules:test",
    )
}
