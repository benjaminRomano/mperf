package com.bromano.mobile.perf.utils

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

class XcodeUtilsTest {
    private lateinit var shell: FakeShell
    private lateinit var xcodeUtils: XcodeUtils

    @BeforeEach
    fun setUp() {
        shell = FakeShell()
        xcodeUtils = XcodeUtils("test-device-id", shell)
    }

    @Test
    fun `getAvailableDevices parses simulator output correctly`() {
        val simulatorOutput =
            """
            == Devices ==
            -- iOS 17.0 --
                iPhone 15 (12345678-1234-1234-1234-123456789012) (Booted)
                iPhone 15 Pro (87654321-4321-4321-4321-210987654321) (Shutdown)
            """.trimIndent()

        shell.runCommandResponses["xcrun simctl list devices available"] = simulatorOutput
        shell.runCommandResponses["xcrun devicectl list devices"] = ""

        val devices = xcodeUtils.getAvailableDevices()

        assertEquals(1, devices.size)
        assertTrue(devices[0].startsWith("Simulator:"))
        assertTrue(devices[0].contains("12345678-1234-1234-1234-123456789012"))
        assertTrue(devices[0].contains("Booted"))
    }

    @Test
    fun `getAvailableDevices parses physical device output correctly`() {
        val deviceOutput =
            """
            Found 1 device:
            My iPhone (1234567890abcdef1234567890abcdef12345678) connected
            """.trimIndent()

        shell.runCommandResponses["xcrun simctl list devices available"] = ""
        shell.runCommandResponses["xcrun devicectl list devices"] = deviceOutput

        val devices = xcodeUtils.getAvailableDevices()

        assertEquals(1, devices.size)
        assertTrue(devices[0].startsWith("Device:"))
        assertTrue(devices[0].contains("1234567890abcdef1234567890abcdef12345678"))
    }

    @Test
    fun `getAvailableDevices handles no devices available`() {
        shell.runCommandResponses["xcrun simctl list devices available"] = ""
        shell.runCommandResponses["xcrun devicectl list devices"] = ""

        val devices = xcodeUtils.getAvailableDevices()

        assertTrue(devices.isEmpty())
    }

    @Test
    fun `isSimulator identifies simulator UUIDs correctly`() {
        assertTrue(xcodeUtils.isSimulator("12345678-1234-1234-1234-123456789012"))
        assertFalse(xcodeUtils.isSimulator("1234567890abcdef1234567890abcdef12345678"))
        assertFalse(xcodeUtils.isSimulator("invalid-id"))
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
        val simulatorUtils = XcodeUtils("12345678-1234-1234-1234-123456789012", simulatorShell)

        simulatorUtils.launchApp("com.example.app")

        assertTrue(
            "xcrun simctl launch 12345678-1234-1234-1234-123456789012 com.example.app" in simulatorShell.runCommandCalls,
        )
    }

    @Test
    fun `isAppRunning on simulator checks launchctl list`() {
        val simulatorShell = FakeShell()
        simulatorShell.runCommandResponses["xcrun simctl spawn 12345678-1234-1234-1234-123456789012 launchctl list"] =
            "com.example.app\ncom.other.app"
        val simulatorUtils = XcodeUtils("12345678-1234-1234-1234-123456789012", simulatorShell)

        assertTrue(simulatorUtils.isAppRunning("com.example.app"))
        assertFalse(simulatorUtils.isAppRunning("com.unknown"))
    }

    @Test
    fun `record uses attach when app already running`() {
        val simulatorShell = FakeShell()
        val simulatorId = "12345678-1234-1234-1234-123456789012"
        simulatorShell.runCommandResponses["xcrun simctl spawn $simulatorId launchctl list"] = "com.example.app"
        simulatorShell.startProcessHandler = { FakeShell.FakeProcess() }
        simulatorShell.newProcessBuilderHandler = { _ ->
            ProcessBuilder(listOf("bash", "-lc", "sleep 0.1"))
        }
        val simulatorUtils = XcodeUtils(simulatorId, simulatorShell)

        withTemporaryInput("\n") {
            simulatorUtils.record(
                template = "Allocations",
                instruments = listOf("allocations", "time-profiler"),
                bundleIdentifier = "com.example.app",
                outputPath = "/tmp/output.trace",
            )
        }

        val recordedCommand = simulatorShell.newProcessBuilderCommands.single()
        assertTrue(recordedCommand.contains("--attach com.example.app"))
        assertTrue(recordedCommand.contains("--template \"Allocations\""))
        assertTrue(recordedCommand.contains("--instrument allocations"))
        assertTrue(recordedCommand.contains("--instrument time-profiler"))
        assertTrue(simulatorShell.startProcessCommands.single().startsWith("kill -INT"))
    }

    @Test
    fun `record uses launch when app not running`() {
        val simulatorShell = FakeShell()
        val simulatorId = "12345678-1234-1234-1234-123456789012"
        simulatorShell.runCommandResponses["xcrun simctl spawn $simulatorId launchctl list"] = ""
        simulatorShell.startProcessHandler = { FakeShell.FakeProcess() }
        simulatorShell.newProcessBuilderHandler = { _ ->
            ProcessBuilder(listOf("bash", "-lc", "sleep 0.1"))
        }
        val simulatorUtils = XcodeUtils(simulatorId, simulatorShell)

        withTemporaryInput("\n") {
            simulatorUtils.record(
                template = "Time Profiler",
                instruments = emptyList(),
                bundleIdentifier = "com.example.app",
                outputPath = "/tmp/output.trace",
            )
        }

        val recordedCommand = simulatorShell.newProcessBuilderCommands.single()
        assertTrue(recordedCommand.contains("--launch com.example.app"))
        assertTrue(recordedCommand.contains("--output \"/tmp/output.trace\""))
    }

    private fun withTemporaryInput(
        input: String,
        block: () -> Unit,
    ) {
        val originalIn = System.`in`
        System.setIn(ByteArrayInputStream(input.toByteArray()))
        try {
            block()
        } finally {
            System.setIn(originalIn)
        }
    }
}
