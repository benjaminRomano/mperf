package com.bromano.mobile.perf.faults

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

internal fun stableAndroidSourceLabel(
    path: String,
    packageName: String,
): String {
    val components = path.split('/').filter(String::isNotBlank)
    val packageIndex =
        components.indexOfFirst { component ->
            component == packageName || component.startsWith("$packageName-")
        }
    return components
        .takeIf { packageIndex >= 0 }
        ?.drop(packageIndex + 1)
        ?.joinToString("/")
        ?.ifBlank { Path.of(path).name }
        ?: path
}

internal class AndroidFaultReport(
    private val engineRoot: Path,
) {
    fun build(
        capture: Path,
        output: Path,
        label: String,
        comparison: Path? = null,
        comparisonLabel: String = "Comparison",
        allowIncomparable: Boolean = false,
    ) {
        val first = reportRun(capture, label)
        val runs = mutableListOf(first)
        comparison?.let { other ->
            val second = reportRun(other, comparisonLabel)
            val a = first.getValue("provenance") as Map<*, *>
            val b = second.getValue("provenance") as Map<*, *>
            require(a["package"] == b["package"] && a["page_size"] == b["page_size"]) {
                "Compare captures of the same package and page size"
            }
            val changed =
                listOf(
                    "activity",
                    "serial",
                    "build_fingerprint",
                    "abi",
                    "kernel",
                    "collector_source_sha256",
                    "collector_binary_sha256",
                    "trace_config_sha256",
                    "ndk",
                    "compiler",
                    "cache_procedure",
                    "cache_max_resident_pages",
                    "reboot_before_collect",
                    "capture_native_callchains",
                    "capture_status",
                    "processing_status",
                    "simpleperf_status",
                    "reclaim_mapped_apks",
                ).filter { a[it] == null || b[it] == null || a[it] != b[it] }
            require(changed.isEmpty() || allowIncomparable) { "Comparison settings differ: ${changed.joinToString()}" }
            for (run in listOf(first, second)) {
                @Suppress("UNCHECKED_CAST")
                (run["notes"] as MutableList<String>).add("Comparison settings differ: ${changed.joinToString().ifBlank { "none" }}.")
            }
            runs.add(second)
        }
        val exactCount = runs.size
        for ((index, path) in listOfNotNull(capture, comparison).withIndex()) {
            @Suppress("UNCHECKED_CAST")
            val metadata = runs[index].getValue("provenance") as Map<String, Any?>

            @Suppress("UNCHECKED_CAST")
            val notes = runs[index].getValue("notes") as MutableList<String>
            AndroidDwarf.reportRun(path, metadata, notes)?.let { runs.add(it.toMutableMap()) }
        }
        check(exactCount >= 1)
        SharedFaultReport(engineRoot).write(runs, output, (first["provenance"] as Map<*, *>)["package"].toString() + " · startup faults")
    }

    internal fun reportRun(
        path: Path,
        label: String,
    ): MutableMap<String, Any?> {
        val metadata = Json.readMap(path.resolve("capture_metadata.json"))
        require((metadata["schema_version"] as? Number)?.toInt() == 5 && metadata["capture_status"] == "collected") {
            "Report requires a completed exact capture (schema 5)"
        }
        require(metadata["processing_status"] == "complete") { "Android capture processing is incomplete" }
        for (name in listOf("lost", "integrity_errors", "throttled", "callchain_overflow", "return_code")) {
            require((metadata["collector_$name"] as? Number)?.toLong() == 0L) { "Missing or failed collector integrity check: $name" }
        }
        val pageSize = (metadata["page_size"] as Number).toLong()
        require(pageSize > 0 && (pageSize and (pageSize - 1)) == 0L) { "Invalid capture page size" }
        val packageName = metadata["package"].toString()
        require(Files.isRegularFile(path.resolve("all_faults.csv"))) { "Processed all_faults.csv is missing" }
        val raw = Csv.read(path.resolve("all_faults.csv"))
        val results = metadata["results"] as? Map<*, *>
        require((results?.get("all_faults") as? Number)?.toInt() == raw.size) { "Fault CSV count differs from metadata" }
        val chains =
            if (Files.exists(path.resolve("resolved_fault_callchains.csv"))) {
                Csv.read(path.resolve("resolved_fault_callchains.csv")).groupBy { it.getValue("sequence").toLong() }
            } else {
                emptyMap()
            }
        val details =
            if (Files.exists(path.resolve("fault_details.json"))) {
                @Suppress("UNCHECKED_CAST")
                (Json.mapper.readValue(path.resolve("fault_details.json").toFile(), List::class.java) as List<Map<String, Any?>>)
                    .associateBy { (it["sequence"] as Number).toLong() }
            } else {
                emptyMap()
            }
        val dwarf = AndroidDwarf.exactMatches(path, metadata)
        val sources = linkedMapOf<String, MutableMap<String, Any?>>()
        val events =
            raw
                .map { row ->
                    require(row["event_type"] in listOf("major", "minor")) { "Unknown fault type" }
                    val seq = row.getValue("sequence").toLong()
                    val file = row["file_name"].orEmpty().ifBlank { "Unattributed memory" }
                    val offset = row["offset"]?.toLongOrNull()
                    sources.putIfAbsent(
                        file,
                        mutableMapOf(
                            "path" to file,
                            "label" to stableAndroidSourceLabel(file, packageName),
                            "app" to androidAppOwned(file, packageName),
                            "mapped" to (row["file_name"].orEmpty().isNotBlank() && offset != null),
                            "boundaries" to mutableListOf<Map<String, Any?>>(),
                        ),
                    )
                    val native =
                        chains[seq].orEmpty().sortedBy { it.getValue("frame_index").toInt() }.map { frame ->
                            mapOf<String, Any?>(
                                "label" to frame["label"],
                                "kind" to frame["frame_kind"],
                                "file" to frame["file_name"],
                                "app" to androidAppOwned(frame["file_name"].orEmpty(), packageName),
                                "unresolved" to (frame["file_name"].isNullOrEmpty() || frame["label"].orEmpty().startsWith("0x")),
                            )
                        }
                    val match = dwarf.matches[seq]
                    val stack = if (match != null) native.filter { it["kind"] == "kernel" } + match.stack else native
                    val detail = details[seq].orEmpty()
                    mapOf<String, Any?>(
                        "id" to seq,
                        "time" to row.getValue("elapsed_ms").toDouble(),
                        "major" to (row["event_type"] == "major"),
                        "address" to "0x${row.getValue("address").toULong().toString(16)}",
                        "source" to file,
                        "fileBacked" to (row["mapping_kind"] == "file"),
                        "page" to offset?.div(pageSize),
                        "offset" to offset,
                        "thread" to "${row["thread_name"].orEmpty().ifBlank { "unnamed" }} (${row["tid"]})",
                        "stack" to stack,
                        "detail" to
                            buildMap<String, Any?> {
                                match?.let { put("Stack evidence", Json.mapper.writeValueAsString(it.provenance)) }
                                put("section", detail["section"].orEmptyString())
                                put("dex", detail["dex"]?.toString()?.ifBlank { null } ?: row["zip_entry_name"].orEmpty())
                                put(
                                    "DEX methods on this page (content, not callers)",
                                    (detail["page_methods"] as? List<*>)?.joinToString("; ").orEmpty(),
                                )
                            },
                    )
                }.sortedWith(compareBy<Map<String, Any?>> { it["time"] as Double }.thenBy { it["id"] as Long })
        require(events.map { it["id"] }.distinct().size == events.size) { "Duplicate fault sequence identifiers" }
        val boundaries = path.resolve("vdex_dex_boundaries.csv")
        if (Files.exists(boundaries)) {
            Csv.read(boundaries).forEach { b ->
                if (b["identity_verified"].equals("true", ignoreCase = true)) {
                    @Suppress("UNCHECKED_CAST")
                    (sources[b["file_name"]]?.get("boundaries") as? MutableList<Map<String, Any?>>)?.add(
                        mapOf("page" to b.getValue("start_offset").toDouble() / pageSize, "label" to b["dex_name"], "kind" to "dex"),
                    )
                }
            }
        }
        val artifacts = path.resolve("artifacts.json")
        if (Files.exists(artifacts)) {
            Json.readMap(artifacts).forEach { (remote, local) ->
                if (remote.endsWith(".apk") && remote in sources && local is String) {
                    @Suppress("UNCHECKED_CAST")
                    (sources[remote]?.get("boundaries") as? MutableList<Map<String, Any?>>)?.addAll(
                        storedDexBoundaries(path.resolve(local), pageSize),
                    )
                }
            }
        }
        val cache = metadata["cache_verification"] as? Map<*, *>
        val resident = (cache?.get("resident_pages") as? Number)?.toLong()
        val count = (cache?.get("files_checked") as? Number)?.toInt() ?: 0
        var cacheText =
            if (resident != null && count > 0) {
                "Pre-launch cache: $resident resident pages across $count checked app files."
            } else {
                "Pre-launch cache: not verified in this capture."
            }
        if (resident != null && resident > 0) cacheText += " Partially warm; not a fully cold capture."
        val notes =
            mutableListOf(
                "Major/minor are emitted Linux perf software fault events, not syscalls. Some kernel-accounted faults do not emit userspace perf samples.",
                "Minor faults include anonymous allocation and copy-on-write, not just file-cache hits.",
                "Read sources come from fault addresses and timestamped mappings. Native stacks are captured in the same event; DWARF stacks require a verified exact event match.",
                "DEX method labels describe instructions stored on the faulted page, not proof those methods executed or triggered the fault.",
                "VDEX names require every ART location checksum to match the APK DEX entries. VDEX remains one analytical file.",
                "Page-cache insertions include app threads and workers touching exact app-owned device/inode pairs. They correlate with reads/readahead, not proof of fault causality.",
                "Compare code-layout changes using repeated, equally prepared captures. R8 DEX order and ART-compiled OAT layout differ.",
            )
        notes += (metadata["warnings"] as? List<*>)?.map { it.toString() }.orEmpty()
        notes += dwarf.warnings
        if (dwarf.coverage.isNotEmpty()) {
            metadata["report_dwarf_enrichment"] = dwarf.coverage
            notes +=
                "DWARF stacks matched ${dwarf.coverage["matched_startup_major_faults"]} / ${dwarf.coverage["startup_major_faults"]} major faults using exact raw identities. Unmatched events retain native stacks; no nearest-time matching."
        }
        val startup = metadata["startup"] as? Map<*, *>
        return mutableMapOf(
            "label" to label,
            "subtitle" to
                "Android ${metadata["release"]} · $packageName · PID ${metadata["pid"]} · startup ${(
                    startup?.get(
                        "duration_ns",
                    ) as? Number
                )?.toDouble()?.div(1e6) ?: 0.0} ms",
            "pageSize" to pageSize,
            "fileBackedOnly" to true,
            "events" to events,
            "sources" to sources,
            "cache" to cacheText,
            "notes" to notes,
            "provenance" to metadata,
        )
    }

    private fun Any?.orEmptyString(): String = this?.toString().orEmpty()

    internal fun storedDexBoundaries(
        archive: Path,
        pageSize: Long,
    ): List<Map<String, Any?>> =
        runCatching { ZipLayout.read(archive) }
            .getOrDefault(emptyList())
            .filter {
                it.compression == "stored" &&
                    it.compressedSize == it.uncompressedSize &&
                    Regex("classes(?:\\d+)?\\.dex").matches(it.name)
            }.map { mapOf("page" to it.dataOffset.toDouble() / pageSize, "label" to it.name, "kind" to "dex") }
}
