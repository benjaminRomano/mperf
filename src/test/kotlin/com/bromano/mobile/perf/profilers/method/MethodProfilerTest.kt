package com.bromano.mobile.perf.profilers.method

import com.bromano.mobile.perf.utils.Adb
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import java.nio.file.Files
import java.nio.file.Path

class MethodProfilerTest {
    @Test
    fun `execute starts and stops tracing when app running`() {
        val pkg = "com.example.app"
        val out: Path = Files.createTempFile("method", ".trace")

        val adb =
            mock<Adb> {
                on { isRunning(eq(pkg)) } doReturn true
                // Force SDK >= 34 so implementation includes --clock-type wall (avoids double-space case)
                on { sdkVersion } doReturn 34
            }

        val profiler = MethodProfiler(adb, awaitStop = { /* end immediately */ })

        profiler.execute(pkg, out)

        // start tracing with streaming and clock type on SDK 34+
        verify(adb).shell(eq("am profile start --streaming --clock-type wall $pkg /data/local/tmp/method.trace"), any(), any())
        // stop via am profile stop <pkg>
        verify(adb).shell(eq("am profile stop $pkg"), any(), any())
        // pulled the trace
        verify(adb).pull(eq("/data/local/tmp/method.trace"), eq(out.toString()))
    }

    @Test
    fun `execute cold start uses start-profiler and stop-profiler`() {
        val pkg = "com.example.app"
        val component = "$pkg/.MainActivity"
        val out: Path = Files.createTempFile("method2", ".trace")

        val adb =
            mock<Adb> {
                on { isRunning(eq(pkg)) } doReturn false
                on { resolveLaunchableActivity(eq(pkg)) } doReturn component
            }

        val profiler = MethodProfiler(adb, awaitStop = { /* end immediately */ })

        profiler.execute(pkg, out)

        verify(adb).shell(eq("am start --start-profiler /data/local/tmp/method.trace --streaming \"$component\""), any(), any())
        verify(adb).shell(eq("am profile stop $pkg"), any(), any())
        verify(adb).pull(eq("/data/local/tmp/method.trace"), eq(out.toString()))
    }

    @Test
    fun `executeTest runs instrumentation and pulls method trace`() {
        val pkg = "com.example.macrobenchmark"
        val instr = "$pkg/androidx.test.runner.AndroidJUnitRunner"
        val testCase = "$pkg.benchmark.LoginBenchmark#loginByIntent"
        val mediaDir = "/storage/emulated/0/Android/media/$pkg/"
        val producedTrace = "methodTracing_LoginBenchmark_loginByIntent_iter001.trace" // any filename containing methodTracing

        val adb =
            mock<Adb> {
                on { getDirUsableByAppAndShell(eq(pkg)) } doReturn mediaDir
                on { ls(eq(mediaDir)) } doReturn listOf(producedTrace)
                on { shell(any(), any(), any()) } doReturn "" // instrumentation output ignored
            }

        val out: Path = Files.createTempFile("method_test", ".trace")
        val profiler = MethodProfiler(adb)

        profiler.executeTest(pkg, instr, testCase, out)

        val expectedCmd =
            "am instrument -w -r -e class \"$testCase\" " +
                "-e androidx.benchmark.profiling.mode MethodTracing " +
                "-e androidx.benchmark.suppressErrors \"EMULATOR\" " +
                instr

        verify(adb).shell(eq(expectedCmd), any(), any())
        verify(adb).pull(eq(mediaDir + producedTrace), eq(out.toString()))
    }
}
