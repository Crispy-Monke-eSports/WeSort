pluginManagement {
    repositories {
        maven {
            name = "Fabric"
            url = uri("https://maven.fabricmc.net/")
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "wesort-addon"

// Keep the mapped 1.21 builds separate from the unobfuscated Java 25 target.
if (providers.gradleProperty("mc").orNull == "26.3") rootProject.buildFileName = "build-26.3.gradle.kts"
