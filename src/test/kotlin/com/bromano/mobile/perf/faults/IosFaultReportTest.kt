package com.bromano.mobile.perf.faults

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IosFaultReportTest {
    @TempDir
    lateinit var output: Path

    @Test
    fun `rejects malformed metadata counts and non-page-sized units`() {
        Json.write(output.resolve("capture_metadata.json"), mapOf("schema_version" to 1))
        Json.write(output.resolve("page_fault_stats.json"), mapOf("event_count" to 1, "page_size_bytes" to 16384))
        Csv.write(output.resolve("page_fault_events.csv"), listOf("event_index"), emptyList())
        assertFailsWith<IllegalArgumentException> { IosFaultReport(output).reportRun(output) }
        Json.write(output.resolve("page_fault_stats.json"), mapOf("event_count" to 0, "page_size_bytes" to 123))
        assertFailsWith<IllegalArgumentException> { IosFaultReport(output).reportRun(output) }
        Json.write(output.resolve("page_fault_stats.json"), mapOf("event_count" to 0, "page_size_bytes" to 16384))
        assertEquals("Cache: not prepared; no cold-cache claim.", IosFaultReport(output).reportRun(output)["cache"])
        Json.write(output.resolve("capture_metadata.json"), mapOf("schema_version" to 99))
        assertFailsWith<IllegalArgumentException> { IosFaultReport(output).reportRun(output) }
    }
}
