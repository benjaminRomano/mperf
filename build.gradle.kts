import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import com.google.protobuf.gradle.id

plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.serialization") version "2.2.20"
    kotlin("plugin.allopen") version "2.2.20"
    id("com.google.protobuf") version "0.9.5"
    id("org.jlleitschuh.gradle.ktlint") version "13.1.0"
    id("com.gradleup.shadow") version "9.0.0"
    application
}

group = "com.bromano"

// Align project version with the release tag when provided
version = providers.gradleProperty("releaseVersion").getOrElse("1.0-SNAPSHOT")

application { mainClass.set("com.bromano.mobile.perf.MainKt") }

repositories { mavenCentral() }

val mockitoAgent = configurations.create("mockitoAgent")

dependencies {
    implementation("com.github.ajalt.clikt:clikt:5.0.3")
    implementation("com.github.ajalt.clikt:clikt-markdown:5.0.3")
    implementation("com.charleskorn.kaml:kaml:0.58.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.6.3")
    implementation("com.google.protobuf:protobuf-kotlin:4.32.0")
    implementation("com.google.protobuf:protobuf-java:4.32.0")
    implementation("io.ktor:ktor-client-core:3.3.1")
    implementation("io.ktor:ktor-client-java:3.3.1")

    runtimeOnly("org.slf4j:slf4j-nop:2.0.16")

    // Testing dependencies
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testImplementation("org.mockito.kotlin:mockito-kotlin:6.0.0")
    testImplementation("org.mockito:mockito-core:5.19.0")
    mockitoAgent("org.mockito:mockito-core:5.19.0") { isTransitive = false }
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.google.code.gson:gson:2.13.2")
}

tasks.test { useJUnitPlatform() }

kotlin { jvmToolchain(21) }

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:4.32.0" }
    generateProtoTasks { all().forEach { it.builtins { id("kotlin") } } }
}

sourceSets.main {
    java.srcDirs(
        "build/generated/source/proto/main/kotlin",
        "build/generated/source/proto/main/java",
    )
}

tasks {
    // Generate CLI docs in Markdown: ./gradlew generateCliDocs
    register<JavaExec>("generateDocs") {
        group = "documentation"
        description = "Generate CLI documentation (Markdown tables) to docs/cli.md"
        mainClass.set("com.bromano.mobile.perf.DocsGenerator")
        classpath = sourceSets["main"].runtimeClasspath
    }

    // Build a runnable fat JAR via: ./gradlew shadowJar
    named<ShadowJar>("shadowJar") {
        archiveClassifier.set("all")
        manifest {
            attributes["Main-Class"] = "com.bromano.mobile.perf.MainKt"
            attributes["Implementation-Version"] = project.version
        }
    }

    test {
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            showStandardStreams = true
        }
        jvmArgs!!.add("-javaagent:${mockitoAgent.asPath}")
    }
}
