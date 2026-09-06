package com.bromano.mobile.perf.faults

import com.bromano.mobile.perf.utils.CommandResult
import com.bromano.mobile.perf.utils.sha256
import java.nio.file.Files
import java.nio.file.Path

/** Optional context, kept separate from exact fault and file attribution. */
internal object AndroidIo {
    private val events =
        listOf(
            "sched/sched_switch",
            "sched/sched_waking",
            "sched/sched_blocked_reason",
            "block/block_rq_issue",
            "block/block_rq_complete",
            "block/block_rq_requeue",
            "block/block_rq_remap",
            "block/block_io_start",
            "block/block_io_done",
        )

    fun prepare(
        adb: AndroidFaultCollector.Device,
        output: Path,
        metadata: MutableMap<String, Any?>,
        baseConfig: String,
    ): String = prepare(output, metadata, baseConfig) { adb.rootShell(it, check = false) }

    internal fun prepare(
        output: Path,
        metadata: MutableMap<String, Any?>,
        baseConfig: String,
        shell: (String) -> CommandResult,
    ): String {
        val diagnostics =
            events.map { event ->
                val probe =
                    runCatching {
                        shell(
                            "for root in /sys/kernel/tracing /sys/kernel/debug/tracing; do " +
                                "if [ -e \"\$root/events/$event/format\" ]; then " +
                                "cat \"\$root/events/$event/format\"; exit \$?; fi; done; " +
                                "if [ -r /sys/kernel/tracing/available_events ] || " +
                                "[ -r /sys/kernel/debug/tracing/available_events ]; then exit 44; else exit 45; fi",
                        )
                    }
                val result = probe.getOrNull()
                val format = result?.stdout.orEmpty()
                val valid =
                    result?.exitCode == 0 &&
                        format.contains("name: ${event.substringAfter('/')}") &&
                        format.contains("field:")
                val state =
                    when {
                        valid -> "available_requested"
                        result?.exitCode == 44 -> "absent"
                        result?.exitCode == 0 -> "invalid_format"
                        else -> "unavailable"
                    }
                val file = "io-event-formats/${event.replace('/', '-')}.txt"
                if (valid) {
                    Files.createDirectories(output.resolve(file).parent)
                    Files.writeString(output.resolve(file), format)
                }
                mapOf(
                    "event" to event,
                    "status" to state,
                    "exit_code" to result?.exitCode,
                    "format_file" to file.takeIf { valid },
                    "diagnostic" to (result?.stderr ?: probe.exceptionOrNull()?.message).orEmpty(),
                )
            }
        val selected = diagnostics.filter { it["status"] == "available_requested" }.map { it["event"].toString() }
        val marker =
            Regex("ftrace_config\\s*\\{").find(baseConfig)
                ?: error("Missing ftrace_config for optional I/O evidence")
        val additions =
            buildString {
                if ("sched/sched_blocked_reason" in selected) {
                    // Kernel caller addresses must be symbolized on-device while recording.
                    append("\n            symbolize_ksyms: true")
                }
                selected.forEach { append("\n            ftrace_events: \"$it\"") }
            }
        val config = baseConfig.substring(0, marker.range.last + 1) + additions + baseConfig.substring(marker.range.last + 1)
        Files.writeString(output.resolve("io-ftrace.config"), config)
        metadata["trace_config_sha256"] = sha256(output.resolve("io-ftrace.config"))
        metadata["io_capture"] =
            mapOf(
                "requested" to true,
                "events" to diagnostics,
                "config_file" to "io-ftrace.config",
                "scope" to "system-wide guest block activity; app scheduling and ART advice",
            )
        Json.write(output.resolve("capture_metadata.json"), metadata)
        return config
    }

    fun export(
        output: Path,
        metadata: MutableMap<String, Any?>,
        pid: Long,
        start: Long,
        end: Long,
        query: (String) -> List<Map<String, String>>,
    ) {
        if (metadata["io_capture"] !is Map<*, *>) return
        require(start >= 0 && end > start && pid > 0)
        val counts = mutableMapOf<String, Int>()
        val failures = mutableListOf<String>()

        fun write(
            name: String,
            fields: List<String>,
            sql: String,
        ) {
            try {
                val rows = query(sql.trimIndent())
                Csv.write(output.resolve("$name.csv"), fields, rows)
                counts[name] = rows.size
            } catch (error: Exception) {
                failures += "$name unavailable: ${error.message}"
            }
        }
        write(
            "io_advice_spans",
            listOf("ts", "dur", "elapsed_ms", "startup_overlap_ns", "incomplete", "tid", "thread_name", "name"),
            """
            SELECT s.ts,s.dur,(s.ts-$start)/1000000.0 AS elapsed_ms,
              CASE WHEN s.dur >= 0 THEN MIN(s.ts+s.dur,$end)-MAX(s.ts,$start) END AS startup_overlap_ns,
              s.dur < 0 AS incomplete,t.tid,t.name AS thread_name,s.name
            FROM slice s JOIN thread_track tt ON tt.id=s.track_id
              JOIN thread t ON t.utid=tt.utid JOIN process p ON p.upid=t.upid
            WHERE p.pid=$pid AND s.name GLOB 'madvising *' AND s.ts < $end
              AND (s.dur < 0 OR s.ts+s.dur > $start) ORDER BY s.ts,s.id;
            """,
        )
        write(
            "io_block_events",
            listOf(
                "ts",
                "elapsed_ms",
                "event",
                "device",
                "sector",
                "nr_sector",
                "bytes",
                "rwbs",
                "error",
                "old_device",
                "old_sector",
                "tid",
                "thread_name",
            ),
            """
            SELECT f.ts,(f.ts-$start)/1000000.0 AS elapsed_ms,f.name AS event,
              EXTRACT_ARG(f.arg_set_id,'dev') AS device,EXTRACT_ARG(f.arg_set_id,'sector') AS sector,
              EXTRACT_ARG(f.arg_set_id,'nr_sector') AS nr_sector,
              COALESCE(EXTRACT_ARG(f.arg_set_id,'bytes'),EXTRACT_ARG(f.arg_set_id,'nr_sector')*512) AS bytes,
              EXTRACT_ARG(f.arg_set_id,'rwbs') AS rwbs,
              COALESCE(EXTRACT_ARG(f.arg_set_id,'error'),EXTRACT_ARG(f.arg_set_id,'errors')) AS error,
              EXTRACT_ARG(f.arg_set_id,'old_dev') AS old_device,EXTRACT_ARG(f.arg_set_id,'old_sector') AS old_sector,
              t.tid,t.name AS thread_name
            FROM ftrace_event f LEFT JOIN thread t ON t.utid=f.utid
            WHERE f.name IN (${events.filter { it.startsWith("block/") }.joinToString(",") { "'${it.substringAfter('/')}'" }})
              AND f.ts >= $start AND f.ts < $end ORDER BY f.ts,f.id;
            """,
        )
        write(
            "io_thread_states",
            listOf(
                "ts",
                "dur",
                "elapsed_ms",
                "startup_overlap_ns",
                "incomplete",
                "tid",
                "thread_name",
                "state",
                "io_wait",
                "blocked_function",
                "waker_utid",
            ),
            """
            SELECT s.ts,s.dur,(s.ts-$start)/1000000.0 AS elapsed_ms,
              CASE WHEN s.dur >= 0 THEN MIN(s.ts+s.dur,$end)-MAX(s.ts,$start) END AS startup_overlap_ns,
              s.dur < 0 AS incomplete,t.tid,t.name AS thread_name,s.state,s.io_wait,s.blocked_function,s.waker_utid
            FROM thread_state s JOIN thread t ON t.utid=s.utid JOIN process p ON p.upid=t.upid
            WHERE p.pid=$pid AND s.ts < $end AND (s.dur < 0 OR s.ts+s.dur > $start) ORDER BY s.ts,s.id;
            """,
        )
        metadata["io_results"] =
            mapOf(
                "status" to if (failures.isEmpty()) "exported" else "partial",
                "rows" to counts,
                "startup_start_ns" to start,
                "startup_end_ns" to end,
                "limitations" to
                    listOf(
                        "Empty streams do not establish zero I/O; inspect io_capture event capabilities.",
                        "Block events are system-wide guest device activity, not exact app-file or physical-media attribution.",
                        "Advice duration, cache insertion and thread blocking are distinct from storage latency.",
                        "No request pairing, queue depth or per-file I/O latency is inferred.",
                    ),
                "warnings" to failures,
            )
        if (failures.isNotEmpty()) metadata["warnings"] = (metadata["warnings"] as? List<*>).orEmpty() + failures
    }
}
