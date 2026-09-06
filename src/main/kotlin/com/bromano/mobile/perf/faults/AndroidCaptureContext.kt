package com.bromano.mobile.perf.faults

import java.nio.file.Files
import java.nio.file.Path

/** Diagnostic streams share a clock, not a causal or request identity. */
internal object AndroidCaptureContext {
    fun health(
        path: Path,
        metadata: Map<String, Any?>,
        events: List<Map<String, Any?>>,
        dwarf: AndroidDwarf.Matches,
        validatedEvents: Boolean = true,
    ): List<Map<String, Any?>> {
        val result = mutableListOf<Map<String, Any?>>()

        fun row(
            name: String,
            state: String,
            value: String,
            action: String = "",
        ) {
            result += mapOf("name" to name, "state" to state, "value" to value, "action" to action)
        }
        val cache = metadata["cache_verification"] as? Map<*, *>
        val resident = (cache?.get("resident_pages") as? Number)?.toLong()
        val checked = (cache?.get("files_checked") as? Number)?.toLong() ?: 0L
        val beforeLaunch = cache?.get("phase") == "before_launch" && checked > 0 && resident != null && resident >= 0
        val cold = beforeLaunch && resident == 0L
        row(
            "Pre-launch app-file cache",
            if (cold) "pass" else "warning",
            when {
                beforeLaunch -> "$resident resident pages across $checked checked files"
                resident != null ->
                    "$resident resident pages across ${cache?.get("files_checked") ?: "unknown"} checked files " +
                        "(${cache?.get("phase") ?: "unknown phase"}; launch not verified)"
                else -> "Not verified"
            },
            if (cold) {
                "Snapshot before launch; does not flush host or storage-controller caches."
            } else {
                "Recollect with --max-resident-pages 0; stop app processes and inspect cache_residency.csv."
            },
        )
        val integrityFields = listOf("lost", "integrity_errors", "throttled", "callchain_overflow", "return_code")
        val integrityPassed = integrityFields.all { (metadata["collector_$it"] as? Number)?.toLong() == 0L }
        val exactValidated =
            validatedEvents &&
                metadata["capture_status"] == "collected" &&
                metadata["processing_status"] == "complete" &&
                integrityPassed
        row(
            "Exact fault stream",
            if (exactValidated) "pass" else "warning",
            if (exactValidated) {
                "${events.size} startup events; collector loss, throttling and integrity checks passed"
            } else {
                "Not validated; capture ${metadata["capture_status"] ?: "unknown"}; processing ${metadata["processing_status"] ?: "incomplete"}"
            },
            if (exactValidated) {
                ""
            } else {
                integrityFields.joinToString("; ") { "$it=${metadata["collector_$it"] ?: "unknown"}" } +
                    ". Inspect collector logs and capture_metadata.json. No validated fault plots are available."
            },
        )
        val traceFailures = ((metadata["trace_integrity"] as? Map<*, *>)?.get("errors_or_data_loss") as? Number)?.toLong()
        row(
            "Perfetto trace integrity",
            if (traceFailures == 0L) "pass" else "warning",
            traceFailures?.let { "$it errors or data-loss statistics" } ?: "Unknown; trace integrity was not verified",
            "Perfetto trace health is separate from collector and companion integrity. Missing checks do not establish a loss-free trace.",
        )
        val majors = events.filter { it["major"] == true }
        val withStacks =
            majors.count { event ->
                (event["stack"] as? List<*>).orEmpty().any { (it as? Map<*, *>)?.get("kind") != "kernel" }
            }
        row(
            "Major-fault user stacks",
            if (validatedEvents && majors.isNotEmpty() && withStacks == majors.size) {
                "pass"
            } else {
                "warning"
            },
            if (!validatedEvents) {
                "Not evaluated; exact fault processing did not complete"
            } else {
                "$withStacks / ${majors.size} major faults have a user stack"
            },
            "Native frame-pointer and exact-matched DWARF stacks count here. Coverage does not establish unwind completeness.",
        )
        val companionPath = path.resolve("simpleperf-metadata.json")
        val companion = if (Files.isRegularFile(companionPath)) runCatching { Json.readMap(companionPath) }.getOrNull() else null
        val status = metadata["simpleperf_status"]?.toString() ?: "not recorded"
        val verified = validatedEvents && status == "complete" && dwarf.coverage.isNotEmpty()
        row(
            "DWARF companion",
            if (verified) {
                "pass"
            } else if (status == "disabled") {
                "info"
            } else {
                "warning"
            },
            if (verified) {
                "${dwarf.matches.size} / ${majors.size} major faults matched by exact event identity"
            } else {
                if (status == "complete" && !validatedEvents) {
                    "Recorded; exact matching not evaluated"
                } else {
                    status
                }
            },
            if (status == "disabled") {
                "Use --dwarf-stacks to collect the companion stream."
            } else if (!verified) {
                (companion?.get("recommendation")?.toString().orEmpty() + " " + dwarf.warnings.joinToString(" ")).trim().ifBlank {
                    "Inspect simpleperf.log and simpleperf-metadata.json; rejected streams are not used for attribution."
                }
            } else {
                "No nearest-time matching; unmatched faults retain their native stack if available."
            },
        )
        companion?.let {
            row(
                "Simpleperf recording health",
                if (it["integrity_passed"] == true) "pass" else "warning",
                "Recorded ${it["samples_recorded"] ?: "unknown"}; lost ${it["samples_lost"] ?: "unknown"}; kernel ${it["kernel_lost_records"] ?: "unknown"}; userspace ${it["userspace_lost_records"] ?: "unknown"}; truncated ${it["truncated_stack_samples"] ?: "unknown"}",
                "System-wide counts, not just this app. Loss may include mapping records, not only fault samples.",
            )
            row(
                "DWARF buffer settings",
                "info",
                "${it["kernel_buffer_pages_per_cpu"] ?: "unknown"} pages per CPU; userspace ${it["user_buffer_bytes"] ?: "default / unknown"} bytes",
                "Kernel bytes per CPU: ${it["kernel_buffer_bytes_per_cpu"] ?: "unknown"}. Larger buffers consume target RAM and can perturb startup.",
            )
        }
        val frames =
            events
                .flatMap {
                    (it["stack"] as? List<*>).orEmpty()
                }.mapNotNull { it as? Map<*, *> }
                .filter { it["kind"] != "kernel" }
        val unresolved = frames.count { it["unresolved"] == true }
        row(
            "Symbol resolution",
            if (validatedEvents && unresolved == 0 && frames.isNotEmpty()) "pass" else "warning",
            if (validatedEvents) {
                "$unresolved / ${frames.size} captured user-frame occurrences unresolved"
            } else {
                "Not evaluated; exact fault processing did not complete"
            },
            "Counts occurrences, not unique methods. Provide matching symbols; see capture warnings for OAT/export failures.",
        )
        val io = metadata["io_results"] as? Map<*, *>
        row(
            "I/O context",
            if (io?.get("status") == "exported") "pass" else "info",
            io?.get("status")?.toString() ?: "Not collected",
            "Use --io-evidence; missing or empty streams do not establish zero disk I/O. Inspect availability in the I/O tab.",
        )
        (metadata["warnings"] as? List<*>)
            .orEmpty()
            .map { it.toString() }
            .distinct()
            .forEach { row("Capture warning", "warning", it) }
        return result
    }

    fun io(
        path: Path,
        metadata: Map<String, Any?>,
    ): Map<String, Any?> {
        val startup = metadata["startup"] as? Map<*, *>
        val start = (startup?.get("ts") as? Number)?.toLong() ?: return emptyMap()
        val end = (startup["ts_end"] as? Number)?.toLong() ?: return emptyMap()
        val duration = (end - start) / 1e6
        val warnings = mutableListOf<String>()

        fun rows(name: String): List<Map<String, String>> {
            val file = path.resolve("$name.csv")
            if (!Files.isRegularFile(file)) {
                warnings += "$name was not exported."
                return emptyList()
            }
            return runCatching { Csv.read(file) }.getOrElse {
                warnings += "$name could not be read: ${it.message}"
                emptyList()
            }
        }

        fun spans(name: String): List<Map<String, Any?>> =
            rows(name).mapNotNull { row ->
                val ts = row["ts"]?.toLongOrNull() ?: return@mapNotNull null
                val dur = row["dur"]?.toLongOrNull() ?: return@mapNotNull null
                if (ts >= end || (dur >= 0 && ts + dur <= start)) return@mapNotNull null
                row +
                    mapOf(
                        "time" to maxOf(0.0, (ts - start) / 1e6),
                        "end" to if (dur < 0) null else minOf(duration, (ts - start + dur) / 1e6),
                        "incomplete" to (dur < 0),
                    )
            }

        fun points(name: String): List<Map<String, Any?>> =
            rows(name).mapNotNull { row ->
                val ts = row["ts"]?.toLongOrNull() ?: return@mapNotNull null
                if (ts < start || ts >= end) null else row + mapOf("time" to (ts - start) / 1e6)
            }
        return mapOf(
            "duration" to duration,
            "advice" to spans("io_advice_spans"),
            "blocking" to spans("io_thread_states").filter { it["state"].toString().startsWith("D") || it["io_wait"] == "1" },
            "cache" to points("page_cache_events"),
            "block" to points("io_block_events"),
            "availability" to ((metadata["io_capture"] as? Map<*, *>)?.get("events") ?: emptyList<Any>()),
            "status" to ((metadata["io_results"] as? Map<*, *>)?.get("status") ?: "not collected"),
            "warnings" to warnings + ((metadata["io_results"] as? Map<*, *>)?.get("warnings") as? List<*>).orEmpty(),
        )
    }
}
