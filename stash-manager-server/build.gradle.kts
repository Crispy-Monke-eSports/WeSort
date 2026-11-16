plugins {
    java
    application
}

group = "com.porter.wesort"
version = "0.1.0-dev.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.google.code.gson:gson:2.13.2")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
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
