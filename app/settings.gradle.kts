rootProject.name = "lework-app"

pluginManagement {
    repositories {
        System.getenv("LEWORK_MAVEN_MIRROR")?.takeIf { it.isNotBlank() }?.let { maven(it) }
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        System.getenv("LEWORK_MAVEN_MIRROR")?.takeIf { it.isNotBlank() }?.let { maven(it) }
        mavenCentral()
        google()
    }
}

include(":shared")
include(":androidApp")
include(":desktopApp")
include(":detekt-rules")
