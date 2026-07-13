package com.bromano.mobile.perf.utils

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
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
}
