package com.bromano.mobile.perf

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.parse
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class OptionsTest {
    private class TestCmd : CliktCommand() {
        lateinit var captured: ProfilerOptionGroup
        private val profiler by androidProfilerOptions()

        override fun run() {
            captured = profiler
        }
    }

    @Test
    fun `default profiler is perfetto`() {
        val cmd = TestCmd()
        cmd.parse(emptyList())
        assertEquals(ProfilerFormat.PERFETTO, cmd.captured.format)
        assertIs<PerfettoOptions>(cmd.captured)
    }

    @Test
    fun `parse simpleperf with defaults`() {
        val cmd = TestCmd()
        cmd.parse(listOf("--format", "simpleperf"))
        assertEquals(ProfilerFormat.SIMPLEPERF, cmd.captured.format)
        assertIs<SimpleperfOptions>(cmd.captured)
        val simple = cmd.captured as SimpleperfOptions
        assertEquals("-e cpu-clock -f 4000 -g", simple.simpleperfArgs)
        assertContentEquals(
            listOf(
                """^io\.reactivex.*$""",
                """^\[DEDUPED\].*$""",
                """^kotlinx?\.coroutines.*$""",
            ),
            simple.removeMethods,
        )
        assertEquals(false, simple.showArtFrames)
    }
}
