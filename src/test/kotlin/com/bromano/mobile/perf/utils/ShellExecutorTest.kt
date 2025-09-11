package com.bromano.mobile.perf.utils

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ShellExecutorTest {
    private class TestShellExecutor(
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

    @Test
    fun `getConnectedAndroidDevices parses adb output`() {
        val output = (
            "List of devices attached\n" +
                "emulator-5554\tdevice\n" +
                "0123456789ABCDEF\tdevice\n"
        )

        val shell = TestShellExecutor(output)
        val devices = shell.getConnectedAndroidDevices()
        assertEquals(listOf("emulator-5554", "0123456789ABCDEF"), devices)
    }
}
