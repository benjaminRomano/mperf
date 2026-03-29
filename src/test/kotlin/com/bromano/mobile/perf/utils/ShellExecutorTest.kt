package com.bromano.mobile.perf.utils

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import java.time.Duration
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ShellExecutorTest {
    private class AdbDevicesShellExecutor(
        private val adbDevicesOutput: String,
    ) : ShellExecutor() {
        override fun runCommand(
            command: String,
            ignoreErrors: Boolean,
        ): String = if (command == "adb devices") adbDevicesOutput else ""

        override fun runCommand(
            command: String,
            ignoreErrors: Boolean,
            shell: Boolean,
            redirectOutput: ProcessBuilder.Redirect,
            redirectError: ProcessBuilder.Redirect,
        ): String = if (command == "adb devices") adbDevicesOutput else ""
    }

    private val shell = ShellExecutor()

    @Test
    fun `getConnectedAndroidDevices parses adb output`() {
        val output = (
            "List of devices attached\n" +
                "emulator-5554\tdevice\n" +
                "0123456789ABCDEF\tdevice\n" +
                "offline-serial\toffline\n" +
                "unauthorized-serial\tunauthorized\n"
        )

        val shell = AdbDevicesShellExecutor(output)
        val devices = shell.getConnectedAndroidDevices()
        assertEquals(listOf("emulator-5554", "0123456789ABCDEF"), devices)
    }

    @Test
    fun `runCommand strips only trailing line terminators`() {
        val output = shell.runCommand("printf '  hello  \\n\\n'")

        assertEquals("  hello  ", output)
    }

    @Test
    fun `runCommand captures stderr when requested`() {
        val stderr =
            shell.runCommand(
                command = "printf 'warn\\n' >&2",
                redirectOutput = ProcessBuilder.Redirect.INHERIT,
                redirectError = ProcessBuilder.Redirect.PIPE,
            )

        assertEquals("warn", stderr)
    }

    @Test
    fun `runCommand throws with exit code and stderr on failure`() {
        val error =
            assertFailsWith<ShellCommandException> {
                shell.runCommand(
                    command = "printf 'boom\\n' >&2; exit 17",
                    redirectOutput = ProcessBuilder.Redirect.PIPE,
                    redirectError = ProcessBuilder.Redirect.PIPE,
                )
            }

        assertEquals(17, error.exitCode)
        assertContains(error.message.orEmpty(), "boom")
    }

    @Test
    fun `runCommand returns captured stdout when errors are ignored`() {
        val output =
            shell.runCommand(
                command = "printf 'partial output'; exit 9",
                ignoreErrors = true,
                redirectOutput = ProcessBuilder.Redirect.PIPE,
                redirectError = ProcessBuilder.Redirect.PIPE,
            )

        assertEquals("partial output", output)
    }

    @Test
    fun `runCommand drains stdout and stderr concurrently for large output`() {
        val output: String =
            assertTimeoutPreemptively(Duration.ofSeconds(10)) {
                shell.runCommand(
                    command =
                        """
                        i=0
                        while [ "${'$'}i" -lt 12000 ]; do
                          printf 'out%05d\n' "${'$'}i"
                          printf 'err%05d\n' "${'$'}i" >&2
                          i=${'$'}(( ${'$'}i + 1 ))
                        done
                        """.trimIndent(),
                    ignoreErrors = true,
                    redirectOutput = ProcessBuilder.Redirect.PIPE,
                    redirectError = ProcessBuilder.Redirect.PIPE,
                )
            }

        val lines = output.lines()
        assertEquals(12000, lines.size)
        assertEquals("out00000", lines.first())
        assertEquals("out11999", lines.last())
    }
}
