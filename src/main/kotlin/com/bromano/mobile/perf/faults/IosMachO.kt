package com.bromano.mobile.perf.faults

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** Attribution requires the recorded UUID and load address, never just a matching file name. */
internal object IosMachO {
    data class Section(
        val name: String,
        val address: Long,
        val size: Long,
        val offset: Long?,
        val code: Boolean,
    )

    data class Image(
        val uuid: String,
        val base: Long,
        val sections: List<Section>,
    )

    data class Binary(
        val name: String = "",
        val path: String = "",
        val uuid: String = "",
        val arch: String = "",
        val loadAddress: String = "",
    )

    fun parse(
        data: ByteArray,
        arch: String,
    ): Image? =
        try {
            parseChecked(data, arch)
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: IndexOutOfBoundsException) {
            null
        }

    private fun parseChecked(
        data: ByteArray,
        arch: String,
    ): Image? {
        val cpu =
            when (arch) {
                "arm64", "arm64e" -> 0x100000c
                "x86_64" -> 0x1000007
                else -> return null
            }
        if (data.size < 32) return null
        val big = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        var start = 0
        var end = data.size
        if (big.getInt(0) in setOf(0xcafebabe.toInt(), 0xcafebabf.toInt())) {
            val wide = big.getInt(0) == 0xcafebabf.toInt()
            val count = big.getInt(4)
            val width = if (wide) 32 else 20
            require(count in 1..64 && 8 + count * width <= data.size)
            val candidates = (0 until count).filter { big.getInt(8 + it * width) == cpu }
            require(candidates.size == 1)
            val cursor = 8 + candidates.single() * width
            val offset = if (wide) big.getLong(cursor + 8) else big.getInt(cursor + 8).toLong() and 0xffffffffL
            val size = if (wide) big.getLong(cursor + 16) else big.getInt(cursor + 12).toLong() and 0xffffffffL
            require(offset >= 8 + count * width && size >= 32 && offset <= data.size && size <= data.size - offset)
            start = offset.toInt()
            end = (offset + size).toInt()
        }
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        require(b.getInt(start) == 0xfeedfacf.toInt() && b.getInt(start + 4) == cpu)
        val count = b.getInt(start + 16)
        val commandSize = b.getInt(start + 20)
        require(count in 0..10000 && commandSize >= 0 && commandSize <= end - start - 32)
        var cursor = start + 32
        val commandEnd = cursor + commandSize
        var uuid: String? = null
        var base: Long? = null
        val sections = mutableListOf<Section>()

        fun name(offset: Int): String =
            data
                .copyOfRange(offset, offset + 16)
                .takeWhile { it != 0.toByte() }
                .toByteArray()
                .toString(Charsets.UTF_8)

        fun fits(
            offset: Long,
            size: Long,
            limit: Long,
        ): Boolean = offset >= 0 && size >= 0 && offset <= limit && size <= limit - offset
        repeat(count) {
            require(cursor <= commandEnd - 8)
            val command = b.getInt(cursor)
            val size = b.getInt(cursor + 4)
            require(size >= 8 && size % 8 == 0 && size <= commandEnd - cursor)
            if (command == 0x1b) {
                require(size == 24 && uuid == null)
                uuid = UUID(big.getLong(cursor + 8), big.getLong(cursor + 16)).toString().uppercase()
            }
            if (command == 0x19) {
                require(size >= 72)
                val segment = name(cursor + 8)
                val address = b.getLong(cursor + 24)
                val vmSize = b.getLong(cursor + 32)
                val offset = b.getLong(cursor + 40)
                val fileSize = b.getLong(cursor + 48)
                val sectionCount = b.getInt(cursor + 64)
                require(fits(address, vmSize, Long.MAX_VALUE) && fileSize <= vmSize && fits(offset, fileSize, (end - start).toLong()))
                if (offset == 0L && fileSize > 0) {
                    require(base == null)
                    base = address
                }
                require(sectionCount in 0..10000 && sectionCount <= (size - 72) / 80)
                repeat(sectionCount) { index ->
                    val s = cursor + 72 + index * 80
                    val sectionName = name(s)
                    val sectionSegment = name(s + 16)
                    val sectionAddress = b.getLong(s + 32)
                    val sectionSize = b.getLong(s + 40)
                    val sectionOffset = b.getInt(s + 48).toLong() and 0xffffffffL
                    val flags = b.getInt(s + 64)
                    val zeroFill = flags and 0xff in setOf(1, 0xc, 0x12)
                    require(sectionSegment == segment && sectionAddress >= address && fits(sectionAddress - address, sectionSize, vmSize))
                    require(zeroFill || (sectionOffset >= offset && fits(sectionOffset - offset, sectionSize, fileSize)))
                    sections +=
                        Section(
                            "$segment,$sectionName",
                            sectionAddress,
                            sectionSize,
                            if (zeroFill) null else start + sectionOffset,
                            flags and 0x80000400.toInt() != 0,
                        )
                }
            }
            cursor += size
        }
        require(cursor == commandEnd)
        return Image(uuid ?: return null, base ?: return null, sections)
    }

    class Resolver {
        private val cache = mutableMapOf<Triple<String, String, String>, Image?>()
        val boundaries = linkedMapOf<String, List<Map<String, Any?>>>()

        fun resolve(
            address: ULong,
            binaries: List<Binary>,
        ): Map<String, Any?> {
            if (address > Long.MAX_VALUE.toULong()) return emptyMap()
            val matches = mutableListOf<Map<String, Any?>>()
            val seen = mutableSetOf<Pair<Triple<String, String, String>, Long>>()
            binaries.forEach { binary ->
                if (binary.path.isBlank() || binary.uuid.isBlank() || binary.loadAddress.isBlank()) return@forEach
                val key = Triple(binary.path, binary.uuid.uppercase(), binary.arch)
                if (!cache.containsKey(key)) {
                    cache[key] =
                        try {
                            val path = Path.of(binary.path)
                            if (Files.isRegularFile(path) && Files.size(path) <= 512L * 1024 * 1024) {
                                parse(Files.readAllBytes(path), binary.arch)?.takeIf { it.uuid == key.second }
                            } else {
                                null
                            }
                        } catch (_: Exception) {
                            null
                        }
                }
                val image = cache[key] ?: return@forEach
                val load =
                    binary.loadAddress.removePrefix("0x").toLongOrNull(if (binary.loadAddress.startsWith("0x")) 16 else 10)
                        ?: return@forEach
                if (load < 0) return@forEach
                val slide = load - image.base
                if (!seen.add(key to slide)) return@forEach
                boundaries[binary.path] =
                    image.sections.filter { it.offset != null }.map {
                        mapOf("offset" to it.offset, "name" to it.name)
                    }
                image.sections.forEach sectionLoop@{ section ->
                    // Reject arithmetic overflow in malformed load addresses instead of wrapping into a section.
                    val mappedStart =
                        try {
                            Math.addExact(section.address, slide)
                        } catch (_: ArithmeticException) {
                            return@sectionLoop
                        }
                    if (mappedStart < 0 || address.toLong() < mappedStart) return@sectionLoop
                    val relative = address.toLong() - mappedStart
                    if (relative >= 0 && relative < section.size) {
                        matches +=
                            mapOf(
                                "read_file" to binary.path,
                                "read_section" to section.name,
                                "read_file_offset" to section.offset?.plus(relative),
                                "read_section_is_code" to section.code,
                            )
                    }
                }
            }
            return matches.singleOrNull().orEmpty()
        }
    }
}
