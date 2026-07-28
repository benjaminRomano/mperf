package com.bromano.mobile.perf.faults

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path

internal data class ArchiveEntry(
    val name: String,
    val headerOffset: Long,
    val dataOffset: Long,
    val dataEnd: Long,
    val compressedSize: Long,
    val uncompressedSize: Long,
    val compression: String,
    val crc32: Long,
)

internal object ZipLayout {
    fun read(path: Path): List<ArchiveEntry> {
        val data = Files.readAllBytes(path)
        val eocd = findSignatureBackwards(data, 0x06054b50, maxOf(0, data.size - 65_557))
        require(eocd >= 0 && eocd + 22 <= data.size) { "Missing ZIP end record in $path" }
        val entries = u16(data, eocd + 10)
        val centralOffset = u32(data, eocd + 16).toInt()
        var cursor = centralOffset
        return buildList {
            repeat(entries) {
                require(u32(data, cursor) == 0x02014b50L) {
                    "Invalid ZIP central directory in $path at $cursor"
                }
                val flags = u16(data, cursor + 8)
                val method = u16(data, cursor + 10)
                val crc = u32(data, cursor + 16)
                val compressed = u32(data, cursor + 20)
                val uncompressed = u32(data, cursor + 24)
                val nameLength = u16(data, cursor + 28)
                val extraLength = u16(data, cursor + 30)
                val commentLength = u16(data, cursor + 32)
                val localOffset = u32(data, cursor + 42).toInt()
                require(localOffset + 30 <= data.size && u32(data, localOffset) == 0x04034b50L) {
                    "Invalid ZIP local header in $path at $localOffset"
                }
                val localNameLength = u16(data, localOffset + 26)
                val localExtraLength = u16(data, localOffset + 28)
                val dataOffset = localOffset.toLong() + 30 + localNameLength + localExtraLength
                val charset = if (flags and 0x800 != 0) Charsets.UTF_8 else Charset.forName("CP437")
                val name = data.copyOfRange(cursor + 46, cursor + 46 + nameLength).toString(charset)
                add(
                    ArchiveEntry(
                        name = name,
                        headerOffset = localOffset.toLong(),
                        dataOffset = dataOffset,
                        dataEnd = dataOffset + compressed,
                        compressedSize = compressed,
                        uncompressedSize = uncompressed,
                        compression = if (method == 0) "stored" else "method-$method",
                        crc32 = crc,
                    ),
                )
                cursor += 46 + nameLength + extraLength + commentLength
            }
        }.sortedBy { it.dataOffset }
    }

    fun dexIdentities(path: Path): List<Pair<String, Long>> =
        read(path)
            .filter { Regex("classes(?:\\d+)?\\.dex").matches(it.name) }
            .sortedBy { if (it.name == "classes.dex") 1 else it.name.substring(7, it.name.length - 4).toInt() }
            .map { it.name to it.crc32 }

    private fun findSignatureBackwards(
        data: ByteArray,
        signature: Int,
        lowerBound: Int,
    ): Int {
        for (index in data.size - 4 downTo lowerBound) {
            if (u32(data, index) == signature.toLong()) return index
        }
        return -1
    }
}

internal data class VdexAnalysis(
    val version: String,
    val checksums: List<Long>,
    val dexRanges: List<ArchiveEntry>,
    val identitiesVerified: Boolean,
)

internal object Vdex {
    fun read(
        path: Path,
        apkDex: List<Pair<String, Long>>?,
    ): VdexAnalysis? {
        val data = Files.readAllBytes(path)
        if (data.size < 12 || String(data, 0, 4, Charsets.US_ASCII) != "vdex") return null
        return when (String(data, 4, 4, Charsets.US_ASCII)) {
            "021\u0000" -> read021(data, apkDex)
            "027\u0000" -> read027(data, apkDex)
            else -> null
        }
    }

    private fun read021(
        data: ByteArray,
        apkDex: List<Pair<String, Long>>?,
    ): VdexAnalysis? {
        if (data.size < 28 || String(data, 8, 4, Charsets.US_ASCII) != "002\u0000") return null
        val count = u32(data, 12).toInt()
        if (count !in 1..10_000 || 28 + count * 4 + 12 > data.size) return null
        val checksums = (0 until count).map { u32(data, 28 + it * 4) }
        val names = verifiedNames(checksums, apkDex)
        val section = 28 + count * 4
        val dexSize = u32(data, section).toInt()
        val sharedSize = u32(data, section + 4).toInt()
        val begin = section + 12
        val end = begin + dexSize
        if (end > data.size || end + sharedSize > data.size) return null
        val ranges = dexRanges(data, begin, end, count, names, 4, "vdex-021")
        if (dexSize > 0 && ranges.size != count) return null
        return VdexAnalysis("021/002", checksums, ranges, names.size == count)
    }

    private fun read027(
        data: ByteArray,
        apkDex: List<Pair<String, Long>>?,
    ): VdexAnalysis? {
        val count = u32(data, 8).toInt()
        if (count !in 3..64 || 12 + count * 12 > data.size) return null
        val sections = mutableMapOf<Int, Pair<Int, Int>>()
        val occupied = mutableListOf<IntRange>()
        repeat(count) { index ->
            val at = 12 + index * 12
            val kind = u32(data, at).toInt()
            val offset = u32(data, at + 4).toInt()
            val size = u32(data, at + 8).toInt()
            if (kind in sections || offset < 0 || size < 0 || offset > data.size - size) return null
            if (size > 0 && offset < 12 + count * 12) return null
            sections[kind] = offset to size
            if (size > 0) occupied += offset until offset + size
        }
        val sorted = occupied.sortedBy { it.first }
        if (sorted.zipWithNext().any { (left, right) -> left.last >= right.first }) return null
        val checksumSection = sections[0] ?: return null
        val dexSection = sections[1] ?: return null
        if (sections[2] == null || checksumSection.second % 4 != 0) return null
        val dexCount = checksumSection.second / 4
        val checksums = (0 until dexCount).map { u32(data, checksumSection.first + it * 4) }
        val names = verifiedNames(checksums, apkDex)
        val ranges =
            if (dexSection.second == 0) {
                emptyList()
            } else {
                dexRanges(
                    data,
                    dexSection.first,
                    dexSection.first + dexSection.second,
                    dexCount,
                    names,
                    0,
                    "vdex-027",
                )
            }
        if (dexSection.second > 0 && ranges.size != dexCount) return null
        return VdexAnalysis("027", checksums, ranges, dexCount > 0 && names.size == dexCount)
    }

    private fun verifiedNames(
        checksums: List<Long>,
        apkDex: List<Pair<String, Long>>?,
    ): List<String> =
        apkDex
            ?.takeIf { it.map(Pair<String, Long>::second) == checksums }
            ?.map(Pair<String, Long>::first)
            .orEmpty()

    private fun dexRanges(
        data: ByteArray,
        begin: Int,
        end: Int,
        count: Int,
        names: List<String>,
        prefix: Int,
        compressionPrefix: String,
    ): List<ArchiveEntry> {
        var cursor = begin
        return buildList {
            repeat(count) { index ->
                val dexStart = cursor + prefix
                if (dexStart + 36 > end) return emptyList()
                val magic = String(data, dexStart, 4, Charsets.US_ASCII)
                if (magic != "dex\n" && magic != "cdex") return emptyList()
                val size = u32(data, dexStart + 32).toInt()
                val dexEnd = dexStart + size
                if (size < 112 || dexEnd > end) return emptyList()
                add(
                    ArchiveEntry(
                        name = names.getOrNull(index) ?: "dex #${index + 1} (identity unverified)",
                        headerOffset = cursor.toLong(),
                        dataOffset = dexStart.toLong(),
                        dataEnd = dexEnd.toLong(),
                        compressedSize = size.toLong(),
                        uncompressedSize = size.toLong(),
                        compression = "$compressionPrefix-${if (magic == "cdex") "compact" else "standard"}",
                        crc32 = 0,
                    ),
                )
                cursor = (dexEnd + 3) and 3.inv()
            }
            if (cursor != end) return emptyList()
        }
    }
}

private fun u16(
    data: ByteArray,
    offset: Int,
): Int =
    ByteBuffer
        .wrap(data, offset, 2)
        .order(ByteOrder.LITTLE_ENDIAN)
        .short
        .toInt() and 0xffff

private fun u32(
    data: ByteArray,
    offset: Int,
): Long =
    ByteBuffer
        .wrap(data, offset, 4)
        .order(ByteOrder.LITTLE_ENDIAN)
        .int
        .toLong() and 0xffffffffL
