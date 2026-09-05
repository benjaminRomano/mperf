package com.bromano.mobile.perf.faults

import java.nio.file.Files
import java.nio.file.Path

/** Normalize Instruments evidence for the same offline explorer used by Android. */
internal class IosFaultReport(
    private val engineRoot: Path,
) {
    fun build(
        capture: Path,
        output: Path,
    ) {
        val run = reportRun(capture)
        val metadata = run.getValue("provenance") as Map<*, *>
        val title = metadata["app_binary_name"] ?: metadata["bundle_id"] ?: "iOS"
        SharedFaultReport(engineRoot).write(listOf(run), output, "$title · startup faults")
    }

    internal fun reportRun(capture: Path): Map<String, Any?> {
        val metadata = Json.readMap(capture.resolve("capture_metadata.json"))
        val stats = Json.readMap(capture.resolve("page_fault_stats.json"))
        require((metadata["schema_version"] as? Number)?.toInt() == 1) { "Unsupported iOS capture schema" }
        val rows = Csv.read(capture.resolve("page_fault_events.csv"))
        require(rows.size == (stats["event_count"] as? Number)?.toInt()) { "Fault CSV count differs from statistics" }
        val pageSize = (stats["page_size_bytes"] as? Number)?.toLong() ?: 0
        require(pageSize > 0 && pageSize and (pageSize - 1) == 0L) { "Capture has no valid page size" }
        val root = metadata["app_bundle_root"]?.toString().orEmpty().removeSuffix("/")
        val sources = linkedMapOf<String, MutableMap<String, Any?>>()
        val events =
            rows
                .map { row ->
                    require(row["fault_class"] in setOf("Major", "Minor")) { "Unsupported fault classification" }
                    val source = row["read_file"].orEmpty().ifBlank { "Unattributed memory" }
                    val offset = row["read_file_offset"]?.takeIf(String::isNotBlank)?.toLong()
                    require(offset == null || offset >= 0) { "Negative file offset" }
                    val definition =
                        sources.getOrPut(source) {
                            mutableMapOf(
                                "path" to source,
                                "label" to if (root.isBlank()) source else source.removePrefix("$root/"),
                                "boundaries" to emptyList<Any>(),
                                "mapped" to false,
                                "app" to row.truth("read_file_is_bundle_owned"),
                            )
                        }
                    if (offset != null) definition["mapped"] = true
                    val stack =
                        row["stack_frames_json"]?.takeIf(String::isNotBlank)?.let {
                            Json.mapper.readValue(it, List::class.java)
                        } ?: row["stack"].orEmpty().split(" ← ").filter(String::isNotBlank).map {
                            mapOf("label" to it, "file" to "", "kind" to "user", "app" to false, "unresolved" to true)
                        }
                    val address = row.getValue("address_hex")
                    val numericAddress = address.removePrefix("0x").toULong(16)
                    val time = row.getValue("time_since_first_fault_ms").toDouble()
                    require(time.isFinite() && time >= 0) { "Invalid fault timestamp" }
                    mapOf(
                        "id" to row.getValue("event_index").toInt(),
                        "time" to time,
                        "major" to (row["fault_class"] == "Major"),
                        "address" to address,
                        "addressPlot" to (numericAddress != ULong.MAX_VALUE),
                        "source" to source,
                        "offset" to offset,
                        "page" to offset?.div(pageSize),
                        "thread" to "${row["thread"].orEmpty().ifBlank { "unnamed" }} (${row["tid"].orEmpty().ifBlank { "?" }})",
                        "stack" to stack,
                        "detail" to
                            mapOf(
                                "Address note" to
                                    if (numericAddress == ULong.MAX_VALUE) {
                                        "All-ones Instruments address; retained in counts and stacks, excluded from address plots"
                                    } else {
                                        ""
                                    },
                                "section" to row["read_section"].orEmpty(),
                                "Code layout evidence" to
                                    if (orderingCandidate(row)) {
                                        "Candidate to investigate: app code read by an app instruction"
                                    } else {
                                        "No candidate identified in this event"
                                    },
                                "VM operation" to row["operation"],
                                "Faulting instruction" to row["faulting_instruction"],
                                "Faulting binary (not read source)" to row["faulting_binary_path"],
                                "VM operation duration (µs)" to row.getValue("duration_ns").toLong() / 1000.0,
                            ),
                    )
                }.sortedWith(compareBy<Map<String, Any?>> { it["time"] as Double }.thenBy { it["id"] as Int })
        require(events.map { it["id"] }.distinct().size == events.size) { "Duplicate fault identifiers" }
        val boundariesPath = capture.resolve("binary_sections.json")
        if (Files.isRegularFile(boundariesPath)) {
            Json.readMap(boundariesPath).forEach { (path, sections) ->
                sources[path]?.set(
                    "boundaries",
                    (sections as? List<*>)
                        ?.mapNotNull { section ->
                            val s = section as? Map<*, *> ?: return@mapNotNull null
                            val offset = s["offset"] as? Number ?: return@mapNotNull null
                            mapOf("page" to offset.toDouble() / pageSize, "label" to s["name"], "kind" to "section")
                        }.orEmpty(),
                )
            }
        }
        val cache = metadata["cache"] as? Map<*, *> ?: emptyMap<Any, Any>()
        val residency = cache["residency_immediately_before_launch"] as? Map<*, *>
        var cacheText =
            if (cache["procedure"] == null || cache["procedure"] == "none") {
                "Cache: not prepared; no cold-cache claim."
            } else {
                "Cache preparation: ${cache["procedure"]} · ${cache["confidence"] ?: "unverified"}."
            }
        if (residency != null) {
            cacheText += " Pre-launch residency: ${residency["resident_pages"] ?: "?"} pages across ${residency["files"] ?: "?"} app files."
        }
        val published = metadata["published_output"]?.toString()?.let { Path.of(it).fileName.toString() }
        return mapOf(
            "label" to (published ?: capture.fileName.toString()),
            "subtitle" to
                "${metadata["target_kind"] ?: "iOS"} · ${metadata["target_name"] ?: ""} · PID ${metadata["target_pid"]} · ${stats["analysis_window_ms"] ?: stats["capture_span_ms"]} ms analyzed",
            "pageSize" to pageSize,
            "events" to events,
            "sources" to sources,
            "cache" to cacheText,
            "notes" to listOf(
                "Major denotes Instruments file-backed page-in operations; minor groups the other supported VM faults. These are analysis categories, not Darwin process counters.",
                "Simulator events describe the macOS host, not physical iPhone storage. A stock physical device has no supported global page-cache flush.",
                "Read-file and Mach-O attribution requires the fault address, recorded image load address, and UUID-matched local binary. Only images in that event’s stack are considered; other memory remains unattributed.",
                "__TEXT,__text contains instructions. __DATA and __DATA_CONST contain data; other __TEXT sections can contain constants or metadata. Zero-fill sections have no file offset.",
                "Layout candidates require an app-owned faulting binary and an app-owned instruction-bearing read section. An app caller alone is not evidence for code ordering.",
                "Stack columns count faults, not time. Summed VM operation durations are not startup wall time.",
            ) + (metadata["capture_quality_warnings"] as? List<*>).orEmpty(),
            "provenance" to metadata,
        )
    }

    private fun Map<String, String>.truth(key: String): Boolean = this[key].equals("true", ignoreCase = true)

    private fun orderingCandidate(row: Map<String, String>): Boolean {
        val frame = row["faulting_frame"].orEmpty()
        return row["fault_class"] == "Major" &&
            listOf("faulting_binary_is_bundle_owned", "read_file_is_bundle_owned", "read_section_is_code").all { row.truth(it) } &&
            frame.isNotBlank() &&
            frame !in setOf("_start", "start", "main", "NSExtensionMain", "UIApplicationMain") &&
            listOf("0x", "__llvm_profile_", "<deduplicated", "<redacted", "<unknown").none(frame::startsWith)
    }
}
