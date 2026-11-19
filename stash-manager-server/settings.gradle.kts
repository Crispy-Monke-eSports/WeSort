// Standalone build for the WeSort Manager web server. It is separate from the
// root Fabric Loom build: run it with `./gradlew -p stash-manager-server build`.
// Keep the Gradle Plugin Portal as a fallback for plugin markers when the
// CI init script adds the Maven Central mirror ahead of plugin resolution.
pluginManagement {
    repositories {
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "wesort-manager"
