package com.bromano.mobile.perf.commands.faults

import com.bromano.mobile.perf.Config
import com.bromano.mobile.perf.IosConfig
import com.bromano.mobile.perf.faults.FaultEngine
import com.bromano.mobile.perf.utils.FakeShell
import com.github.ajalt.clikt.testing.test
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IosFaultsCommandTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `runs simulator capture with auditable cache controls`() {
        val workflow = RecordingIosFaultWorkflow()
        val output = temporaryDirectory.resolve("capture")
        val command =
            command(
                Config(ios = IosConfig(bundleIdentifier = "com.example.app", deviceId = "SIMULATOR-UDID")),
                workflow,
            )

        val result =
            command.test(
                "--out $output --cache-policy auto --require-cold-cache --allow-host-pressure " +
                    "--residency-threshold 0.02 --settle-seconds 2.5 --no-open",
            )

        assertEquals(0, result.statusCode, result.output)
        val request = workflow.requests.single()
        assertEquals("com.example.app", request.bundleIdentifier)
        assertEquals("SIMULATOR-UDID", request.device)
        assertEquals("auto", request.cachePolicy)
        assertTrue(request.requireColdCache)
        assertTrue(request.allowHostPressure)
        assertEquals(0.02, request.residencyThreshold)
        assertEquals(2.5, request.settleSeconds)
    }

    @Test
    fun `requires bundle or installable app`() {
        val result =
            command(Config(ios = null), RecordingIosFaultWorkflow())
                .test("--out ${temporaryDirectory.resolve("capture")} --no-open")

        assertEquals(1, result.statusCode)
        assertTrue(result.output.contains("Bundle identifier must be provided"))
    }

    @Test
    fun `reprocesses a portable saved capture without bundle identity`() {
        val workflow = RecordingIosFaultWorkflow()
        val output = temporaryDirectory.resolve("capture")
        val result = command(Config(ios = null), workflow).test("--out $output --skip-collect --no-open")

        assertEquals(0, result.statusCode, result.output)
        val request = workflow.requests.single()
        assertTrue(request.skipCollect)
        assertEquals(null, request.bundleIdentifier)
        assertEquals(null, request.app)
    }

    @Test
    fun `rejects contradictory strict and unconfirmed cache flags`() {
        val workflow = RecordingIosFaultWorkflow()
        val result =
            command(Config(ios = IosConfig(bundleIdentifier = "com.example.app")), workflow)
                .test(
                    "--out ${temporaryDirectory.resolve("capture")} " +
                        "--require-cold-cache --allow-unconfirmed-cache --no-open",
                )

        assertEquals(1, result.statusCode)
        assertTrue(result.output.contains("cannot be combined"))
        assertTrue(workflow.requests.isEmpty())
    }

    @Test
    fun `explicit app does not inherit configured bundle`() {
        val app =
            temporaryDirectory
                .resolve("Different.app")
                .toFile()
                .also { it.mkdirs() }
                .toPath()
        val workflow = RecordingIosFaultWorkflow()
        val result =
            command(Config(ios = IosConfig(bundleIdentifier = "com.stale.config")), workflow)
                .test("--app $app --out ${temporaryDirectory.resolve("capture")} --no-open")

        assertEquals(0, result.statusCode, result.output)
        val request = workflow.requests.single()
        assertEquals(null, request.bundleIdentifier)
        assertEquals(app.toAbsolutePath(), request.app)
    }

    @Test
    fun `explicit bundle overrides configured bundle`() {
        val workflow = RecordingIosFaultWorkflow()
        val result =
            command(Config(ios = IosConfig(bundleIdentifier = "com.stale.config")), workflow)
                .test("--bundle com.explicit.app --out ${temporaryDirectory.resolve("capture")} --no-open")

        assertEquals(0, result.statusCode, result.output)
        assertEquals("com.explicit.app", workflow.requests.single().bundleIdentifier)
    }

    @Test
    fun `recording window must cover settle window`() {
        val workflow = RecordingIosFaultWorkflow()
        val result =
            command(Config(ios = IosConfig(bundleIdentifier = "com.example.app")), workflow)
                .test(
                    "--settle-seconds 2.1 --time-limit 2 " +
                        "--out ${temporaryDirectory.resolve("capture")} --no-open",
                )

        assertEquals(1, result.statusCode)
        assertTrue(result.output.contains("must be at least"))
        assertTrue(workflow.requests.isEmpty())
    }

    @Test
    fun `recording window accepts ceiling of settle window`() {
        val workflow = RecordingIosFaultWorkflow()
        val result =
            command(Config(ios = IosConfig(bundleIdentifier = "com.example.app")), workflow)
                .test(
                    "--settle-seconds 2.1 --time-limit 3 " +
                        "--out ${temporaryDirectory.resolve("capture")} --no-open",
                )

        assertEquals(0, result.statusCode, result.output)
        assertEquals(3, workflow.requests.single().timeLimit)
        assertFalse(workflow.requests.single().skipCollect)
    }

    private fun command(
        config: Config,
        workflow: IosFaultWorkflow,
    ) = IosFaultsCommand(
        FakeShell(),
        config,
        FixedIosFaultEngine(temporaryDirectory.resolve("engine")),
        workflow,
    )
}

private class RecordingIosFaultWorkflow : IosFaultWorkflow {
    val requests = mutableListOf<IosFaultRequest>()

    override fun run(request: IosFaultRequest): Path {
        requests += request
        return request.output.resolve("report.html")
    }
}

private class FixedIosFaultEngine(
    private val root: Path,
) : FaultEngine {
    override fun materialize(): Path = root
}
