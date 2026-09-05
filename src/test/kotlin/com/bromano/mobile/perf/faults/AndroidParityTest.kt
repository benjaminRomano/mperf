package com.bromano.mobile.perf.faults

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidParityTest {
    @TempDir lateinit var directory: Path

    @Test fun `recorder readiness is visible before newline or pipe close`() {
        val input = PipedInputStream()
        val writer = PipedOutputStream(input)
        val output = StringBuffer()
        val reader = thread(isDaemon = true) { AndroidRecorder.drain(input, output) }
        try {
            writer.write("MPERF_RECORDER_PID=123\nSTARTED".toByteArray())
            writer.flush()
            val deadline = System.nanoTime() + 2_000_000_000L
            while (!output.endsWith("STARTED") && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(output.endsWith("STARTED"), output.toString())
            assertTrue(reader.isAlive, "Readiness must arrive while the recorder pipe remains open")
        } finally {
            writer.close()
            reader.join(2_000)
        }
        assertFalse(reader.isAlive)
    }

    @Test fun `portable processing discovers symbolizer after recorded tool path disappears`() {
        val sdk = directory.resolve("sdk")
        val tool = sdk.resolve("ndk/29.0.0/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-symbolizer")
        Files.createDirectories(tool.parent)
        Files.writeString(tool, "fixture")
        assertTrue(tool.toFile().setExecutable(true))
        assertEquals(
            tool,
            AndroidBinary.findSymbolizer(directory.resolve("old-host/tool"), mapOf("ANDROID_HOME" to sdk.toString()), directory),
        )
        assertEquals(tool, AndroidBinary.findSymbolizer(tool, emptyMap(), directory))
        assertNull(AndroidBinary.findSymbolizer(null, emptyMap(), directory))
    }

    @Test fun `APK boundaries identify stored DEX payloads only and keep file relative pages`() {
        val archive = directory.resolve("base.apk")
        ZipOutputStream(Files.newOutputStream(archive)).use { zip ->
            for ((name, stored) in listOf("classes.dex" to true, "classes2.dex" to false, "resources.arsc" to true)) {
                val payload = byteArrayOf(1, 2, 3, 4)
                val entry = ZipEntry(name)
                if (stored) {
                    entry.method = ZipEntry.STORED
                    entry.size = payload.size.toLong()
                    entry.compressedSize = entry.size
                    entry.crc = CRC32().apply { update(payload) }.value
                }
                zip.putNextEntry(entry)
                zip.write(payload)
                zip.closeEntry()
            }
        }
        val boundaries = AndroidFaultReport(directory).storedDexBoundaries(archive, 4096)
        assertEquals(1, boundaries.size)
        assertEquals("classes.dex", boundaries.single()["label"])
        assertEquals("dex", boundaries.single()["kind"])
        assertEquals(ZipLayout.read(archive).first().dataOffset / 4096.0, boundaries.single()["page"])
    }

    private fun dex(): ByteArray {
        val data = ByteArray(512)
        "dex\n035\u0000".toByteArray().copyInto(data)
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(32, 512)
        b.putInt(36, 112)
        b.putInt(40, 0x12345678)
        listOf(
            Triple(56, 2, 112),
            Triple(64, 1, 120),
            Triple(72, 1, 124),
            Triple(88, 1, 136),
            Triple(96, 1, 144),
            Triple(104, 320, 192),
        ).forEach { (at, count, offset) ->
            b.putInt(at, count)
            b.putInt(at + 4, offset)
        }
        b.putInt(112, 192)
        b.putInt(116, 220)
        b.putInt(140, 1)
        b.putInt(168, 256)
        val name = "Lexample/Startup;".toByteArray()
        data[192] = name.size.toByte()
        name.copyInto(data, 193)
        byteArrayOf(5, 115, 116, 97, 114, 116, 0).copyInto(data, 220)
        byteArrayOf(0, 0, 1, 0, 0, 1, 0xc0.toByte(), 2).copyInto(data, 256)
        b.putInt(332, 4)
        return data
    }

    @Test fun `standard DEX methods are page content not caller evidence`() {
        assertEquals(listOf(AndroidBinary.Region(336, 344, "example.Startup.start")), AndroidBinary.dexMethods(dex()))
        val unicode = dex()
        byteArrayOf(2, 0xed.toByte(), 0xa0.toByte(), 0xbd.toByte(), 0xed.toByte(), 0xb8.toByte(), 0x80.toByte(), 0).copyInto(unicode, 220)
        assertEquals("example.Startup.😀", AndroidBinary.dexMethods(unicode).single().name)
    }

    @Test fun `DEX malformed pointers tables identities and unsupported versions fail closed`() {
        for ((offset, value) in listOf(108 to 0, 112 to 36, 168 to -16, 92 to 112, 144 to 1, 332 to 10000)) {
            val data = dex()
            ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value)
            assertTrue(AndroidBinary.dexMethods(data).isEmpty(), "offset=$offset")
        }
        val modern = dex()
        "041".toByteArray().copyInto(modern, 4)
        assertTrue(AndroidBinary.dexMethods(modern).isEmpty())
        val malformed = dex()
        malformed[220] = 4
        assertTrue(AndroidBinary.dexMethods(malformed).isEmpty())
    }

    @Test fun `reboot readiness requires a different valid boot ID`() {
        val old = "12345678-1234-1234-1234-123456789abc"
        val new = "abcdef12-1234-1234-1234-123456789abc"
        assertFalse(AndroidCache.newBootReady(old, old, "1"))
        assertFalse(AndroidCache.newBootReady(old, new, "0"))
        assertFalse(AndroidCache.newBootReady(old, "", "1"))
        assertTrue(AndroidCache.newBootReady(old, new, "1"))
    }

    @Test fun `mapped APK reclaim is bounded and advice does not prove eviction`() {
        val apk = "/data/app/com.example.app-x/base.apk"
        val header = "pid\tstarttime\tbegin\tend\toffset\tdev\tinode\tpermissions\trequested\tresult\terrno\tpath\n"
        val row = "123\t456\t1000\t2000\t0\tfe:34\t1234\tr--p\t4096\t4096\t0\t$apk\n"
        assertEquals(1, AndroidCache.parseAudit(header + row, listOf(apk)).size)
        for (bad in listOf(row.replace("r--p", "rw-p"), row.replace("4096\t4096", "4096\t8192"), row.replace(apk, "$apk.other"))) {
            assertFailsWith<IllegalArgumentException> { AndroidCache.parseAudit(header + bad, listOf(apk)) }
        }
        assertFailsWith<IllegalArgumentException> { AndroidCache.validateApks(listOf("/data/user/0/com.example.app/file.apk")) }
        assertFailsWith<IllegalArgumentException> { AndroidCache.validateApks(listOf(apk, apk)) }
    }

    @Test fun `residency permits disappearing optional files but never missing APK evidence`() {
        val row = mapOf("file_name" to "base.apk", "size_bytes" to "16385", "total_pages" to "2", "resident_pages" to "0")
        AndroidCache.validateResidency(listOf(row), listOf("base.apk", "temp"), listOf("base.apk"), 16384)
        assertFailsWith<IllegalArgumentException> {
            AndroidCache.validateResidency(
                emptyList(),
                listOf("base.apk"),
                listOf("base.apk"),
                16384,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AndroidCache.validateResidency(listOf(row, row), listOf("base.apk"), listOf("base.apk"), 16384)
        }
        assertFailsWith<IllegalArgumentException> {
            AndroidCache.validateResidency(listOf(row + ("resident_pages" to "3")), listOf("base.apk"), listOf("base.apk"), 16384)
        }
        assertFalse(androidAppOwned("/data/app/com.example.application-x/base.apk", "com.example.app"))
        assertTrue(androidAppOwned("/data/app/~~x/com.example.app-random/base.apk", "com.example.app"))
    }

    data class Input(
        val time: Long = 150,
        val ip: Long = 4096,
        val cpu: Int = 2,
        val tid: Int = 11,
    )

    private fun perf(samples: List<Input>): ByteArray {
        val data = ByteArray(264 + 72 * samples.size)
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        "PERFILE2".toByteArray().copyInto(data)
        listOf(104L, 152L, 112L, 152L, 264L, (72 * samples.size).toLong()).forEachIndexed { i, value -> b.putLong(8 + i * 8, value) }
        b.putLong(104, 42)
        b.putInt(112, 1)
        b.putInt(116, 136)
        b.putLong(120, 6)
        b.putLong(128, 1)
        b.putLong(136, 0x1e7)
        b.putLong(152, (1L shl 25) or (1L shl 5))
        b.putInt(204, 7)
        b.putLong(248, 104)
        b.putLong(256, 8)
        samples.forEachIndexed { i, sample ->
            val at = 264 + i * 72
            b.putInt(at, 9)
            b.putShort(at + 4, 2)
            b.putShort(at + 6, 72)
            b.putLong(at + 8, sample.ip)
            b.putInt(at + 16, 10)
            b.putInt(at + 20, sample.tid)
            b.putLong(at + 24, sample.time)
            b.putLong(at + 32, 42)
            b.putInt(at + 40, sample.cpu)
            b.putLong(at + 48, 1)
            b.putLong(at + 56, 1)
            b.putLong(at + 64, sample.ip)
        }
        return data
    }

    private fun hash(data: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    private fun capture(
        raw: List<Input> = listOf(Input()),
        native: List<Input> = listOf(Input()),
        symbols: List<Input> = raw,
    ): MutableMap<String, Any?> {
        Files.write(directory.resolve("simpleperf.data"), perf(raw))
        Files.writeString(
            directory.resolve("simpleperf-stacks.txt"),
            symbols.joinToString("\n") {
                "sample:\n event_type: major-faults:u\n time: ${it.time}\n event_count: 1\n thread_id: ${it.tid}\n vaddr_in_file: 1000\n file: /data/app/com.example.app-x/base.apk\n symbol: Example.start\n"
            },
        )
        Csv.write(
            directory.resolve("fault_events.csv"),
            listOf("timestamp_ns", "event_type", "pid", "tid", "ip", "address", "cpu"),
            native.map {
                mapOf(
                    "timestamp_ns" to it.time,
                    "event_type" to "major",
                    "pid" to 10,
                    "tid" to it.tid,
                    "ip" to "0x${it.ip.toString(16)}",
                    "address" to "0xabc",
                    "cpu" to it.cpu,
                )
            },
        )
        Csv.write(
            directory.resolve("all_faults.csv"),
            listOf("sequence", "ts", "event_type", "tid", "ip", "address"),
            native
                .filter {
                    it.time in
                        100 until 200
                }.mapIndexed { index, it ->
                    mapOf("sequence" to index, "ts" to it.time, "event_type" to "major", "tid" to it.tid, "ip" to it.ip, "address" to 0xabc)
                },
        )
        val boot = "12345678-1234-1234-1234-123456789abc"
        Json.write(
            directory.resolve("simpleperf-metadata.json"),
            mapOf(
                "target_pid" to 10,
                "clock" to "boottime",
                "integrity_passed" to true,
                "return_code" to 0,
                "samples_lost" to 0,
                "samples_recorded" to raw.size,
                "joiner" to false,
                "gap_removal" to false,
                "capture_binding" to
                    mapOf(
                        "boot_id_start" to boot,
                        "boot_id_end" to boot,
                        "collector_start_ns" to 1,
                        "serial" to "emulator-test",
                        "artifacts_sha256" to
                            listOf(
                                "simpleperf.data",
                                "simpleperf-stacks.txt",
                            ).associateWith { hash(Files.readAllBytes(directory.resolve(it))) },
                    ),
            ),
        )
        return mutableMapOf<String, Any?>(
            "boot_id" to boot,
            "serial" to "emulator-test",
            "pid" to 10,
            "package" to "com.example.app",
            "collector_start_ns" to 1,
            "collector_clock" to "boottime",
            "collector" to "perf-software-page-fault-events",
            "capture_status" to "collected",
            "simpleperf_status" to "complete",
            "startup" to mapOf("ts" to 100, "ts_end" to 200),
        ).apply {
            listOf("lost", "integrity_errors", "throttled", "return_code", "callchain_overflow").forEach { put("collector_$it", 0) }
        }
    }

    @Test fun `DWARF exact raw matching binds stacks but never creates addresses`() {
        val metadata = capture()
        val result = AndroidDwarf.exactMatches(directory, metadata)
        assertTrue(result.warnings.isEmpty())
        val match = result.matches.getValue(0)
        assertEquals("Example.start", match.stack.single()["label"])
        assertEquals("user", match.stack.single()["kind"])
        assertEquals(1, result.coverage["matched_startup_major_faults"])
        assertTrue(match.provenance["address_source"].toString().startsWith("Native fault event"))
        assertNotNull(AndroidDwarf.reportRun(directory, metadata))
    }

    @Test fun `missing native integrity counters reject DWARF enrichment`() {
        val metadata = capture()
        metadata.remove("collector_lost")
        val result = AndroidDwarf.exactMatches(directory, metadata)
        assertTrue(result.matches.isEmpty())
        assertTrue(result.warnings.any { "integrity mismatch" in it })
    }

    @Test fun `near timestamp mismatched IP CPU or TID cannot be paired`() {
        for (changed in listOf(Input(time = 151), Input(ip = 4097), Input(cpu = 3), Input(tid = 12))) {
            val result = AndroidDwarf.exactMatches(directory, capture(raw = listOf(changed)))
            assertTrue(result.matches.isEmpty())
            assertEquals(1, result.coverage["unmatched_startup_major_faults"])
        }
    }

    @Test fun `duplicate identities are rejected across all saved streams`() {
        for (mode in 0..2) {
            val duplicate = listOf(Input(), Input())
            val metadata =
                when (mode) {
                    0 -> capture(raw = duplicate)
                    1 -> capture(native = duplicate)
                    else -> capture(symbols = duplicate)
                }
            val result = AndroidDwarf.exactMatches(directory, metadata)
            assertTrue(result.matches.isEmpty())
            if (mode == 2) {
                assertTrue(result.warnings.any { "Export sample count" in it })
            } else {
                assertEquals(1, result.coverage["ambiguous_target_keys"])
            }
        }
    }

    private fun refreshCompanionHashes() {
        val companion = Json.readMap(directory.resolve("simpleperf-metadata.json"))

        @Suppress("UNCHECKED_CAST")
        val binding = companion["capture_binding"] as Map<String, Any?>
        companion["capture_binding"] = binding +
            mapOf(
                "artifacts_sha256" to
                    listOf("simpleperf.data", "simpleperf-stacks.txt")
                        .associateWith { hash(Files.readAllBytes(directory.resolve(it))) },
            )
        Json.write(directory.resolve("simpleperf-metadata.json"), companion)
    }

    private fun assertInvalidCompanionPreservesNativeHtml(metadata: MutableMap<String, Any?>) {
        val warnings = mutableListOf<String>()
        assertNull(AndroidDwarf.reportRun(directory, metadata, warnings))
        assertTrue(warnings.any { "DWARF companion omitted" in it })
        assertTrue(AndroidDwarf.exactMatches(directory, metadata).matches.isEmpty())
        val rows = Csv.read(directory.resolve("all_faults.csv")).map { it + ("elapsed_ms" to "0.0") }
        Csv.write(directory.resolve("all_faults.csv"), rows.first().keys.toList(), rows)
        metadata.putAll(
            mapOf(
                "schema_version" to 5,
                "processing_status" to "complete",
                "page_size" to 4096,
                "results" to mapOf("all_faults" to rows.size),
            ),
        )
        Json.write(directory.resolve("capture_metadata.json"), metadata)
        val engine = BundledFaultEngine(directory.resolve("engine")).materialize()
        val html = directory.resolve("report.html")
        AndroidFaultReport(engine).build(directory, html, "Valid native capture")
        val document = Files.readString(html)
        val payload =
            document
                .substringAfter("const REPORT = ")
                .substringBefore('\n')
                .trim()
                .removeSuffix(";")
        val runs = Json.mapper.readTree(payload).path("runs")
        assertEquals(1, runs.size())
        assertEquals(rows.size, runs[0].path("events").size())
        assertTrue(runs[0].path("notes").any { "DWARF companion omitted" in it.asText() })
    }

    @Test fun `stale companion hash cannot appear beside valid native HTML`() {
        val metadata = capture()
        Files.writeString(directory.resolve("simpleperf-stacks.txt"), "stale stream")
        assertInvalidCompanionPreservesNativeHtml(metadata)
    }

    @Test fun `truncated raw companion cannot abort valid native HTML`() {
        val metadata = capture()
        val raw = directory.resolve("simpleperf.data")
        Files.write(raw, Files.readAllBytes(raw).copyOf(300))
        refreshCompanionHashes()
        assertInvalidCompanionPreservesNativeHtml(metadata)
    }

    @Test fun `export count mismatch cannot appear as an independent stack run`() {
        val metadata = capture()
        Files.writeString(directory.resolve("simpleperf-stacks.txt"), "meta_info:\n  event_type: major-faults:u\n")
        refreshCompanionHashes()
        assertInvalidCompanionPreservesNativeHtml(metadata)
    }

    @Test fun `recorded count mismatch cannot appear as an independent stack run`() {
        val metadata = capture()
        val companion = Json.readMap(directory.resolve("simpleperf-metadata.json"))
        companion["samples_recorded"] = 10
        Json.write(directory.resolve("simpleperf-metadata.json"), companion)
        assertInvalidCompanionPreservesNativeHtml(metadata)
    }

    @Test fun `malformed complete export cannot abort valid native HTML`() {
        val metadata = capture()
        val export = directory.resolve("simpleperf-stacks.txt")
        Files.writeString(export, Files.readString(export).replace("symbol: Example.start", "symbol is missing"))
        refreshCompanionHashes()
        assertInvalidCompanionPreservesNativeHtml(metadata)
    }

    @Test fun `boot clock and file binding failures never silently enrich`() {
        var metadata = capture()
        metadata["boot_id"] = "other"
        assertTrue(AndroidDwarf.exactMatches(directory, metadata).matches.isEmpty())
        metadata = capture()
        metadata["collector_clock"] = "monotonic"
        assertTrue(AndroidDwarf.exactMatches(directory, metadata).matches.isEmpty())
        metadata = capture()
        Files.writeString(directory.resolve("simpleperf-stacks.txt"), "different stream")
        assertTrue(
            AndroidDwarf
                .exactMatches(directory, metadata)
                .warnings
                .single()
                .contains("hash mismatch"),
        )
    }

    @Test fun `raw perf parser rejects malformed layouts and nonperiod-one events`() {
        for ((offset, value) in listOf(120 to 5L, 128 to 2L, 136 to 0x1efL, 264 + 48 to 2L, 264 + 56 to 100L)) {
            val data = perf(listOf(Input()))
            ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).putLong(offset, value)
            assertFailsWith<IllegalArgumentException> { AndroidDwarf.readIdentities(data) }
        }
        assertFailsWith<IllegalArgumentException> { AndroidDwarf.readIdentities(perf(listOf(Input())).copyOf(300)) }
    }

    @Test fun `collector summary preserves capacities and buffer provenance`() {
        val metadata =
            AndroidFaultCollector(
                directory,
            ).parseCollectorSummary(
                "capture_start_ns=1 capture_end_ns=2 samples=3 mappings=4 lost=0 integrity_errors=0 throttled=0 callchain_entries=5 callchain_overflow=0 lost_counter_supported=1 max_samples=500000 max_mappings=100000 max_callchain_entries=4000000 record_buffer_bytes=117600000 perf_ring_bytes=33685504\n",
            )
        assertEquals(117600000L, metadata["record_buffer_bytes"])
        assertEquals(1L, metadata["start"])
        assertFailsWith<IllegalArgumentException> { AndroidFaultCollector(directory).parseCollectorSummary("capture_start_ns=1\n") }
    }
}
