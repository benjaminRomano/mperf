package com.bromano.mobile.perf.faults

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class AndroidElfIdentity(
    val architecture: Int,
    val bits: Int,
    val buildId: String,
) {
    companion object {
        fun read(data: ByteArray): AndroidElfIdentity? =
            runCatching {
                require(data.size >= 64 && data.take(4) == listOf<Byte>(127, 69, 76, 70) && data[5] == 1.toByte())
                val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

                fun u16(at: Int) = b.getShort(at).toInt() and 65535

                fun u32(at: Int) = b.getInt(at).toLong() and 0xffffffffL

                fun size(
                    at: Int,
                    wide: Boolean,
                ) = if (wide) b.getLong(at) else u32(at)
                val wide = data[4] == 2.toByte()
                require(wide || data[4] == 1.toByte())
                val notes = mutableSetOf<Pair<Long, Long>>()
                for (program in listOf(true, false)) {
                    val table =
                        size(
                            if (program) {
                                if (wide) 32 else 28
                            } else {
                                if (wide) 40 else 32
                            },
                            wide,
                        )
                    val entrySize =
                        u16(
                            if (program) {
                                if (wide) 54 else 42
                            } else {
                                if (wide) 58 else 46
                            },
                        )
                    val count =
                        u16(
                            if (program) {
                                if (wide) 56 else 44
                            } else {
                                if (wide) 60 else 48
                            },
                        )
                    require(table >= 0 && table <= data.size && count.toLong() * entrySize <= data.size - table)
                    require(
                        count == 0 ||
                            entrySize >=
                            if (program) {
                                if (wide) 56 else 32
                            } else {
                                if (wide) 64 else 40
                            },
                    )
                    repeat(count) { index ->
                        val at = (table + index * entrySize).toInt()
                        if (u32(at + if (program) 0 else 4) == if (program) 4L else 7L) {
                            val offset =
                                size(
                                    at +
                                        if (program) {
                                            if (wide) 8 else 4
                                        } else {
                                            if (wide) 24 else 16
                                        },
                                    wide,
                                )
                            val length =
                                size(
                                    at +
                                        if (program) {
                                            if (wide) 32 else 16
                                        } else {
                                            if (wide) 32 else 20
                                        },
                                    wide,
                                )
                            require(offset >= 0 && length >= 0 && offset <= data.size.toLong() - length)
                            notes += offset to length
                        }
                    }
                }
                val ids = mutableSetOf<String>()
                for ((offset, length) in notes) {
                    var at = offset
                    val end = offset + length
                    while (at + 12 <= end) {
                        val name = u32(at.toInt())
                        val desc = u32(at.toInt() + 4)
                        val type = u32(at.toInt() + 8)
                        val descAt = at + 12 + ((name + 3) and -4L)
                        val next = descAt + ((desc + 3) and -4L)
                        require(next <= end)
                        if (type == 3L &&
                            name == 4L &&
                            desc > 0 &&
                            data.copyOfRange(at.toInt() + 12, at.toInt() + 16).contentEquals(byteArrayOf(71, 78, 85, 0))
                        ) {
                            ids += data.copyOfRange(descAt.toInt(), (descAt + desc).toInt()).joinToString("") { "%02x".format(it) }
                        }
                        at = next
                    }
                }
                AndroidElfIdentity(u16(18), if (wide) 64 else 32, ids.single())
            }.getOrNull()
    }
}
