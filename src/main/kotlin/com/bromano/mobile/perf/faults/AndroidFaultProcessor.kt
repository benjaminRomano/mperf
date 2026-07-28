package com.bromano.mobile.perf.faults

import com.fasterxml.jackson.core.type.TypeReference
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension

internal fun matchingApkForVdex(
    vdex: String,
    artifacts: Collection<String>,
): String? {
    val vdexPath = Path.of(vdex).normalize()
    val installRoot = vdexPath.parent?.parent?.parent ?: return null
    val stem = vdexPath.fileName.toString().substringBeforeLast('.')
    return artifacts
        .filter { it.endsWith(".apk", ignoreCase = true) }
        .filter { candidate ->
            val apk = Path.of(candidate).normalize()
            apk.parent == installRoot && apk.fileName.toString().substringBeforeLast('.') == stem
        }.singleOrNull()
}

internal class AndroidFaultProcessor(
    private val engineRoot: Path,
) {
    private data class MapEntry(
        val begin: Long,
        val end: Long,
        val permissions: String,
        val fileOffset: Long,
        val device: Long,
        val inode: Long,
        val fileName: String?,
    )

    private data class MappingEvent(
        val timestamp: Long,
        val mapping: MapEntry,
    )

    private data class Startup(
        val id: Long,
        val start: Long,
        val end: Long,
        val duration: Long,
        val type: String?,
    )

    fun process(output: Path) {
        val metadataPath = output.resolve("capture_metadata.json")
        require(Files.isRegularFile(metadataPath)) { "Missing capture_metadata.json in $output" }
        val metadata = Json.readMap(metadataPath)
        require((metadata["schema_version"] as? Number)?.toInt() == 5) {
            "Capture predates timestamped mapping attribution"
        }
        validateIntegrity(metadata)
        val trace = output.resolve("faults.pftrace")
        val startup = queryStartup(trace, metadata["package"].toString())
        val traceFailures =
            query(
                trace,
                """
                SELECT name, severity, source, value FROM stats
                WHERE severity IN ('error', 'data_loss') AND value != 0
                ORDER BY name, source;
                """.trimIndent(),
            )
        require(traceFailures.isEmpty()) {
            "Perfetto trace integrity failures: ${traceFailures.joinToString { "${it["name"]}=${it["value"]}" }}"
        }
        metadata["trace_integrity"] = mapOf("errors_or_data_loss" to 0)
        metadata["startup"] =
            mapOf(
                "id" to startup.id,
                "ts" to startup.start,
                "ts_end" to startup.end,
                "duration_ns" to startup.duration,
                "type" to startup.type,
            )

        val pid = (metadata["pid"] as Number).toLong()
        val pageSize = (metadata["page_size"] as Number).toLong()
        val snapshot = parseMaps(Files.readString(output.resolve("maps.txt")))
        val mappingEvents = parseMappingEvents(output.resolve("mapping_events.csv"), pid)
        val inodes = parseInodes(output, snapshot)
        val artifacts = readArtifacts(output)
        val archiveEntries =
            artifacts
                .filterValues { it.extension.lowercase() in setOf("apk", "jar", "zip") }
                .mapValues { (_, local) -> ZipLayout.read(local) }
        val vdexAnalyses = analyzeVdex(output, artifacts, metadata)
        val sectionEntries =
            archiveEntries +
                vdexAnalyses
                    .filterValues { it.identitiesVerified }
                    .mapValues { (_, analysis) -> analysis.dexRanges }
        writeVdexBoundaries(output, vdexAnalyses, pageSize)

        val threadNames =
            query(
                trace,
                """
                SELECT thread.tid, COALESCE(thread.name, '') AS thread_name
                FROM thread JOIN process USING (upid)
                WHERE process.pid = $pid
                GROUP BY thread.tid ORDER BY thread.tid;
                """.trimIndent(),
            ).associate { it.getValue("tid").toLong() to it["thread_name"].orEmpty() }

        val allFaults =
            writeFaults(
                output,
                metadata,
                startup,
                snapshot,
                mappingEvents,
                sectionEntries,
                threadNames,
            )
        val callchainResults =
            writeCallchains(
                output,
                pid,
                metadata["abi"].toString(),
                allFaults,
                snapshot,
                mappingEvents,
            )
        val pageCache =
            writePageCache(
                output,
                trace,
                pid,
                startup,
                pageSize,
                inodes.paths,
                inodes.appKeys,
                sectionEntries,
            )
        writeFileSizes(output, inodes.sizes, sectionEntries)
        val fileBacked = allFaults.filter { it["mapping_kind"] == "file" }
        metadata["callchain_results"] = callchainResults
        metadata["faults"] =
            mapOf(
                "total" to allFaults.size,
                "major" to allFaults.count { it["is_major"] == true },
                "minor" to allFaults.count { it["is_major"] == false },
                "file_backed" to allFaults.count { it["mapping_kind"] == "file" },
            )
        metadata["page_cache_events"] = pageCache.size
        metadata["results"] =
            mapOf(
                "all_faults" to allFaults.size,
                "file_backed_faults" to fileBacked.size,
                "major_file_backed_faults" to fileBacked.count { it["is_major"] == true },
                "minor_file_backed_faults" to fileBacked.count { it["is_major"] == false },
                "page_cache_insertions" to pageCache.size,
            ) + callchainResults
        metadata["processing_engine"] = "kotlin"
        metadata["processing_status"] = "complete"
        Json.write(metadataPath, metadata)
    }

    private fun validateIntegrity(metadata: Map<String, Any?>) {
        require(metadata["capture_status"] == "collected") {
            "Capture is incomplete: capture_status=${metadata["capture_status"]}"
        }
        val keys = listOf("lost", "integrity_errors", "throttled", "callchain_overflow")
        val values = keys.associateWith { (metadata["collector_$it"] as? Number)?.toLong() ?: 0 }
        require(((metadata["collector_return_code"] as? Number)?.toInt() ?: 0) == 0 && values.values.all { it == 0L }) {
            "Collector integrity failure: $values"
        }
    }

    private fun queryStartup(
        trace: Path,
        packageName: String,
    ): Startup {
        val escaped = packageName.replace("'", "''")
        val rows =
            query(
                trace,
                """
                INCLUDE PERFETTO MODULE android.startup.startups;
                SELECT startup_id, ts, ts_end, dur, package, startup_type
                FROM android_startups WHERE package = '$escaped' ORDER BY ts;
                """.trimIndent(),
            )
        require(rows.size == 1) {
            "Expected exactly one startup for $packageName, found ${rows.size}"
        }
        val row = rows.single()
        return Startup(
            id = row.getValue("startup_id").toLong(),
            start = row.getValue("ts").toLong(),
            end = row.getValue("ts_end").toLong(),
            duration = row.getValue("dur").toLong(),
            type = row["startup_type"]?.takeUnless { it.isBlank() || it == "[NULL]" },
        )
    }

    private fun query(
        trace: Path,
        sql: String,
    ): List<Map<String, String>> {
        val processor = engineRoot.resolve("android/trace_processor")
        val result = Processes.run(listOf(processor.toString(), "-Q", sql, trace.toString()))
        val temporary = Files.createTempFile("mperf-trace-query-", ".csv")
        try {
            Files.writeString(temporary, result.stdout.trimStart())
            return Csv.read(temporary)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun parseMaps(text: String): List<MapEntry> =
        text
            .lineSequence()
            .filter { it.isNotBlank() }
            .map { line ->
                val match =
                    Regex(
                        "^([0-9a-fA-F]+)-([0-9a-fA-F]+)\\s+(\\S+)\\s+([0-9a-fA-F]+)\\s+" +
                            "([0-9a-fA-F]+):([0-9a-fA-F]+)\\s+(\\d+)(?:\\s+(.*))?$",
                    ).matchEntire(line) ?: error("Malformed maps line: $line")
                MapEntry(
                    begin = match.groupValues[1].toLong(16),
                    end = match.groupValues[2].toLong(16),
                    permissions = match.groupValues[3],
                    fileOffset = match.groupValues[4].toLong(16),
                    device = linuxDevice(match.groupValues[5].toLong(16), match.groupValues[6].toLong(16)),
                    inode = match.groupValues[7].toLong(),
                    fileName = match.groupValues[8].ifBlank { null },
                )
            }.sortedBy { it.begin }
            .toList()

    private fun parseMappingEvents(
        path: Path,
        pid: Long,
    ): List<MappingEvent> =
        Csv
            .read(path)
            .asSequence()
            .filter { it.getValue("pid").toLong() == pid }
            .map { row ->
                val protection = row.getValue("protection").toInt()
                val flags = row.getValue("flags").toInt()
                val begin = row.hex("address")
                MappingEvent(
                    timestamp = row.getValue("timestamp_ns").toLong(),
                    mapping =
                        MapEntry(
                            begin = begin,
                            end = begin + row.hex("length"),
                            permissions =
                                buildString {
                                    append(if (protection and 1 != 0) 'r' else '-')
                                    append(if (protection and 2 != 0) 'w' else '-')
                                    append(if (protection and 4 != 0) 'x' else '-')
                                    append(if (flags and 1 != 0) 's' else 'p')
                                },
                            fileOffset = row.hex("file_offset"),
                            device =
                                linuxDevice(
                                    row.getValue("device_major").toLong(),
                                    row.getValue("device_minor").toLong(),
                                ),
                            inode = row.getValue("inode").toLong(),
                            fileName = row["file_name"]?.ifBlank { null },
                        ),
                )
            }.sortedBy { it.timestamp }
            .toList()

    private fun findMapping(
        snapshot: List<MapEntry>,
        events: List<MappingEvent>,
        address: Long,
        timestamp: Long,
    ): MapEntry? {
        val relevant = events.filter { address >= it.mapping.begin && address < it.mapping.end }
        relevant.asReversed().firstOrNull { it.timestamp <= timestamp }?.let { return it.mapping }
        if (relevant.isNotEmpty()) return null
        var low = 0
        var high = snapshot.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (snapshot[middle].begin <= address) low = middle + 1 else high = middle
        }
        return snapshot.getOrNull(low - 1)?.takeIf { address < it.end }
    }

    private data class Inodes(
        val paths: Map<Pair<Long, Long>, String>,
        val sizes: Map<String, Long>,
        val appKeys: Set<Pair<Long, Long>>,
    )

    private fun parseInodes(
        output: Path,
        snapshot: List<MapEntry>,
    ): Inodes {
        val paths = mutableMapOf<Pair<Long, Long>, String>()
        val sizes = mutableMapOf<String, Long>()
        val appKeys = mutableSetOf<Pair<Long, Long>>()
        snapshot.forEach { entry ->
            val file = entry.fileName
            if (entry.inode != 0L && file != null && !file.startsWith("[")) {
                paths[entry.device to entry.inode] = file
                sizes[file] = maxOf(sizes[file] ?: 0, entry.fileOffset + entry.end - entry.begin)
            }
        }
        val path = output.resolve("inodes.txt")
        if (Files.exists(path)) {
            Files.readAllLines(path).forEach { line ->
                val columns = line.split("|", limit = 4)
                if (columns.size == 4) {
                    val key = columns[0].toLong() to columns[1].toLong()
                    paths.putIfAbsent(key, columns[3])
                    sizes[columns[3]] = columns[2].toLong()
                    appKeys += key
                }
            }
        }
        return Inodes(paths, sizes, appKeys)
    }

    private fun readArtifacts(output: Path): Map<String, Path> {
        val path = output.resolve("artifacts.json")
        if (!Files.exists(path)) return emptyMap()
        val values =
            Files.newBufferedReader(path).use { reader ->
                Json.mapper.readValue(reader, object : TypeReference<Map<String, String>>() {})
            }
        return values.mapValues { (_, local) -> output.resolve(local) }
    }

    private fun analyzeVdex(
        output: Path,
        artifacts: Map<String, Path>,
        metadata: MutableMap<String, Any?>,
    ): Map<String, VdexAnalysis> {
        val analyses =
            artifacts
                .filter { (remote, _) -> remote.endsWith(".vdex") }
                .mapNotNull { (remote, local) ->
                    val apk = matchingApkForVdex(remote, artifacts.keys)?.let(artifacts::get)
                    val identities = apk?.let(ZipLayout::dexIdentities)
                    Vdex.read(local, identities)?.let { remote to it }
                }.toMap()
        metadata["vdex_files"] =
            analyses.map { (file, analysis) ->
                mapOf(
                    "file_name" to file,
                    "format_version" to analysis.version,
                    "stored_dex_checksums" to analysis.checksums.map { "0x%08x".format(it) },
                    "embedded_dex_files" to analysis.dexRanges.size,
                    "dex_identities_verified" to analysis.identitiesVerified,
                )
            }
        return analyses
    }

    private fun writeVdexBoundaries(
        output: Path,
        analyses: Map<String, VdexAnalysis>,
        pageSize: Long,
    ) {
        val fields =
            listOf(
                "file_name",
                "dex_name",
                "start_offset",
                "page_index",
                "checksum",
                "format_version",
                "identity_verified",
            )
        val rows =
            analyses.flatMap { (file, analysis) ->
                if (!analysis.identitiesVerified) return@flatMap emptyList()
                analysis.dexRanges.mapIndexed { index, range ->
                    mapOf(
                        "file_name" to file,
                        "dex_name" to range.name,
                        "start_offset" to range.dataOffset,
                        "page_index" to range.dataOffset / pageSize,
                        "checksum" to "0x%08x".format(analysis.checksums[index]),
                        "format_version" to analysis.version,
                        "identity_verified" to true,
                    )
                }
            }
        Csv.write(output.resolve("vdex_dex_boundaries.csv"), fields, rows)
    }

    private fun writeFaults(
        output: Path,
        metadata: Map<String, Any?>,
        startup: Startup,
        snapshot: List<MapEntry>,
        mappingEvents: List<MappingEvent>,
        archives: Map<String, List<ArchiveEntry>>,
        threads: Map<Long, String>,
    ): List<Map<String, Any?>> {
        val pid = (metadata["pid"] as Number).toLong()
        val pageSize = (metadata["page_size"] as Number).toLong()
        val rows =
            Csv
                .read(output.resolve("fault_events.csv"))
                .filter {
                    it.getValue("pid").toLong() == pid &&
                        it.getValue("timestamp_ns").toLong() in startup.start until startup.end
                }
        val faults =
            rows.mapIndexed { sequence, row ->
                val timestamp = row.getValue("timestamp_ns").toLong()
                val tid = row.getValue("tid").toLong()
                val address = row.hex("address")
                val mapping = findMapping(snapshot, mappingEvents, address, timestamp)
                var mappingKind = "unmapped"
                var fileName: String? = null
                var fileOffset: Long? = null
                var entry: ArchiveEntry? = null
                if (mapping != null) {
                    val mappedName = mapping.fileName
                    if (
                        mapping.inode != 0L &&
                        mappedName != null &&
                        !mappedName.startsWith("[") &&
                        !mappedName.startsWith("/dev/") &&
                        !mappedName.startsWith("/memfd:")
                    ) {
                        mappingKind = "file"
                        fileName = mappedName.removeSuffix(" (deleted)")
                        fileOffset = mapping.fileOffset + address - mapping.begin
                        entry = archives[fileName]?.find { fileOffset >= it.dataOffset && fileOffset < it.dataEnd }
                    } else {
                        mappingKind = "anonymous"
                        fileName = mappedName
                    }
                }
                mapOf(
                    "ts" to timestamp,
                    "process_name" to metadata["package"],
                    "thread_name" to threads[tid].orEmpty(),
                    "file_name" to fileName,
                    "zip_entry_name" to entry?.name,
                    "offset" to fileOffset,
                    "is_major" to (row["event_type"] == "major"),
                    "event_type" to row["event_type"],
                    "elapsed_ms" to (timestamp - startup.start) / 1_000_000.0,
                    "sequence" to sequence,
                    "tid" to tid,
                    "address" to address,
                    "ip" to row.hexUnsigned("ip"),
                    "mapping_kind" to mappingKind,
                    "page_index" to fileOffset?.div(pageSize),
                    "zip_entry_offset" to entry?.let { fileOffset?.minus(it.dataOffset) },
                    "section_page" to entry?.let { fileOffset?.minus(it.dataOffset)?.div(pageSize) },
                    "category" to classify(fileName, entry?.name),
                )
            }
        val fields =
            listOf(
                "ts",
                "process_name",
                "thread_name",
                "file_name",
                "zip_entry_name",
                "offset",
                "is_major",
                "event_type",
                "elapsed_ms",
                "sequence",
                "tid",
                "address",
                "ip",
                "mapping_kind",
                "page_index",
                "zip_entry_offset",
                "section_page",
                "category",
            )
        Csv.write(output.resolve("all_faults.csv"), fields, faults)
        Csv.write(output.resolve("mapped_faults.csv"), fields, faults.filter { it["mapping_kind"] == "file" })
        Csv.write(
            output.resolve("faults.csv"),
            listOf("ts", "process_name", "thread_name", "address", "ip", "event_type", "tid", "is_major"),
            faults,
        )
        return faults
    }

    private fun writeCallchains(
        output: Path,
        pid: Long,
        abi: String,
        faults: List<Map<String, Any?>>,
        snapshot: List<MapEntry>,
        mappingEvents: List<MappingEvent>,
    ): Map<String, Int> {
        val path = output.resolve("fault_callchains.csv")
        val resolvedPath = output.resolve("resolved_fault_callchains.csv")
        if (!Files.exists(path)) {
            Files.deleteIfExists(resolvedPath)
            return mapOf(
                "faults_with_callchains" to 0,
                "callchain_frames" to 0,
                "resolved_user_frames" to 0,
                "unresolved_user_frames" to 0,
            )
        }

        data class RawKey(
            val pid: Long,
            val timestamp: Long,
            val tid: Long,
            val address: Long,
            val type: String,
        )
        val rows = Csv.read(path).filter { it.getValue("pid").toLong() == pid }
        val grouped = rows.groupBy { it.getValue("fault_index").toLong() }
        val chains =
            grouped.values
                .map { chain ->
                    val first = chain.first()
                    RawKey(
                        pid,
                        first.getValue("timestamp_ns").toLong(),
                        first.getValue("tid").toLong(),
                        first.hex("address"),
                        first.getValue("event_type"),
                    ) to chain.map { it.hexUnsigned("ip") }.filter { it != 0UL }
                }.groupBy({ it.first }, { it.second })
                .mapValues { (_, values) -> ArrayDeque(values) }
        val contextNames =
            mapOf(
                0xffffffffffffffe0UL to "hypervisor",
                0xffffffffffffff80UL to "kernel",
                0xfffffffffffffe00UL to "user",
                0xfffffffffffff800UL to "guest",
                0xfffffffffffff780UL to "guest_kernel",
                0xfffffffffffff600UL to "guest_user",
            )
        var resolved = 0
        var unresolved = 0
        var withChains = 0
        val outputRows = mutableListOf<Map<String, Any?>>()
        faults.forEach { fault ->
            val key =
                RawKey(
                    pid,
                    fault["ts"] as Long,
                    fault["tid"] as Long,
                    fault["address"] as Long,
                    fault["event_type"].toString(),
                )
            val chain = chains[key]?.removeFirstOrNull() ?: error("Missing exact native callchain for fault ${fault["sequence"]}")
            withChains++
            var context = "unknown"
            var contextFrame = 0
            chain.forEachIndexed { frameIndex, rawIp ->
                val signed = rawIp
                contextNames[signed]?.let {
                    context = it
                    contextFrame = 0
                    return@forEachIndexed
                }
                val adjustment =
                    if (contextFrame++ == 0) {
                        0
                    } else if (abi in setOf("arm64-v8a", "armeabi-v7a")) {
                        2
                    } else {
                        1
                    }
                val normalizedIp =
                    if (abi == "arm64-v8a" && context == "user") {
                        rawIp and 0x00ffffffffffffffUL
                    } else {
                        rawIp
                    }
                val ip = normalizedIp - minOf(normalizedIp, adjustment.toULong())
                var mapping: MapEntry? = null
                var fileName: String? = null
                var fileOffset: Long? = null
                if (context == "user") {
                    mapping =
                        ip
                            .takeIf { it <= Long.MAX_VALUE.toULong() }
                            ?.let {
                                findMapping(
                                    snapshot,
                                    mappingEvents,
                                    it.toLong(),
                                    fault["ts"] as Long,
                                )
                            }
                    fileName = mapping?.fileName?.removeSuffix(" (deleted)")
                    if (fileName != null) {
                        fileOffset = mapping!!.fileOffset + ip.toLong() - mapping.begin
                        resolved++
                    } else {
                        unresolved++
                    }
                }
                val label =
                    when {
                        fileName != null && fileOffset != null -> "${Path.of(fileName).fileName}+0x${fileOffset.toString(16)}"
                        context == "kernel" -> "[kernel]+0x${ip.toString(16)}"
                        else -> "0x${ip.toString(16)}"
                    }
                outputRows +=
                    mapOf(
                        "sequence" to fault["sequence"],
                        "ts" to fault["ts"],
                        "elapsed_ms" to fault["elapsed_ms"],
                        "event_type" to fault["event_type"],
                        "is_major" to fault["is_major"],
                        "tid" to fault["tid"],
                        "fault_address" to fault["address"],
                        "fault_file_name" to fault["file_name"],
                        "fault_offset" to fault["offset"],
                        "frame_index" to frameIndex,
                        "frame_kind" to context,
                        "raw_ip" to rawIp,
                        "ip" to ip,
                        "file_name" to fileName,
                        "file_offset" to fileOffset,
                        "label" to label,
                    )
            }
        }
        val fields =
            listOf(
                "sequence",
                "ts",
                "elapsed_ms",
                "event_type",
                "is_major",
                "tid",
                "fault_address",
                "fault_file_name",
                "fault_offset",
                "frame_index",
                "frame_kind",
                "raw_ip",
                "ip",
                "file_name",
                "file_offset",
                "label",
            )
        Csv.write(resolvedPath, fields, outputRows)
        return mapOf(
            "faults_with_callchains" to withChains,
            "callchain_frames" to outputRows.size,
            "resolved_user_frames" to resolved,
            "unresolved_user_frames" to unresolved,
        )
    }

    private fun writePageCache(
        output: Path,
        trace: Path,
        pid: Long,
        startup: Startup,
        pageSize: Long,
        inodePaths: Map<Pair<Long, Long>, String>,
        appKeys: Set<Pair<Long, Long>>,
        archives: Map<String, List<ArchiveEntry>>,
    ): List<Map<String, Any?>> {
        val values =
            appKeys
                .sortedWith(compareBy<Pair<Long, Long>> { it.first }.thenBy { it.second })
                .joinToString(",\n") { "(${it.first}, ${it.second})" }
                .ifBlank { "(-1, -1)" }
        val rows =
            query(
                trace,
                """
                WITH app_inodes(sdev,inode) AS (VALUES $values),
                cache_events AS (
                  SELECT ftrace_event.ts,ftrace_event.utid,
                    EXTRACT_ARG(ftrace_event.arg_set_id,'s_dev') AS sdev,
                    EXTRACT_ARG(ftrace_event.arg_set_id,'i_ino') AS inode,
                    EXTRACT_ARG(ftrace_event.arg_set_id,'index') AS page_index,
                    COALESCE(EXTRACT_ARG(ftrace_event.arg_set_id,'order'),0) AS page_order
                  FROM ftrace_event
                  WHERE ftrace_event.name='mm_filemap_add_to_page_cache'
                    AND ftrace_event.ts >= ${startup.start} AND ftrace_event.ts < ${startup.end}
                )
                SELECT cache_events.ts,COALESCE(process.name,'[kernel worker]') AS process_name,
                  CASE WHEN thread.name IS NOT NULL THEN thread.name
                       WHEN process.pid IS NULL THEN '[kernel worker]' ELSE '[unnamed thread]' END AS thread_name,
                  COALESCE(thread.tid,0) AS tid,cache_events.sdev,cache_events.inode,
                  cache_events.page_index,cache_events.page_order
                FROM cache_events
                LEFT JOIN thread USING(utid) LEFT JOIN process USING(upid)
                LEFT JOIN app_inodes ON app_inodes.sdev=cache_events.sdev AND app_inodes.inode=cache_events.inode
                WHERE process.pid=$pid OR app_inodes.inode IS NOT NULL ORDER BY cache_events.ts;
                """.trimIndent(),
            )
        val events =
            rows.map { row ->
                val key = row.getValue("sdev").toLong() to row.getValue("inode").toLong()
                val file = inodePaths[key]
                val page = row.getValue("page_index").toLong()
                val offset = page * pageSize
                val entry = file?.let { archives[it]?.find { candidate -> offset in candidate.dataOffset until candidate.dataEnd } }
                val order = row.getValue("page_order").toInt()
                mapOf(
                    "ts" to row.getValue("ts").toLong(),
                    "elapsed_ms" to (row.getValue("ts").toLong() - startup.start) / 1_000_000.0,
                    "process_name" to row["process_name"],
                    "thread_name" to row["thread_name"],
                    "tid" to row.getValue("tid").toLong(),
                    "device" to key.first,
                    "inode" to key.second,
                    "file_name" to file,
                    "zip_entry_name" to entry?.name,
                    "offset" to offset,
                    "page_index" to page,
                    "page_order" to order,
                    "page_count" to (1L shl order),
                    "category" to classify(file, entry?.name),
                )
            }
        val fields =
            listOf(
                "ts",
                "elapsed_ms",
                "process_name",
                "thread_name",
                "tid",
                "device",
                "inode",
                "file_name",
                "zip_entry_name",
                "offset",
                "page_index",
                "page_order",
                "page_count",
                "category",
            )
        Csv.write(output.resolve("page_cache_events.csv"), fields, events)
        return events
    }

    private fun writeFileSizes(
        output: Path,
        sizes: Map<String, Long>,
        archives: Map<String, List<ArchiveEntry>>,
    ) {
        val fields =
            listOf("file_name", "zip_entry_name", "size", "file_offset", "uncompressed_size", "data_end", "compression")
        val rows =
            sizes.toSortedMap().flatMap { (file, size) ->
                listOf(
                    mapOf(
                        "file_name" to file,
                        "zip_entry_name" to null,
                        "size" to size,
                        "file_offset" to 0,
                        "uncompressed_size" to size,
                        "data_end" to size,
                        "compression" to "file",
                    ),
                ) +
                    archives[file].orEmpty().map { entry ->
                        mapOf(
                            "file_name" to file,
                            "zip_entry_name" to entry.name,
                            "size" to entry.compressedSize,
                            "file_offset" to entry.dataOffset,
                            "uncompressed_size" to entry.uncompressedSize,
                            "data_end" to entry.dataEnd,
                            "compression" to entry.compression,
                        )
                    }
            }
        Csv.write(output.resolve("file_sizes.csv"), fields, rows)
    }

    private fun classify(
        file: String?,
        entry: String?,
    ): String {
        val target = (entry ?: file).orEmpty().lowercase()
        val images = listOf(".avif", ".gif", ".heic", ".jpeg", ".jpg", ".png", ".svg", ".webp")
        if (entry != null) {
            return when {
                images.any(target::endsWith) -> "image"
                Regex("(?:^|[/ ·])classes\\d*\\.dex(?:\\s|$)").containsMatchIn(target) ||
                    "compactdex data" in target -> "dex"
                target.endsWith(".so") -> "native_code"
                target == "resources.arsc" || target.startsWith("res/") -> "resources"
                target.startsWith("assets/") -> "asset"
                target.startsWith("meta-inf/") -> "metadata"
                else -> "apk_other"
            }
        }
        return when {
            target.endsWith(".odex") || target.endsWith(".oat") -> "compiled_code"
            target.endsWith(".art") -> "art_image"
            target.endsWith(".vdex") || target.endsWith(".dex") -> "dex"
            target.endsWith(".so") -> "native_code"
            images.any(target::endsWith) -> "image"
            target.endsWith(".apk") -> "apk_container"
            file == null || file.startsWith("[") -> "anonymous"
            file.startsWith("/system/") ||
                file.startsWith("/apex/") ||
                file.startsWith("/vendor/") ||
                file.startsWith("/product/") -> "system"
            else -> "other_file"
        }
    }

    private fun linuxDevice(
        major: Long,
        minor: Long,
    ): Long = (minor and 0xff) or (major shl 8) or ((minor and 0xff.inv().toLong()) shl 12)

    private fun Map<String, String>.hex(key: String): Long = getValue(key).removePrefix("0x").toULong(16).toLong()

    private fun Map<String, String>.hexUnsigned(key: String): ULong = getValue(key).removePrefix("0x").toULong(16)
}
