plugins {
    id("net.fabricmc.fabric-loom") version "1.17.21"
}

version = "0.1.0"
group = "com.porter.wesort"

extra["wesortVendorRoot"] = rootProject.file("../../vendor")
apply(from = rootProject.file("../../gradle/frozen-meteor.gradle"))

repositories {
    maven("https://maven.meteordev.org/releases")
    maven("https://maven.meteordev.org/snapshots")
    exclusiveContent {
        forRepository {
            ivy {
                name = "dekrom-baritone"
                url = uri("https://github.com/dekrom/baritone/releases/download/")
                patternLayout { artifact("v[revision]/[module]-[revision].[ext]") }
                metadataSources { artifact() }
            }
        }
        filter { includeGroup("dekrom") }
    }
}

dependencies {
    minecraft("com.mojang:minecraft:26.3")
    implementation("net.fabricmc:fabric-loader:0.19.5")
    implementation("meteordevelopment:meteor-client:26.3-20261115.173242-22")
    implementation("dekrom:baritone-api-fabric:1.4.0-26.3")
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}
tasks.withType<JavaCompile>().configureEach { options.release = 25 }

loom {
    runs {
        named("client") {
            runDir("build/probe-run")
            vmArg("-Dwesort.toolchain.probe=true")
        }
    }
}

tasks.jar {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from("../../LICENSE")
}
