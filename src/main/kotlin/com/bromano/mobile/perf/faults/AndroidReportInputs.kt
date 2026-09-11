package com.bromano.mobile.perf.faults

import com.bromano.mobile.perf.utils.sha256
import java.nio.file.Files
import java.nio.file.Path

/** Hash evidence before and after rendering; embed the manifest in the atomically published HTML. */
internal object AndroidReportInputs {
    fun hashes(capture: Path): Map<String, String> {
        val names =
            listOf(
                "capture_metadata.json",
                "all_faults.csv",
                "resolved_fault_callchains.csv",
                "fault_details.json",
                "vdex_dex_boundaries.csv",
                "artifacts.json",
                "fault_events.csv",
                "simpleperf.data",
                "simpleperf-stacks.txt",
                "simpleperf-metadata.json",
                "page_cache_events.csv",
                "io_advice_spans.csv",
                "io_block_events.csv",
                "io_thread_states.csv",
                "faults.pftrace",
                "oatdump.json",
            )
        val files = names.map(capture::resolve).filter(Files::isRegularFile).toMutableList()
        if (Files.isRegularFile(capture.resolve("artifacts.json"))) {
            files +=
                Json
                    .readMap(capture.resolve("artifacts.json"))
                    .values
                    .map { capture.resolve(it.toString()) }
                    .filter(Files::isRegularFile)
        }
        return files.distinct().sortedBy { it.toString() }.associate { capture.relativize(it).toString() to sha256(it) }
    }

    private fun codeHashes(): Map<String, String> {
        val source =
            Path.of(
                AndroidFaultReport::class.java.protectionDomain.codeSource.location
                    .toURI(),
            )
        if (Files.isRegularFile(source)) return mapOf(source.fileName.toString() to sha256(source))
        return Files.walk(source).use { paths ->
            paths
                .filter { Files.isRegularFile(it) && it.toString().endsWith(".class") }
                .sorted()
                .toList()
                .associate { source.relativize(it).toString() to sha256(it) }
        }
    }

    fun renderer(engine: Path): Map<String, Any?> =
        mapOf(
            "version" to (AndroidFaultReport::class.java.`package`.implementationVersion ?: "development"),
            "code_sha256" to codeHashes(),
            "assets_sha256" to
                Files.list(engine.resolve("shared")).use { paths ->
                    paths
                        .filter(Files::isRegularFile)
                        .sorted()
                        .toList()
                        .associate { it.fileName.toString() to sha256(it) }
                },
        )
}
