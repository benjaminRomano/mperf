package com.bromano.mobile.perf.faults

import com.bromano.mobile.perf.tools.NativeTraceProcessor
import com.bromano.mobile.perf.utils.Processes
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AndroidStartupWindowTest {
    @TempDir lateinit var directory: Path

    private fun row(fullyDrawn: String) =
        mapOf(
            "startup_id" to "1",
            "ts" to "100",
            "ts_end" to "200",
            "startup_type" to "cold",
            "fully_drawn_ts" to fullyDrawn,
        )

    @Test fun `fully drawn replaces first frame and recomputes duration`() {
        val startup = AndroidFaultProcessor().queryStartup(Path.of("unused"), "app", 42) { _, _ -> listOf(row("500")) }
        assertEquals(500L, startup.end)
        assertEquals(400L, startup.duration)
        assertEquals(200L, startup.firstFrameEnd)
        assertEquals("reportFullyDrawn", startup.endMarker)
    }

    @Test fun `missing fully drawn preserves first frame cutoff`() {
        val startup = AndroidFaultProcessor().queryStartup(Path.of("unused"), "app", 42) { _, _ -> listOf(row("[NULL]")) }
        assertEquals(200L, startup.end)
        assertEquals(100L, startup.duration)
        assertEquals("first_frame", startup.endMarker)
    }

    @Test fun `early fully drawn uses marker timestamp without waiting for another frame`() {
        val startup = AndroidFaultProcessor().queryStartup(Path.of("unused"), "app", 42) { _, _ -> listOf(row("150")) }
        assertEquals(150L, startup.end)
        assertEquals(50L, startup.duration)
    }

    @Test fun `missing and ambiguous startups fail closed`() {
        for (rows in listOf(emptyList(), listOf(row("500"), row("600")))) {
            assertFailsWith<IllegalArgumentException> {
                AndroidFaultProcessor().queryStartup(Path.of("unused"), "app", 42) { _, _ -> rows }
            }
        }
    }

    @Test fun `native SQL selects earliest main thread marker from the captured startup process`() {
        assumeTrue(java.lang.Boolean.getBoolean("mperf.integration.enabled"), "Uses the pinned native Perfetto binary")
        val trace = Files.createFile(directory.resolve("empty.trace"))
        val processor = NativeTraceProcessor().materialize()
        val cases =
            listOf(
                "(1, 50, 'reportFullyDrawn'), (2, 250, 'reportFullyDrawn.worker'), (3, 300, 'reportFullyDrawn.other')" to 200L,
                "(1, 600, 'reportFullyDrawn'), (1, 500, 'reportFullyDrawn.custom'), (1, 400, 'unrelated')" to 500L,
                "(1, 150, 'reportFullyDrawn')" to 150L,
                "(4, 250, 'reportFullyDrawn.reused-pid')" to 200L,
            )
        for ((slices, expected) in cases) {
            val startup =
                AndroidFaultProcessor().queryStartup(trace, "app", 42) { path, sql ->
                    // Shadow only the input relations; execute the production selection SQL in Perfetto.
                    val query =
                        """
                        WITH android_startups(startup_id, ts, ts_end, startup_type, package) AS (
                          VALUES (1, 100, 200, 'cold', 'app')
                        ), android_startup_processes(startup_id, upid) AS (VALUES (1, 10)),
                        process(upid, pid) AS (VALUES (10, 42), (20, 99), (30, 42)),
                        thread(utid, upid, is_main_thread) AS (VALUES (1, 10, 1), (2, 10, 0), (3, 20, 1), (4, 30, 1)),
                        thread_track(id, utid) AS (VALUES (1, 1), (2, 2), (3, 3), (4, 4)),
                        slice(track_id, ts, name) AS (VALUES $slices)
                        ${sql.substringAfter("INCLUDE PERFETTO MODULE android.startup.startups;")}
                        """.trimIndent()
                    val csv = directory.resolve("query.csv")
                    Files.writeString(csv, Processes.run(listOf(processor.toString(), "-Q", query, path.toString())).stdout.trimStart())
                    Csv.read(csv)
                }
            assertEquals(expected, startup.end, slices)
        }
    }
}
