package com.bromano.mobile.perf.profilers

import com.bromano.mobile.perf.PerfettoOptions
import com.bromano.mobile.perf.ProfilerFormat
import com.bromano.mobile.perf.ProfilerOptionGroup
import com.bromano.mobile.perf.utils.FakeShell
import com.bromano.mobile.perf.utils.ProfileOpener
import com.bromano.mobile.perf.utils.ProfileViewer
import com.bromano.mobile.perf.utils.Shell
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempFile
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProfilerExecutorTest {
    @Test
    fun `PERFETTO path uses factory and opener`() {
        val shell = FakeShell()
        val device = "abc123"
        val pkg = "com.example"
        val out: Path = createTempFile("trace", ".perfetto-trace").also { Files.writeString(it, "x") }

        var collected = false
        var opened = false
        var capturedPkg = ""
        var capturedOut: Path? = null

        val factories: Map<ProfilerFormat, (Shell, String, ProfilerOptionGroup) -> Profiler> =
            mapOf(
                ProfilerFormat.PERFETTO to { _, dev, group ->
                    // ensure device and option type passed through
                    assertEquals(device, dev)
                    assertTrue(group is PerfettoOptions)
                    object : Profiler {
                        override fun execute(
                            packageName: String,
                            output: Path,
                        ) {
                            collected = true
                            capturedPkg = packageName
                            capturedOut = output
                        }

                        override fun executeTest(
                            packageName: String,
                            instrumentationRunner: String,
                            testCase: String,
                            output: Path,
                        ) {
                            TODO("Not yet implemented")
                        }
                    }
                },
            )

        val openerFactory: (Shell) -> ProfileOpener = {
            object : ProfileOpener(it) {
                override fun openProfile(
                    packageName: String?,
                    trace: Path,
                    format: ProfilerFormat,
                    profileViewerOverride: ProfileViewer?,
                ) {
                    opened = true
                    assertTrue(Files.exists(trace))
                    assertEquals(ProfilerFormat.PERFETTO, format)
                }
            }
        }

        val opts: ProfilerOptionGroup = PerfettoOptions()

        val executor = ProfilerExecutorImpl(factories, openerFactory(shell))
        executor.execute(opts, shell, device, pkg, out)

        assertTrue(collected)
        assertTrue(opened)
        assertEquals(pkg, capturedPkg)
        assertEquals(out, capturedOut)
    }

    @Test
    fun `SIMPLEPERF path throws when factory missing`() {
        val shell = FakeShell()
        val out: Path = createTempFile("trace", ".trace")
        val simple = object : ProfilerOptionGroup(ProfilerFormat.SIMPLEPERF) {}

        assertFailsWith<IllegalStateException> {
            ProfilerExecutorImpl(emptyMap(), ProfileOpener(shell)).execute(simple, shell, "dev", "pkg", out)
        }
    }
}
