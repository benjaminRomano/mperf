package com.bromano.mobile.perf.integration

import com.bromano.mobile.perf.PerfettoOptions
import com.bromano.mobile.perf.ProfilerOptionGroup
import com.bromano.mobile.perf.SimpleperfOptions
import com.bromano.mobile.perf.androidProfilerOptions
import com.bromano.mobile.perf.profilers.method.MethodProfiler
import com.bromano.mobile.perf.profilers.perfetto.PerfettoProfiler
import com.bromano.mobile.perf.profilers.simpleperf.SimpleperfProfiler
import com.bromano.mobile.perf.utils.Adb
import com.bromano.mobile.perf.utils.ShellExecutor
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.parse
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AndroidProfilerIntegrationTest {
    private val shell = ShellExecutor()
    private val device by lazy {
        System.getProperty("mperf.integration.device")
            ?: shell.getConnectedAndroidDevices().firstOrNull()
    }
    private val instrumentationRunner =
        System.getProperty(
            "mperf.integration.instrumentation",
            "com.bromano.mperf.fixture.benchmark/androidx.test.runner.AndroidJUnitRunner",
        )
    private val packageName =
        System.getProperty("mperf.integration.package", "com.bromano.mperf.fixture")
    private val testCase =
        System.getProperty(
            "mperf.integration.testCase",
            "com.bromano.mperf.fixture.benchmark.FixtureBenchmark#startup",
        )
    private val launchableActivity =
        System.getProperty(
            "mperf.integration.activity",
            "com.bromano.mperf.fixture/.FixtureActivity",
        )

    private fun assumeEnabled() {
        assumeTrue(
            java.lang.Boolean.getBoolean("mperf.integration.enabled"),
            "Set -Dmperf.integration.enabled=true to run emulator integration tests",
        )
        assumeTrue(device != null, "No connected Android device found")
    }

    private fun createAdb(): Adb = Adb(device, shell)

    private fun assertBenchmarkSampleAvailable(adb: Adb) {
        assertTargetAvailable(adb)
        assertContains(adb.findInstrumentationRunners(), instrumentationRunner)
        assertContains(adb.getTests(instrumentationRunner), testCase)
    }

    private fun assertTargetAvailable(adb: Adb) {
        assertEquals(launchableActivity, adb.resolveLaunchableActivity(packageName))
    }

    private fun launchTargetApp(adb: Adb) {
        adb.shell("am start -W -n $launchableActivity")
    }

    private fun exerciseTargetApp(adb: Adb) {
        Thread.sleep(1000)
        launchTargetApp(adb)
        Thread.sleep(1500)
    }

    private fun defaultSimpleperfOptions(): SimpleperfOptions {
        class SimpleperfCommand : CliktCommand() {
            lateinit var captured: ProfilerOptionGroup
            private val profiler by androidProfilerOptions()

            override fun run() {
                captured = profiler
            }
        }

        return SimpleperfCommand()
            .apply {
                parse(listOf("--format", "simpleperf"))
            }.captured as SimpleperfOptions
    }

    private fun assertNonEmptyFile(path: Path) {
        assertTrue(path.toFile().exists(), "expected output to exist: $path")
        assertTrue(Files.size(path) > 0, "expected non-empty output: $path")
    }

    private fun assertGeckoProfile(path: Path) {
        val profile =
            Files.newInputStream(path).use { input ->
                GZIPInputStream(input).bufferedReader().use(JsonParser::parseReader).asJsonObject
            }
        assertTrue(profile.getAsJsonArray("threads").size() > 0, "expected Gecko profile to contain threads")
        profile.getAsJsonArray("threads").forEach { thread ->
            val samples =
                thread
                    .asJsonObject
                    .getAsJsonObject("samples")
                    .getAsJsonArray("data")
            assertTrue(samples.size() > 0)
        }
    }

    private fun assertContainsTraceMarkers(path: Path) {
        val traceBytes = Files.readAllBytes(path).toString(Charsets.ISO_8859_1)
        FIXTURE_TRACE_MARKERS.forEach { marker ->
            assertTrue(traceBytes.contains(marker), "expected trace to contain marker: $marker")
        }
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    fun discovers_macrobenchmark_runner_test_and_launchable_activity() {
        assumeEnabled()

        val adb = createAdb()

        assertBenchmarkSampleAvailable(adb)
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    fun collects_perfetto_trace_from_macrobenchmark_sample() {
        assumeEnabled()

        val adb = createAdb()
        assertBenchmarkSampleAvailable(adb)
        val output = IntegrationArtifacts.androidTrace("macrobenchmark-perfetto", ".perfetto-trace")

        PerfettoProfiler(shell, adb, PerfettoOptions()).executeTest(
            packageName = packageName,
            instrumentationRunner = instrumentationRunner,
            testCase = testCase,
            output = output,
        )

        assertNonEmptyFile(output)
        assertContainsTraceMarkers(output)
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    fun collects_simpleperf_trace_from_macrobenchmark_sample() {
        assumeEnabled()

        val adb = createAdb()
        assertBenchmarkSampleAvailable(adb)
        val output = IntegrationArtifacts.androidTrace("macrobenchmark-simpleperf", ".perfetto-trace")

        SimpleperfProfiler(shell, adb, defaultSimpleperfOptions()).executeTest(
            packageName = packageName,
            instrumentationRunner = instrumentationRunner,
            testCase = testCase,
            output = output,
        )

        assertNonEmptyFile(output)
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    fun collects_method_trace_from_macrobenchmark_sample() {
        assumeEnabled()

        val adb = createAdb()
        assertBenchmarkSampleAvailable(adb)
        val output = IntegrationArtifacts.androidTrace("macrobenchmark-method", ".trace")

        MethodProfiler(adb).executeTest(
            packageName = packageName,
            instrumentationRunner = instrumentationRunner,
            testCase = testCase,
            output = output,
        )

        assertNonEmptyFile(output)
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    fun collects_perfetto_trace_from_ad_hoc_session() {
        assumeEnabled()

        val adb = createAdb()
        assertTargetAvailable(adb)
        adb.shell("am force-stop $packageName", ignoreErrors = true)
        val output = IntegrationArtifacts.androidTrace("adhoc-perfetto", ".perfetto-trace")

        PerfettoProfiler(
            shell,
            adb,
            PerfettoOptions(),
            awaitStop = { exerciseTargetApp(adb) },
        ).execute(packageName, output)

        assertNonEmptyFile(output)
        assertContainsTraceMarkers(output)
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun collects_simpleperf_profile_from_ad_hoc_session() {
        assumeEnabled()

        val adb = createAdb()
        assertTargetAvailable(adb)
        adb.shell("am force-stop $packageName", ignoreErrors = true)
        val output = IntegrationArtifacts.androidTrace("adhoc-simpleperf", ".json.gz")

        SimpleperfProfiler(
            shell,
            adb,
            defaultSimpleperfOptions(),
            awaitStop = { exerciseTargetApp(adb) },
        ).execute(packageName, output)

        assertNonEmptyFile(output)
        assertGeckoProfile(output)
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    fun collects_method_trace_from_ad_hoc_session() {
        assumeEnabled()

        val adb = createAdb()
        assertTargetAvailable(adb)
        adb.shell("am force-stop $packageName", ignoreErrors = true)
        val output = IntegrationArtifacts.androidTrace("adhoc-method", ".trace")

        MethodProfiler(
            adb,
            awaitStop = { exerciseTargetApp(adb) },
        ).execute(packageName, output)

        assertNonEmptyFile(output)
    }

    private companion object {
        val FIXTURE_TRACE_MARKERS =
            listOf(
                "mperf.fixture.sync-workload",
                "mperf.fixture.async-lifecycle",
                "mperf.fixture.work-batches",
            )
    }
}
