package com.bromano.mperf.fixture

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import androidx.tracing.Trace
import androidx.tracing.trace
import kotlin.concurrent.thread

class FixtureActivity : Activity() {
    private val startupAllocations = mutableListOf<ByteArray>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            TextView(this).apply {
                text = "mperf profiling fixture"
                textSize = 24f
                contentDescription = "mperf profiling fixture"
            },
        )
        runProfiledWorkload()
        window.decorView.postDelayed({
            trace("mperf.fixture.before-fully-drawn") { allocatePages() }
            reportFullyDrawn()
            window.decorView.postDelayed({
                trace("mperf.fixture.after-fully-drawn") { allocatePages() }
            }, 300)
        }, 1_000)
    }

    private fun allocatePages() {
        startupAllocations += ByteArray(8 * 1024 * 1024).also { pages ->
            for (offset in pages.indices step 4096) pages[offset] = 1
        }
    }

    private fun runProfiledWorkload() {
        thread(name = "mperf-fixture-workload", isDaemon = true) {
            // A worker's similarly named section must not end the startup window.
            trace("reportFullyDrawn.fixture-worker") { Thread.sleep(1) }
            val deadline = System.nanoTime() + 5_000_000_000L
            var value = 1L
            val asyncCookie = System.identityHashCode(this)
            Trace.beginAsyncSection(ASYNC_TRACE_MARKER, asyncCookie)
            try {
                trace(SYNC_TRACE_MARKER) {
                    var batches = 0L
                    while (System.nanoTime() < deadline) {
                        repeat(100_000) {
                            value = (value * 1_103_515_245L + 12_345L) xor (value ushr 11)
                        }
                        batches++
                        Trace.setCounter(COUNTER_TRACE_MARKER, batches)
                    }
                }
            } finally {
                Trace.endAsyncSection(ASYNC_TRACE_MARKER, asyncCookie)
            }
            if (value == 0L) error("unreachable")
        }
    }

    private companion object {
        const val SYNC_TRACE_MARKER = "mperf.fixture.sync-workload"
        const val ASYNC_TRACE_MARKER = "mperf.fixture.async-lifecycle"
        const val COUNTER_TRACE_MARKER = "mperf.fixture.work-batches"
    }
}
