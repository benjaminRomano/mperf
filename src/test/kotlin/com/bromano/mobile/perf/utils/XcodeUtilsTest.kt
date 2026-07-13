package com.bromano.mobile.perf.utils

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText

class XcodeUtilsTest {
    private lateinit var shell: FakeShell
    private lateinit var xcodeUtils: XcodeUtils

    @BeforeEach
    fun setUp() {
        shell = FakeShell()
        xcodeUtils = XcodeUtils("test-device-id", shell)
    }

    private fun simulatorJson(
        state: String = "Booted",
        udid: String = "12345678-1234-1234-1234-123456789012",
        name: String = "iPhone 15",
    ): String =
        """
        {
          "devices": {
            "com.apple.CoreSimulator.SimRuntime.iOS-17-0": [
              {"name":"$name","udid":"$udid","state":"$state","isAvailable":true}
            ]
          }
        }
        """.trimIndent()

    @Test
    fun `getAvailableDevices parses simulator output correctly`() {
        shell.runCommandResponses["xcrun simctl list devices available --json"] = simulatorJson()

        val devices = xcodeUtils.getAvailableDevices()

        assertEquals(1, devices.size)
        assertTrue(devices[0].startsWith("Simulator:"))
        assertTrue(devices[0].contains("12345678-1234-1234-1234-123456789012"))
        assertTrue(devices[0].contains("Booted"))
    }

    @Test
    fun `getAvailableDevices parses physical device JSON correctly`(
        @TempDir tempDir: Path,
    ) {
        val deviceOutput =
            """
            {"result":{"devices":[{
              "identifier":"ABCDEF12-3456-7890-ABCD-EF1234567890",
              "connectionProperties":{"tunnelState":"connected"},
              "deviceProperties":{"name":"My iPhone"}
            }]}}
            """.trimIndent()
        val jsonOutput = tempDir.resolve("devices.json").also { it.writeText(deviceOutput) }
        xcodeUtils = XcodeUtils("test-device-id", shell, temporaryFile = { jsonOutput })

        shell.runCommandResponses["xcrun simctl list devices available --json"] = ""

        val devices = xcodeUtils.getAvailableDevices()

        assertEquals(1, devices.size)
        assertTrue(devices[0].startsWith("Device:"))
        assertTrue(devices[0].contains("ABCDEF12-3456-7890-ABCD-EF1234567890"))
    }

    @Test
    fun `getAvailableDevices handles no devices available`() {
        shell.runCommandResponses["xcrun simctl list devices available --json"] = ""

        val devices = xcodeUtils.getAvailableDevices()

        assertTrue(devices.isEmpty())
    }

    @Test
    fun `isSimulatorIdentifier checks CoreSimulator inventory instead of UUID shape`() {
        shell.runCommandResponses["xcrun simctl list devices available --json"] = simulatorJson()

        assertTrue(xcodeUtils.isSimulatorIdentifier("12345678-1234-1234-1234-123456789012"))
        assertFalse(xcodeUtils.isSimulatorIdentifier("ABCDEF12-3456-7890-ABCD-EF1234567890"))
    }

    @Test
    fun `getInstrumentsTemplates parses xctrace output correctly`() {
        val xctraceOutput =
            """
            == Standard Templates ==
            Activity Monitor
            Allocations
            Time Profiler
            """.trimIndent()

        shell.runCommandResponses["xcrun xctrace list templates"] = xctraceOutput

        val templates = xcodeUtils.getInstrumentsTemplates()

        assertTrue("Time Profiler" in templates)
        assertTrue("Allocations" in templates)
        assertFalse(templates.any { it.startsWith("==") })
    }

    @Test
    fun `launchApp on simulator calls simctl launch`() {
        val simulatorShell = FakeShell()
        simulatorShell.runCommandResponses["xcrun simctl list devices available --json"] = simulatorJson()
        simulatorShell.runCommandResponses[
            "xcrun simctl launch '12345678-1234-1234-1234-123456789012' 'com.example.app'",
        ] = "com.example.app: 321"
        val simulatorUtils = XcodeUtils("12345678-1234-1234-1234-123456789012", simulatorShell)

        simulatorUtils.launchApp("com.example.app")

        assertTrue(
            "xcrun simctl launch '12345678-1234-1234-1234-123456789012' 'com.example.app'" in
                simulatorShell.runCommandCalls,
        )
    }

    @Test
    fun `isAppRunning on simulator checks launchctl list`() {
        val simulatorShell = FakeShell()
        simulatorShell.runCommandResponses["xcrun simctl list devices available --json"] = simulatorJson()
        simulatorShell.runCommandResponses[
            "xcrun simctl spawn '12345678-1234-1234-1234-123456789012' launchctl list",
        ] = "123\t0\tUIKitApplication:com.example.app[abc]\n456\t0\tUIKitApplication:com.example.application[def]"
        val simulatorUtils = XcodeUtils("12345678-1234-1234-1234-123456789012", simulatorShell)

        assertTrue(simulatorUtils.isAppRunning("com.example.app"))
        assertFalse(simulatorUtils.isAppRunning("com.unknown"))
    }

    @Test
    fun `record collects all host processes when simulator app is already running`() {
        val simulatorShell = FakeShell()
        val simulatorId = "12345678-1234-1234-1234-123456789012"
        simulatorShell.runCommandResponses["xcrun simctl list devices available --json"] = simulatorJson()
        simulatorShell.runCommandResponses["xcrun simctl spawn '$simulatorId' launchctl list"] =
            "123\t0\tUIKitApplication:com.example.app[abc]"
        simulatorShell.startProcessHandler = { FakeShell.FakeProcess() }
        simulatorShell.newProcessBuilderHandler = { _ ->
            ProcessBuilder(listOf("bash", "-lc", "sleep 0.1"))
        }
        val simulatorUtils = XcodeUtils(simulatorId, simulatorShell, awaitStop = {})

        simulatorUtils.record(
            template = "Allocations",
            instruments = listOf("Core Animation", "Time Profiler"),
            bundleIdentifier = "com.example.app",
            outputPath = "/tmp/output.trace",
        )

        val recordedCommand = simulatorShell.newProcessBuilderCommands.single()
        assertTrue(recordedCommand.contains("'--all-processes'"))
        assertTrue(recordedCommand.contains("'--template' 'Allocations'"))
        assertTrue(recordedCommand.contains("'--instrument' 'Core Animation'"))
        assertTrue(recordedCommand.contains("'--instrument' 'Time Profiler'"))
        assertTrue(simulatorShell.startProcessCommands.single().startsWith("kill -INT"))
        assertFalse(recordedCommand.contains("'--attach'"))
        assertFalse(recordedCommand.contains("'--device'"))
    }

    @Test
    fun `record launches simulator app with simctl then records host processes`() {
        val simulatorShell = FakeShell()
        val simulatorId = "12345678-1234-1234-1234-123456789012"
        simulatorShell.runCommandResponses["xcrun simctl list devices available --json"] = simulatorJson()
        simulatorShell.runCommandResponses["xcrun simctl spawn '$simulatorId' launchctl list"] = ""
        simulatorShell.runCommandResponses["xcrun simctl launch '$simulatorId' 'com.example.app'"] =
            "com.example.app: 321"
        simulatorShell.startProcessHandler = { FakeShell.FakeProcess() }
        simulatorShell.newProcessBuilderHandler = { _ ->
            ProcessBuilder(listOf("bash", "-lc", "sleep 0.1"))
        }
        val simulatorUtils =
            XcodeUtils(
                simulatorId,
                simulatorShell,
                awaitStop = {},
                awaitSimulatorProcessRegistration = {},
            )

        simulatorUtils.record(
            template = "Time Profiler",
            instruments = emptyList(),
            bundleIdentifier = "com.example.app",
            outputPath = "/tmp/output.trace",
        )

        val recordedCommand = simulatorShell.newProcessBuilderCommands.single()
        assertTrue(recordedCommand.endsWith("'--all-processes'"))
        assertTrue(recordedCommand.contains("'--output' '/tmp/output.trace'"))
        assertTrue("xcrun simctl launch '$simulatorId' 'com.example.app'" in simulatorShell.runCommandCalls)
    }

    @Test
    fun `record preserves timeout classification after graceful termination`() {
        val waitResults = ArrayDeque(listOf(false, true))
        val simulatorShell =
            object : FakeShell() {
                override fun waitFor(
                    process: Process,
                    timeout: Long,
                    unit: TimeUnit,
                ): Boolean = waitResults.removeFirst()
            }
        val simulatorId = "12345678-1234-1234-1234-123456789012"
        simulatorShell.runCommandResponses["xcrun simctl list devices available --json"] = simulatorJson()
        simulatorShell.runCommandResponses["xcrun simctl spawn '$simulatorId' launchctl list"] =
            "123\t0\tUIKitApplication:com.example.app[abc]"
        val process = RecordingProcess()
        val simulatorUtils = XcodeUtils(simulatorId, simulatorShell, processStarter = { process })

        val error =
            assertThrows(IllegalStateException::class.java) {
                simulatorUtils.record("Time Profiler", emptyList(), "com.example.app", "/tmp/output.trace", "1s")
            }

        assertEquals("xctrace did not stop before the collection timeout; the trace may be unusable", error.message)
        assertTrue(process.destroyCalled)
        assertFalse(process.destroyForciblyCalled)
        assertTrue(waitResults.isEmpty())
    }

    @Test
    fun `record reports non-retryable failure when timed-out process cannot be reaped`() {
        val waitResults = ArrayDeque(listOf(false, false, false))
        val simulatorShell =
            object : FakeShell() {
                override fun waitFor(
                    process: Process,
                    timeout: Long,
                    unit: TimeUnit,
                ): Boolean = waitResults.removeFirst()
            }
        val simulatorId = "12345678-1234-1234-1234-123456789012"
        simulatorShell.runCommandResponses["xcrun simctl list devices available --json"] = simulatorJson()
        simulatorShell.runCommandResponses["xcrun simctl spawn '$simulatorId' launchctl list"] =
            "123\t0\tUIKitApplication:com.example.app[abc]"
        val process = RecordingProcess()
        val simulatorUtils = XcodeUtils(simulatorId, simulatorShell, processStarter = { process })

        val error =
            assertThrows(IllegalStateException::class.java) {
                simulatorUtils.record("Time Profiler", emptyList(), "com.example.app", "/tmp/output.trace", "1s")
            }

        assertEquals("xctrace could not be terminated after the collection timeout", error.message)
        assertTrue(process.destroyCalled)
        assertTrue(process.destroyForciblyCalled)
        assertTrue(waitResults.isEmpty())
    }

    @Test
    fun `isAppInstalled checks exact simulator bundle identifier`() {
        val simulatorShell = FakeShell()
        val simulatorId = "12345678-1234-1234-1234-123456789012"
        simulatorShell.runCommandResponses["xcrun simctl list devices available --json"] = simulatorJson()
        simulatorShell.runCommandResponses[
            "xcrun simctl get_app_container '$simulatorId' 'com.example.app' app",
        ] = "/tmp/Example.app"
        val simulatorUtils = XcodeUtils(simulatorId, simulatorShell)

        assertTrue(simulatorUtils.isAppInstalled("com.example.app"))
        assertFalse(simulatorUtils.isAppInstalled("com.example.other"))
    }

    private class RecordingProcess : Process() {
        var destroyCalled = false
            private set
        var destroyForciblyCalled = false
            private set

        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()

        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun waitFor(): Int = 0

        override fun waitFor(
            timeout: Long,
            unit: TimeUnit,
        ): Boolean = error("Shell controls waits in this test")

        override fun exitValue(): Int = if (destroyCalled) 143 else 0

        override fun destroy() {
            destroyCalled = true
        }

        override fun destroyForcibly(): Process {
            destroyForciblyCalled = true
            return this
        }

        override fun isAlive(): Boolean = !destroyCalled || destroyForciblyCalled
    }
}
