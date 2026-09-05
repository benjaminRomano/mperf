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
        val workflow = RecordingAndroidFaultWorkflow()
        val command =
            AndroidFaultsCommand(
                shell,
                Config(android = AndroidConfig(packageName = "com.example.app")),
                FixedFaultEngine(engineRoot),
                workflow,
            )

        val result =
            command.test(
                "--device emulator-5554 --out $output --reboot-before-collect " +
                    "--max-resident-pages 0 --settle-ms 900 --native-stacks --dwarf-stacks " +
                    "--reclaim-mapped-apks --overwrite --no-open",
            )

        assertEquals(0, result.statusCode, result.output)
        val request = workflow.requests.single()
        assertEquals("com.example.app", request.packageName)
        assertEquals("emulator-5554", request.device)
        assertTrue(request.rebootBeforeCollect)
        assertEquals(0, request.maxResidentPages)
        assertEquals(900, request.settleMs)
        assertTrue(request.pullArtifacts)
        assertTrue(request.nativeStacks)
        assertTrue(request.dwarfStacks)
        assertTrue(request.reclaimMappedApks)
        assertTrue(request.overwrite)
        assertEquals("speed-profile", request.compilation)
    }

    @Test
    fun `supports report-only comparisons without pulling artifacts`() {
        val shell = FakeShell()
        val engineRoot = temporaryDirectory.resolve("engine")
        val output = temporaryDirectory.resolve("capture").also { it.toFile().mkdirs() }
        val comparison = temporaryDirectory.resolve("comparison").also { it.toFile().mkdirs() }
        val workflow = RecordingAndroidFaultWorkflow()
        val command =
            AndroidFaultsCommand(
                shell,
                Config(android = null),
                FixedFaultEngine(engineRoot),
                workflow,
            )

        val result =
            command.test(
                "--out $output --skip-collect --no-pull-artifacts " +
                    "--compare $comparison --compare-label reordered --allow-incomparable --compilation as-is --no-open",
            )

        assertEquals(0, result.statusCode, result.output)
        val request = workflow.requests.single()
        assertTrue(request.skipCollect)
        assertEquals(null, request.packageName)
        assertTrue(!request.pullArtifacts)
        assertEquals(comparison.toAbsolutePath(), request.comparison)
        assertEquals("reordered", request.comparisonLabel)
        assertTrue(request.allowIncomparable)
        assertEquals("as-is", request.compilation)
    }

    @Test
    fun `new collection still requires a package`() {
        val command =
            AndroidFaultsCommand(
                FakeShell(),
                Config(android = null),
                FixedFaultEngine(temporaryDirectory.resolve("engine")),
            )

        val result = command.test("--out ${temporaryDirectory.resolve("capture")} --no-open")

        assertEquals(1, result.statusCode)
        assertTrue(result.output.contains("Package name must be provided"))
    }

    @Test
    fun `saved capture identity overrides configured package`() {
        val shell = FakeShell()
        val workflow = RecordingAndroidFaultWorkflow()
        val command =
            AndroidFaultsCommand(
                shell,
                Config(android = AndroidConfig(packageName = "com.stale.config")),
                FixedFaultEngine(temporaryDirectory.resolve("engine")),
                workflow,
            )

        val result =
            command.test(
                "--out ${temporaryDirectory.resolve("saved-capture")} --skip-collect --no-open",
            )

        assertEquals(0, result.statusCode, result.output)
        assertEquals(null, workflow.requests.single().packageName)
    }
}

private class RecordingAndroidFaultWorkflow : AndroidFaultWorkflow {
    val requests = mutableListOf<AndroidFaultRequest>()

    override fun run(request: AndroidFaultRequest): Path {
        requests += request
        return request.output.resolve("report.html")
    }
}

private class FixedFaultEngine(
    private val root: Path,
) : FaultEngine {
    override fun materialize(): Path = root
}
