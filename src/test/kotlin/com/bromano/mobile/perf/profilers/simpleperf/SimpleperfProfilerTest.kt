package com.bromano.mobile.perf.profilers.simpleperf

import com.bromano.mobile.perf.SimpleperfOptions
import com.bromano.mobile.perf.androidProfilerOptions
import com.github.ajalt.clikt.core.parse
import com.bromano.mobile.perf.utils.Adb
import com.bromano.mobile.perf.utils.Shell
import com.github.ajalt.clikt.core.CliktCommand
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.nio.file.Files
import java.nio.file.Path

class SimpleperfProfilerTest {
    private lateinit var shell: Shell
    private lateinit var adb: Adb

    private var originalHome: String? = null

    @BeforeEach
    fun setUp() {
        shell = mock()
        adb = Adb(null, shell)

        // Make device appear non-rootable by default
        whenever(shell.runCommand(argThat { contains("adb") && contains("which su") }, any())).thenReturn("")
        // SDK version and basic props
        whenever(shell.runCommand(argThat { contains("adb") && contains("getprop ro.build.version.sdk") }, any())).thenReturn("33")

        // Default no-op for any other shell.runCommand calls
        whenever(shell.runCommand(any<String>(), any())).thenReturn("")

        // Mock long-running process start used by SimpleperfCollector
        val proc: Process = mock()
        whenever(shell.startProcess(any())).thenReturn(proc)
    }

    @AfterEach
    fun tearDown() {
        // Restore user.home property if modified
        originalHome?.let { System.setProperty("user.home", it) }
    }

    @Test
    fun executes_simpleperf_non_rootable_and_converts() {
        // Arrange: make pidof return a pid once, then blank for shutdown check
        whenever(
            shell.runCommand(
                argThat { contains("adb") && contains("pidof simpleperf") },
                any(),
            ),
        ).thenReturn("1234").thenReturn("")

        // Simulate pull succeeding
        whenever(
            shell.runCommand(
                argThat { startsWith("adb ") && contains(" pull ") },
                any(),
            ),
        ).thenReturn("")

        // Ensure simpleperf scripts directory exists to avoid network in getSimpleperfScripts
        val tmpHome = Files.createTempDirectory("home")
        originalHome = System.getProperty("user.home")
        System.setProperty("user.home", tmpHome.toString())
        Files.createDirectories(tmpHome.resolve(".mobileperf/simpleperf"))

        // Build a parsed SimpleperfOptions via a tiny Clikt command
        class OptsCmd : CliktCommand() {
            lateinit var captured: com.bromano.mobile.perf.ProfilerOptionGroup
            private val profiler by androidProfilerOptions()

            override fun run() {
                captured = profiler
            }
        }
        val cmd = OptsCmd()
        cmd.parse(listOf("--format", "simpleperf"))
        val options = cmd.captured as SimpleperfOptions
        val collector = SimpleperfProfiler(shell, adb, options, awaitStop = { /* end immediately */ })

        val output: Path = Files.createTempFile("out", ".json.gz")

        // Act
        collector.execute("com.example.app", output)

        // Assert: started simpleperf via adb shell sh -c with correct command contents
        verify(shell).startProcess(
            argThat {
                contains("adb ") &&
                    contains(" shell sh -c ") &&
                    contains("simpleperf record --app com.example.app -o /data/local/tmp/perf.data") &&
                    contains("-e cpu-clock -f 4000 -g")
            },
        )

        // Assert: kill -2 <pid> sent
        verify(shell).runCommand(argThat { contains("adb") && contains("kill -2 1234") }, any())

        // Assert: perf.data was pulled
        verify(shell).runCommand(argThat { contains("adb ") && contains(" pull ") && contains("/data/local/tmp/perf.data") }, any())

        // Assert: gecko converter was invoked
        verify(shell, times(1)).runCommand(argThat { contains("python3") && contains("gecko_profile_generator.py") }, any())
    }

    @Test
    fun executes_simpleperf_rootable_uses_sideload_binary_and_buffer_size() {
        // Make device rootable
        whenever(shell.runCommand(argThat { contains("adb") && contains("which su") }, any())).thenReturn("/system/bin/su")
        // su --help output detection (to choose correct su variant)
        whenever(
            shell.runCommand(
                argThat { contains("adb") && contains("su --help") },
                any(),
            ),
        ).thenReturn("usage: su [WHO [COMMAND...]]")
        // ABI for sideload map
        whenever(shell.runCommand(argThat { contains("adb") && contains("getprop ro.product.cpu.abi") }, any())).thenReturn("arm64-v8a")
        // Sideload check: simpleperf already present and md5 matches
        whenever(shell.runCommand(argThat { contains("adb") && contains("ls /data/local/tmp") }, any())).thenReturn("simpleperf")
        whenever(
            shell.runCommand(
                argThat {
                    contains("adb") && contains("md5sum /data/local/tmp/simpleperf")
                },
                any(),
            ),
        ).thenReturn("e80b79d38160a9ec4d6cd8f06ab39e28  /data/local/tmp/simpleperf")

        // pidof once, then blank to indicate shutdown
        whenever(
            shell.runCommand(
                argThat { contains("adb") && contains("pidof simpleperf") },
                any(),
            ),
        ).thenReturn("4321").thenReturn("")

        // Simulate pull succeeding
        whenever(
            shell.runCommand(
                argThat { startsWith("adb ") && contains(" pull ") },
                any(),
            ),
        ).thenReturn("")

        // Ensure simpleperf scripts directory exists to avoid network in getSimpleperfScripts
        val tmpHome = Files.createTempDirectory("home2")
        val prevHome = System.getProperty("user.home")
        if (originalHome == null) originalHome = prevHome
        System.setProperty("user.home", tmpHome.toString())
        Files.createDirectories(tmpHome.resolve(".mobileperf/simpleperf"))

        // Build a parsed SimpleperfOptions via a tiny Clikt command
        class OptsCmd2 : CliktCommand() {
            lateinit var captured: com.bromano.mobile.perf.ProfilerOptionGroup
            private val profiler by androidProfilerOptions()

            override fun run() {
                captured = profiler
            }
        }
        val cmd = OptsCmd2()
        cmd.parse(listOf("--format", "simpleperf"))
        val options = cmd.captured as SimpleperfOptions

        val collector = SimpleperfProfiler(shell, adb, options, awaitStop = { /* end immediately */ })
        val output: Path = Files.createTempFile("out2", ".json.gz")

        // Act
        collector.execute("com.example.app", output)

        // Assert: started simpleperf using sideloaded binary and buffer size
        verify(shell).startProcess(
            argThat { contains("/data/local/tmp/simpleperf record --app com.example.app") && contains("--user-buffer-size 1G") },
        )

        // Assert: kill -2 <pid> sent
        verify(shell).runCommand(argThat { contains("adb") && contains("kill -2 4321") }, any())

        // Assert: gecko converter was invoked
        verify(shell, times(1)).runCommand(argThat { contains("python3") && contains("gecko_profile_generator.py") }, any())
    }

    @Test
    fun waits_for_shutdown_and_times_out() {
        // Non-rootable device
        whenever(shell.runCommand(argThat { contains("adb") && contains("which su") }, any())).thenReturn("")
        // pidof always returns a pid to force timeout
        whenever(
            shell.runCommand(
                argThat { contains("adb") && contains("pidof simpleperf") },
                any(),
            ),
        ).thenReturn("9999")

        // Build parsed options
        class OptsCmd3 : CliktCommand() {
            lateinit var captured: com.bromano.mobile.perf.ProfilerOptionGroup
            private val profiler by androidProfilerOptions()

            override fun run() {
                captured = profiler
            }
        }
        val cmd = OptsCmd3()
        cmd.parse(listOf("--format", "simpleperf"))
        val options = cmd.captured as SimpleperfOptions

        val collector =
            SimpleperfProfiler(
                shell,
                adb,
                options,
                awaitStop = { /* end immediately */ },
                shutdownTimeoutMs = 10L,
            )

        val output: Path = Files.createTempFile("out3", ".json.gz")

        assertThrows<IllegalStateException> {
            collector.execute("com.example.app", output)
        }
    }
}
