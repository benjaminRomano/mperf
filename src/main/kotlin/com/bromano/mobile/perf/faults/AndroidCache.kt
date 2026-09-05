package com.bromano.mobile.perf.faults

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

internal object AndroidCache {
    private val fields =
        listOf("pid", "starttime", "begin", "end", "offset", "dev", "inode", "permissions", "requested", "result", "errno", "path")

    fun newBootReady(
        previous: String,
        current: String,
        completed: String,
    ): Boolean =
        Regex("[0-9a-fA-F-]{36}").matches(previous) &&
            Regex("[0-9a-fA-F-]{36}").matches(current) &&
            current != previous &&
            completed == "1"

    fun validateApks(apks: List<String>) {
        require(
            apks.isNotEmpty() &&
                apks.size <= 128 &&
                apks.distinct().size == apks.size &&
                apks.all {
                    it.startsWith("/data/app/") &&
                        it.endsWith(".apk") &&
                        ".." !in it.split('/') &&
                        it.none { char -> char in "\r\n\t" }
                },
        ) { "Mapped reclaim requires exact installed /data/app APK paths" }
    }

    fun parseAudit(
        text: String,
        apks: List<String>,
    ): List<Map<String, Any?>> {
        val lines = text.lineSequence().filter(String::isNotEmpty).toList()
        require(lines.firstOrNull()?.split('\t') == fields) { "Invalid mapped APK reclaim audit header" }
        val rows =
            lines.drop(1).map { line ->
                val values = line.split('\t')
                require(values.size == fields.size) { "Malformed mapped APK reclaim audit row" }
                val row = fields.zip(values).toMap().toMutableMap<String, Any?>()
                listOf("pid", "starttime", "inode", "requested", "result", "errno").forEach { row[it] = row[it].toString().toLong() }
                listOf("begin", "end", "offset").forEach { row[it] = row[it].toString().removePrefix("0x").toLong(16) }

                fun n(key: String) = row[key] as Long
                require(
                    row["path"] in apks &&
                        row["permissions"] in listOf("r--s", "r--p") &&
                        Regex("[0-9a-f]+:[0-9a-f]+").matches(row["dev"].toString()) &&
                        n("pid") > 1 &&
                        n("starttime") > 0 &&
                        n("inode") > 0 &&
                        n("begin") > 0 &&
                        n("offset") >= 0 &&
                        n("end") - n("begin") == n("requested") &&
                        n("requested") in 1..256 * 1024 * 1024L &&
                        n("result") in -1..n("requested") &&
                        n("errno") >= 0 &&
                        (n("result") == -1L) == (n("errno") > 0),
                ) { "Out-of-scope or inconsistent mapped APK reclaim audit row" }
                row
            }
        require(rows.size <= 256 && rows.sumOf { it["requested"] as Long } <= 1024 * 1024 * 1024L) {
            "Mapped APK reclaim exceeds bounded scope"
        }
        return rows
    }

    fun reclaim(
        adb: AndroidFaultCollector.Device,
        apks: List<String>,
        output: Path,
        phase: String,
    ): Map<String, Any?> {
        validateApks(apks)
        require(phase in listOf("after_drop", "before_launch"))
        val command =
            "/data/local/tmp/android-fault-visualizer/page_fault_collector --reclaim-mapped-apks " +
                apks.joinToString(" ", transform = ::androidQuote)
        val attempt = runCatching { adb.rootShell(command, check = false, timeout = Duration.ofSeconds(15)) }
        val result = attempt.getOrNull()
        val stdout = result?.stdout.orEmpty()
        val stderr = result?.stderr ?: attempt.exceptionOrNull()?.message.orEmpty()
        val artifact = "mapped-apk-reclaim-$phase.tsv"
        Files.writeString(output.resolve(artifact), stdout)
        Files.writeString(output.resolve("mapped-apk-reclaim-$phase.stderr.txt"), stderr)
        require(stdout.isNotEmpty() || result?.exitCode != 0) { "Mapped reclaim returned no audit header" }
        val rows = if (stdout.isEmpty()) emptyList() else parseAudit(stdout, apks)
        val failed = rows.count { it["result"] != it["requested"] }
        val diagnostics =
            mutableMapOf<String, Any?>(
                "phase" to phase,
                "method" to "process_madvise(MADV_PAGEOUT) on exact read-only installed APK mappings",
                "command" to command,
                "return_code" to result?.exitCode,
                "audit_file" to artifact,
                "stderr" to stderr.trim(),
                "ranges_attempted" to rows.size,
                "requested_bytes" to rows.sumOf { it["requested"] as Long },
                "advised_bytes" to rows.sumOf { maxOf(0, it["result"] as Long) },
                "failed_ranges" to failed,
                "eviction_verified" to false,
            )
        if (result?.exitCode != 0 || failed > 0) {
            diagnostics["warning"] =
                "Mapped APK reclaim was incomplete (exit=${result?.exitCode}, failed ranges=$failed). Strict residency checks remain authoritative."
        }
        adb.rootShell(
            "/data/local/tmp/android-fault-visualizer/page_fault_collector --evict " + apks.joinToString(" ", transform = ::androidQuote),
        )
        return diagnostics
    }

    fun validateResidency(
        rows: List<Map<String, String>>,
        files: List<String>,
        required: List<String>,
        pageSize: Int,
    ) {
        require(pageSize > 0 && pageSize and (pageSize - 1) == 0) { "Invalid page size" }
        val seen = mutableSetOf<String>()
        rows.forEach { row ->
            val path = row.getValue("file_name")
            val size = row.getValue("size_bytes").toLong()
            val pages = row.getValue("total_pages").toLong()
            val resident = row.getValue("resident_pages").toLong()
            require(path in files && seen.add(path) && size >= 0 && pages == (size + pageSize - 1) / pageSize && resident in 0..pages) {
                "Invalid or duplicate cache residency row: $path"
            }
        }
        require(seen.containsAll(required)) { "Missing required installed APK residency rows: ${required - seen}" }
    }
}

internal fun androidQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"

internal fun androidAppOwned(
    path: String,
    packageName: String,
): Boolean {
    if (path.startsWith("/data/app/")) return path.split('/').any { it == packageName || it.startsWith("$packageName-") }
    return listOf("/data/user/0/$packageName", "/data/user_de/0/$packageName", "/data/data/$packageName").any {
        path == it || path.startsWith("$it/")
    }
}
