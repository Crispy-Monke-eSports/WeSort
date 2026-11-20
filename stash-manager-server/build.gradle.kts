plugins {
    java
    application
    id("com.diffplug.spotless") version "8.11.0"
}

group = "com.porter.wesort"
version = "0.1.0-dev.10"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.google.code.gson:gson:2.15.0")
    implementation("org.xerial:sqlite-jdbc:3.54.1.0")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

application {
    mainClass = "com.porter.wesortmanager.WeSortManager"
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 21
}

// Runnable fat jar: build/libs/wesort-manager.jar, start it with `java -jar`.
tasks.jar {
    archiveFileName = "wesort-manager.jar"
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from("../LICENSE")
    manifest {
        attributes("Main-Class" to application.mainClass.get())
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.map { cp -> cp.map { if (it.isDirectory) it else zipTree(it) } }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/9/module-info.class", "module-info.class")
    }
}

sourceSets {
    main { java.srcDir("../common/src/main/java") }
    test { java.srcDir("../common/src/test/java") }
}

// The production /tmp is noexec. Extract only the supported Linux amd64 native library at image build time.
val sqliteNative = tasks.register<Copy>("sqliteNative") {
    from(configurations.runtimeClasspath.map { cp -> cp.filter { it.name.startsWith("sqlite-jdbc-") }.map { zipTree(it) } })
    include("org/sqlite/native/Linux/x86_64/libsqlitejdbc.so")
    eachFile { path = name }
    includeEmptyDirs = false
    into(layout.buildDirectory.dir("sqlite-native"))
}
tasks.build { dependsOn(sqliteNative) }

// The Manager is a separate Gradle build. Shared code under ../common is
// formatted from the repository root so both builds use a single owner.
spotless {
    // Do not introduce a mandatory formatting gate before the baseline is clean.
    setEnforceCheck(false)
    java {
        target("src/main/java/**/*.java", "src/test/java/**/*.java")
        palantirJavaFormat("2.103.0")
        trimTrailingWhitespace()
        endWithNewline()
    }
}
