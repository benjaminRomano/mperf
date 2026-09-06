package com.bromano.mobile.perf.faults

import com.bromano.mobile.perf.utils.CommandResult
import com.bromano.mobile.perf.utils.sha256
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidIoTest {
    @TempDir lateinit var output: Path

    @Test fun `optional config requests only readable valid event formats and records unavailable probes`() {
        val metadata = mutableMapOf<String, Any?>()
        val config =
            AndroidIo.prepare(output, metadata, "ftrace_config { ftrace_events: \"ftrace/print\" }") { command ->
                when {
                    "block_rq_issue" in command -> CommandResult(0, "name: block_rq_issue\nfield:dev_t dev;\n", "")
                    "sched_switch" in command -> CommandResult(1, "", "Permission denied")
                    "block_rq_complete" in command -> CommandResult(0, "malformed", "")
                    else -> CommandResult(44, "", "")
                }
            }
        assertTrue("ftrace_events: \"block/block_rq_issue\"" in config)
        assertFalse("ftrace_events: \"sched/sched_switch\"" in config)
        assertFalse("ftrace_events: \"block/block_rq_complete\"" in config)
        assertFalse("symbolize_ksyms" in config)
        assertTrue("ftrace_events: \"ftrace/print\"" in config)
        assertEquals(sha256(output.resolve("io-ftrace.config")), metadata["trace_config_sha256"])
        assertTrue(Files.isRegularFile(output.resolve("io-event-formats/block-block_rq_issue.txt")))
        val evidence = metadata["io_capture"].toString()
        assertTrue("invalid_format" in evidence && "unavailable" in evidence && "absent" in evidence)
    }

    @Test fun `blocked reason events request record time kernel symbolization`() {
        val config =
            AndroidIo.prepare(output, mutableMapOf(), "ftrace_config { }") { command ->
                if ("sched_blocked_reason" in command) {
                    CommandResult(0, "name: sched_blocked_reason\nfield:unsigned long caller;\n", "")
                } else {
                    CommandResult(44, "", "")
                }
            }
        assertTrue("ftrace_events: \"sched/sched_blocked_reason\"" in config)
        assertTrue("symbolize_ksyms: true" in config)
        assertEquals(config, Files.readString(output.resolve("io-ftrace.config")))
    }

    @Test fun `exports preserve independent system block events and clipped app states without inferred ownership`() {
        val metadata = mutableMapOf<String, Any?>("io_capture" to emptyMap<String, Any?>())
        AndroidIo.export(output, metadata, 42, 100, 200) { sql ->
            assertFalse("JOIN app_inodes" in sql)
            when {
                "FROM ftrace_event" in sql -> {
                    assertTrue("LEFT JOIN thread" in sql)
                    assertTrue("*512" in sql)
                    assertTrue("f.ts >= 100 AND f.ts < 200" in sql)
                    assertFalse("p.pid=42" in sql)
                    listOf(mapOf("event" to "block_rq_complete", "device" to "1", "bytes" to "512", "tid" to "[NULL]"))
                }
                "FROM thread_state" in sql -> {
                    assertTrue("p.pid=42" in sql)
                    assertTrue("MAX(s.ts,100)" in sql)
                    assertTrue("CASE WHEN s.dur >= 0" in sql)
                    listOf(mapOf("dur" to "-1", "incomplete" to "1", "state" to "D"))
                }
                else -> emptyList()
            }
        }
        val block = Csv.read(output.resolve("io_block_events.csv")).single()
        assertEquals("512", block["bytes"])
        assertEquals("[NULL]", block["tid"])
        assertFalse(block.containsKey("file_name") || block.containsKey("latency"))
        val state = Csv.read(output.resolve("io_thread_states.csv")).single()
        assertEquals("", state["io_wait"])
        assertEquals("", state["startup_overlap_ns"])
        assertEquals("1", state["incomplete"])
        assertTrue(metadata["io_results"].toString().contains("status=exported"))
    }

    @Test fun `disabled captures are unchanged and optional export failure preserves other evidence`() {
        AndroidIo.export(output, mutableMapOf(), 1, 10, 20) { error("Must not query") }
        val metadata = mutableMapOf<String, Any?>("io_capture" to emptyMap<String, Any?>(), "warnings" to listOf("existing"))
        AndroidIo.export(output, metadata, 1, 10, 20) { sql ->
            if ("FROM ftrace_event" in sql) error("unsupported parser")
            emptyList()
        }
        assertTrue(Files.isRegularFile(output.resolve("io_advice_spans.csv")))
        assertTrue(Files.isRegularFile(output.resolve("io_thread_states.csv")))
        assertTrue(metadata["io_results"].toString().contains("status=partial"))
        assertTrue(metadata["warnings"].toString().contains("existing"))
    }
}
