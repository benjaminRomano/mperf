package com.bromano.mobile.perf.commands.ios

import com.bromano.mobile.perf.Config
import com.bromano.mobile.perf.IosConfig
import com.bromano.mobile.perf.profilers.ProfilerExecutor
import com.bromano.mobile.perf.utils.FakeShell
import com.bromano.mobile.perf.utils.ProfileViewer
import com.github.ajalt.clikt.testing.test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

class IosStartCommandTest {
    private lateinit var mockExecutor: ProfilerExecutor
    private lateinit var shell: TestShell
    private lateinit var config: Config
    private lateinit var command: IosStartCommand

    class TestShell : FakeShell() {
        var selectChoiceResult: String? = null
        var selectChoiceCallCount = 0

        override fun selectChoice(
            choices: List<String>,
            prompt: String?,
        ): String? {
            selectChoiceCallCount++
            return selectChoiceResult
        }
    }

    @BeforeEach
    fun setUp() {
        mockExecutor = mock()
        shell = TestShell()
        config = Config(ios = IosConfig(bundleIdentifier = "com.example.app"))
        command = IosStartCommand(shell, config, mockExecutor)
    }

    @Test
    fun `command uses bundle identifier from config when not provided`() {
        shell.selectChoiceResult = "Simulator: iPhone 15 (12345678-1234-1234-1234-123456789012) [Booted]"

        val result = command.test("--device 12345678-1234-1234-1234-123456789012")

        assertEquals(0, result.statusCode)
        verify(mockExecutor).execute(
            any(),
            eq(shell),
            eq("12345678-1234-1234-1234-123456789012"),
            eq("com.example.app"),
            any(),
            eq(null),
        )
    }

    @Test
    fun `command uses provided bundle identifier over config`() {
        shell.selectChoiceResult = "Simulator: iPhone 15 (12345678-1234-1234-1234-123456789012) [Booted]"

        val result = command.test("--bundle com.other.app --device 12345678-1234-1234-1234-123456789012")

        assertEquals(0, result.statusCode)
        verify(mockExecutor).execute(
            any(),
            eq(shell),
            eq("12345678-1234-1234-1234-123456789012"),
            eq("com.other.app"),
            any(),
            eq(null),
        )
    }

    @Test
    fun `command fails when no bundle identifier provided and none in config`() {
        val configWithoutBundle = Config(ios = null)
        val commandWithoutBundle = IosStartCommand(shell, configWithoutBundle, mockExecutor)

        val result = commandWithoutBundle.test("")

        assertEquals(1, result.statusCode)
        assertTrue(result.output.contains("Bundle identifier must be provided"))
    }

    @Test
    fun `command prompts for device selection when none provided`() {
        shell.selectChoiceResult = "Device: My iPhone (1234567890abcdef1234567890abcdef12345678)"

        val result = command.test("--bundle com.example.app")

        assertEquals(0, result.statusCode)
        assertEquals(1, shell.selectChoiceCallCount)
        verify(mockExecutor).execute(
            any(),
            eq(shell),
            eq("1234567890abcdef1234567890abcdef12345678"),
            eq("com.example.app"),
            any(),
            eq(null),
        )
    }

    @Test
    fun `command fails when no devices available`() {
        shell.selectChoiceResult = null // Simulate no device selected

        val result = command.test("--bundle com.example.app")

        assertEquals(1, result.statusCode)
        assertTrue(result.output.contains("No devices/simulators available"))
    }

    @Test
    fun `command creates default output path when not provided`() {
        shell.selectChoiceResult = "Simulator: iPhone 15 (12345678-1234-1234-1234-123456789012) [Booted]"

        val result = command.test("--bundle com.example.app --device 12345678-1234-1234-1234-123456789012")

        assertEquals(0, result.statusCode)

        // Verify that execute was called and capture the output path
        val argumentCaptor = argumentCaptor<java.nio.file.Path>()
        verify(mockExecutor).execute(
            any(),
            eq(shell),
            eq("12345678-1234-1234-1234-123456789012"),
            eq("com.example.app"),
            argumentCaptor.capture(),
            eq(null),
        )

        val outputPath = argumentCaptor.firstValue
        assertTrue(outputPath.toString().contains("artifacts/trace_out"))
        assertTrue(outputPath.toString().contains("instruments-"))
        assertTrue(outputPath.toString().endsWith(".trace"))
    }

    @Test
    fun `command uses provided output path`() {
        shell.selectChoiceResult = "Simulator: iPhone 15 (12345678-1234-1234-1234-123456789012) [Booted]"
        val customOutput = "/custom/path/trace.trace"

        val result = command.test("--bundle com.example.app --device 12345678-1234-1234-1234-123456789012 --out $customOutput")

        assertEquals(0, result.statusCode)
        verify(mockExecutor).execute(
            any(),
            eq(shell),
            eq("12345678-1234-1234-1234-123456789012"),
            eq("com.example.app"),
            eq(
                java.nio.file.Paths
                    .get(customOutput),
            ),
            eq(null),
        )
    }

    @Test
    fun `command passes profile viewer override to executor`() {
        shell.selectChoiceResult = "Simulator: iPhone 15 (12345678-1234-1234-1234-123456789012) [Booted]"

        val result = command.test("--bundle com.example.app --device 12345678-1234-1234-1234-123456789012 --ui PERFETTO")

        assertEquals(0, result.statusCode)
        verify(mockExecutor).execute(
            any(),
            eq(shell),
            eq("12345678-1234-1234-1234-123456789012"),
            eq("com.example.app"),
            any(),
            eq(ProfileViewer.PERFETTO),
        )
    }

    @Test
    fun `command extracts device UDID from simulator selection correctly`() {
        shell.selectChoiceResult = "Simulator: iPhone 15 Pro Max (ABCDEF12-3456-7890-ABCD-EF1234567890) [Shutdown]"

        val result = command.test("--bundle com.example.app")

        assertEquals(0, result.statusCode)
        verify(mockExecutor).execute(
            any(),
            eq(shell),
            eq("ABCDEF12-3456-7890-ABCD-EF1234567890"),
            eq("com.example.app"),
            any(),
            eq(null),
        )
    }

    @Test
    fun `command extracts device UDID from physical device selection correctly`() {
        shell.selectChoiceResult = "Device: My iPhone 15 (1234567890ABCDEF1234567890ABCDEF12345678)"

        val result = command.test("--bundle com.example.app")

        assertEquals(0, result.statusCode)
        verify(mockExecutor).execute(
            any(),
            eq(shell),
            eq("1234567890ABCDEF1234567890ABCDEF12345678"),
            eq("com.example.app"),
            any(),
            eq(null),
        )
    }
}
