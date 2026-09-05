package com.bromano.mobile.perf.faults

import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IosRecordingLifecycleTest {
    @Test
    fun `recording requires positive finite windows and ceiling of settle time`() {
        listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach {
            assertFailsWith<IllegalArgumentException> { IosRecordingLifecycle.validateWindow(it, null) }
        }
        assertFailsWith<IllegalArgumentException> { IosRecordingLifecycle.validateWindow(2.1, 2) }
        IosRecordingLifecycle.validateWindow(2.1, 3)
    }

    @Test
    fun `notification readiness requires both successful listener and live recorder`() {
        val recorder = mock(Process::class.java)
        val listener = mock(Process::class.java)
        `when`(recorder.isAlive).thenReturn(true)
        `when`(listener.isAlive).thenReturn(false)
        `when`(listener.exitValue()).thenReturn(0)
        assertTrue(IosRecordingLifecycle.awaitReady(recorder, listener, Duration.ofSeconds(1)) { "" } > 0)
        `when`(listener.exitValue()).thenReturn(1)
        assertFailsWith<IllegalStateException> {
            IosRecordingLifecycle.awaitReady(recorder, listener, Duration.ofSeconds(1)) { "listener failed" }
        }
        `when`(listener.exitValue()).thenReturn(0)
        `when`(recorder.isAlive).thenReturn(false)
        assertFailsWith<IllegalStateException> {
            IosRecordingLifecycle.awaitReady(recorder, listener, Duration.ofSeconds(1)) { "recorder failed" }
        }
    }

    @Test
    fun `readiness has a bounded timeout and logs alone are insufficient`() {
        val recorder = mock(Process::class.java)
        val listener = mock(Process::class.java)
        `when`(recorder.isAlive).thenReturn(true)
        `when`(listener.isAlive).thenReturn(true)
        assertFailsWith<IllegalStateException> {
            IosRecordingLifecycle.awaitReady(recorder, listener, Duration.ofMillis(1)) { "Starting recording" }
        }
    }

    @Test
    fun `early recorder exit cannot silently truncate startup`() {
        val recorder = mock(Process::class.java)
        `when`(recorder.waitFor(2100, TimeUnit.MILLISECONDS)).thenReturn(true)
        assertFailsWith<IllegalStateException> {
            IosRecordingLifecycle.requirePostLaunchWindow(recorder, 2.1) { "" }
        }
        `when`(recorder.waitFor(2100, TimeUnit.MILLISECONDS)).thenReturn(false)
        IosRecordingLifecycle.requirePostLaunchWindow(recorder, 2.1) { "" }
    }
}
