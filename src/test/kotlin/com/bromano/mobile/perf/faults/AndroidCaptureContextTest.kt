package com.bromano.mobile.perf.faults

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AndroidCaptureContextTest {
    @TempDir lateinit var directory: Path

    @Test fun `capture rejects simulated page size and unknown kernel page size`() {
        val collector = AndroidFaultCollector(directory)
        collector.validateKernelPageSize(16384, "KernelPageSize:       16 kB\n")
        assertFailsWith<IllegalArgumentException> { collector.validateKernelPageSize(16384, "KernelPageSize:        4 kB\n") }
        assertFailsWith<IllegalArgumentException> { collector.validateKernelPageSize(4096, "") }
        assertEquals(
            false,
            AndroidDwarf.recordingHealth("Samples recorded: 3. Samples lost: 0 (kernelspace: 2, userspace: 0).", 0)["integrity_passed"],
        )
    }

    @Test fun `loss diagnosis selects kernel or userspace buffers without accepting incomplete stacks`() {
        val kernel = AndroidDwarf.recordingHealth("Samples recorded: 4,239. Samples lost: 303 (kernelspace: 303, userspace: 0).", 0)
        assertEquals(4239L, kernel["samples_recorded"])
        assertEquals(303L, kernel["kernel_lost_records"])
        assertEquals(false, kernel["integrity_passed"])
        assertTrue(kernel["recommendation"].toString().contains("--dwarf-kernel-pages"))
        val truncated = AndroidDwarf.recordingHealth("Samples recorded: 3 (1 with truncated stacks). Samples lost: 0.", 0)
        assertEquals(1L, truncated["truncated_stack_samples"])
        assertEquals(false, truncated["integrity_passed"])
        assertTrue(truncated["recommendation"].toString().contains("--dwarf-user-buffer-mb"))
        val user = AndroidDwarf.recordingHealth("Samples recorded: 3. Samples lost: 2 (kernelspace: 0, userspace: 2).", 0)
        assertEquals(2L, user["userspace_lost_records"])
        assertEquals(false, AndroidDwarf.recordingHealth("", 0)["integrity_passed"])
        assertEquals(false, AndroidDwarf.recordingHealth("Samples recorded: 3. Samples lost: 0.", 1)["integrity_passed"])
        assertEquals(true, AndroidDwarf.recordingHealth("Samples recorded: 3. Samples lost: 0.", 0)["integrity_passed"])
    }

    @Test fun `buffer tuning preserves exact capture settings and validates bounded sizes`() {
        val command = AndroidDwarf.Buffers(8192, 512).command()
        listOf("-m 8192", "--user-buffer-size 512M", "-c 1", "--no-cut-samples", "--no-callchain-joiner", "--clockid boottime").forEach {
            assertTrue(command.contains(it))
        }
        for (pages in listOf(-1, 0, 100, 32768)) assertFailsWith<IllegalArgumentException> { AndroidDwarf.Buffers(pages, 256) }
        assertFailsWith<IllegalArgumentException> { AndroidDwarf.Buffers(4096, 0) }
    }

    @Test fun `context clips spans without inventing endpoints for unfinished operations`() {
        Csv.write(
            directory.resolve("io_advice_spans.csv"),
            listOf("ts", "dur", "name"),
            listOf(
                mapOf("ts" to 900_000, "dur" to 300_000, "name" to "madvising dex"),
                mapOf("ts" to 1_500_000, "dur" to -1, "name" to "madvising oat"),
                mapOf("ts" to 2_000_000, "dur" to 100, "name" to "outside"),
            ),
        )
        Csv.write(
            directory.resolve("page_cache_events.csv"),
            listOf("ts", "page_count"),
            listOf(
                mapOf("ts" to 1_500_000, "page_count" to 4),
                mapOf("ts" to 2_000_000, "page_count" to 1),
            ),
        )
        val io = AndroidCaptureContext.io(directory, mapOf("startup" to mapOf("ts" to 1_000_000, "ts_end" to 2_000_000)))
        val advice = io["advice"] as List<*>
        assertEquals(2, advice.size)
        assertEquals(0.0, (advice[0] as Map<*, *>)["time"])
        assertEquals(0.2, (advice[0] as Map<*, *>)["end"])
        assertEquals(null, (advice[1] as Map<*, *>)["end"])
        assertEquals(1, (io["cache"] as List<*>).size)
        assertEquals("not collected", io["status"])
        assertTrue((io["warnings"] as List<*>).any { it.toString().contains("io_block_events") })
    }

    @Test fun `capture health keeps unknown cache and failed DWARF visible`() {
        val rows =
            AndroidCaptureContext.health(
                directory,
                mapOf("simpleperf_status" to "failed"),
                listOf(
                    mapOf("major" to true, "stack" to emptyList<Any>()),
                ),
                AndroidDwarf.Matches(),
            )
        assertEquals("Not verified", rows.first { it["name"] == "Pre-launch app-file cache" }["value"])
        assertEquals("warning", rows.first { it["name"] == "DWARF companion" }["state"])
        assertTrue(rows.first { it["name"] == "Major-fault user stacks" }["value"].toString().startsWith("0 / 1"))
    }
}
