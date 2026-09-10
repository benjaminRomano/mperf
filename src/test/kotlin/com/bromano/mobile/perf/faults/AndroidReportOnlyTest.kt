package com.bromano.mobile.perf.faults

import com.bromano.mobile.perf.commands.faults.AndroidFaultRequest
import com.bromano.mobile.perf.commands.faults.DefaultAndroidFaultWorkflow
import com.bromano.mobile.perf.utils.sha256
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AndroidReportOnlyTest {
    @TempDir lateinit var directory: Path

    @Test fun `report only never recollects or invokes preprocessing and output is reproducible`() {
        val capture = directory.resolve("capture").also(Files::createDirectories)
        Json.write(
            capture.resolve("capture_metadata.json"),
            mapOf(
                "schema_version" to 5,
                "package" to "test.app",
                "page_size" to 4096,
                "capture_status" to "collected",
                "processing_status" to "complete",
                "collector_lost" to 0,
                "collector_integrity_errors" to 0,
                "collector_throttled" to 0,
                "collector_callchain_overflow" to 0,
                "collector_return_code" to 0,
                "results" to mapOf("all_faults" to 1),
                "startup" to mapOf("ts" to 0, "ts_end" to 1_000_000, "duration_ns" to 1_000_000, "end_marker" to "firstFrame"),
            ),
        )
        Csv.write(
            capture.resolve("all_faults.csv"),
            listOf("sequence", "event_type", "elapsed_ms", "address", "tid"),
            listOf(mapOf("sequence" to 0, "event_type" to "major", "elapsed_ms" to 0.5, "address" to "123", "tid" to 1)),
        )
        // These raw inputs are deliberately not preprocessable. Rendering uses the already processed table.
        Files.writeString(capture.resolve("faults.pftrace"), "not a trace")
        val before = AndroidReportInputs.hashes(capture)
        val request =
            AndroidFaultRequest(
                null,
                null,
                null,
                capture,
                0,
                0,
                false,
                false,
                false,
                false,
                false,
                null,
                "Control",
                "Treatment",
                false,
                reportOnly = true,
            )
        val workflow = DefaultAndroidFaultWorkflow(BundledFaultEngine(directory.resolve("engine")))
        val report = workflow.run(request)
        val first = sha256(report)
        assertEquals(before, AndroidReportInputs.hashes(capture))
        workflow.run(request)
        assertEquals(first, sha256(report))
        assertEquals(before, AndroidReportInputs.hashes(capture))
        val payload =
            Files
                .readString(report)
                .substringAfter("const REPORT = ")
                .substringBefore('\n')
                .trim()
                .removeSuffix(";")
        val run = Json.mapper.readTree(payload).path("runs")[0]
        assertEquals(
            before["all_faults.csv"],
            run
                .path("reportInputs")
                .path("sha256")
                .path("all_faults.csv")
                .asText(),
        )
        assertTrue(run.path("reportInputs").path("renderer").has("version"))
        assertTrue(run.path("experiment").path("fullyDrawnMs").isNull)
        Files.writeString(capture.resolve("all_faults.csv"), "invalid\n")
        assertFailsWith<IllegalArgumentException> { workflow.run(request) }
        assertEquals(first, sha256(report), "Failed render must leave prior report intact")
    }

    @Test fun `lean Perfetto preserves startup and explicitly removes page cache evidence`() {
        val collector = AndroidFaultCollector(directory)
        val base =
            "fill_policy: RING_BUFFER\nftrace_events: \"filemap/mm_filemap_add_to_page_cache\"\n" +
                "atrace_categories: \"am\"\natrace_categories: \"dalvik\"\n"
        val lean = collector.perfettoConfig(base, "lean")
        assertTrue("fill_policy: DISCARD" in lean)
        assertTrue("\"am\"" in lean)
        assertTrue("filemap" !in lean && "dalvik" !in lean)
        assertTrue("filemap" in collector.perfettoConfig(base, "full"))
        assertFailsWith<IllegalArgumentException> { collector.perfettoConfig(base, "unknown") }
    }
}
