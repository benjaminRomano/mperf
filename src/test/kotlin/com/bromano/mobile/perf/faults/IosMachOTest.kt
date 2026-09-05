package com.bromano.mobile.perf.faults

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IosMachOTest {
    @TempDir
    lateinit var output: Path

    @Test
    fun `parses code and constants without treating all TEXT as code`() {
        val image = IosMachO.parse(machoFixture(), "arm64")!!
        assertEquals(TEST_UUID, image.uuid)
        assertEquals(listOf("__TEXT,__text", "__TEXT,__const"), image.sections.map { it.name })
        assertTrue(image.sections.first().code)
        assertFalse(image.sections.last().code)
        assertEquals(512L, image.sections.first().offset)
    }

    @Test
    fun `rejects truncated malformed and wrong architecture images`() {
        assertNull(IosMachO.parse(machoFixture().copyOf(200), "arm64"))
        assertNull(IosMachO.parse(machoFixture(), "x86_64"))
        val malformed = machoFixture()
        ByteBuffer.wrap(malformed).order(ByteOrder.LITTLE_ENDIAN).putInt(60, Int.MAX_VALUE)
        assertNull(IosMachO.parse(malformed, "arm64"))
    }

    @Test
    fun `requires UUID and in-event load address and resolves actual read address`() {
        val file = output.resolve("Example")
        Files.write(file, machoFixture())
        val resolver = IosMachO.Resolver()
        val binary = IosMachO.Binary("Example", file.toString(), TEST_UUID, "arm64", "0x200000000")
        val result = resolver.resolve(0x200000210UL, listOf(binary, binary))
        assertEquals(file.toString(), result["read_file"])
        assertEquals(528L, result["read_file_offset"])
        assertEquals("__TEXT,__text", result["read_section"])
        assertTrue(resolver.resolve(0x200000210UL, listOf(binary.copy(uuid = "wrong"))).isEmpty())
        assertTrue(resolver.resolve(0x200000210UL, listOf(binary.copy(loadAddress = ""))).isEmpty())
        assertTrue(resolver.resolve(0x300000210UL, listOf(binary)).isEmpty())
        assertTrue(resolver.resolve(ULong.MAX_VALUE, listOf(binary)).isEmpty())
    }

    @Test
    fun `ambiguous overlapping images remain unattributed`() {
        val one = output.resolve("One")
        val two = output.resolve("Two")
        Files.write(one, machoFixture())
        Files.write(two, machoFixture())
        val binaries = listOf(one, two).map { IosMachO.Binary("Example", it.toString(), TEST_UUID, "arm64", "0x200000000") }
        assertTrue(IosMachO.Resolver().resolve(0x200000210UL, binaries).isEmpty())
    }

    @Test
    fun `fat image offsets refer to the whole file and duplicate architecture is rejected`() {
        val thin = machoFixture()
        val fat = ByteArray(512 + thin.size)
        val b = ByteBuffer.wrap(fat).order(ByteOrder.BIG_ENDIAN)
        b.putInt(0, 0xcafebabe.toInt())
        b.putInt(4, 1)
        b.putInt(8, 0x100000c)
        b.putInt(16, 512)
        b.putInt(20, thin.size)
        thin.copyInto(fat, 512)
        assertEquals(
            1024L,
            IosMachO
                .parse(fat, "arm64")!!
                .sections
                .first()
                .offset,
        )
        b.putInt(4, 2)
        b.putInt(28, 0x100000c)
        assertNull(IosMachO.parse(fat, "arm64"))
    }

    companion object {
        const val TEST_UUID = "12345678-1234-5678-9ABC-DEF012345678"

        fun machoFixture(): ByteArray {
            val bytes = ByteArray(1024)
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            b.putInt(0, 0xfeedfacf.toInt())
            b.putInt(4, 0x100000c)
            b.putInt(16, 2)
            b.putInt(20, 256)
            b.putInt(32, 0x1b)
            b.putInt(36, 24)
            val uuid = UUID.fromString(TEST_UUID)
            ByteBuffer
                .wrap(bytes)
                .order(ByteOrder.BIG_ENDIAN)
                .putLong(40, uuid.mostSignificantBits)
                .putLong(48, uuid.leastSignificantBits)
            b.putInt(56, 0x19)
            b.putInt(60, 232)

            fun name(
                offset: Int,
                value: String,
            ) {
                value.toByteArray().copyInto(bytes, offset)
            }
            name(64, "__TEXT")
            b.putLong(80, 0x100000000)
            b.putLong(88, 1024)
            b.putLong(96, 0)
            b.putLong(104, 1024)
            b.putInt(120, 2)
            repeat(2) { index ->
                val s = 128 + index * 80
                name(s, if (index == 0) "__text" else "__const")
                name(s + 16, "__TEXT")
                b.putLong(s + 32, 0x100000200 + index * 128)
                b.putLong(s + 40, 128)
                b.putInt(s + 48, 512 + index * 128)
                b.putInt(s + 64, if (index == 0) 0x80000400.toInt() else 0)
            }
            return bytes
        }
    }
}
