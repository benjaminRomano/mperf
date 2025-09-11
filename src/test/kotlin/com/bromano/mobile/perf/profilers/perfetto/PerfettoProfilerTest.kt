package com.bromano.mobile.perf.profilers.perfetto

import com.bromano.mobile.perf.PerfettoOptions
import com.bromano.mobile.perf.utils.Adb
import com.bromano.mobile.perf.utils.FakeShell
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempFile

class PerfettoProfilerTest {
    private class TestProcess : Process() {
        override fun destroy() {}

        override fun exitValue(): Int = 0

        override fun getErrorStream() = ByteArrayInputStream(ByteArray(0))

        override fun getInputStream() = ByteArrayInputStream(ByteArray(0))

        override fun getOutputStream() = ByteArrayOutputStream()

        override fun isAlive(): Boolean = false

        override fun waitFor(): Int = 0

        override fun waitFor(
            timeout: Long,
            unit: TimeUnit,
        ): Boolean = true

        override fun destroyForcibly(): Process = this

        override fun toString(): String = "TestProcess"
    }

    @Test
    fun `executes perfetto and pulls trace`() {
        val adb =
            mock<Adb> {
                // sdk >= 29 path to avoid sideload/network
                on { sdkVersion } doReturn 29
                on { pidof(eq("perfetto")) } doReturn "4321"
                // atrace categories used by config builder
                on { shell(eq("atrace --list_categories | awk '{print $1;}'"), any(), any()) } doReturn "am\nwm\n"
            }

        val started = mutableListOf<String>()
        val testProc = TestProcess()
        val shellWithProc =
            object : FakeShell() {
                override fun startProcess(command: String): Process {
                    started += command
                    return testProc
                }

                override fun waitFor(process: Process) {}

                override fun waitFor(
                    process: Process,
                    timeout: Long,
                    unit: TimeUnit,
                ): Boolean = true
            }

        val out = createTempFile("trace", ".perfetto-trace").also { Files.writeString(it, "") }

        val profiler = PerfettoProfiler(shellWithProc, adb, PerfettoOptions()) { /* no-op stop */ }
        profiler.execute("com.example.app", out)

        // started perfetto via adb shell piping config
        assert(started.isNotEmpty())
        val cmd = started.first()
        assert(cmd.contains("adb "))
        assert(cmd.contains("shell 'cat /data/local/tmp/perfetto_config.pb | perfetto -c - -o "))

        // ensure we set persist.traced.enable on supported devices
        verify(adb).setProp(eq("persist.traced.enable"), eq("1"))
        // ensure we pushed config
        verify(adb).push(any(), eq("/data/local/tmp/perfetto_config.pb"))
        // ensure we attempted to terminate and then pulled
        verify(adb).shell(eq("kill -TERM 4321"), any(), any())
        verify(adb).shell(eq("cat /data/misc/perfetto-traces/trace2.perfetto-trace > $out"), any(), any())
    }

    @Test
    fun `executeTest runs instrumentation and pulls single perfetto trace`() {
        val pkg = "com.example.macrobenchmark"
        val instr = "$pkg/androidx.test.runner.AndroidJUnitRunner"
        val testCase = "$pkg.benchmark.LoginBenchmark#loginByIntent"
        val mediaDir = "/storage/emulated/0/Android/media/$pkg/"
        val producedTrace = "LoginBenchmark_loginByIntent_iter000_2025-09-07-22-20-24.perfetto-trace"

        val adb =
            mock<Adb> {
                on { getDirUsableByAppAndShell(eq(pkg)) } doReturn mediaDir
                on { ls(eq(mediaDir)) } doReturn listOf(producedTrace)
                on { shell(any(), any(), any()) } doReturn "" // instrumentation output ignored here
            }

        val shell = FakeShell()
        val out = createTempFile("trace", ".perfetto-trace")

        val profiler = PerfettoProfiler(shell, adb, PerfettoOptions())
        profiler.executeTest(pkg, instr, testCase, out)

        val expectedCmd =
            "am instrument -w -r -e class \"$testCase\" " +
                "-e androidx.benchmark.dryRunMode.enable true " +
                "-e androidx.benchmark.suppressErrors \"EMULATOR\" " +
                instr

        verify(adb).shell(eq(expectedCmd), any(), any())
        verify(adb).ls(eq(mediaDir))
        verify(adb).pull(eq(mediaDir + producedTrace), eq(out.toString()))
    }

    @Test
    fun `executeTest throws when no perfetto trace found`() {
        val pkg = "com.example.macrobenchmark"
        val instr = "$pkg/androidx.test.runner.AndroidJUnitRunner"
        val testCase = "$pkg.benchmark.LoginBenchmark#loginByIntent"
        val mediaDir = "/storage/emulated/0/Android/media/$pkg/"

        val adb =
            mock<Adb> {
                on { getDirUsableByAppAndShell(eq(pkg)) } doReturn mediaDir
                on { ls(eq(mediaDir)) } doReturn emptyList()
                on { shell(any(), any(), any()) } doReturn ""
            }

        val shell = FakeShell()
        val out = createTempFile("trace", ".perfetto-trace")

        val profiler = PerfettoProfiler(shell, adb, PerfettoOptions())

        var thrown = false
        try {
            profiler.executeTest(pkg, instr, testCase, out)
        } catch (e: Exception) {
            thrown = true
        }
        assert(thrown)
    }
}
