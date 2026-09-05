package com.bromano.mobile.perf.faults

import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

internal object IosRecordingLifecycle {
    fun validateWindow(
        settleSeconds: Double,
        timeLimit: Int?,
    ) {
        require(settleSeconds.isFinite() && settleSeconds > 0) { "--settle-seconds must be positive and finite" }
        timeLimit?.let {
            require(it > 0) { "--time-limit must be positive" }
            require(it >= ceil(settleSeconds).toInt()) {
                "--time-limit must be at least ceil(--settle-seconds) (${ceil(settleSeconds).toInt()} seconds)"
            }
        }
    }

    fun awaitReady(
        recorder: Process,
        listener: Process,
        timeout: Duration,
        logs: () -> String,
    ): Long {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (System.nanoTime() < deadline) {
            if (!listener.isAlive) {
                check(listener.exitValue() == 0 && recorder.isAlive) {
                    "xctrace readiness listener or recorder exited before launch: ${logs()}"
                }
                return System.nanoTime()
            }
            check(recorder.isAlive) { "xctrace exited before readiness: ${logs()}" }
            Thread.sleep(50)
        }
        error("xctrace timed out before reporting that recording began: ${logs()}")
    }

    fun requirePostLaunchWindow(
        recorder: Process,
        settleSeconds: Double,
        logs: () -> String,
    ) {
        if (recorder.waitFor(ceil(settleSeconds * 1000).toLong(), TimeUnit.MILLISECONDS)) {
            error(
                "xctrace ended before the requested post-launch analysis window completed " +
                    "(exit ${recorder.exitValue()}, requested ${settleSeconds}s)\n${logs()}",
            )
        }
    }
}
