package com.bromano.mobile.perf.faults

import java.nio.file.Files
import java.nio.file.Path

/** One offline viewer for both platforms; trace strings are data, never template source. */
internal class SharedFaultReport(
    private val engineRoot: Path,
) {
    fun write(
        runs: List<Map<String, Any?>>,
        output: Path,
        title: String,
    ) {
        val assets = engineRoot.resolve("shared")
        val payload =
            Json.mapper
                .writeValueAsString(mapOf("title" to title, "runs" to runs.map(::internStacks)))
                .replace("<", "\\u003c")
                .replace(">", "\\u003e")
                .replace("&", "\\u0026")
        val replacements =
            mapOf(
                "TITLE" to
                    title
                        .replace("&", "&amp;")
                        .replace("<", "&lt;")
                        .replace(">", "&gt;")
                        .replace("\"", "&quot;")
                        .replace("'", "&#39;"),
                "STYLE" to Files.readString(assets.resolve("report.css")),
                "MODEL" to Files.readString(assets.resolve("model.js")),
                "STACKS" to Files.readString(assets.resolve("stacks.js")),
                "PERFETTO" to Files.readString(assets.resolve("perfetto.js")),
                "SCRIPT" to Files.readString(assets.resolve("report.js")),
                "PLOTLY" to Files.readString(assets.resolve("plotly.min.js")),
                "DATA" to payload,
            )
        val document =
            Regex("__(TITLE|STYLE|PLOTLY|DATA|SCRIPT|MODEL|STACKS|PERFETTO)__")
                .replace(Files.readString(assets.resolve("report.html"))) { replacements.getValue(it.groupValues[1]) }
        output.toAbsolutePath().parent?.let(Files::createDirectories)
        Files.writeString(output, document)
    }

    /** Repeated stacks share storage, not event identity or analytical weight. */
    internal fun internStacks(run: Map<String, Any?>): Map<String, Any?> {
        val frames = linkedMapOf<Any?, Int>()
        val stacks = linkedMapOf<List<Int>, Int>()
        val events =
            (run["events"] as? List<*>).orEmpty().map { value ->
                require(value is Map<*, *>) { "Fault event must be an object" }
                val stack =
                    (value["stack"] as? List<*>).orEmpty().map { frame ->
                        frames.getOrPut(frame) { frames.size }
                    }
                val stackId = stacks.getOrPut(stack) { stacks.size }
                value.entries.associate { it.key.toString() to it.value }.toMutableMap().apply {
                    remove("stack")
                    put("stackId", stackId)
                }
            }
        return run + mapOf("events" to events, "frames" to frames.keys.toList(), "stacks" to stacks.keys.toList())
    }
}
