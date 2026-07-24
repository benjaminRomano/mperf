package com.bromano.mobile.perf.commands.faults

import com.bromano.mobile.perf.AndroidConfig
import com.bromano.mobile.perf.Config
import com.bromano.mobile.perf.faults.FaultEngine
import com.bromano.mobile.perf.utils.FakeShell
import com.github.ajalt.clikt.testing.test
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AndroidFaultsCommandTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `runs exact capture then report with explicit cache provenance`() {
        val shell = FakeShell()
        val engineRoot = temporaryDirectory.resolve("engine")
        val output = temporaryDirectory.resolve("capture")
        val command =
            AndroidFaultsCommand(
                shell,
                Config(android = AndroidConfig(packageName = "com.example.app")),
                FixedFaultEngine(engineRoot),
            )

        val result =
            command.test(
                "--device emulator-5554 --out $output --reboot-before-collect " +
                    "--max-resident-pages 0 --settle-ms 900 --overwrite --no-open",
            )

        assertEquals(0, result.statusCode, result.output)
        assertEquals("uv --version", shell.runCommandCalls[0])
        val capture = shell.runCommandCalls[1]
        assertTrue(capture.contains("'faults.py'"))
        assertTrue(capture.contains("'--package' 'com.example.app'"))
        assertTrue(capture.contains("'--serial' 'emulator-5554'"))
        assertTrue(capture.contains("'--reboot-before-collect'"))
        assertTrue(capture.contains("'--max-resident-pages' '0'"))
        assertTrue(capture.contains("'--settle-ms' '900'"))
        assertTrue(capture.contains("'--pull-apks'"))
        assertTrue(capture.contains("'--overwrite'"))
        val report = shell.runCommandCalls[2]
        assertTrue(report.contains("'report.py'"))
        assertTrue(report.contains("'${output.resolve("report.html")}'"))
    }

    @Test
    fun `supports report-only comparisons without pulling artifacts`() {
        val shell = FakeShell()
        val engineRoot = temporaryDirectory.resolve("engine")
        val output = temporaryDirectory.resolve("capture").also { it.toFile().mkdirs() }
        val comparison = temporaryDirectory.resolve("comparison").also { it.toFile().mkdirs() }
        val command =
            AndroidFaultsCommand(
                shell,
                Config(android = null),
                FixedFaultEngine(engineRoot),
            )

        val result =
            command.test(
                "--package com.example.app --out $output --skip-collect --no-pull-artifacts " +
                    "--compare $comparison --compare-label reordered --allow-incomparable --no-open",
            )

        assertEquals(0, result.statusCode, result.output)
        assertTrue(shell.runCommandCalls[1].contains("'--skip-collect'"))
        assertTrue(shell.runCommandCalls[1].contains("'--no-pull-apks'"))
        assertTrue(shell.runCommandCalls[2].contains("'--compare' '${comparison.toAbsolutePath()}'"))
        assertTrue(shell.runCommandCalls[2].contains("'--compare-label' 'reordered'"))
        assertTrue(shell.runCommandCalls[2].contains("'--allow-incomparable'"))
    }
}

private class FixedFaultEngine(
    private val root: Path,
) : FaultEngine {
    override fun materialize(): Path = root
}
