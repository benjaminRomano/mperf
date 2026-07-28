package com.bromano.mobile.perf.faults

import java.nio.file.Files
import java.nio.file.Path
import java.util.ArrayDeque
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants

internal class IosFaultProcessor {
    private data class Binary(
        val name: String,
        val path: String,
    )

    private data class Source(
        val path: String,
        val line: Int,
    )

    private data class Frame(
        val name: String,
        val address: String,
        val binary: Binary,
        val source: Source,
    )

    private data class TaggedBacktrace(
        val label: String,
        val frames: List<Frame>,
    )

    private data class ProcessInfo(
        val name: String,
        val pid: Long?,
    )

    private data class ThreadInfo(
        val name: String,
        val tid: Long?,
        val process: ProcessInfo,
    )

    private data class ElementNode(
        val tag: String,
        val attributes: Map<String, String>,
        val children: MutableList<ElementNode> = mutableListOf(),
        val text: StringBuilder = StringBuilder(),
        var value: Any? = null,
    )

    private data class Event(
        val sourceIndex: Int,
        val timestamp: Long,
        val faultClass: String,
        val operation: String,
        val processName: String,
        val address: ULong,
        val sizeBytes: Long,
        val durationNs: Long,
        val thread: ThreadInfo,
        val tagged: TaggedBacktrace,
    )

    private val minorOperations = setOf("Page Cache Hit", "Zero Fill", "Copy On Write", "Decompress Memory")
    private val majorOperations = setOf("File Backed Page In")
    private val genericEntryPoints = setOf("_start", "start", "main", "NSExtensionMain", "UIApplicationMain")
    private val genericPrefixes = listOf("__llvm_profile_", "<deduplicated", "<redacted", "<unknown")

    fun process(output: Path) {
        val metadataPath = output.resolve("capture_metadata.json")
        require(Files.isRegularFile(metadataPath)) { "Missing iOS capture metadata: $metadataPath" }
        val metadata = Json.readMap(metadataPath)
        require((metadata["schema_version"] as? Number)?.toInt() == 1) {
            "Unsupported iOS capture schema: ${metadata["schema_version"]}"
        }
        val vmPath = output.resolve("virtual-memory.xml")
        require(Files.size(vmPath) >= 100) { "The trace exported no Virtual Memory rows" }
        val targetPid = (metadata["target_pid"] as Number).toLong()
        val appBinaryName = metadata["app_binary_name"].toString()
        val explicitBundleRoot = metadata["app_bundle_root"]?.toString().orEmpty()
        val analysisWindowMs = (metadata["settle_seconds"] as Number).toDouble() * 1_000.0
        val parsed = parse(vmPath, targetPid)
        require(parsed.isNotEmpty()) { "No supported Virtual Memory fault rows were found for PID $targetPid" }
        val sorted = parsed.sortedWith(compareBy<Event> { it.timestamp }.thenBy { it.sourceIndex })
        val first = sorted.first().timestamp
        val events = sorted.filter { it.timestamp - first <= analysisWindowMs * 1_000_000.0 }
        var bundleRoot = normalizePosix(explicitBundleRoot)
        if (bundleRoot.isBlank()) {
            bundleRoot = inferBundleRoot(events, appBinaryName)
        }
        val rows =
            events.mapIndexed { index, event ->
                eventRow(index + 1, event, first, appBinaryName, bundleRoot)
            }
        val majorRows = rows.filter { it["fault_class"] == "Major" }
        val summaries = summarize(rows)
        Csv.write(output.resolve("page_fault_events.csv"), eventFields, rows)
        Csv.write(output.resolve("major_page_fault_events.csv"), eventFields, majorRows)
        Csv.write(output.resolve("major_page_fault_code_summary.csv"), summaryFields, summaries)
        writeSqlite(output)

        val classCounts = rows.groupingBy { it["fault_class"].toString() }.eachCount().toSortedMap()
        val operationCounts = rows.groupingBy { it["operation"].toString() }.eachCount().toSortedMap()
        val pageSize =
            rows
                .mapNotNull { (it["size_bytes"] as? Number)?.toLong()?.takeIf { size -> size > 0 } }
                .groupingBy { it }
                .eachCount()
                .maxByOrNull { it.value }
                ?.key
        val stats =
            mapOf(
                "schema_version" to 1,
                "target_pid" to targetPid,
                "event_count" to rows.size,
                "class_counts" to classCounts,
                "operation_counts" to operationCounts,
                "capture_span_ms" to rows.last()["time_since_first_fault_ms"],
                "analysis_window_ms" to analysisWindowMs,
                "page_size_bytes" to pageSize,
                "major_faults_with_stack" to majorRows.count { (it["stack_depth"] as Number).toInt() > 0 },
                "major_faults_with_symbolicated_top_frame" to
                    majorRows.count {
                        it["faulting_frame"].toString().isNotBlank() &&
                            !it["faulting_frame"].toString().startsWith("0x")
                    },
                "major_faults_with_app_frame" to majorRows.count { it["first_app_frame"].toString().isNotBlank() },
                "major_faults_with_bundle_owned_faulting_binary" to
                    majorRows.count { it["faulting_binary_is_bundle_owned"] == true },
                "ordering_candidate_groups" to summaries.size,
                "ordering_candidate_faults" to summaries.sumOf { (it["major_fault_count"] as Number).toInt() },
                "classification" to mapOf("Major" to majorOperations.sorted(), "Minor" to minorOperations.sorted()),
                "classification_note" to
                    "Major/minor are analysis buckets inferred from Instruments VM operations, not kernel accounting labels emitted by iOS.",
            )
        Json.write(output.resolve("page_fault_stats.json"), stats)
        metadata["app_bundle_root"] = bundleRoot
        metadata["stats"] = stats
        metadata["processing_engine"] = "kotlin"
        metadata["processing_status"] = "complete"
        Json.write(metadataPath, metadata)
    }

    private fun parse(
        path: Path,
        targetPid: Long,
    ): List<Event> {
        val factory = XMLInputFactory.newFactory()
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false)
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false)
        val registry = HashMap<String, Any?>()
        val stack = ArrayDeque<ElementNode>()
        val events = mutableListOf<Event>()
        var sourceIndex = 0
        Files.newInputStream(path).use { input ->
            val reader = factory.createXMLStreamReader(input)
            while (reader.hasNext()) {
                when (reader.next()) {
                    XMLStreamConstants.START_ELEMENT -> {
                        val attributes =
                            (0 until reader.attributeCount).associate {
                                reader.getAttributeLocalName(it) to reader.getAttributeValue(it)
                            }
                        stack.addLast(ElementNode(reader.localName, attributes))
                    }

                    XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> {
                        stack.lastOrNull()?.text?.append(reader.text)
                    }

                    XMLStreamConstants.END_ELEMENT -> {
                        val node = stack.removeLast()
                        node.value = resolve(node, registry)
                        node.attributes["id"]?.let { registry["${node.tag}:$it"] = node.value }
                        if (node.tag == "row") {
                            sourceIndex++
                            rowEvent(node, sourceIndex, targetPid)?.let(events::add)
                        } else {
                            stack.lastOrNull()?.children?.add(node)
                        }
                    }
                }
            }
        }
        return events
    }

    private fun resolve(
        node: ElementNode,
        registry: Map<String, Any?>,
    ): Any? {
        node.attributes["ref"]?.let { return registry["${node.tag}:$it"] }

        fun child(tag: String): Any? = node.children.firstOrNull { it.tag == tag }?.value
        return when (node.tag) {
            "start-time", "duration", "address", "size-in-bytes", "tid", "pid" ->
                node.text.toString().trim().toULongOrNull()?.let {
                    if (it <= Long.MAX_VALUE.toULong()) it.toLong() else it
                } ?: 0L

            "vm-op", "path" -> node.attributes["fmt"] ?: node.text.toString()
            "binary" ->
                Binary(
                    name = node.attributes["name"].orEmpty(),
                    path = node.attributes["path"].orEmpty(),
                )

            "source" -> Source(path = child("path")?.toString().orEmpty(), line = node.attributes["line"]?.toIntOrNull() ?: 0)
            "frame" ->
                Frame(
                    name = node.attributes["name"].orEmpty(),
                    address = node.attributes["addr"].orEmpty(),
                    binary = child("binary") as? Binary ?: Binary("", ""),
                    source = child("source") as? Source ?: Source("", 0),
                )

            "backtrace" -> node.children.filter { it.tag == "frame" }.mapNotNull { it.value as? Frame }
            "tagged-backtrace" ->
                TaggedBacktrace(
                    label = node.attributes["fmt"].orEmpty(),
                    frames = (child("backtrace") as? List<*>)?.filterIsInstance<Frame>().orEmpty(),
                )

            "process" -> ProcessInfo(node.attributes["fmt"].orEmpty(), (child("pid") as? Number)?.toLong())
            "thread" ->
                ThreadInfo(
                    node.attributes["fmt"].orEmpty(),
                    (child("tid") as? Number)?.toLong(),
                    child("process") as? ProcessInfo ?: ProcessInfo("", null),
                )

            else -> node.text.toString()
        }
    }

    private fun rowEvent(
        row: ElementNode,
        sourceIndex: Int,
        targetPid: Long,
    ): Event? {
        fun child(tag: String): Any? = row.children.firstOrNull { it.tag == tag }?.value
        val process = child("process") as? ProcessInfo ?: return null
        if (process.pid != targetPid) return null
        val operation = child("vm-op")?.toString().orEmpty()
        val faultClass =
            when (operation) {
                in majorOperations -> "Major"
                in minorOperations -> "Minor"
                else -> return null
            }
        val address =
            when (val value = child("address")) {
                is ULong -> value
                is Number -> value.toLong().toULong()
                else -> 0UL
            }
        return Event(
            sourceIndex = sourceIndex,
            timestamp = (child("start-time") as? Number)?.toLong() ?: 0,
            faultClass = faultClass,
            operation = operation,
            processName = process.name,
            address = address,
            sizeBytes = (child("size-in-bytes") as? Number)?.toLong() ?: 0,
            durationNs = (child("duration") as? Number)?.toLong() ?: 0,
            thread = child("thread") as? ThreadInfo ?: ThreadInfo("", null, process),
            tagged = child("tagged-backtrace") as? TaggedBacktrace ?: TaggedBacktrace("", emptyList()),
        )
    }

    private fun inferBundleRoot(
        events: List<Event>,
        appBinaryName: String,
    ): String {
        val candidates =
            events
                .asSequence()
                .flatMap { it.tagged.frames.asSequence() }
                .map { it.binary }
                .filter { it.name == appBinaryName && it.path.isNotBlank() }
                .mapNotNull { binary ->
                    val normalized = normalizePosix(binary.path)
                    val marker = "/$appBinaryName.app/"
                    val markerIndex = normalized.lastIndexOf(marker)
                    when {
                        markerIndex >= 0 && normalized == normalized.substring(0, markerIndex + marker.length - 1) + "/$appBinaryName" ->
                            normalized.substring(0, markerIndex + marker.length - 1)

                        normalized.substringAfterLast('/') == appBinaryName -> {
                            val parent = normalized.substringBeforeLast('/')
                            parent.takeIf { it.substringAfterLast('/').endsWith(".app") }
                        }

                        else -> null
                    }
                }.distinct()
                .toList()
        return candidates.singleOrNull().orEmpty()
    }

    private fun eventRow(
        eventIndex: Int,
        event: Event,
        firstTimestamp: Long,
        appBinaryName: String,
        bundleRoot: String,
    ): Map<String, Any?> {
        val frames = event.tagged.frames
        val faulting = frames.firstOrNull()
        val symbolicated = frames.firstOrNull { it.name.isNotBlank() && !it.name.startsWith("0x") }
        val appFrame = frames.firstOrNull { appOwned(it.binary, event.processName, appBinaryName, bundleRoot) }
        val faultingName = faulting?.name?.ifBlank { faulting.address } ?: event.tagged.label
        return mapOf(
            "event_index" to eventIndex,
            "trace_time_seconds" to event.timestamp / 1_000_000_000.0,
            "time_since_first_fault_ms" to (event.timestamp - firstTimestamp) / 1_000_000.0,
            "fault_class" to event.faultClass,
            "operation" to event.operation,
            "process_name" to event.processName,
            "pid" to event.thread.process.pid,
            "address" to event.address,
            "address_hex" to "0x${event.address.toString(16)}",
            "size_bytes" to event.sizeBytes,
            "duration_ns" to event.durationNs,
            "thread" to event.thread.name,
            "tid" to event.thread.tid,
            "faulting_frame" to faultingName,
            "faulting_instruction" to faulting?.address.orEmpty(),
            "faulting_binary" to faulting?.binary?.name.orEmpty(),
            "faulting_binary_path" to faulting?.binary?.path.orEmpty(),
            "faulting_binary_is_bundle_owned" to (faulting?.let { pathWithin(it.binary.path, bundleRoot) } ?: false),
            "faulting_source_path" to faulting?.source?.path.orEmpty(),
            "faulting_source_line" to (faulting?.source?.line ?: 0),
            "first_symbolicated_frame" to symbolicated?.name.orEmpty(),
            "first_symbolicated_binary" to symbolicated?.binary?.name.orEmpty(),
            "first_app_frame" to appFrame?.name.orEmpty(),
            "first_app_binary" to appFrame?.binary?.name.orEmpty(),
            "first_app_source_path" to appFrame?.source?.path.orEmpty(),
            "first_app_source_line" to (appFrame?.source?.line ?: 0),
            "stack_depth" to frames.size,
            "stack" to frames.joinToString(" ← ") { frameLabel(it) },
        )
    }

    private fun summarize(rows: List<Map<String, Any?>>): List<Map<String, Any?>> {
        val eligible =
            rows.filter {
                val frame = it["faulting_frame"].toString()
                it["fault_class"] == "Major" &&
                    it["faulting_binary_is_bundle_owned"] == true &&
                    frame.isNotBlank() &&
                    !frame.startsWith("0x") &&
                    frame !in genericEntryPoints &&
                    genericPrefixes.none(frame::startsWith)
            }
        return eligible
            .groupBy {
                listOf(
                    it["faulting_frame"],
                    it["faulting_instruction"],
                    it["faulting_binary"],
                    it["faulting_binary_path"],
                    it["faulting_source_path"],
                    it["faulting_source_line"],
                    it["first_symbolicated_frame"],
                    it["first_symbolicated_binary"],
                    it["first_app_frame"],
                    it["first_app_binary"],
                    it["first_app_source_path"],
                    it["first_app_source_line"],
                )
            }.map { (key, events) ->
                mapOf(
                    "major_fault_count" to events.size,
                    "unique_addresses" to events.map { it["address"] }.distinct().size,
                    "first_fault_ms" to events.minOf { (it["time_since_first_fault_ms"] as Number).toDouble() },
                    "last_fault_ms" to events.maxOf { (it["time_since_first_fault_ms"] as Number).toDouble() },
                    "total_fault_duration_ms" to events.sumOf { (it["duration_ns"] as Number).toLong() } / 1_000_000.0,
                    "faulting_frame" to key[0],
                    "faulting_instruction" to key[1],
                    "faulting_binary" to key[2],
                    "faulting_binary_path" to key[3],
                    "faulting_source_path" to key[4],
                    "faulting_source_line" to key[5],
                    "first_symbolicated_frame" to key[6],
                    "first_symbolicated_binary" to key[7],
                    "first_app_frame" to key[8],
                    "first_app_binary" to key[9],
                    "first_app_source_path" to key[10],
                    "first_app_source_line" to key[11],
                    "example_address_hex" to events.first()["address_hex"],
                    "example_stack" to events.first()["stack"],
                )
            }.sortedWith(
                compareByDescending<Map<String, Any?>> { (it["major_fault_count"] as Number).toInt() }
                    .thenBy { (it["first_fault_ms"] as Number).toDouble() }
                    .thenBy { it["faulting_frame"].toString() },
            )
    }

    private fun writeSqlite(output: Path) {
        val sqlite = Processes.run(listOf("sh", "-c", "command -v sqlite3"), check = false).stdout.trim()
        if (sqlite.isBlank()) return
        val database = output.resolve("page_faults.sqlite")
        Files.deleteIfExists(database)
        Processes.run(
            listOf(
                sqlite,
                database.toString(),
                ".mode csv",
                ".import ${sqliteArgument(output.resolve("page_fault_events.csv"))} page_fault_events",
                ".import ${sqliteArgument(output.resolve("major_page_fault_code_summary.csv"))} major_page_fault_code_summary",
            ),
        )
    }

    private fun sqliteArgument(path: Path): String = "\"${path.toString().replace("\"", "\"\"")}\""

    private fun appOwned(
        binary: Binary,
        processName: String,
        appBinaryName: String,
        bundleRoot: String,
    ): Boolean =
        if (bundleRoot.isNotBlank()) {
            pathWithin(binary.path, bundleRoot)
        } else {
            binary.name in setOf(processName, appBinaryName) &&
                (binary.path.isBlank() || normalizePosix(binary.path).substringAfterLast('/') == appBinaryName)
        }

    private fun pathWithin(
        path: String,
        root: String,
    ): Boolean {
        val normalizedPath = normalizePosix(path)
        val normalizedRoot = normalizePosix(root)
        return normalizedPath.isNotBlank() &&
            normalizedRoot.isNotBlank() &&
            (normalizedPath == normalizedRoot || normalizedPath.startsWith("$normalizedRoot/"))
    }

    private fun normalizePosix(value: String): String =
        value
            .split('/')
            .fold(mutableListOf<String>()) { parts, part ->
                when (part) {
                    "", "." -> Unit
                    ".." -> if (parts.isNotEmpty()) parts.removeLast()
                    else -> parts += part
                }
                parts
            }.joinToString("/", prefix = if (value.startsWith('/')) "/" else "")
            .removeSuffix("/")

    private fun frameLabel(frame: Frame): String {
        val name = frame.name.ifBlank { frame.address }
        val location =
            when {
                frame.source.path.isNotBlank() && frame.source.line > 0 -> "${frame.source.path}:${frame.source.line}"
                frame.source.path.isNotBlank() -> frame.source.path
                else -> frame.binary.name
            }
        return if (location.isBlank()) name else "$name [$location]"
    }

    companion object {
        val eventFields =
            listOf(
                "event_index",
                "trace_time_seconds",
                "time_since_first_fault_ms",
                "fault_class",
                "operation",
                "process_name",
                "pid",
                "address",
                "address_hex",
                "size_bytes",
                "duration_ns",
                "thread",
                "tid",
                "faulting_frame",
                "faulting_instruction",
                "faulting_binary",
                "faulting_binary_path",
                "faulting_binary_is_bundle_owned",
                "faulting_source_path",
                "faulting_source_line",
                "first_symbolicated_frame",
                "first_symbolicated_binary",
                "first_app_frame",
                "first_app_binary",
                "first_app_source_path",
                "first_app_source_line",
                "stack_depth",
                "stack",
            )
        val summaryFields =
            listOf(
                "major_fault_count",
                "unique_addresses",
                "first_fault_ms",
                "last_fault_ms",
                "total_fault_duration_ms",
                "faulting_frame",
                "faulting_instruction",
                "faulting_binary",
                "faulting_binary_path",
                "faulting_source_path",
                "faulting_source_line",
                "first_symbolicated_frame",
                "first_symbolicated_binary",
                "first_app_frame",
                "first_app_binary",
                "first_app_source_path",
                "first_app_source_line",
                "example_address_hex",
                "example_stack",
            )
    }
}
