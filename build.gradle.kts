import java.util.Properties

plugins {
    id("net.fabricmc.fabric-loom") version "1.17.21"
}

// Only Minecraft 26.3 is supported. Old -Pmc targets must fail instead of silently building a different jar.
val requestedMc = providers.gradleProperty("mc").orNull
require(requestedMc == null || requestedMc == "26.3") { "WeSort supports only Minecraft 26.3 (requested: $requestedMc)" }
val mc = "26.3"
val target = Properties().apply {
    val file = rootProject.file("versions/$mc.properties")
    require(file.exists()) { "No versions/$mc.properties, supported versions are in the versions folder." }
    file.reader().use { load(it) }
}
fun target(key: String) = target.getProperty(key)

base {
    archivesName = properties["archives_base_name"] as String
    version = "${libs.versions.mod.version.get()}+$mc"
    group = properties["maven_group"] as String
}

// Translate named API changes once; keep algorithms shared with the mapped baselines.
// The generated sources are inspectable under build/generated/sources/mc26.
val portedSources = layout.buildDirectory.dir("generated/sources/mc26")
val port26Sources by tasks.registering {
    inputs.dir("src/main/java")
    inputs.file("src/26.3/renames.tsv")
    outputs.dir(portedSources)
    doLast {
        val renames = file("src/26.3/renames.tsv").readLines().filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split('\t', limit = 2).let { parts -> require(parts.size == 2); parts[0] to parts[1] } }
        val destination = portedSources.get().asFile
        delete(destination)
        fileTree("src/main/java").matching { include("**/*.java") }.forEach { input ->
            var text = input.readText()
            renames.forEach { (before, after) -> text = text.replace(before, after) }
            destination.resolve(input.relativeTo(file("src/main/java")).path).apply { parentFile.mkdirs(); writeText(text) }
        }
    }
}
sourceSets {
    main {
        java.setSrcDirs(listOf(portedSources, "src/26.3/java", "common/src/main/java"))
        resources.srcDir("src/26.3/resources")
    }
    test { java.srcDir("common/src/test/java") }
}
tasks.compileJava { dependsOn(port26Sources) }

repositories {
    maven {
        name = "meteor-maven"
        url = uri("https://maven.meteordev.org/releases")
    }
    maven {
        name = "meteor-maven-snapshots"
        url = uri("https://maven.meteordev.org/snapshots")
    }

    // dekrom's Baritone fork isn't on a maven, so its API jar comes straight from the GitHub release
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
    // Fabric
    minecraft("com.mojang:minecraft:${target("minecraft")}")
    implementation("net.fabricmc:fabric-loader:0.19.5")
    // Minecraft is patched with this interface; Meteor supplies the same module at runtime.
    compileOnly("net.fabricmc.fabric-api:fabric-resource-loader-v1:3.0.4+ba0d6c0d5d")

    // Meteor
    implementation("meteordevelopment:meteor-client:${target("meteor")}")

    // Baritone (dekrom's fork, https://github.com/dekrom/baritone), optional at runtime
    compileOnly("dekrom:baritone-api-fabric:${target("baritone")}")
    if (providers.gradleProperty("withBaritone").orNull == "true") {
        runtimeOnly("dekrom:baritone-api-fabric:${target("baritone")}")
    }

    // Tests of the pure classes (recipes, crafting plans, spot search); nothing of Minecraft is started
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks {
    test {
        useJUnitPlatform()
    }

    processResources {
        val propertyMap = mapOf(
            "version" to project.version,
            "mc_version" to target("minecraft"),
            "java_version" to "25"
        )

        inputs.properties(propertyMap)

        filteringCharset = "UTF-8"

        filesMatching("fabric.mod.json") {
            expand(propertyMap)
        }
    }

    jar {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
        inputs.property("archivesName", project.base.archivesName.get())

        from("LICENSE") {
            rename { "${it}_${inputs.properties["archivesName"]}" }
        }
    }

    java {
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }

    withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release = 25
        options.compilerArgs.add("-Xlint:deprecation")
        options.compilerArgs.add("-Xlint:unchecked")
        options.compilerArgs.addAll(listOf("-Xmaxerrs", "1000"))
    }
}

loom {
    runs {
        named("client") {
            runDir(providers.gradleProperty("gameDir").orElse("build/run-26.3").get())
            vmArg("-Dwesort.runtime.probe=" + providers.gradleProperty("runtimeProbe").orElse("false").get())
            vmArg("-Dwesort.e2e=" + providers.gradleProperty("worldTest").orElse("false").get())
            providers.gradleProperty("testServer").orNull?.let { programArgs("--quickPlayMultiplayer", it) }
            if (providers.gradleProperty("worldTest").orNull == "true") programArgs("--username", "WeSortE2E")
        }
    }
}
