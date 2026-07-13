package com.bromano.mobile.perf.profilers

import com.github.ajalt.clikt.core.PrintMessage
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BenchmarkInstrumentationTest {
    @Test
    fun `build command includes explicit output directory and arguments`() {
        val command =
            buildBenchmarkInstrumentationCommand(
                instrumentationRunner = "com.example.benchmark/androidx.test.runner.AndroidJUnitRunner",
                testCase = "com.example.StartupBenchmark#startup",
                outputDirectory = "/storage/emulated/0/Android/media/com.example.benchmark/",
                arguments = linkedMapOf("androidx.benchmark.dryRunMode.enable" to "true"),
            )

        assertEquals(
            "am instrument -w -r -e class \"com.example.StartupBenchmark#startup\" " +
                "-e additionalTestOutputDir \"/storage/emulated/0/Android/media/com.example.benchmark/\" " +
                "-e androidx.benchmark.dryRunMode.enable \"true\" " +
                "com.example.benchmark/androidx.test.runner.AndroidJUnitRunner",
            command,
        )
    }

    @Test
    fun `validation accepts successful instrumentation`() {
        validateBenchmarkInstrumentationOutput("INSTRUMENTATION_CODE: -1")
    }

    @Test
    fun `validation rejects crashes and missing completion codes`() {
        assertThrows<PrintMessage> {
            validateBenchmarkInstrumentationOutput("INSTRUMENTATION_RESULT: shortMsg=Process crashed.")
        }
        assertThrows<PrintMessage> {
            validateBenchmarkInstrumentationOutput("INSTRUMENTATION_STATUS_CODE: 0")
        }
    }

    @Test
    fun `reported output path takes precedence over directory fallback`() {
        val reported = "/storage/emulated/0/Android/media/com.example/new.perfetto-trace"
        val output = "INSTRUMENTATION_RESULT: additionalTestOutputFile_trace=$reported"

        assertEquals(
            reported,
            findNewBenchmarkOutput(
                instrumentationOutput = output,
                filesBeforeRun = setOf("old.perfetto-trace"),
                filesAfterRun = listOf("old.perfetto-trace", "fallback.perfetto-trace"),
                outputDirectory = "/output/",
                matches = { it.endsWith(".perfetto-trace") },
            ),
        )
    }

    @Test
    fun `fallback only returns an artifact created by this run`() {
        assertEquals(
            "/output/new.trace",
            findNewBenchmarkOutput(
                instrumentationOutput = "",
                filesBeforeRun = setOf("stale.trace"),
                filesAfterRun = listOf("stale.trace", "new.trace"),
                outputDirectory = "/output/",
                matches = { it.endsWith(".trace") },
            ),
        )
        assertNull(
            findNewBenchmarkOutput(
                instrumentationOutput = "",
                filesBeforeRun = setOf("stale.trace"),
                filesAfterRun = listOf("stale.trace"),
                outputDirectory = "/output/",
                matches = { it.endsWith(".trace") },
            ),
        )
    }
}
