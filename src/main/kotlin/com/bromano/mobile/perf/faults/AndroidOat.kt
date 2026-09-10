package com.bromano.mobile.perf.faults

import com.bromano.mobile.perf.utils.sha256
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/** Version-matched ART tool output, bound to the exact captured APK and ODEX. */
internal object AndroidOat {
    data class Method(
        val start: Long,
        val end: Long,
        val dex: String,
        val name: String,
        val index: Int,
    )

    private val methodLine = Regex("^  \\d+: (.+) \\(dex_method_idx=(\\d+)\\)$")
    private val codeLine = Regex("^    CODE: \\(code_offset=0x([0-9a-fA-F]+) size=(\\d+)\\).*${'$'}")
    private const val MAX_DUMP = 64L * 1024 * 1024

    fun parse(
        lines: Sequence<String>,
        apk: String,
        identities: List<Pair<String, Long>>,
        elf: AndroidBinary.Elf,
    ): List<Method> {
        require(identities.isNotEmpty() && identities.map { it.first }.distinct().size == identities.size)
        val expected =
            identities.associate { (name, checksum) ->
                (if (name == "classes.dex") apk else "$apk!$name") to (name to checksum)
            }
        val seen = mutableSetOf<String>()
        val result = mutableListOf<Method>()
        var location = ""
        var dex = ""
        var method: MatchResult? = null
        var complete = false
        for (line in lines) {
            when {
                line.startsWith("location: ") -> {
                    require(method == null) { "Method missing code range" }
                    require(location.isEmpty() || dex.isNotEmpty()) { "DEX location missing checksum" }
                    location = line.removePrefix("location: ")
                    require(location in expected && seen.add(location)) { "Unexpected or duplicate OAT DEX location" }
                    dex = ""
                }
                line.startsWith("checksum: ") -> {
                    val identity = requireNotNull(expected[location]) { "Checksum without DEX location" }
                    require(dex.isEmpty() && line.removePrefix("checksum: ").removePrefix("0x").toLong(16) == identity.second) {
                        "OAT/APK DEX location checksum mismatch"
                    }
                    dex = identity.first
                }
                methodLine.matches(line) -> {
                    require(dex.isNotEmpty() && method == null) { "Unverified DEX or incomplete method" }
                    method = methodLine.matchEntire(line)
                }
                line.startsWith("    CODE:") -> {
                    val declaration = requireNotNull(method) { "Code without method" }
                    val code = requireNotNull(codeLine.matchEntire(line)) { "Unsupported OAT code range" }
                    val address = code.groupValues[1].toLong(16)
                    val size = code.groupValues[2].toLong()
                    if (size > 0) {
                        require(address > 0 && size <= Long.MAX_VALUE - address)
                        val segment =
                            elf.segments.singleOrNull {
                                address >= it.address && address + size <= it.address + (it.end - it.start)
                            } ?: error("OAT method outside ELF load segment")
                        val start = segment.start + address - segment.address
                        val end = start + size
                        require(elf.sections.any { it.name == ".text" && start >= it.start && end <= it.end }) {
                            "OAT method outside stored .text section"
                        }
                        result += Method(start, end, dex, declaration.groupValues[1], declaration.groupValues[2].toInt())
                    } else {
                        require(address == 0L) { "Nonzero OAT code address without size" }
                    }
                    method = null
                }
                line == "OAT FILE STATS:" -> complete = true
            }
        }
        require(complete && method == null && dex.isNotEmpty() && seen == expected.keys) { "Incomplete OAT dump or DEX set" }
        // ART may deduplicate code. Retain all aliases, but reject inconsistent overlapping ranges.
        val sorted = result.distinct().sortedWith(compareBy<Method> { it.start }.thenBy { it.end })
        sorted.zipWithNext().forEach { (a, b) ->
            require(a.end <= b.start || (a.start == b.start && a.end == b.end)) { "Partially overlapping OAT method ranges" }
        }
        return sorted
    }

    fun collect(
        adb: AndroidFaultCollector.Device,
        output: Path,
        warnings: MutableList<String>,
    ) {
        val diagnostics = mutableListOf<Map<String, Any?>>()

        fun failure(
            stage: String,
            error: String,
            remote: String? = null,
        ) {
            diagnostics += mapOf("artifact" to remote, "stage" to stage, "error" to error)
            Json.write(output.resolve("oat-attribution-diagnostics.json"), diagnostics)
        }
        val abi = Json.readMap(output.resolve("capture_metadata.json"))["abi"]
        if (abi !in listOf("arm64-v8a", "x86", "x86_64")) {
            failure("abi_validation", "Unsupported ABI: $abi")
            warnings += "OAT method attribution is not yet validated for ABI $abi."
            return
        }
        val artifacts = Json.readMap(output.resolve("artifacts.json")).mapValues { output.resolve(it.value.toString()) }
        val manifest = mutableMapOf<String, Any?>()
        val tool =
            listOf("/apex/com.android.art/bin/oatdump", "/apex/com.android.runtime/bin/oatdump", "/system/bin/oatdump")
                .firstOrNull { adb.rootShell("test -x ${quote(it)}", check = false).exitCode == 0 }
        if (tool == null) {
            failure("tool_discovery", "Device oatdump is missing")
            warnings += "OAT method attribution unavailable: device oatdump is missing."
            return
        }
        for ((remote, local) in artifacts.filterKeys { it.endsWith(".odex") }) {
            var stage = "artifact_identity"
            try {
                val elf = AndroidBinary.elf(Files.readAllBytes(local))
                if (elf.sections.none { it.name == ".text" }) continue
                val apk = matchingApkForVdex(remote, artifacts.keys) ?: continue
                val apkFile = artifacts.getValue(apk)
                val odexHash = sha256(local)
                val apkHash = sha256(apkFile)
                val vdex = remote.removeSuffix(".odex") + ".vdex"
                val vdexHash = sha256(artifacts.getValue(vdex))

                fun verifyCurrent() {
                    for ((path, hash) in listOf(remote to odexHash, apk to apkHash, vdex to vdexHash)) {
                        require(adb.rootShell("sha256sum ${quote(path)}").stdout.substringBefore(' ') == hash) {
                            "Artifact changed while collecting OAT symbols: $path"
                        }
                    }
                }
                verifyCurrent()
                // Filter on-device: a full dump can contain gigabytes of verifier/disassembly text.
                // pipefail + completion marker reject tool failure, timeout and truncated output.
                // Fixed strings avoid pathological regex cost on very large verifier lines.
                // The host parser still requires exact line prefixes and validates the complete DEX set.
                val selection =
                    listOf("location: ", "checksum: ", "(dex_method_idx=", "    CODE:", "OAT FILE STATS:")
                        .joinToString(" ") { "-e ${quote(it)}" }
                val limit = "{ n += length(\$0) + 1; if (n > $MAX_DUMP) exit 1; print }"
                val command =
                    "set -o pipefail; timeout 120 ${quote(tool)} --oat-file=${quote(remote)} " +
                        "--dex-file=${quote(apk)} --no-disassemble --no-dump:vmap | grep -F $selection | awk ${quote(limit)}"
                stage = "oatdump_execution"
                val dump = adb.rootShell(command, timeout = Duration.ofSeconds(130))
                stage = "post_dump_identity"
                verifyCurrent()
                stage = "method_range_parsing"
                val parsed = parse(dump.stdout.lineSequence(), apk, ZipLayout.dexIdentities(apkFile), elf)
                val file = output.resolve("oatdump/${sha256(remote.toByteArray()).take(10)}.txt")
                Files.createDirectories(file.parent)
                Files.writeString(file, dump.stdout)
                manifest[remote] =
                    mapOf(
                        "file" to output.relativize(file).toString(),
                        "sha256" to sha256(file),
                        "apk" to apk,
                        "apk_sha256" to apkHash,
                        "odex_sha256" to odexHash,
                        "vdex" to vdex,
                        "vdex_sha256" to vdexHash,
                        "tool" to tool,
                        "methods" to parsed.size,
                    )
            } catch (error: Exception) {
                failure(stage, error.message ?: error.javaClass.simpleName, remote)
                warnings += "OAT method attribution unavailable for $remote at $stage: ${error.message}"
            }
        }
        Json.write(output.resolve("oatdump.json"), manifest)
    }

    fun load(
        output: Path,
        artifacts: Map<String, Path>,
        warnings: MutableList<String>,
    ): Map<String, List<Method>> {
        val manifest = output.resolve("oatdump.json")
        if (!Files.isRegularFile(manifest)) return emptyMap()
        val result = mutableMapOf<String, List<Method>>()
        val entries =
            try {
                Json.readMap(manifest)
            } catch (error: Exception) {
                warnings += "OAT method attribution ignored: invalid oatdump.json (${error.message})"
                return emptyMap()
            }
        for ((remote, value) in entries) {
            try {
                val item = value as Map<*, *>
                val odex = artifacts.getValue(remote)
                val apk = item["apk"].toString()
                require(matchingApkForVdex(remote, artifacts.keys) == apk)
                val apkFile = artifacts.getValue(apk)
                require(item["vdex"] == remote.removeSuffix(".odex") + ".vdex")
                require(sha256(artifacts.getValue(item["vdex"].toString())) == item["vdex_sha256"])
                val dump = output.resolve(item["file"].toString()).normalize()
                require(dump.startsWith(output.normalize()) && Files.size(dump) <= MAX_DUMP)
                require(sha256(dump) == item["sha256"] && sha256(odex) == item["odex_sha256"] && sha256(apkFile) == item["apk_sha256"]) {
                    "OAT attribution provenance mismatch"
                }
                result[remote] =
                    Files.newBufferedReader(dump).use {
                        parse(it.lineSequence(), apk, ZipLayout.dexIdentities(apkFile), AndroidBinary.elf(Files.readAllBytes(odex)))
                    }
            } catch (error: Exception) {
                warnings += "OAT method attribution ignored for $remote: ${error.message}"
            }
        }
        return result
    }

    fun at(
        methods: List<Method>,
        start: Long,
        end: Long,
    ): List<Method> {
        var low = 0
        var high = methods.size
        while (low < high) {
            val middle = (low + high) / 2
            if (methods[middle].end <= start) low = middle + 1 else high = middle
        }
        return methods
            .asSequence()
            .drop(low)
            .takeWhile { it.start < end }
            .toList()
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
