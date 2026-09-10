package com.bromano.mobile.perf.faults

import com.bromano.mobile.perf.utils.sha256
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Report-time names are candidates. Physical DEX and page contents remain separate evidence. */
internal object AndroidReportAttribution {
    data class Options(
        val symbols: Path? = null,
        val mapping: Path? = null,
        val apkSha256: String? = null,
        val startup: Path? = null,
        val baseline: Path? = null,
    ) {
        fun hashes(): Map<String, String> {
            val files = listOfNotNull(mapping, startup, baseline).toMutableList()
            symbols?.let { root -> Files.walk(root).use { paths -> files += paths.filter(Files::isRegularFile).toList() } }
            return files.sorted().associate { it.toAbsolutePath().toString() to sha256(it) }
        }
    }

    internal class Mapping(
        text: String,
    ) {
        private val classes = mutableMapOf<String, MutableSet<String>>()
        private val methods = mutableMapOf<String, MutableSet<String>>()

        init {
            var original = ""
            var obfuscated = ""
            text.lineSequence().forEach { line ->
                if (!line.startsWith(" ") && " -> " in line && line.endsWith(":")) {
                    original = line.substringBefore(" -> ")
                    obfuscated = line.substringAfter(" -> ").removeSuffix(":")
                    classes.getOrPut(obfuscated) { linkedSetOf() } += original
                } else if (line.startsWith(" ") && "(" in line && " -> " in line && obfuscated.isNotEmpty()) {
                    val method = line.substringBefore('(').trim().substringAfterLast(' ')
                    val qualified = if ('.' in method) method else "$original.$method"
                    methods.getOrPut("$obfuscated.${line.substringAfterLast(" -> ")}") { linkedSetOf() } += qualified
                }
            }
        }

        fun origins(label: String): List<String> {
            val name = label.substringBefore('(').substringAfterLast(' ')
            return methods[name]?.toList() ?: classes[name.substringBeforeLast('.', "")]
                ?.map {
                    "$it.${name.substringAfterLast('.')}"
                }.orEmpty()
        }
    }

    internal class Profile(
        text: String,
    ) {
        private val roots =
            text
                .lineSequence()
                .map { it.substringBefore('#').trim() }
                .filter(String::isNotEmpty)
                .toSet()

        init {
            require(roots.all { Regex("[HSP]*L[^;]+;(?:->[^()]+\\([^)]*\\).+)?").matches(it) }) {
                "Profiles must be consumed original-name text rules; binary profiles and unsupported patterns cannot be audited"
            }
        }

        fun audit(origin: String): String {
            val owner = origin.substringBeforeLast('.', "")
            val method = origin.substringAfterLast('.')
            if (owner.isEmpty()) return "unknown origin"
            val descriptor = "L${owner.replace('.', '/')};"
            val classRoot = roots.any { it.trimStart('H', 'S', 'P') == descriptor }
            val methodRoots = roots.map { it.trimStart('H', 'S', 'P') }.filter { it.startsWith("$descriptor->$method(") }
            return when {
                methodRoots.isNotEmpty() -> "method root candidate (signature unavailable): ${methodRoots.joinToString()}"
                method == "<clinit>" && classRoot -> "class root only; no explicit class initializer method root"
                classRoot -> "class root only; no explicit method root"
                else -> "absent from supplied consumed profile"
            }
        }
    }

    fun apply(
        capture: Path,
        run: MutableMap<String, Any?>,
        options: Options,
    ) {
        @Suppress("UNCHECKED_CAST")
        val metadata = run["provenance"] as Map<String, Any?>
        val artifacts =
            if (Files.isRegularFile(capture.resolve("artifacts.json"))) {
                Json.readMap(capture.resolve("artifacts.json")).mapValues { capture.resolve(it.value.toString()) }
            } else {
                emptyMap()
            }
        val usesProfiles = options.mapping != null || options.startup != null || options.baseline != null
        if (usesProfiles) {
            require(options.apkSha256?.matches(Regex("[0-9a-fA-F]{64}")) == true) { "Mapping/profile inputs require --mapping-apk-sha256" }
            require(artifacts.filterKeys { it.endsWith(".apk") }.values.any { sha256(it).equals(options.apkSha256, true) }) {
                "Mapping/profile build binding does not match a captured APK"
            }
        }
        val mapping = options.mapping?.let { Mapping(Files.readString(it)) }
        val profiles =
            listOfNotNull(
                options.startup?.let { "startup" to Profile(Files.readString(it)) },
                options.baseline?.let { "baseline" to Profile(Files.readString(it)) },
            ).toMap()

        @Suppress("UNCHECKED_CAST")
        val events = run["events"] as List<Map<String, Any?>>
        val symbolizer =
            if (options.symbols == null) {
                null
            } else {
                requireNotNull(
                    AndroidBinary.findSymbolizer(metadata["llvm_symbolizer"]?.toString()?.let(Path::of)),
                ) { "llvm-symbolizer is required for --symbol-dir" }
            }
        val symbolizerHash = symbolizer?.let(::sha256)
        val symbols = resolveSymbols(artifacts, events, options, metadata)
        require(symbolizer == null || sha256(symbolizer) == symbolizerHash) { "Symbolizer changed during rendering" }
        val dexMethods = physicalDexIndex(artifacts)
        run["events"] =
            events.map { event ->
                @Suppress("UNCHECKED_CAST")
                val frames =
                    (event["stack"] as? List<Map<String, Any?>>).orEmpty().map { frame ->
                        val originalLabel = frame["label"].toString()
                        val methodName = originalLabel.substringBefore('(').substringAfterLast(' ')
                        val physicalCandidates = dexMethods[methodName].orEmpty().sorted()
                        val origins =
                            mapping?.origins(originalLabel).orEmpty().ifEmpty {
                                if (physicalCandidates.isNotEmpty()) listOf(methodName) else emptyList()
                            }
                        val native = symbols[frame["file"].toString() to addressKey(frame)]
                        val physicalDex =
                            Regex("(?:!|/)(classes(?:\\d+)?\\.dex)(?:$|[^A-Za-z0-9.])")
                                .find(frame["file"].toString())
                                ?.groupValues
                                ?.get(1) ?: physicalCandidates.singleOrNull()?.substringAfterLast('!')
                        frame +
                            mapOf(
                                "label" to (native ?: origins.joinToString(" | ").ifBlank { originalLabel }),
                                "recordedLabel" to originalLabel,
                                "origins" to origins,
                                "originStatus" to
                                    when {
                                        origins.size > 1 -> "merged or ambiguous"
                                        origins.size == 1 -> "mapping candidate"
                                        else -> "unresolved/original"
                                    },
                                "physicalDex" to physicalDex,
                                "physicalDexCandidates" to physicalCandidates,
                                "physicalDexEvidence" to
                                    if (physicalCandidates.size >
                                        1
                                    ) {
                                        "ambiguous across captured DEX files"
                                    } else {
                                        "captured DEX method definition or explicit frame file"
                                    },
                                "unresolved" to if (native != null) false else frame["unresolved"],
                                "profileAudit" to profiles.mapValues { (_, profile) -> origins.associateWith(profile::audit) },
                            )
                    }
                val content = (event["detail"] as? Map<*, *>).orEmpty()
                event +
                    mapOf(
                        "stack" to frames,
                        "callerDex" to frames.mapNotNull { it["physicalDex"] }.distinct(),
                        "detail" to content +
                            mapOf(
                                "Caller physical DEX (distinct from faulted DEX)" to
                                    frames
                                        .mapNotNull { it["physicalDex"] }
                                        .distinct()
                                        .joinToString()
                                        .ifBlank { "unknown or ambiguous" },
                            ),
                    )
            }
        run["attributionInputs"] =
            mapOf(
                "sha256" to options.hashes(),
                "apk_sha256" to options.apkSha256,
                "symbolizer" to symbolizer?.toString(),
                "symbolizer_sha256" to symbolizerHash,
                "profile_contract" to
                    "User-supplied consumed profiles, original names; build-bound by APK SHA-256. Candidate roots, not proof of execution.",
            )
    }

    private fun physicalDexIndex(artifacts: Map<String, Path>): Map<String, Set<String>> {
        val index = mutableMapOf<String, MutableSet<String>>()
        for ((remote, local) in artifacts.filterKeys { it.endsWith(".apk") }) {
            java.util.zip.ZipFile(local.toFile()).use { archive ->
                for (entry in archive.entries().asSequence().filter { Regex("classes(?:\\d+)?\\.dex").matches(it.name) }) {
                    if (entry.size !in 1..(256L * 1024 * 1024)) continue
                    val data = archive.getInputStream(entry).use { it.readNBytes(256 * 1024 * 1024 + 1) }
                    if (data.size.toLong() != entry.size) continue
                    for (method in AndroidBinary.dexMethods(data)) {
                        index.getOrPut(method.name) { linkedSetOf() } += "$remote!${entry.name}"
                    }
                }
            }
        }
        return index
    }

    private fun addressKey(frame: Map<*, *>): String =
        frame["fileOffset"]?.toString()?.takeIf(String::isNotBlank)?.let { "offset:$it" } ?: "vaddr:${frame["ip"]}"

    private fun resolveSymbols(
        artifacts: Map<String, Path>,
        events: List<Map<String, Any?>>,
        options: Options,
        metadata: Map<String, Any?>,
    ): Map<Pair<String, String>, String> {
        if (options.symbols == null) return emptyMap()
        val tool =
            requireNotNull(AndroidBinary.findSymbolizer(metadata["llvm_symbolizer"]?.toString()?.let(Path::of))) {
                "llvm-symbolizer is required for --symbol-dir"
            }
        val candidates =
            Files
                .walk(options.symbols)
                .use { files -> files.filter(Files::isRegularFile).sorted().toList() }
                .mapNotNull { file ->
                    AndroidElfIdentity.read(Files.readAllBytes(file))?.let { it to file }
                }.groupBy({ it.first }, { it.second })
        val result = mutableMapOf<Pair<String, String>, String>()
        val frames = events.flatMap { (it["stack"] as? List<*>).orEmpty() }.filterIsInstance<Map<*, *>>()
        for ((remote, group) in frames.groupBy { it["file"].toString() }) {
            val data =
                if ('!' in remote) {
                    val apk = artifacts[remote.substringBefore('!')] ?: continue
                    java.util.zip.ZipFile(apk.toFile()).use { archive ->
                        val entry = archive.getEntry(remote.substringAfter('!').removePrefix("/"))
                        if (entry == null || entry.size !in 1..(256L * 1024 * 1024)) {
                            null
                        } else {
                            archive.getInputStream(entry).use { it.readNBytes(256 * 1024 * 1024 + 1) }
                        }
                    } ?: continue
                } else {
                    Files.readAllBytes(artifacts[remote] ?: continue)
                }
            val identity = AndroidElfIdentity.read(data) ?: continue
            val matches = candidates[identity].orEmpty()
            if (matches.isEmpty()) continue
            // Multiple copies with identical bytes are harmless; differing same-ID files need an explicit choice.
            require(matches.map(::sha256).distinct().size == 1) { "Ambiguous debug ELF files for $remote (${identity.buildId})" }
            val local = matches.first()
            val segments = AndroidBinary.elf(data).segments
            val addresses =
                group
                    .mapNotNull { frame ->
                        val offset = frame["fileOffset"]?.toString()?.toLongOrNull()
                        val address =
                            if (offset != null) {
                                segments.singleOrNull { offset in it.start until it.end }?.let { it.address + offset - it.start }
                            } else {
                                frame["ip"]?.toString()?.removePrefix("0x")?.toLongOrNull(16)?.takeIf { address ->
                                    segments.any { address >= it.address && address - it.address < it.end - it.start }
                                }
                            }
                        address?.let { addressKey(frame) to it }
                    }.toMap()
            if (addresses.isEmpty()) continue
            val process =
                ProcessBuilder(
                    tool.toString(),
                    "--obj",
                    local.toString(),
                    "--output-style=JSON",
                    "--no-inlines",
                    "--demangle",
                ).start()
            var stdout = ""
            var stderr = ""
            val reader = thread(isDaemon = true) { stdout = process.inputStream.bufferedReader().use { it.readText() } }
            val errors = thread(isDaemon = true) { stderr = process.errorStream.bufferedReader().use { it.readText() } }
            try {
                process.outputStream.bufferedWriter().use { writer -> addresses.values.forEach { writer.write("0x${it.toString(16)}\n") } }
                require(process.waitFor(60, TimeUnit.SECONDS)) { "Symbolizer timed out" }
                reader.join(5000)
                errors.join(5000)
                require(!reader.isAlive && !errors.isAlive && process.exitValue() == 0) { "Symbolizer failed: $stderr" }
                val values =
                    stdout
                        .lineSequence()
                        .filter(String::isNotBlank)
                        .map { Json.mapper.readTree(it) }
                        .toList()
                require(values.size == addresses.size) { "Symbolizer output count mismatch" }
                addresses.keys.zip(values).forEach { (offset, value) ->
                    value.path("Symbol").firstOrNull()?.path("FunctionName")?.asText()?.takeUnless { it.isBlank() || it == "??" }?.let {
                        result[remote to offset] = it
                    }
                }
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
        }
        return result
    }
}
