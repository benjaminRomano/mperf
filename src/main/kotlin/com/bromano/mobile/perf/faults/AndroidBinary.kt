package com.bromano.mobile.perf.faults

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

internal object AndroidBinary {
    data class Region(
        val start: Long,
        val end: Long,
        val name: String,
        val address: Long = 0,
    )

    data class Elf(
        val segments: List<Region> = emptyList(),
        val sections: List<Region> = emptyList(),
    )

    private class Bytes(
        val data: ByteArray,
    ) {
        private val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        fun bounds(
            at: Long,
            length: Long,
        ) {
            require(at >= 0 && length >= 0 && at <= data.size.toLong() - length) { "Binary range outside file" }
        }

        fun u16(at: Long): Int {
            bounds(at, 2)
            return buffer.getShort(at.toInt()).toInt() and 65535
        }

        fun u32(at: Long): Long {
            bounds(at, 4)
            return buffer.getInt(at.toInt()).toLong() and 0xffffffffL
        }

        fun u64(at: Long): Long {
            bounds(at, 8)
            return buffer.getLong(at.toInt()).also { require(it >= 0) }
        }
    }

    fun elf(data: ByteArray): Elf =
        runCatching {
            if (data.size < 64 || !data.copyOfRange(0, 4).contentEquals(byteArrayOf(127, 69, 76, 70)) || data[5] != 1.toByte()) return Elf()
            val b = Bytes(data)
            val wide = data[4] == 2.toByte()
            require(wide || data[4] == 1.toByte())
            val ph = if (wide) b.u64(32) else b.u32(28)
            val sh = if (wide) b.u64(40) else b.u32(32)
            val sizes = if (wide) 54L else 42L
            val phSize = b.u16(sizes)
            val phCount = b.u16(sizes + 2)
            val shSize = b.u16(sizes + 4)
            val shCount = b.u16(sizes + 6)
            val namesIndex = b.u16(sizes + 8)
            require(phCount == 0 || phSize >= if (wide) 56 else 32)
            require(shCount == 0 || shSize >= if (wide) 64 else 40)
            b.bounds(ph, phCount.toLong() * phSize)
            b.bounds(sh, shCount.toLong() * shSize)
            val segments =
                (0 until phCount).mapNotNull { i ->
                    val at = ph + i * phSize
                    val offset = if (wide) b.u64(at + 8) else b.u32(at + 4)
                    val address = if (wide) b.u64(at + 16) else b.u32(at + 8)
                    val size = if (wide) b.u64(at + 32) else b.u32(at + 16)
                    if (b.u32(at) == 1L &&
                        size > 0 &&
                        offset <= data.size.toLong() - size
                    ) {
                        Region(offset, offset + size, "PT_LOAD", address)
                    } else {
                        null
                    }
                }
            if (shCount == 0 || namesIndex >= shCount) return Elf(segments)

            fun field(
                at: Long,
                index: Int,
            ): Long =
                if (index <
                    2
                ) {
                    b.u32(at + index * 4)
                } else if (wide) {
                    b.u64(at + 8 + (index - 2) * 8)
                } else {
                    b.u32(at + index * 4)
                }
            val namesHeader = sh + namesIndex * shSize
            val namesAt = field(namesHeader, 4)
            val namesSize = field(namesHeader, 5)
            b.bounds(namesAt, namesSize)
            val sections =
                (0 until shCount).mapNotNull { i ->
                    val at = sh + i * shSize
                    val nameIndex = field(at, 0)
                    val kind = field(at, 1)
                    val flags = field(at, 2)
                    val address = field(at, 3)
                    val offset = field(at, 4)
                    val size = field(at, 5)
                    if ((flags and 2) == 0L ||
                        kind == 8L ||
                        size == 0L ||
                        offset > data.size.toLong() - size ||
                        nameIndex >= namesSize
                    ) {
                        return@mapNotNull null
                    }
                    val start = (namesAt + nameIndex).toInt()
                    val stop = (start until (namesAt + namesSize).toInt()).firstOrNull { data[it] == 0.toByte() } ?: return@mapNotNull null
                    Region(offset, offset + size, String(data, start, stop - start, Charsets.UTF_8), address)
                }
            Elf(segments, sections)
        }.getOrDefault(Elf())

    fun dexMethods(data: ByteArray): List<Region> =
        runCatching {
            require(
                data.size >= 112 &&
                    String(data, 0, 4, Charsets.US_ASCII) == "dex\n" &&
                    String(data, 4, 4, Charsets.US_ASCII) in listOf("035\u0000", "037\u0000", "038\u0000", "039\u0000", "040\u0000"),
            )
            val b = Bytes(data)
            require(b.u32(32) == data.size.toLong() && b.u32(36) == 112L && b.u32(40) == 0x12345678L)
            val dataSize = b.u32(104)
            val dataStart = b.u32(108)
            val dataEnd = dataStart + dataSize
            require(dataSize > 0 && dataStart >= 112 && dataStart % 4 == 0L && dataEnd <= data.size)
            val tables = mutableListOf<Pair<Long, Long>>()

            fun table(
                at: Long,
                width: Int,
            ): Pair<Long, Long> {
                val count = b.u32(at)
                val offset = b.u32(at + 4)
                if (count == 0L) {
                    require(offset == 0L)
                    return 0L to 0L
                }
                val end = offset + count * width
                require(
                    count <= 2_000_000 &&
                        offset >= 112 &&
                        offset % 4 == 0L &&
                        end <= dataStart &&
                        tables.none { offset < it.second && it.first < end },
                ) { "Invalid DEX table" }
                tables += offset to end
                return count to offset
            }

            fun uleb(at: Long): Pair<Long, Long> {
                require(at in dataStart until dataEnd)
                var cursor = at
                var value = 0L
                for (shift in 0..28 step 7) {
                    require(cursor < dataEnd)
                    val byte = data[cursor++.toInt()].toInt() and 255
                    require(shift != 28 || byte <= 15)
                    value = value or ((byte and 127).toLong() shl shift)
                    if ((byte and 128) == 0) return value to cursor
                }
                error("Invalid ULEB128")
            }
            val (stringCount, stringsAt) = table(56, 4)
            val (typeCount, typesAt) = table(64, 4)
            val (protoCount, _) = table(72, 12)
            val (fieldCount, fieldsAt) = table(80, 8)
            val (methodCount, methodsAt) = table(88, 8)
            val (classCount, classesAt) = table(96, 32)
            val strings = mutableMapOf<Long, String>()

            fun string(index: Long): String =
                strings.getOrPut(index) {
                    require(index < stringCount)
                    val (length, start) = uleb(b.u32(stringsAt + index * 4))
                    var cursor = start.toInt()
                    val chars = StringBuilder()

                    fun next(): Int {
                        require(cursor < dataEnd)
                        return data[cursor++].toInt() and 255
                    }
                    while (true) {
                        val first = next()
                        if (first == 0) break
                        val unit =
                            when {
                                first in 1..127 -> first
                                first in 192..223 -> {
                                    val second = next()
                                    require((second and 192) == 128)
                                    val value = ((first and 31) shl 6) or (second and 63)
                                    require(value == 0 || value >= 128)
                                    value
                                }
                                first in 224..239 -> {
                                    val second = next()
                                    val third = next()
                                    require((second and 192) == 128 && (third and 192) == 128)
                                    val value = ((first and 15) shl 12) or ((second and 63) shl 6) or (third and 63)
                                    require(value >= 2048)
                                    value
                                }
                                else -> error("Invalid modified UTF-8")
                            }
                        chars.append(unit.toChar())
                    }
                    require(chars.length.toLong() == length)
                    chars.toString()
                }
            val result = mutableListOf<Region>()
            for (i in 0 until classCount) {
                val owner = b.u32(classesAt + i * 32)
                require(owner < typeCount)
                var cursor = b.u32(classesAt + i * 32 + 24)
                if (cursor == 0L) continue

                fun read(): Long {
                    val pair = uleb(cursor)
                    cursor = pair.second
                    return pair.first
                }
                val counts = List(4) { read() }
                require(counts.sum() <= data.size)
                for (count in counts.take(2)) {
                    var field = 0L
                    for (ordinal in 0 until count) {
                        val delta = read()
                        field += delta
                        read()
                        require(field < fieldCount && (ordinal == 0L || delta > 0) && b.u16(fieldsAt + field * 8).toLong() == owner)
                    }
                }
                for (count in counts.drop(2)) {
                    var method = 0L
                    for (ordinal in 0 until count) {
                        val delta = read()
                        method += delta
                        read()
                        val code = read()
                        require(method < methodCount && (ordinal == 0L || delta > 0))
                        val at = methodsAt + method * 8
                        val klass = b.u16(at).toLong()
                        val proto = b.u16(at + 2).toLong()
                        val name = b.u32(at + 4)
                        require(klass == owner && proto < protoCount && name < stringCount)
                        if (code == 0L) continue
                        val instructions = code + 16
                        require(code >= dataStart && instructions <= dataEnd && code % 4 == 0L)
                        val end = instructions + 2 * b.u32(code + 12)
                        require(end <= dataEnd)
                        if (end > instructions) {
                            result +=
                                Region(
                                    instructions,
                                    end,
                                    string(b.u32(typesAt + klass * 4)).removePrefix("L").removeSuffix(";").replace('/', '.') + "." +
                                        string(name),
                                )
                        }
                    }
                }
            }
            result.sortedBy(Region::start)
        }.getOrDefault(emptyList())

    fun enrich(
        output: Path,
        artifacts: Map<String, Path>,
        pageSize: Long,
    ): List<String> {
        val warnings = mutableListOf<String>()
        val oat = AndroidOat.load(output, artifacts, warnings)
        val sections = mutableMapOf<String, List<Region>>()
        val methods = mutableMapOf<String, MutableList<Region>>()
        val dex = mutableMapOf<String, MutableList<Region>>()
        artifacts.forEach { (remote, local) ->
            val data = Files.readAllBytes(local)
            val elf = elf(data)
            if (elf.sections.isNotEmpty()) sections[remote] = elf.sections
            val entries =
                when {
                    remote.endsWith(".apk") -> ZipLayout.read(local).filter { it.name.endsWith(".dex") && it.compression == "stored" }
                    remote.endsWith(".vdex") -> {
                        val apk = matchingApkForVdex(remote, artifacts.keys)?.let(artifacts::get)
                        Vdex
                            .read(local, apk?.let(ZipLayout::dexIdentities))
                            ?.takeIf { it.identitiesVerified }
                            ?.dexRanges
                            .orEmpty()
                    }
                    else -> emptyList()
                }
            entries.forEach { entry ->
                dex.getOrPut(remote, ::mutableListOf).add(Region(entry.dataOffset, entry.dataEnd, entry.name))
                if (entry.uncompressedSize <= 256 * 1024 * 1024L && entry.dataEnd <= data.size) {
                    methods.getOrPut(remote, ::mutableListOf).addAll(
                        dexMethods(data.copyOfRange(entry.dataOffset.toInt(), entry.dataEnd.toInt())).map {
                            Region(entry.dataOffset + it.start, entry.dataOffset + it.end, "${entry.name}: ${it.name}")
                        },
                    )
                }
            }
        }
        val sortedMethods = methods.mapValues { (_, value) -> value.sortedBy(Region::start) }
        val details =
            Csv.read(output.resolve("all_faults.csv")).mapNotNull { row ->
                val offset = row["offset"]?.toLongOrNull() ?: return@mapNotNull null
                val remote = row["file_name"]
                val section = sections[remote]?.find { offset in it.start until it.end }?.name.orEmpty()
                val payload = dex[remote]?.find { offset in it.start until it.end }?.name.orEmpty()
                val start = offset / pageSize * pageSize
                val ranges = sortedMethods[remote].orEmpty()
                var low = 0
                var high = ranges.size
                while (low < high) {
                    val middle = (low + high) / 2
                    if (ranges[middle].start < start) low = middle + 1 else high = middle
                }
                while (low > 0 && ranges[low - 1].end > start) low--
                val labels =
                    ranges
                        .asSequence()
                        .drop(low)
                        .takeWhile {
                            it.start < start + pageSize
                        }.filter { it.end > start }
                        .map(Region::name)
                        .distinct()
                        .sorted()
                        .toList()
                val compiled = AndroidOat.at(oat[remote].orEmpty(), offset, offset + 1)
                val compiledPage = AndroidOat.at(oat[remote].orEmpty(), start, start + pageSize)
                if (section.isBlank() && payload.isBlank() && labels.isEmpty() && compiled.isEmpty()) {
                    null
                } else {
                    mapOf(
                        "sequence" to row["sequence"]?.toLong(),
                        "section" to section,
                        "dex" to (compiled.map { it.dex }.distinct().singleOrNull() ?: payload),
                        "page_methods" to labels,
                        "aot_methods" to compiled.map { "${it.dex} #${it.index}: ${it.name}" },
                        "aot_page_methods" to compiledPage.map { "${it.dex} #${it.index}: ${it.name}" },
                    )
                }
            }
        Json.write(output.resolve("fault_details.json"), details)
        return warnings
    }

    internal fun findSymbolizer(
        preferred: Path?,
        environment: Map<String, String> = System.getenv(),
        userDirectory: Path = Path.of(System.getProperty("user.home")),
    ): Path? {
        preferred?.takeIf(Files::isExecutable)?.let { return it }
        val ndks = mutableListOf<Path>()
        listOf("ANDROID_NDK_HOME", "ANDROID_NDK_ROOT").mapNotNull(environment::get).map(Path::of).forEach(ndks::add)
        val sdks =
            listOfNotNull(environment["ANDROID_HOME"], environment["ANDROID_SDK_ROOT"]).map(Path::of) +
                listOf(userDirectory.resolve("Library/Android/sdk"), userDirectory.resolve("Android/Sdk"))
        for (sdk in sdks.distinct()) {
            val root = sdk.resolve("ndk")
            if (Files.isDirectory(root)) {
                Files.list(root).use { paths ->
                    paths.filter(Files::isDirectory).sorted(Comparator.reverseOrder()).forEach(ndks::add)
                }
            }
        }
        for (ndk in ndks.distinct()) {
            val prebuilt = ndk.resolve("toolchains/llvm/prebuilt")
            if (Files.isDirectory(prebuilt)) {
                Files
                    .list(prebuilt)
                    .use { paths ->
                        paths
                            .map { it.resolve("bin/llvm-symbolizer") }
                            .filter(Files::isExecutable)
                            .findFirst()
                            .orElse(null)
                    }?.let { return it }
            }
        }
        return environment["PATH"]
            .orEmpty()
            .split(java.io.File.pathSeparator)
            .asSequence()
            .filter(String::isNotBlank)
            .map { Path.of(it, "llvm-symbolizer") }
            .firstOrNull(Files::isExecutable)
    }

    fun symbolize(
        output: Path,
        artifacts: Map<String, Path>,
        preferredExecutable: Path?,
    ): List<String> {
        val warnings = mutableListOf<String>()
        val executable = findSymbolizer(preferredExecutable)
        val path = output.resolve("resolved_fault_callchains.csv")
        if (executable == null || !Files.isExecutable(executable) || !Files.exists(path)) return warnings
        val rows = Csv.read(path).map { it.toMutableMap() }
        rows
            .filter { it["frame_kind"] == "user" && it["file_name"] in artifacts && it["file_offset"].orEmpty().isNotBlank() }
            .groupBy { it.getValue("file_name") }
            .forEach { (remote, frames) ->
                try {
                    val local = artifacts.getValue(remote)
                    val segments = elf(Files.readAllBytes(local)).segments
                    val addresses = linkedMapOf<Long, Long>()
                    frames.forEach { row ->
                        val offset = row.getValue("file_offset").toLong()
                        segments.find { offset in it.start until it.end }?.let { addresses[offset] = it.address + offset - it.start }
                    }
                    if (addresses.isEmpty()) return@forEach
                    val process =
                        ProcessBuilder(
                            executable.toString(),
                            "--obj",
                            local.toString(),
                            "--output-style=JSON",
                            "--no-inlines",
                            "--demangle",
                        ).start()
                    var stdout = ""
                    var stderr = ""
                    val reader = thread(isDaemon = true) { stdout = process.inputStream.bufferedReader().readText() }
                    val errors = thread(isDaemon = true) { stderr = process.errorStream.bufferedReader().readText() }
                    try {
                        process.outputStream.bufferedWriter().use { writer ->
                            addresses.values.forEach { writer.write("0x${it.toString(16)}\n") }
                        }
                        check(process.waitFor(60, TimeUnit.SECONDS)) { "Symbolizer timed out" }
                        reader.join(5_000)
                        errors.join(5_000)
                        check(process.exitValue() == 0) { "Symbolizer failed: $stderr" }
                        val values =
                            stdout
                                .lineSequence()
                                .filter(String::isNotBlank)
                                .map { Json.mapper.readTree(it) }
                                .toList()
                        require(values.size == addresses.size) { "Symbolizer output count differs from submitted addresses" }
                        val labels =
                            addresses.keys
                                .zip(values)
                                .mapNotNull { (offset, value) ->
                                    value
                                        .path(
                                            "Symbol",
                                        ).firstOrNull()
                                        ?.path("FunctionName")
                                        ?.asText()
                                        ?.takeUnless { it.isBlank() || it == "??" }
                                        ?.let {
                                            offset to
                                                it
                                        }
                                }.toMap()
                        frames.forEach { row -> labels[row.getValue("file_offset").toLong()]?.let { row["label"] = it } }
                    } finally {
                        if (process.isAlive) process.destroyForcibly()
                    }
                } catch (error: Exception) {
                    warnings += "Native symbols unavailable for $remote; retaining file/offset frames: ${error.message}"
                }
            }
        if (rows.isNotEmpty()) Csv.write(path, rows.first().keys.toList(), rows)
        return warnings
    }
}
