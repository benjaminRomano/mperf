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
import kotlin.test.assertTrue

class IosFaultsCommandTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `runs simulator capture with auditable cache controls`() {
        val shell = FakeShell()
        val output = temporaryDirectory.resolve("capture")
        val command =
            IosFaultsCommand(
                shell,
                Config(
                    ios =
                        IosConfig(
                            bundleIdentifier = "com.example.app",
                            deviceId = "SIMULATOR-UDID",
                        ),
                ),
                FixedIosFaultEngine(temporaryDirectory.resolve("engine")),
            )

        val result =
            command.test(
                "--out $output --cache-policy auto --require-cold-cache --allow-host-pressure " +
                    "--residency-threshold 0.02 --settle-seconds 2.5 --no-open",
            )

        assertEquals(0, result.statusCode, result.output)
        assertEquals("uv --version", shell.runCommandCalls[0])
        val capture = shell.runCommandCalls[1]
        assertTrue(capture.contains("'faults.py'"))
        assertTrue(capture.contains("'--bundle-id' 'com.example.app'"))
        assertTrue(capture.contains("'--device' 'SIMULATOR-UDID'"))
        assertTrue(capture.contains("'--cache-policy' 'auto'"))
        assertTrue(capture.contains("'--require-cold-cache'"))
        assertTrue(capture.contains("'--allow-host-pressure'"))
        assertTrue(capture.contains("'--residency-threshold' '0.02'"))
        assertTrue(capture.contains("'--settle-seconds' '2.5'"))
    }

    @Test
    fun `requires bundle or installable app`() {
        val command =
            IosFaultsCommand(
                FakeShell(),
                Config(ios = null),
                FixedIosFaultEngine(temporaryDirectory.resolve("engine")),
            )

        val result = command.test("--out ${temporaryDirectory.resolve("capture")} --no-open")

        assertEquals(1, result.statusCode)
        assertTrue(result.output.contains("Bundle identifier must be provided"))
    }

    @Test
    fun `reprocesses a portable saved capture without bundle identity`() {
        val shell = FakeShell()
        val output = temporaryDirectory.resolve("capture")
        val command =
            IosFaultsCommand(
                shell,
                Config(ios = null),
                FixedIosFaultEngine(temporaryDirectory.resolve("engine")),
            )

        val result = command.test("--out $output --skip-collect --no-open")

        assertEquals(0, result.statusCode, result.output)
        assertTrue(shell.runCommandCalls[1].contains("'--skip-collect'"))
        assertTrue(!shell.runCommandCalls[1].contains("'--bundle-id'"))
        assertTrue(!shell.runCommandCalls[1].contains("'--app'"))
    }

    @Test
    fun `rejects contradictory strict and unconfirmed cache flags`() {
        val shell = FakeShell()
        val command =
            IosFaultsCommand(
                shell,
                Config(ios = IosConfig(bundleIdentifier = "com.example.app")),
                FixedIosFaultEngine(temporaryDirectory.resolve("engine")),
            )

        val result =
            command.test(
                "--out ${temporaryDirectory.resolve("capture")} " +
                    "--require-cold-cache --allow-unconfirmed-cache --no-open",
            )

        assertEquals(1, result.statusCode)
        assertTrue(result.output.contains("cannot be combined"))
        assertTrue(shell.runCommandCalls.isEmpty())
    }
}

private class FixedIosFaultEngine(
    private val root: Path,
) : FaultEngine {
    override fun materialize(): Path = root
}
