package com.bromano.mobile.perf.faults

import java.nio.file.Files
import java.nio.file.Path

internal object AndroidExperiments {
    data class Capture(
        val capture: Path,
        val label: String,
        val cohort: String,
    )

    fun read(path: Path): List<Capture> {
        val rows = Json.mapper.readTree(Files.readString(path))
        require(rows.isArray && rows.size() <= 100) { "Cohort must be an array of at most 100 captures" }
        return rows.map { row ->
            val capture = row.path("capture").asText()
            val label = row.path("label").asText()
            val cohort = row.path("cohort").asText()
            require(capture.isNotBlank() && label.isNotBlank() && cohort.isNotBlank()) { "Each cohort row needs capture, label and cohort" }
            Capture(
                path
                    .toAbsolutePath()
                    .parent
                    .resolve(capture)
                    .normalize(),
                label,
                cohort,
            )
        }
    }

    fun summary(
        run: Map<String, Any?>,
        hashes: Map<String, String>,
    ): Map<String, Any?> {
        val metadata = run["provenance"] as Map<*, *>
        val startup = metadata["startup"] as? Map<*, *>
        val duration = (startup?.get("duration_ns") as? Number)?.toDouble()?.div(1e6)
        val sources = run["sources"] as Map<*, *>
        val events = (run["events"] as List<*>).filterIsInstance<Map<*, *>>()
        val owned = events.filter { it["major"] == true && (sources[it["source"]] as? Map<*, *>)?.get("app") == true }
        val dex = owned.groupingBy { (it["detail"] as? Map<*, *>)?.get("dex")?.toString().orEmpty() }.eachCount()
        return mapOf(
            "fullyDrawnMs" to duration.takeIf { startup?.get("end_marker") == "reportFullyDrawn" },
            "cutoffMs" to duration,
            "cutoff" to startup?.get("end_marker"),
            "appMajorFaults" to owned.size,
            "dex3Plus" to dex.filterKeys { Regex("classes(?:[3-9]|[1-9][0-9]+)\\.dex").matches(it) }.values.sum(),
            "build" to hashes.filterKeys { it.endsWith(".apk") },
            "compilation" to metadata["compilation_mode"],
            "compilationBefore" to metadata["compilation_before"],
            "compilationAfter" to metadata["compilation_after"],
            "cache" to metadata["cache_verification"],
            "recorder" to
                metadata.filterKeys {
                    it.toString().startsWith("collector_") ||
                        it.toString().startsWith("simpleperf_") ||
                        it == "perfetto_mode"
                },
        )
    }
}
