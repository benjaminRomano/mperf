import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import com.google.protobuf.gradle.id
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.bundling.AbstractArchiveTask

plugins {
    kotlin("jvm") version "2.4.10"
    id("com.google.protobuf") version "0.10.0"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
    id("com.gradleup.shadow") version "9.6.1"
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
    implementation(platform("org.jetbrains.kotlin:kotlin-bom:2.4.10"))
    implementation("com.github.ajalt.clikt:clikt:5.1.0")
    implementation("com.github.ajalt.clikt:clikt-markdown:5.1.0")
    implementation("com.google.protobuf:protobuf-kotlin:4.36.0")
    implementation("com.google.protobuf:protobuf-java:4.36.0")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.22.2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.22.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("com.google.code.gson:gson:2.14.0")

    runtimeOnly("org.slf4j:slf4j-nop:2.0.18")

    // Testing dependencies
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testImplementation("org.mockito.kotlin:mockito-kotlin:6.3.0")
    testImplementation("org.mockito:mockito-core:5.23.0")
    mockitoAgent("org.mockito:mockito-core:5.23.0") { isTransitive = false }
}

kotlin { jvmToolchain(21) }

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:4.36.0" }
    generateProtoTasks { all().forEach { it.builtins { id("kotlin") } } }
}

val faultEngineManifest = layout.projectDirectory.file("src/main/resources/faults-engine/manifest.txt")

fun declaredFaultResources(): Set<String> =
    faultEngineManifest.asFile
        .readLines()
        .filter(String::isNotBlank)
        .map { "faults-engine/$it" }
        .toSet() +
        "faults-engine/manifest.txt"

tasks.processResources {
    // Ship only the explicitly declared native helpers and offline viewer assets.
    val declared = declaredFaultResources()
    filesMatching("faults-engine/**") {
        if (path !in declared) exclude()
    }
}

val verifyFaultResources =
    tasks.register("verifyFaultResources") {
        group = "verification"
        description = "Verify the packaged fault-engine resources match the manifest"
        dependsOn(tasks.processResources)
        doLast {
            val root = tasks.processResources.get().destinationDir
            val actual = fileTree(root.resolve("faults-engine")).files.map { it.relativeTo(root).invariantSeparatorsPath }.toSet()
            val declared = declaredFaultResources()
            check(actual == declared) {
                "Fault resource mismatch: unexpected=${actual - declared}, missing=${declared - actual}"
            }
        }
    }

tasks.named("jar") { dependsOn(verifyFaultResources) }
tasks.named("shadowJar") { dependsOn(verifyFaultResources) }

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

    register<Exec>("testFaultViewer") {
        group = "verification"
        description = "Run shared fault viewer model and navigation regression tests"
        commandLine(
            "node",
            "--test",
            "src/test/javascript/report_model.test.cjs",
            "src/test/javascript/report_stacks.test.cjs",
            "src/test/javascript/report_perfetto.test.cjs",
            "src/test/javascript/release_ci.test.cjs",
            "src/test/javascript/report_context.test.cjs",
            "src/test/javascript/report_health.test.cjs",
        )
    }

    check { dependsOn("testFaultViewer", verifyFaultResources) }

    // Build a runnable fat JAR via: ./gradlew shadowJar
    named<ShadowJar>("shadowJar") {
        archiveClassifier.set("all")
        manifest {
            attributes["Main-Class"] = "com.bromano.mobile.perf.MainKt"
            attributes["Implementation-Version"] = project.version
        }
    }

    withType<Test>().configureEach {
        useJUnitPlatform()
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
        // Device state is external to Gradle inputs: never reuse live test results.
        val live = forwardedTestSystemProperties.any { it.endsWith(".enabled") && System.getProperty(it) == "true" }
        outputs.cacheIf("Live device tests cannot be cached") { !live }
        outputs.upToDateWhen { !live }
        jvmArgs("-javaagent:${mockitoAgent.asPath}", "-XX:+EnableDynamicAgentLoading")
    }
}
