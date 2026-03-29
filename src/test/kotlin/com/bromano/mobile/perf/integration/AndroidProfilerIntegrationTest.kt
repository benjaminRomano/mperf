package com.bromano.mobile.perf.integration

import com.bromano.mobile.perf.PerfettoOptions
import com.bromano.mobile.perf.ProfilerOptionGroup
import com.bromano.mobile.perf.SimpleperfOptions
import com.bromano.mobile.perf.androidProfilerOptions
import com.bromano.mobile.perf.profilers.perfetto.PerfettoProfiler
import com.bromano.mobile.perf.profilers.simpleperf.SimpleperfProfiler
import com.bromano.mobile.perf.utils.Adb
import com.bromano.mobile.perf.utils.ShellExecutor
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.parse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempFile
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AndroidProfilerIntegrationTest {
    private val shell = ShellExecutor()
    private val device =
        System.getProperty("mperf.integration.device")
            ?: shell.getConnectedAndroidDevices().firstOrNull()
    private val instrumentationRunner =
        System.getProperty(
            "mperf.integration.instrumentation",
            "com.example.macrobenchmark/androidx.test.runner.AndroidJUnitRunner",
        )
    private val packageName =
        System.getProperty("mperf.integration.package", "com.example.macrobenchmark.target")
    private val testCase =
        System.getProperty(
            "mperf.integration.testCase",
            "com.example.macrobenchmark.benchmark.LoginBenchmark#loginByIntent",
        )
    private val launchableActivity =
        System.getProperty(
            "mperf.integration.activity",
            "com.example.macrobenchmark.target/.activity.MainActivity",
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
        assertContains(adb.findInstrumentationRunners(), instrumentationRunner)
        assertContains(adb.getTests(instrumentationRunner), testCase)
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

    private fun assertGzipFile(path: Path) {
        Files.newInputStream(path).use { input ->
            assertEquals(0x1F, input.read())
            assertEquals(0x8B, input.read())
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
        val output = createTempFile("macrobenchmark-perfetto", ".perfetto-trace")

        PerfettoProfiler(shell, adb, PerfettoOptions()).executeTest(
            packageName = packageName,
            instrumentationRunner = instrumentationRunner,
            testCase = testCase,
            output = output,
        )

        assertNonEmptyFile(output)
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    fun collects_simpleperf_trace_from_macrobenchmark_sample() {
        assumeEnabled()

        val adb = createAdb()
        assertBenchmarkSampleAvailable(adb)
        val output = createTempFile("macrobenchmark-simpleperf", ".perfetto-trace")

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
    fun collects_perfetto_trace_from_ad_hoc_session() {
        assumeEnabled()

        val adb = createAdb()
        assertBenchmarkSampleAvailable(adb)
        adb.shell("am force-stop $packageName", ignoreErrors = true)
        val output = createTempFile("adhoc-perfetto", ".perfetto-trace")

        PerfettoProfiler(
            shell,
            adb,
            PerfettoOptions(),
            awaitStop = { exerciseTargetApp(adb) },
        ).execute(packageName, output)

        assertNonEmptyFile(output)
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun collects_simpleperf_profile_from_ad_hoc_session() {
        assumeEnabled()

        val adb = createAdb()
        assertBenchmarkSampleAvailable(adb)
        adb.shell("am force-stop $packageName", ignoreErrors = true)
        val output = createTempFile("adhoc-simpleperf", ".json.gz")

        SimpleperfProfiler(
            shell,
            adb,
            defaultSimpleperfOptions(),
            awaitStop = { exerciseTargetApp(adb) },
        ).execute(packageName, output)

        assertNonEmptyFile(output)
        assertGzipFile(output)
    }
}
