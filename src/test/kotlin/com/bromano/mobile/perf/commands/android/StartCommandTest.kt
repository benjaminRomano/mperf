package com.bromano.mobile.perf.commands.android

import com.bromano.mobile.perf.AndroidConfig
import com.bromano.mobile.perf.Config
import com.bromano.mobile.perf.ProfilerOptionGroup
import com.bromano.mobile.perf.profilers.ProfilerExecutor
import com.bromano.mobile.perf.utils.ProfileViewer
import com.bromano.mobile.perf.utils.Shell
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.parse
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import java.nio.file.Path
import kotlin.test.assertFailsWith

class StartCommandTest {
    @Test
    fun `throws when package not provided`() {
        val shell = mock<Shell>()
        val profilerExecutor = mock<ProfilerExecutor>()
        val cmd = StartCommand(shell, Config(android = null), profilerExecutor)
        assertFailsWith<PrintMessage> {
            cmd.parse(emptyList())
        }
        verify(profilerExecutor, never()).execute(any(), any(), any(), any(), any(), anyOrNull())
    }

    @Test
    fun `throws when no devices connected`() {
        val shell =
            mock<Shell> {
                // No devices and selection returns null
                on { getConnectedAndroidDevices() } doReturn emptyList()
                on { selectChoice(any(), anyOrNull()) } doReturn null
            }
        val profilerExecutor = mock<ProfilerExecutor>()
        val cmd = StartCommand(shell, Config(android = AndroidConfig(packageName = "com.example.app")), profilerExecutor)
        assertFailsWith<PrintMessage> {
            cmd.parse(emptyList())
        }
        verify(profilerExecutor, never()).execute(any(), any(), any(), any(), any(), anyOrNull())
    }

    @Test
    fun `positive flow calls executor with defaults`() {
        val shell =
            mock<Shell> {
                on { getConnectedAndroidDevices() } doReturn listOf("device-1")
                on { selectChoice(any(), anyOrNull()) } doReturn "device-1"
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
                ) {
                    executed = true
                    // basic sanity
                    require(device == "device-1")
                    require(packageName == "com.example.app")
                    require(output.toString().endsWith(".trace"))
                }

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
                    TODO("Not yet implemented")
                }
            }

        val cmd = StartCommand(shell, Config(android = AndroidConfig(packageName = "com.example.app")), executor)
        cmd.parse(emptyList())
        kotlin.test.assertTrue(executed)
    }
}
