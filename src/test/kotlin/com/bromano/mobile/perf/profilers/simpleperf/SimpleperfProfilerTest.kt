package com.bromano.mobile.perf.profilers.simpleperf

import com.bromano.mobile.perf.SimpleperfOptions
import com.bromano.mobile.perf.androidProfilerOptions
import com.bromano.mobile.perf.utils.Adb
import com.bromano.mobile.perf.utils.Shell
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.parse
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFalse

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
    fun `converter failure removes partial output`() {
        val tmpHome = Files.createTempDirectory("converter-failure-home")
        originalHome = System.getProperty("user.home")
        System.setProperty("user.home", tmpHome.toString())
        val scripts = Files.createDirectories(tmpHome.resolve(".mperf/simpleperf"))
        Files.writeString(scripts.resolve(".mperf-version"), "fc2494a2abd7ab21774d03deb09c1362bbb0bba8")
        Files.writeString(scripts.resolve("gecko_profile_generator.py"), "")
        whenever(shell.runCommand(argThat { contains("python3") }, any()))
            .thenThrow(IllegalStateException("converter failed"))

        class OptionsCommand : CliktCommand() {
            lateinit var captured: com.bromano.mobile.perf.ProfilerOptionGroup
            private val profiler by androidProfilerOptions()

            override fun run() {
                captured = profiler
            }
        }

        val options = OptionsCommand().apply { parse(listOf("--format", "simpleperf")) }.captured as SimpleperfOptions
        val output = Files.createTempFile("partial", ".json.gz")

        assertThrows<IllegalStateException> {
            SimpleperfProfiler(shell, adb, options).convertToGecko(options, Files.createTempFile("perf", ".data"), output)
        }
        assertFalse(Files.exists(output))
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
        Files.createDirectories(tmpHome.resolve(".mperf/simpleperf"))
        Files.writeString(tmpHome.resolve(".mperf/simpleperf/.mperf-version"), "fc2494a2abd7ab21774d03deb09c1362bbb0bba8")
        Files.writeString(tmpHome.resolve(".mperf/simpleperf/gecko_profile_generator.py"), "")
        val symfs = Files.createDirectories(tmpHome.resolve("symbols with spaces"))
        val mapping = Files.writeString(tmpHome.resolve("mapping with spaces.txt"), "")

        // Build a parsed SimpleperfOptions via a tiny Clikt command
        class OptsCmd : CliktCommand() {
            lateinit var captured: com.bromano.mobile.perf.ProfilerOptionGroup
            private val profiler by androidProfilerOptions()

            override fun run() {
                captured = profiler
            }
        }
        val cmd = OptsCmd()
        cmd.parse(
            listOf(
                "--format",
                "simpleperf",
                "--symfs",
                symfs.toString(),
                "--mapping",
                mapping.toString(),
                "--show-art-frames",
            ),
        )
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
        verify(shell, times(1)).runCommand(
            argThat {
                contains("python3") &&
                    contains("gecko_profile_generator.py") &&
                    contains("--show-art-frames") &&
                    contains("--symfs '$symfs'") &&
                    contains("--proguard-mapping-file '$mapping'") &&
                    !contains("| gzip")
            },
            any(),
        )
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
        ).thenReturn("2dca6449abf98f651135f544ce46a1cd  /data/local/tmp/simpleperf")

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
        Files.createDirectories(tmpHome.resolve(".mperf/simpleperf"))
        Files.writeString(tmpHome.resolve(".mperf/simpleperf/.mperf-version"), "fc2494a2abd7ab21774d03deb09c1362bbb0bba8")
        Files.writeString(tmpHome.resolve(".mperf/simpleperf/gecko_profile_generator.py"), "")

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

    @Test
    fun `executeTest uses benchmark stack sampling and pulls perfetto trace`() {
        whenever(
            shell.runCommand(
                argThat {
                    contains("adb") &&
                        contains("am instrument") &&
                        contains("androidx.benchmark.profiling.mode \"StackSampling\"")
                },
                any(),
            ),
        ).thenReturn(
            "INSTRUMENTATION_STATUS: additionalTestOutputFile_LoginBenchmark_loginByIntent_iter000=" +
                "/storage/emulated/0/Android/media/com.example.macrobenchmark/" +
                "LoginBenchmark_loginByIntent_iter000.perfetto-trace\n" +
                "INSTRUMENTATION_CODE: -1",
        )

        class OptsCmd4 : CliktCommand() {
            lateinit var captured: com.bromano.mobile.perf.ProfilerOptionGroup
            private val profiler by androidProfilerOptions()

            override fun run() {
                captured = profiler
            }
        }
        val cmd = OptsCmd4()
        cmd.parse(listOf("--format", "simpleperf"))
        val options = cmd.captured as SimpleperfOptions
        val collector = SimpleperfProfiler(shell, adb, options)
        val output: Path = Files.createTempFile("macro-simpleperf", ".perfetto-trace")

        collector.executeTest(
            packageName = "com.example.macrobenchmark.target",
            instrumentationRunner = "com.example.macrobenchmark/androidx.test.runner.AndroidJUnitRunner",
            testCase = "com.example.macrobenchmark.benchmark.LoginBenchmark#loginByIntent",
            output = output,
        )

        verify(shell).runCommand(
            argThat {
                contains("adb") &&
                    contains("am instrument") &&
                    contains("androidx.benchmark.profiling.mode \"StackSampling\"")
            },
            any(),
        )
        verify(shell).runCommand(
            argThat {
                contains("adb ") &&
                    contains(" pull /storage/emulated/0/Android/media/com.example.macrobenchmark/") &&
                    contains("LoginBenchmark_loginByIntent_iter000.perfetto-trace")
            },
            any(),
        )
        verify(shell, never()).runCommand(argThat { contains("python3") }, any())
    }
}
