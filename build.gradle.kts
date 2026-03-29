import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import com.google.protobuf.gradle.id

plugins {
    kotlin("jvm") version "2.3.20"
    kotlin("plugin.serialization") version "2.3.20"
    kotlin("plugin.allopen") version "2.3.20"
    id("com.google.protobuf") version "0.9.6"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
    id("com.gradleup.shadow") version "9.4.1"
    application
}

group = "com.bromano"

// Align project version with the release tag when provided
version = providers.gradleProperty("releaseVersion").getOrElse("1.0-SNAPSHOT")

application { mainClass.set("com.bromano.mobile.perf.MainKt") }

repositories { mavenCentral() }

val mockitoAgent = configurations.create("mockitoAgent")
val forwardedTestSystemProperties =
    listOf(
        "mperf.integration.enabled",
        "mperf.integration.device",
        "mperf.integration.instrumentation",
        "mperf.integration.package",
        "mperf.integration.testCase",
    )

dependencies {
    implementation("com.github.ajalt.clikt:clikt:5.1.0")
    implementation("com.github.ajalt.clikt:clikt-markdown:5.1.0")
    implementation("com.google.protobuf:protobuf-kotlin:4.32.0")
    implementation("com.google.protobuf:protobuf-java:4.32.0")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.20.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.20.0")
    implementation("io.ktor:ktor-client-core:3.4.1")
    implementation("io.ktor:ktor-client-java:3.4.1")

    runtimeOnly("org.slf4j:slf4j-nop:2.0.17")

    // Testing dependencies
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.3")
    testImplementation("org.mockito.kotlin:mockito-kotlin:6.3.0")
    testImplementation("org.mockito:mockito-core:5.23.0")
    mockitoAgent("org.mockito:mockito-core:5.23.0") { isTransitive = false }
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
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
    // Generate CLI docs in Markdown: ./gradlew generateDocs
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
        forwardedTestSystemProperties.forEach { key ->
            System.getProperty(key)?.let { value ->
                systemProperty(key, value)
            }
        }
        jvmArgs("-javaagent:${mockitoAgent.asPath}", "-XX:+EnableDynamicAgentLoading")
    }
}
