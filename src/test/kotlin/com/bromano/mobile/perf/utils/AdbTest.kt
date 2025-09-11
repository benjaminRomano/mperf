package com.bromano.mobile.perf.utils

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdbTest {
    private lateinit var shell: Shell
    private lateinit var adb: Adb

    @BeforeEach
    fun setup() {
        shell = mock()
        adb = Adb(device = "test-device", shell = shell)
    }

    @Test
    fun `runCommand executes correct adb command`() {
        whenever(shell.runCommand(any(), any())).thenReturn("")
        adb.runCommand("test-command")
        verify(shell).runCommand(
            command = eq("adb -s test-device test-command"),
            ignoreErrors = any(),
        )
    }

    @Test
    fun `shell executes correct adb shell command`() {
        whenever(shell.runCommand(any(), any())).thenReturn("")
        adb.shell("test-command")
        verify(shell).runCommand(
            command = eq("adb -s test-device shell test-command"),
            ignoreErrors = any(),
        )
    }

    @Test
    fun `sdkVersion returns correct sdk version`() {
        doReturn("30").whenever(shell).runCommand(
            command = eq("adb -s test-device shell getprop ro.build.version.sdk"),
            ignoreErrors = any(),
        )
        assertEquals(30, adb.sdkVersion)
    }

    @Test
    fun `abi returns correct abi`() {
        doReturn("x86_64").whenever(shell).runCommand(
            command = eq("adb -s test-device shell getprop ro.product.cpu.abi"),
            ignoreErrors = any(),
        )
        assertEquals("x86_64", adb.abi)
    }

    @Test
    fun `isRootable returns true when su exists`() {
        doReturn("/system/bin/su").whenever(shell).runCommand(
            command = eq("adb -s test-device shell which su"),
            ignoreErrors = any(),
        )
        assertTrue(adb.isRootable())
    }

    @Test
    fun `isRootable returns false when su does not exist`() {
        doReturn("").whenever(shell).runCommand(
            command = eq("adb -s test-device shell which su"),
            ignoreErrors = any(),
        )
        assertFalse(adb.isRootable())
    }

    @Test
    fun `isRunning returns true when process is running on Android N or higher`() {
        doReturn("24").whenever(shell).runCommand(
            command = eq("adb -s test-device shell getprop ro.build.version.sdk"),
            ignoreErrors = any(),
        )
        doReturn("1234").whenever(shell).runCommand(
            command = eq("adb -s test-device shell pidof test-package"),
            ignoreErrors = any(),
        )
        assertTrue(adb.isRunning("test-package"))
    }

    @Test
    fun `isRunning returns false when process is not running on Android N or higher`() {
        doReturn("24").whenever(shell).runCommand(
            command = eq("adb -s test-device shell getprop ro.build.version.sdk"),
            ignoreErrors = any(),
        )
        doReturn("").whenever(shell).runCommand(
            command = eq("adb -s test-device shell pidof test-package"),
            ignoreErrors = any(),
        )
        assertFalse(adb.isRunning("test-package"))
    }

    @Test
    fun `isRunning returns true when process is running on Android M or lower`() {
        doReturn("23").whenever(shell).runCommand(
            command = eq("adb -s test-device shell getprop ro.build.version.sdk"),
            ignoreErrors = any(),
        )
        doReturn("test-package").whenever(shell).runCommand(
            command = eq("adb -s test-device shell ps"),
            ignoreErrors = any(),
        )
        assertTrue(adb.isRunning("test-package"))
    }

    @Test
    fun `isRunning returns false when process is not running on Android M or lower`() {
        doReturn("23").whenever(shell).runCommand(
            command = eq("adb -s test-device shell getprop ro.build.version.sdk"),
            ignoreErrors = any(),
        )
        doReturn("").whenever(shell).runCommand(
            command = eq("adb -s test-device shell ps"),
            ignoreErrors = any(),
        )
        assertFalse(adb.isRunning("test-package"))
    }

    @Test
    fun `pidof returns pid when process is running`() {
        doReturn("1234 5678").whenever(shell).runCommand(
            command = eq("adb -s test-device shell pidof test-process"),
            ignoreErrors = any(),
        )
        doReturn("").whenever(shell).runCommand(
            command = eq("adb -s test-device shell which su"),
            ignoreErrors = any(),
        )

        assertEquals("1234", adb.pidof("test-process"))
    }

    @Test
    fun `pidof returns null when process is not running`() {
        doReturn("").whenever(shell).runCommand(
            command = eq("adb -s test-device shell pidof test-process"),
            ignoreErrors = any(),
        )

        doReturn("").whenever(shell).runCommand(
            command = eq("adb -s test-device shell which su"),
            ignoreErrors = any(),
        )

        assertNull(adb.pidof("test-process"))
    }

    @Test
    fun `deleteSystemSetting executes correct command`() {
        adb.deleteSystemSetting("test-property")
        verify(shell).runCommand(
            command = eq("adb -s test-device shell settings delete system test-property"),
            ignoreErrors = any(),
        )
    }

    @Test
    fun `setProp executes correct command`() {
        adb.setProp("test-property", "test-value")
        verify(shell).runCommand(
            command = eq("adb -s test-device shell setprop test-property test-value"),
            ignoreErrors = any(),
        )
    }

    @Test
    fun `putSystemSetting executes correct command`() {
        adb.putSystemSetting("test-property", "test-value")
        verify(shell).runCommand(
            command = eq("adb -s test-device shell settings put system test-property test-value"),
            ignoreErrors = any(),
        )
    }

    @Test
    fun `getSystemSetting executes correct command`() {
        adb.getSystemSetting("test-property")
        verify(shell).runCommand(
            command = eq("adb -s test-device shell settings get system test-property"),
            ignoreErrors = any(),
        )
    }

    @Test
    fun `pull executes correct command`() {
        adb.pull("remote-path", "local-path")
        verify(shell).runCommand(
            command = eq("adb -s test-device pull remote-path local-path"),
            ignoreErrors = any(),
        )
    }

    @Test
    fun `push executes correct command`() {
        adb.push("local-path", "remote-path")
        verify(shell).runCommand(
            command = eq("adb -s test-device push local-path remote-path"),
            ignoreErrors = any(),
        )
    }

    @Test
    fun `delete executes correct command`() {
        adb.delete("/data/local/tmp/test-path")
        verify(shell).runCommand(
            command = eq("adb -s test-device shell rm \"/data/local/tmp/test-path\""),
            ignoreErrors = any(),
        )
    }

    @Test
    fun `delete with force executes correct command`() {
        adb.delete("/data/local/tmp/test-path", force = true)
        verify(shell).runCommand(
            command = eq("adb -s test-device shell rm -f \"/data/local/tmp/test-path\""),
            ignoreErrors = any(),
        )
    }

    @Test
    fun `ls executes correct command`() {
        whenever(shell.runCommand(any(), any())).thenReturn("file1 file2")
        val files = adb.ls("/data/local/tmp")
        verify(shell).runCommand(
            command = eq("adb -s test-device ls /data/local/tmp"),
            ignoreErrors = any(),
        )
        assertEquals(listOf("file1", "file2"), files)
    }

    @Test
    fun `findInstrumentationRunners parses output and filters by target`() {
        val pkg = "com.example.app"
        val output =
            """
            instrumentation:com.example.app.test/androidx.test.runner.AndroidJUnitRunner (target=com.example.app)
            instrumentation:com.other.test/androidx.test.runner.AndroidJUnitRunner (target=com.other)
            """.trimIndent()

        doReturn(output)
            .whenever(shell)
            .runCommand(
                command = eq("adb -s test-device shell pm list instrumentation"),
                ignoreErrors = any(),
            )

        val runners = adb.findInstrumentationRunners()
        assertEquals(
            listOf(
                "com.example.app.test/androidx.test.runner.AndroidJUnitRunner",
                "com.other.test/androidx.test.runner.AndroidJUnitRunner",
            ),
            runners,
        )
    }

    @Test
    fun `getTests parses instrumentation status output`() {
        val instr = "com.example.app.test/androidx.test.runner.AndroidJUnitRunner"
        val output =
            """
            INSTRUMENTATION_STATUS: class=com.example.macrobenchmark.baselineprofile.ComposeActivityBaselineProfileGenerator
            INSTRUMENTATION_STATUS: current=1
            INSTRUMENTATION_STATUS: id=AndroidJUnitRunner
            INSTRUMENTATION_STATUS: numtests=40
            INSTRUMENTATION_STATUS: stream=
            com.example.macrobenchmark.baselineprofile.ComposeActivityBaselineProfileGenerator:
            INSTRUMENTATION_STATUS: test=null
            INSTRUMENTATION_STATUS_CODE: 1
            INSTRUMENTATION_STATUS: class=com.example.macrobenchmark.baselineprofile.ComposeActivityBaselineProfileGenerator
            INSTRUMENTATION_STATUS: current=1
            INSTRUMENTATION_STATUS: id=AndroidJUnitRunner
            INSTRUMENTATION_STATUS: numtests=40
            INSTRUMENTATION_STATUS: stream=
            com.example.macrobenchmark.baselineprofile.ComposeActivityBaselineProfileGenerator:
            INSTRUMENTATION_STATUS: test=null
            INSTRUMENTATION_STATUS_CODE: -3
            INSTRUMENTATION_STATUS: class=com.example.macrobenchmark.baselineprofile.LoginBaselineProfileGenerator
            INSTRUMENTATION_STATUS: current=2
            INSTRUMENTATION_STATUS: id=AndroidJUnitRunner
            INSTRUMENTATION_STATUS: numtests=40
            INSTRUMENTATION_STATUS: stream=
            com.example.macrobenchmark.baselineprofile.LoginBaselineProfileGenerator:
            INSTRUMENTATION_STATUS: test=generate
            INSTRUMENTATION_STATUS_CODE: 1
            INSTRUMENTATION_STATUS: class=com.example.macrobenchmark.baselineprofile.LoginBaselineProfileGenerator
            INSTRUMENTATION_STATUS: current=2
            INSTRUMENTATION_STATUS: id=AndroidJUnitRunner
            INSTRUMENTATION_STATUS: numtests=40
            INSTRUMENTATION_STATUS: stream=.
            INSTRUMENTATION_STATUS: test=generate
            """.trimIndent()

        doReturn(output)
            .whenever(shell)
            .runCommand(
                command = eq("adb -s test-device shell am instrument -r -w -e log true -e logOnly true $instr"),
                ignoreErrors = any(),
            )

        val tests = adb.getTests(instr)
        assertEquals(
            listOf(
                "com.example.macrobenchmark.baselineprofile.LoginBaselineProfileGenerator#generate",
            ),
            tests,
        )
    }

    @Test
    fun `getDirUsableByAppAndShell returns correct path for sdk 30`() {
        doReturn("30").whenever(shell).runCommand(
            command = eq("adb -s test-device shell getprop ro.build.version.sdk"),
            ignoreErrors = any(),
        )
        assertEquals(
            "/storage/emulated/0/Android/media/test-package/",
            adb.getDirUsableByAppAndShell("test-package"),
        )
    }

    @Test
    fun `getDirUsableByAppAndShell returns correct path for sdk 29`() {
        doReturn("29").whenever(shell).runCommand(
            command = eq("adb -s test-device shell getprop ro.build.version.sdk"),
            ignoreErrors = any(),
        )
        assertEquals(
            "/storage/emulated/0/Android/media/test-package/",
            adb.getDirUsableByAppAndShell("test-package"),
        )
    }

    @Test
    fun `getDirUsableByAppAndShell returns correct path for sdk 23`() {
        doReturn("23").whenever(shell).runCommand(
            command = eq("adb -s test-device shell getprop ro.build.version.sdk"),
            ignoreErrors = any(),
        )
        assertEquals(
            "/storage/emulated/0/Android/data/test-package/cache/",
            adb.getDirUsableByAppAndShell("test-package"),
        )
    }

    @Test
    fun `getDirUsableByAppAndShell returns correct path for sdk 22`() {
        doReturn("22").whenever(shell).runCommand(
            command = eq("adb -s test-device shell getprop ro.build.version.sdk"),
            ignoreErrors = any(),
        )
        assertEquals(
            "/data/data/test-package/cache/",
            adb.getDirUsableByAppAndShell("test-package"),
        )
    }

    @Test
    fun `resolveLaunchableActivity returns resolved component when present`() {
        val pkg = "com.example.app"
        val briefOutput = "com.example.app/.MainActivity"

        doReturn(briefOutput)
            .whenever(shell)
            .runCommand(
                command = eq("adb -s test-device shell cmd package resolve-activity --brief $pkg"),
                ignoreErrors = any(),
            )

        val resolved = adb.resolveLaunchableActivity(pkg)
        assertEquals("com.example.app/.MainActivity", resolved)
    }

    @Test
    fun `resolveLaunchableActivity returns component as-is`() {
        val pkg = "com.example.app"
        val briefOutput = "com.example.app/com.example.app.MainActivity"

        doReturn(briefOutput)
            .whenever(shell)
            .runCommand(
                command = eq("adb -s test-device shell cmd package resolve-activity --brief $pkg"),
                ignoreErrors = any(),
            )

        val resolved = adb.resolveLaunchableActivity(pkg)
        assertEquals("com.example.app/com.example.app.MainActivity", resolved)
    }

    @Test
    fun `resolveLaunchableActivity returns null when not found`() {
        val pkg = "com.example.app"
        val notFound = "no activity found"

        doReturn(notFound)
            .whenever(shell)
            .runCommand(
                command = eq("adb -s test-device shell cmd package resolve-activity --brief $pkg"),
                ignoreErrors = any(),
            )

        val resolved = adb.resolveLaunchableActivity(pkg)
        assertNull(resolved)
    }
}
