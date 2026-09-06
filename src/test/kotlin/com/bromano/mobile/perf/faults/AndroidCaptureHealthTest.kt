package com.bromano.mobile.perf.faults

import com.bromano.mobile.perf.commands.faults.AndroidFaultRequest
import com.bromano.mobile.perf.commands.faults.DefaultAndroidFaultWorkflow
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidCaptureHealthTest {
    @TempDir lateinit var directory: Path

    private fun health(metadata: Map<String, Any?>) =
        AndroidCaptureContext
            .health(directory, metadata, emptyList(), AndroidDwarf.Matches(), validatedEvents = false)
            .associateBy { it["name"] }

    @Test
    fun `failed or unprocessed captures never pass exact stream or stack coverage`() {
        for (status in listOf("preparing", "cache_verification_failed", "collector_integrity_failed", "collected")) {
            val rows = health(mapOf("capture_status" to status, "simpleperf_status" to "complete"))
            for (name in listOf("Exact fault stream", "Major-fault user stacks", "Symbol resolution", "DWARF companion")) {
                assertEquals("warning", rows.getValue(name)["state"])
            }
            assertTrue(rows.getValue("Major-fault user stacks")["value"].toString().startsWith("Not evaluated"))
            assertFalse(rows.getValue("DWARF companion")["value"].toString().contains("0 / 0"))
        }
    }

    @Test
    fun `cold cache requires actual before-launch verification of nonempty file set`() {
        fun row(
            phase: String,
            files: Int,
            resident: Int,
        ) = health(
            mapOf("cache_verification" to mapOf("phase" to phase, "files_checked" to files, "resident_pages" to resident)),
        ).getValue("Pre-launch app-file cache")
        assertEquals("warning", row("after_drop", 2, 0)["state"])
        assertEquals(
            "345 resident pages across 2 checked files (after_drop; launch not verified)",
            row("after_drop", 2, 345)["value"],
        )
        assertEquals("warning", row("before_launch", 0, 0)["state"])
        assertEquals("warning", row("before_launch", 2, -1)["state"])
        assertEquals("warning", row("before_launch", 2, 1)["state"])
        assertEquals("pass", row("before_launch", 2, 0)["state"])
    }

    @Test
    fun `failed stream diagnostics retain available counters and unknown trace health`() {
        val rows = health(mapOf("collector_lost" to 9, "collector_throttled" to 2, "collector_integrity_errors" to 3))
        val action = rows.getValue("Exact fault stream")["action"].toString()
        for (counter in listOf("lost=9", "throttled=2", "integrity_errors=3", "callchain_overflow=unknown")) {
            assertTrue(action.contains(counter))
        }
        assertEquals("warning", rows.getValue("Perfetto trace integrity")["state"])
        assertTrue(rows.getValue("Perfetto trace integrity")["value"].toString().startsWith("Unknown"))
        for (count in listOf(0, 4)) {
            val trace =
                health(mapOf("trace_integrity" to mapOf("errors_or_data_loss" to count)))
                    .getValue("Perfetto trace integrity")
            assertEquals(if (count == 0) "pass" else "warning", trace["state"])
            assertEquals("$count errors or data-loss statistics", trace["value"])
        }
    }

    @Test
    fun `exact stream pass requires complete processing and every zero integrity counter`() {
        val valid = mutableMapOf<String, Any?>("capture_status" to "collected", "processing_status" to "complete")
        val counters = listOf("lost", "integrity_errors", "throttled", "callchain_overflow", "return_code")
        counters.forEach { valid["collector_$it"] = 0 }

        fun state(metadata: Map<String, Any?>) =
            AndroidCaptureContext
                .health(
                    directory,
                    metadata,
                    emptyList(),
                    AndroidDwarf.Matches(),
                ).first { it["name"] == "Exact fault stream" }["state"]
        assertEquals("pass", state(valid))
        counters.forEach { counter ->
            assertEquals("warning", state(valid - "collector_$counter"))
            assertEquals("warning", state(valid + ("collector_$counter" to 1)))
        }
        assertEquals("warning", state(valid - "processing_status"))
    }

    @Test
    fun `failed processing emits only health data preserves evidence and still throws`() {
        val capture = Files.createDirectory(directory.resolve("capture"))
        val metadata = "{\"schema_version\":5,\"capture_status\":\"collector_integrity_failed\"}"
        Files.writeString(capture.resolve("capture_metadata.json"), metadata)
        Files.writeString(capture.resolve("all_faults.csv"), "partial,invalid,data")
        val engine = BundledFaultEngine(directory.resolve("engine"))
        val failure =
            assertFailsWith<IllegalArgumentException> {
                DefaultAndroidFaultWorkflow(engine).run(request(capture))
            }
        assertTrue(failure.message.orEmpty().contains("Capture is incomplete"))
        val html = Files.readString(capture.resolve("capture-health.html"))
        val payload = Json.mapper.readTree(html.substringAfter("const REPORT = ").substringBefore(";\n"))
        val run = payload["runs"][0]
        assertTrue(run["healthOnly"].asBoolean())
        assertEquals(0, run["events"].size())
        assertEquals(0, run["sources"].size())
        assertFalse(Files.exists(capture.resolve("report.html")))
        assertEquals(metadata, Files.readString(capture.resolve("capture_metadata.json")))
        assertEquals("partial,invalid,data", Files.readString(capture.resolve("all_faults.csv")))
    }

    @Test
    fun `rejected output does not add a health report to an unchanged capture`() {
        val capture = Files.createDirectory(directory.resolve("capture"))
        Files.writeString(capture.resolve("capture_metadata.json"), "{\"schema_version\":5}")
        val engine = BundledFaultEngine(directory.resolve("engine"))
        assertFailsWith<IllegalArgumentException> {
            DefaultAndroidFaultWorkflow(engine).run(request(capture).copy(skipCollect = false))
        }
        assertFalse(Files.exists(capture.resolve("capture-health.html")))
    }

    @Test
    fun `failure report tolerates missing metadata and escapes failure text`() {
        val engine = BundledFaultEngine(directory.resolve("engine")).materialize()
        val report = directory.resolve("capture-health.html")
        AndroidFaultReport(engine).buildHealth(directory, report, "Failed", IllegalStateException("</script>__DATA__"))
        val html = Files.readString(report)
        assertTrue(html.contains("\\u003c/script\\u003e__DATA__"))
        assertFalse(html.contains("</script>__DATA__"))
    }

    private fun request(capture: Path) =
        AndroidFaultRequest(
            packageName = "com.example.app",
            activity = null,
            device = null,
            output = capture,
            settleMs = 100,
            maxResidentPages = 0,
            rebootBeforeCollect = false,
            nativeStacks = true,
            pullArtifacts = false,
            skipCollect = true,
            overwrite = false,
            comparison = null,
            label = "Failed capture",
            comparisonLabel = "Comparison",
            allowIncomparable = false,
        )
}
