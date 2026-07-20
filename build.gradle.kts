import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import com.google.protobuf.gradle.id
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.bundling.AbstractArchiveTask

plugins {
    kotlin("jvm") version "2.4.0"
    kotlin("plugin.serialization") version "2.4.10"
    id("com.google.protobuf") version "0.10.0"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
    id("com.gradleup.shadow") version "9.5.1"
    id("me.champeau.jmh") version "0.7.3"
    application
}

group = "com.bromano"

val releaseVersion = providers.gradleProperty("releaseVersion")
releaseVersion.orNull?.let {
    require(it.matches(Regex("\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.-]+)?"))) {
        "releaseVersion must be a SemVer value without a leading 'v': $it"
    }
}
version = releaseVersion.getOrElse("1.0-SNAPSHOT")

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
        "mperf.integration.activity",
        "mperf.integration.ios.enabled",
        "mperf.integration.ios.device",
        "mperf.integration.ios.bundle",
        "mperf.integration.ios.appPath",
    )

dependencies {
    implementation(platform("org.jetbrains.kotlin:kotlin-bom:2.4.0"))
    implementation("com.github.ajalt.clikt:clikt:5.1.0")
    implementation("com.github.ajalt.clikt:clikt-markdown:5.1.0")
    implementation("com.google.protobuf:protobuf-kotlin:4.35.1")
    implementation("com.google.protobuf:protobuf-java:4.35.1")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.22.1")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.22.1")
    implementation("io.ktor:ktor-client-core:3.5.1")
    implementation("io.ktor:ktor-client-java:3.5.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("com.google.code.gson:gson:2.14.0")

    runtimeOnly("org.slf4j:slf4j-nop:2.0.18")

    // Testing dependencies
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.2")
    testImplementation("org.mockito.kotlin:mockito-kotlin:6.3.0")
    testImplementation("org.mockito:mockito-core:5.23.0")
    mockitoAgent("org.mockito:mockito-core:5.23.0") { isTransitive = false }
}

tasks.test { useJUnitPlatform() }

kotlin { jvmToolchain(21) }

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:4.35.1" }
    generateProtoTasks { all().forEach { it.builtins { id("kotlin") } } }
}

sourceSets.main {
    java.srcDirs(
        "build/generated/source/proto/main/kotlin",
        "build/generated/source/proto/main/java",
    )
}

sourceSets.named("jmh") {
    resources.srcDir("src/test/resources")
}

jmh {
    jmhVersion.set("1.37")
    includes.set(listOf(".*InstrumentsConverterBenchmark.*"))
    warmupIterations.set(1)
    iterations.set(3)
    fork.set(1)
    timeOnIteration.set("1s")
    timeUnit.set("ms")
}

tasks {
    withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }

    withType<ShadowJar>().configureEach {
        // Shadow transforms Kotlin module metadata, so duplicate resources must reach the transformer.
        filesMatching("META-INF/*.kotlin_module") {
            duplicatesStrategy = DuplicatesStrategy.INCLUDE
        }
    }

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
