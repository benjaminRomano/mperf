package com.bromano.mobile.perf.integration

import com.bromano.mobile.perf.commands.faults.AndroidFaultRequest
import com.bromano.mobile.perf.commands.faults.DefaultAndroidFaultWorkflow
import com.bromano.mobile.perf.faults.AndroidDwarf
import com.bromano.mobile.perf.faults.BundledFaultEngine
import com.bromano.mobile.perf.faults.Csv
import com.bromano.mobile.perf.faults.Json
import com.bromano.mobile.perf.tools.NativeTraceProcessor
import com.bromano.mobile.perf.utils.Processes
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AndroidFaultCaptureIntegrationTest {
    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun collects_verified_cold_start_faults_with_exact_dwarf_and_io_evidence() {
        assumeTrue(
            java.lang.Boolean.getBoolean("mperf.integration.enabled") &&
                System.getenv("MPERF_TEST_FAULTS") == "true",
            "Run the opt-in Android compatibility faults suite on a dedicated rooted fixture device",
        )
        val device = requireNotNull(System.getProperty("mperf.integration.device")) { "An explicit device is required" }
        val root = IntegrationArtifacts.directory("android-faults")
        val output =
            if (root == null) {
                Files.createTempDirectory("mperf-fixture-faults-")
            } else {
                Files.createTempDirectory(root, "capture-")
            }
        val report =
            DefaultAndroidFaultWorkflow(BundledFaultEngine()).run(
                AndroidFaultRequest(
                    packageName = "com.bromano.mperf.fixture",
                    activity = "com.bromano.mperf.fixture/.FixtureActivity",
                    device = device,
                    output = output,
                    settleMs = 2_000,
                    maxResidentPages = 0,
                    rebootBeforeCollect = false,
                    nativeStacks = false,
                    pullArtifacts = true,
                    skipCollect = false,
                    overwrite = false,
                    comparison = null,
                    label = "Compatibility fixture",
                    comparisonLabel = "Unused",
                    allowIncomparable = false,
                    dwarfStacks = true,
                    reclaimMappedApks = true,
                    compilation = "as-is",
                    ioEvidence = true,
                ),
            )
        assertTrue(Files.size(report) > 0, "Expected a processed fixture report at $report")
        val metadata = Json.readMap(output.resolve("capture_metadata.json"))
        assertEquals("collected", metadata["capture_status"])
        assertEquals("complete", metadata["processing_status"])
        assertEquals("com.bromano.mperf.fixture", metadata["package"])
        val faults = metadata["faults"] as Map<*, *>
        assertTrue(number(faults["total"]) > 0, "Expected startup faults from the fixture")
        val startup = metadata["startup"] as Map<*, *>
        assertTrue(number(startup["duration_ns"]) > 0)
        assertEquals("reportFullyDrawn", startup["end_marker"])
        val end = (startup["ts_end"] as Number).toLong()
        val firstFrame = (startup["first_frame_ts_end"] as Number).toLong()
        assertTrue(end > firstFrame, "Fixture reports fully drawn after initial display")
        val slices = output.resolve("fixture-markers.csv")
        Files.writeString(
            slices,
            Processes
                .run(
                    listOf(
                        NativeTraceProcessor().materialize().toString(),
                        "-Q",
                        """
                        SELECT slice.name, slice.ts, slice.dur FROM slice
                        JOIN thread_track ON thread_track.id = slice.track_id
                        JOIN thread USING (utid) JOIN process USING (upid)
                        WHERE process.pid = ${metadata["pid"]} AND thread.is_main_thread = 1
                          AND (slice.name GLOB 'reportFullyDrawn*' OR slice.name GLOB 'mperf.fixture.*fully-drawn')
                        ORDER BY slice.ts;
                        """.trimIndent(),
                        output.resolve("faults.pftrace").toString(),
                    ),
                ).stdout
                .trimStart(),
        )
        val markers = Csv.read(slices)
        assertEquals(end, markers.first { it.getValue("name").startsWith("reportFullyDrawn") }.getValue("ts").toLong())
        val before = markers.single { it["name"] == "mperf.fixture.before-fully-drawn" }
        val after = markers.single { it["name"] == "mperf.fixture.after-fully-drawn" }
        val processed = Csv.read(output.resolve("all_faults.csv"))
        val raw = Csv.read(output.resolve("fault_events.csv"))

        fun inSlice(
            row: Map<String, String>,
            slice: Map<String, String>,
        ): Boolean {
            val begin = slice.getValue("ts").toLong()
            return (row["timestamp_ns"] ?: row.getValue("ts")).toLong() in begin until begin + slice.getValue("dur").toLong()
        }
        assertTrue(before.getValue("ts").toLong() > firstFrame)
        assertTrue(processed.any { inSlice(it, before) }, "Include faults after first frame and before fully drawn")
        assertTrue(
            raw.any { it["pid"] == metadata["pid"].toString() && inSlice(it, after) },
            "Capture post-marker app faults to prove exclusion",
        )
        assertTrue(processed.none { it.getValue("ts").toLong() >= end }, "Exclude faults at and after fully drawn")
        val cache = metadata["cache_verification"] as Map<*, *>
        assertEquals("before_launch", cache["phase"])
        assertEquals(0, number(cache["resident_pages"]))
        assertTrue(number(cache["files_checked"]) > 0)
        for (key in listOf("return_code", "lost", "integrity_errors", "throttled", "callchain_overflow")) {
            assertEquals(0, number(metadata["collector_$key"]), "Unexpected collector $key")
        }
        assertEquals(0, number((metadata["trace_integrity"] as Map<*, *>)["errors_or_data_loss"]))
        assertEquals(true, (metadata["io_capture"] as Map<*, *>)["requested"])
        assertEquals("exported", (metadata["io_results"] as Map<*, *>)["status"])
        assertEquals("complete", metadata["simpleperf_status"], "DWARF collection must not silently degrade")
        val matches = AndroidDwarf.exactMatches(output, metadata)
        assertTrue(matches.warnings.isEmpty(), "DWARF validation failed: ${matches.warnings}")
        val majors = number(faults["major"])
        assertEquals(majors, number(matches.coverage["startup_major_faults"]))
        assertEquals(0, number(matches.coverage["ambiguous_target_keys"]))
        if (majors > 0) {
            assertEquals(majors, number(matches.coverage["matched_startup_major_faults"]))
            assertEquals(0, number(matches.coverage["unmatched_startup_major_faults"]))
        }
    }

    private fun number(value: Any?): Int = (value as Number).toInt()
}
