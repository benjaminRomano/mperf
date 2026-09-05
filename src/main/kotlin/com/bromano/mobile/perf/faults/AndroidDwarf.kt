package com.bromano.mobile.perf.faults

import com.bromano.mobile.perf.utils.sha256
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

internal object AndroidDwarf {
    private const val REMOTE = "/data/local/tmp/android-fault-visualizer/dwarf.data"
    const val COMMAND =
        "simpleperf record -a -c 1 -m 1024 -e major-faults:u --call-graph dwarf --post-unwind=yes " +
            "--no-callchain-joiner --no-cut-samples --clockid boottime --no-dump-kernel-symbols " +
            "--start_profiling_fd 1 --duration 60 -o $REMOTE"

    data class Running(
        val recorder: AndroidRecorder,
        val bootId: String,
    )

    data class Key(
        val pid: Long,
        val tid: Long,
        val timestamp: Long,
    )

    data class Identity(
        val key: Key,
        val ip: ULong,
        val cpu: Long,
        val frames: Long,
    )

    data class Sample(
        val time: Long,
        val tid: Long,
        val thread: String,
        val stack: List<Map<String, Any?>>,
    )

    data class Match(
        val stack: List<Map<String, Any?>>,
        val provenance: Map<String, Any?>,
    )

    data class Matches(
        val matches: Map<Long, Match> = emptyMap(),
        val coverage: Map<String, Any?> = emptyMap(),
        val warnings: List<String> = emptyList(),
    )

    private fun bootId(adb: AndroidFaultCollector.Device): String =
        adb.shell("cat /proc/sys/kernel/random/boot_id", timeout = Duration.ofSeconds(5)).stdout.trim().also {
            require(Regex("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}").matches(it)) { "Invalid capture boot identity" }
        }

    fun start(adb: AndroidFaultCollector.Device): Running {
        require(adb.pid("simpleperf") == null) { "Another Simpleperf is already recording" }
        val recorder = AndroidRecorder.start(adb, COMMAND)
        try {
            recorder.await(Regex("(?m)^STARTED\\r?$"), 20)
            return Running(recorder, bootId(adb))
        } catch (error: Throwable) {
            recorder.abort(error)
            throw IllegalStateException(
                "DWARF recording requires a compatible Simpleperf supporting --no-cut-samples, " +
                    "--no-callchain-joiner, boottime clocks and recording readiness. No app was launched. ${error.message}",
                error,
            )
        }
    }

    fun finish(
        adb: AndroidFaultCollector.Device,
        running: Running,
        capture: Map<String, Any?>,
        output: Path,
        pull: (String, Path) -> Unit,
    ) {
        val log = running.recorder.stop()
        Files.writeString(output.resolve("simpleperf.log"), log)
        val summary = Regex("Samples recorded:\\s*([\\d,]+)\\.\\s*Samples lost:\\s*([\\d,]+)(?:\\s*\\([^)]*\\))?\\.").find(log)
        val count =
            summary
                ?.groupValues
                ?.get(1)
                ?.replace(",", "")
                ?.toLong()
        val lost =
            summary
                ?.groupValues
                ?.get(2)
                ?.replace(",", "")
                ?.toLong()
        val status = running.recorder.process.exitValue()
        val valid = count != null && lost == 0L && status == 0
        val metadata =
            mutableMapOf<String, Any?>(
                "target_pid" to capture["pid"],
                "samples_recorded" to count,
                "samples_lost" to lost,
                "return_code" to status,
                "integrity_passed" to valid,
                "record_command" to COMMAND,
                "kernel_buffer_pages_per_cpu" to 1024,
                "page_size" to capture["page_size"],
                "kernel_buffer_bytes_per_cpu" to ((capture["page_size"] as? Number)?.toLong()?.times(1024)),
                "online_cpus" to capture["online_cpus_sysfs"],
                "scope" to "system-wide; filtered by exact PID after capture",
                "clock" to "boottime",
                "joiner" to false,
                "gap_removal" to false,
            )
        Json.write(output.resolve("simpleperf-metadata.json"), metadata)
        require(valid) { "Simpleperf failed integrity checks: $log" }
        pull(REMOTE, output.resolve("simpleperf.data"))
        val pid = (capture["pid"] as? Number)?.toLong()
        if (pid != null) {
            val stacks =
                adb.shell(
                    "simpleperf report-sample -i $REMOTE --show-callchain --remove-gaps 0 --include-pid $pid",
                    timeout = Duration.ofMinutes(2),
                )
            Files.writeString(output.resolve("simpleperf-stacks.txt"), stacks.stdout)
        }
        val endBoot = bootId(adb)
        if (pid != null && running.bootId == endBoot && endBoot == capture["boot_id"]) {
            metadata["capture_binding"] =
                mapOf(
                    "boot_id_start" to running.bootId,
                    "boot_id_end" to endBoot,
                    "collector_start_ns" to capture["collector_start_ns"],
                    "serial" to capture["serial"],
                    "artifacts_sha256" to
                        listOf("simpleperf.data", "simpleperf-stacks.txt").associateWith {
                            sha256(output.resolve(it))
                        },
                )
            Json.write(output.resolve("simpleperf-metadata.json"), metadata)
        }
        adb.rootShell("rm -f $REMOTE")
    }

    fun parseSamples(text: String): List<Sample> {
        val samples = mutableListOf<MutableMap<String, Any?>>()
        var sample: MutableMap<String, Any?>? = null
        var frames = mutableListOf<MutableMap<String, Any?>>()
        var frame: MutableMap<String, Any?>? = null
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line == "sample:") {
                frames = mutableListOf()
                sample = mutableMapOf<String, Any?>("stack" to frames).also(samples::add)
                frame = null
            } else if (sample != null && ':' in line) {
                val key = line.substringBefore(':')
                val value = line.substringAfter(':').trim()
                when {
                    key == "vaddr_in_file" -> {
                        frame = mutableMapOf("ip" to value)
                        frames.add(requireNotNull(frame))
                    }
                    key in listOf("file", "symbol") && frame != null -> frame[if (key == "symbol") "label" else "file"] = value
                    key in listOf("event_type", "time", "event_count", "thread_id", "thread_name") -> sample[key] = value
                }
            }
        }
        return samples.map {
            require(
                it["event_count"].toString().toLongOrNull() == 1L && it["event_type"].toString().substringBefore(':') == "major-faults",
            ) {
                "DWARF stream requires period-one major-fault events"
            }
            @Suppress("UNCHECKED_CAST")
            val stack = it["stack"] as List<Map<String, Any?>>
            require(
                stack.isNotEmpty() &&
                    stack.all { frame ->
                        frame.containsKey("file") &&
                            frame.containsKey("label") &&
                            frame["ip"].toString().removePrefix("0x").toULongOrNull(16) != null
                    },
            ) { "Incomplete or malformed DWARF stack export" }
            Sample(
                it["time"].toString().toLong(),
                it["thread_id"].toString().toLong(),
                it["thread_name"]?.toString().orEmpty(),
                stack,
            )
        }
    }

    /** Narrow ABI parser: unfamiliar layouts disable enrichment rather than guessing offsets. */
    fun readIdentities(data: ByteArray): List<Identity> {
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        fun bounds(
            offset: Long,
            size: Long,
        ) {
            require(offset >= 0 && size >= 0 && offset <= data.size.toLong() - size) { "Truncated perf record" }
        }

        fun u32(at: Long): Long {
            bounds(at, 4)
            return buffer.getInt(at.toInt()).toLong() and 0xffffffffL
        }

        fun u64(at: Long): Long {
            bounds(at, 8)
            return buffer.getLong(at.toInt())
        }

        fun u16(at: Long): Int {
            bounds(at, 2)
            return buffer.getShort(at.toInt()).toInt() and 65535
        }
        bounds(0, 104)
        require(String(data, 0, 8, Charsets.US_ASCII) == "PERFILE2" && u64(8) == 104L) { "Unsupported perf header" }
        val attrSize = u64(16)
        val attr = u64(24)
        val attrsSize = u64(32)
        val begin = u64(40)
        val size = u64(48)
        require(attrSize == attrsSize && attrSize >= 112 && attr >= 104) { "Expected one event attribute" }
        bounds(attr, attrSize)
        val flags = u64(attr + 40)
        require(
            u32(attr) == 1L &&
                u64(attr + 8) == 6L &&
                u64(attr + 16) == 1L &&
                u64(attr + 24) == 0x1e7L &&
                u32(attr + 4) >= 96 &&
                u32(attr + 4) + 16 <= attrSize &&
                (flags and ((1L shl 4) or (1L shl 10))) == 0L &&
                (flags and (1L shl 5)) != 0L &&
                (flags and (1L shl 25)) != 0L &&
                u32(attr + 92) == 7L,
        ) { "Unsupported event, sample layout, period, or clock" }
        require(begin >= attr + attrsSize) { "Invalid perf data bounds" }
        bounds(begin, size)
        val idsAt = u64(attr + attrSize - 16)
        val idsSize = u64(attr + attrSize - 8)
        require(idsAt >= 104 && idsSize > 0 && idsSize % 8 == 0L)
        bounds(idsAt, idsSize)
        val ids = (0 until idsSize / 8).map { u64(idsAt + it * 8) }.toSet()
        val result = mutableListOf<Identity>()
        var at = begin
        while (at < begin + size) {
            bounds(at, 8)
            val type = u32(at)
            val misc = u16(at + 4)
            val recordSize = u16(at + 6)
            require(recordSize >= 8 && at + recordSize <= begin + size) { "Invalid perf record bounds" }
            require(type !in listOf(2L, 5L, 13L)) { "Perf data includes loss or throttling" }
            if (type == 9L) {
                require(recordSize >= 64) { "Truncated perf sample" }
                val count = u64(at + 56)
                require(
                    count >= 0 &&
                        count <= (recordSize - 64) / 8 &&
                        recordSize.toLong() == 64 + count * 8 &&
                        (misc and 7) == 2 &&
                        u64(at + 48) == 1L &&
                        u64(at + 32) in ids,
                ) { "Invalid perf sample payload or mode" }
                result += Identity(Key(u32(at + 16), u32(at + 20), u64(at + 24)), u64(at + 8).toULong(), u32(at + 40), count)
            }
            at += recordSize
        }
        return result
    }

    private data class ValidatedCompanion(
        val metadata: Map<String, Any?>,
        val raw: List<Identity>,
        val samples: List<Sample>,
    )

    /** Both same-fault enrichment and the independent stack view use this artifact contract. */
    private fun validateCompanion(
        path: Path,
        metadata: Map<String, Any?>,
    ): ValidatedCompanion {
        require(metadata["simpleperf_status"] == "complete") { "DWARF recording is incomplete" }
        val companion = Json.readMap(path.resolve("simpleperf-metadata.json"))
        val binding = companion["capture_binding"] as? Map<*, *> ?: error("No verified same-boot recorder binding")
        require(
            metadata["boot_id"] != null &&
                binding["boot_id_start"] == metadata["boot_id"] &&
                binding["boot_id_end"] == metadata["boot_id"] &&
                metadata["serial"] != null &&
                binding["serial"] == metadata["serial"] &&
                number(binding["collector_start_ns"]) == number(metadata["collector_start_ns"]) &&
                number(metadata["collector_start_ns"]) > 0,
        ) {
            "No verified same-boot recorder binding"
        }
        require(
            metadata["collector_clock"] == "boottime" &&
                companion["clock"] == "boottime" &&
                number(companion["target_pid"]) == number(metadata["pid"]) &&
                companion["integrity_passed"] == true &&
                number(companion["return_code"]) == 0L &&
                number(companion["samples_lost"]) == 0L &&
                companion["joiner"] == false &&
                companion["gap_removal"] == false &&
                metadata["collector"] == "perf-software-page-fault-events" &&
                metadata["capture_status"] == "collected" &&
                listOf("lost", "integrity_errors", "throttled", "return_code", "callchain_overflow").all {
                    metadata["collector_$it"] is Number && number(metadata["collector_$it"]) == 0L
                },
        ) {
            "Capture identity, clock, or integrity mismatch"
        }
        val hashes = binding["artifacts_sha256"] as? Map<*, *> ?: error("Missing artifact binding")
        val files =
            listOf("simpleperf.data", "simpleperf-stacks.txt").associateWith {
                Files.readAllBytes(path.resolve(it)).also { bytes ->
                    require(sha256(bytes) == hashes[it]) { "Artifact hash mismatch: $it" }
                }
            }
        val raw = readIdentities(files.getValue("simpleperf.data"))
        require(raw.size.toLong() == number(companion["samples_recorded"])) { "Raw sample count differs from recorder" }
        val samples = parseSamples(files.getValue("simpleperf-stacks.txt").toString(Charsets.UTF_8))
        val pid = number(metadata["pid"])
        val rawKeys = raw.filter { it.key.pid == pid }.groupingBy(Identity::key).eachCount()
        val exportKeys = samples.groupingBy { Key(pid, it.tid, it.time) }.eachCount()
        require(rawKeys == exportKeys) { "Export sample count or identities differ from raw target samples" }
        return ValidatedCompanion(companion, raw, samples)
    }

    fun exactMatches(
        path: Path,
        metadata: Map<String, Any?>,
    ): Matches {
        if (metadata["simpleperf_status"] != "complete") return Matches()
        return try {
            val validated = validateCompanion(path, metadata)
            val raw = validated.raw
            val samples = validated.samples
            val pid = number(metadata["pid"])
            val native = Csv.read(path.resolve("fault_events.csv"))

            fun key(row: Map<String, String>) =
                Key(row.getValue("pid").toLong(), row.getValue("tid").toLong(), row.getValue("timestamp_ns").toLong())
            val nativeByKey = native.groupBy(::key)
            val rawByKey = raw.groupBy(Identity::key)
            val symbols = samples.groupBy { Key(pid, it.tid, it.time) }
            val startup = metadata["startup"] as Map<*, *>
            val begin = number(startup["ts"])
            val end = number(startup["ts_end"])
            val selected = native.filter { key(it).pid == pid && key(it).timestamp in begin until end }
            val processed = Csv.read(path.resolve("all_faults.csv"))
            require(selected.size == processed.size) { "Processed faults differ from native startup stream" }
            selected.zip(processed).forEachIndexed { index, (row, processedRow) ->
                require(
                    processedRow["sequence"]?.toLong() == index.toLong() &&
                        processedRow["ts"]?.toLong() == key(row).timestamp &&
                        processedRow["event_type"] == row["event_type"] &&
                        processedRow["tid"] == row["tid"] &&
                        listOf("ip", "address").all { unsigned(processedRow.getValue(it)) == unsigned(row.getValue(it)) },
                ) {
                    "Processed fault identity differs from native record"
                }
            }
            val verified = mutableMapOf<Key, Pair<Identity, Sample>>()
            var ambiguous = 0
            rawByKey.filterKeys { it.pid == pid }.forEach { (key, records) ->
                val n = nativeByKey[key].orEmpty()
                val s = symbols[key].orEmpty()
                if (records.size > 1 || n.size > 1 || s.size > 1) {
                    ambiguous++
                    return@forEach
                }
                if (n.size != 1 || s.size != 1) return@forEach
                val r = records.single()
                val fault = n.single()
                val symbol = s.single()
                if (fault["event_type"] == "major" &&
                    r.ip == unsigned(fault.getValue("ip")) &&
                    r.cpu == fault["cpu"]?.toLong() &&
                    r.frames > 0 &&
                    symbol.stack.isNotEmpty()
                ) {
                    verified[key] = r to symbol
                }
            }
            val matches = mutableMapOf<Long, Match>()
            selected.forEachIndexed { index, row ->
                verified[key(row)]?.let { (rawSample, sample) ->
                    matches[index.toLong()] =
                        Match(
                            sample.stack.map { frame ->
                                frame +
                                    mapOf(
                                        "kind" to "user",
                                        "app" to androidAppOwned(frame["file"].toString(), metadata["package"].toString()),
                                        "unresolved" to (frame["label"] in listOf("Unresolved", "[unknown]")),
                                    )
                            },
                            mapOf(
                                "stream" to "Simpleperf DWARF",
                                "match" to "Exact PID, TID, boottime timestamp, runtime IP, CPU; period-one major event",
                                "timestamp_ns" to rawSample.key.timestamp,
                                "ip" to "0x${rawSample.ip.toString(16)}",
                                "cpu" to rawSample.cpu,
                                "boot_id" to metadata["boot_id"],
                                "address_source" to "Native fault event; not recorded by Simpleperf",
                            ),
                        )
                }
            }
            val majors = selected.count { it["event_type"] == "major" }
            val targetCount = raw.count { it.key.pid == pid }
            Matches(
                matches,
                mapOf(
                    "startup_major_faults" to majors,
                    "matched_startup_major_faults" to matches.size,
                    "unmatched_startup_major_faults" to majors - matches.size,
                    "raw_target_samples" to targetCount,
                    "matched_target_samples" to verified.size,
                    "unmatched_target_samples" to targetCount - verified.size,
                    "ambiguous_target_keys" to ambiguous,
                ),
            )
        } catch (error: Exception) {
            Matches(warnings = listOf("DWARF enrichment unavailable: ${error.message}."))
        }
    }

    fun reportRun(
        path: Path,
        metadata: Map<String, Any?>,
        warnings: MutableList<String> = mutableListOf(),
    ): Map<String, Any?>? {
        if (metadata["simpleperf_status"] != "complete") return null
        return try {
            val validated = validateCompanion(path, metadata)
            val startup = metadata["startup"] as Map<*, *>
            val begin = number(startup["ts"])
            val end = number(startup["ts_end"])
            val sources = mutableMapOf<String, Any?>()
            val events =
                validated.samples.filter { it.time in begin until end }.mapIndexed {
                    i,
                    sample,
                    ->
                    val source =
                        sample.stack
                            .firstOrNull()
                            ?.get("file")
                            ?.toString() ?: "Unresolved stack"
                    sources[source] = mapOf("label" to source, "path" to source, "mapped" to false, "boundaries" to emptyList<Any>())
                    mapOf(
                        "id" to i,
                        "time" to (sample.time - begin) / 1e6,
                        "major" to true,
                        "address" to "0x0",
                        "source" to source,
                        "offset" to null,
                        "page" to null,
                        "thread" to "${sample.thread} (${sample.tid})",
                        "stack" to
                            sample.stack.map {
                                it +
                                    mapOf(
                                        "app" to androidAppOwned(it["file"].toString(), metadata["package"].toString()),
                                        "unresolved" to (it["label"] in listOf("Unresolved", "[unknown]")),
                                    )
                            },
                        "detail" to mapOf("Read source" to "Not recorded by Simpleperf"),
                    )
                }
            mapOf(
                "label" to "${path.fileName} · DWARF stacks",
                "subtitle" to "Android Simpleperf · independent major-fault samples · same launch window",
                "stacksOnly" to true,
                "pageSize" to metadata["page_size"],
                "sources" to sources,
                "events" to events,
                "cache" to "See the exact capture for pre-launch cache verification.",
                "provenance" to validated.metadata,
                "notes" to
                    listOf(
                        "Recording starts before launch; DWARF/ART unwinding can recover managed methods. Stack joining and gap removal are disabled.",
                        "These are stack instruction sources, not read-file attribution. Exact-fault enrichment requires uniquely matching raw identities and capture provenance.",
                        "Two recorders add overhead. Compare only equivalently instrumented runs.",
                    ),
            )
        } catch (error: Exception) {
            warnings += "DWARF companion omitted: ${error.message}. Exact native events remain available."
            null
        }
    }

    private fun number(value: Any?): Long = (value as? Number)?.toLong() ?: error("Missing numeric capture metadata")

    private fun unsigned(value: String): ULong = if (value.startsWith("0x")) value.substring(2).toULong(16) else value.toULong()
}
