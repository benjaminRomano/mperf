package com.bromano.mobile.perf.commands.android

import com.bromano.mobile.perf.AndroidConfig
import com.bromano.mobile.perf.Config
import com.bromano.mobile.perf.ProfilerOptionGroup
import com.github.ajalt.clikt.core.parse
import com.bromano.mobile.perf.profilers.ProfilerExecutor
import com.bromano.mobile.perf.utils.ProfileViewer
import com.bromano.mobile.perf.utils.Shell
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import java.nio.file.Path
import kotlin.test.assertTrue

class CollectCommandTest {
    @Test
    fun `uses fully qualified test when provided`() {
        val device = "device-1"
        val pkg = "com.example.app"
        val instr = "com.example.app.test/androidx.test.runner.AndroidJUnitRunner"
        val fqTest = "com.example.bench.MyBenchmark#startup"

        val shell =
            mock<Shell> {
                on {
                    runCommand(
                        command = eq("adb -s $device shell am instrument -r -w -e log true -e logOnly true $instr"),
                        ignoreErrors = any(),
                    )
                } doReturn (
                    """
                    INSTRUMENTATION_STATUS: class=com.example.bench.MyBenchmark
                    INSTRUMENTATION_STATUS: test=startup
                    INSTRUMENTATION_STATUS_CODE: 1
                    INSTRUMENTATION_CODE: -1
                    """.trimIndent()
                )
                // When only one test is available, selection should return that test
                on { selectChoice(eq(listOf(fqTest)), anyOrNull()) } doReturn fqTest
            }

        var executed = false
        val executor =
            object : ProfilerExecutor {
                override fun execute(
                    profilerOptionGroup: ProfilerOptionGroup,
                    shell: Shell,
                    device: String,
                    packageName: String,
                    output: Path,
                    profileViewerOverride: ProfileViewer?,
                ) = Unit

                override fun executeTest(
                    profilerOptionGroup: ProfilerOptionGroup,
                    shell: Shell,
                    device: String,
                    packageName: String,
                    instrumentationPackageName: String,
                    testCase: String,
                    output: Path,
                    profileViewerOverride: ProfileViewer?,
                ) {
                    executed = true
                    require(testCase == fqTest)
                }
            }

        val cmd = CollectCommand(shell, Config(android = AndroidConfig(packageName = pkg)), executor)
        cmd.parse(listOf("-d", device, "-p", pkg, "-i", instr, "-t", fqTest))
        assertTrue(executed)

        // Ensure we enumerated tests
        verify(shell).runCommand(
            command = eq("adb -s $device shell am instrument -r -w -e log true -e logOnly true $instr"),
            ignoreErrors = any(),
        )
    }

    @Test
    fun `prompts selection when method name has multiple matches`() {
        val device = "device-1"
        val pkg = "com.example.app"
        val instr = "com.example.app.test/androidx.test.runner.AndroidJUnitRunner"

        val output =
            """
            INSTRUMENTATION_STATUS: class=com.example.bench.StartupBenchmark
            INSTRUMENTATION_STATUS: test=startup
            INSTRUMENTATION_STATUS_CODE: 1
            INSTRUMENTATION_STATUS: class=com.example.bench2.StartupBenchmark
            INSTRUMENTATION_STATUS: test=startup
            INSTRUMENTATION_STATUS_CODE: 1
            INSTRUMENTATION_CODE: -1
            """.trimIndent()

        val expectedChoices =
            listOf(
                "com.example.bench.StartupBenchmark#startup",
                "com.example.bench2.StartupBenchmark#startup",
            )

        val shell =
            mock<Shell> {
                on {
                    runCommand(
                        command = eq("adb -s $device shell am instrument -r -w -e log true -e logOnly true $instr"),
                        ignoreErrors = any(),
                    )
                } doReturn output

                on { selectChoice(eq(expectedChoices), anyOrNull()) } doReturn expectedChoices[1]
            }

        var selected: String? = null
        val executor =
            object : ProfilerExecutor {
                override fun execute(
                    profilerOptionGroup: ProfilerOptionGroup,
                    shell: Shell,
                    device: String,
                    packageName: String,
                    output: Path,
                    profileViewerOverride: ProfileViewer?,
                ) = Unit

                override fun executeTest(
                    profilerOptionGroup: ProfilerOptionGroup,
                    shell: Shell,
                    device: String,
                    packageName: String,
                    instrumentationPackageName: String,
                    testCase: String,
                    output: Path,
                    profileViewerOverride: ProfileViewer?,
                ) {
                    selected = testCase
                }
            }

        val cmd = CollectCommand(shell, Config(android = AndroidConfig(packageName = pkg)), executor)
        cmd.parse(listOf("-d", device, "-p", pkg, "-i", instr, "-t", "startup"))
        assertTrue(selected == expectedChoices[1])
    }
}
