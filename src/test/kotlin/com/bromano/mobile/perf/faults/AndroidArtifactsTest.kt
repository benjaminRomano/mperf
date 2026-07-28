package com.bromano.mobile.perf.faults

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

class AndroidArtifactsTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `android 10 dex identities require the complete checksum list`() {
        val checksums = listOf(0x12345678L, 0x9abcdef0L)
        val analysis =
            parse(
                android10Vdex(checksums),
                listOf("classes.dex" to checksums[0], "classes2.dex" to checksums[1]),
            )!!

        assertTrue(analysis.identitiesVerified)
        assertEquals(listOf("classes.dex", "classes2.dex"), analysis.dexRanges.map { it.name })
    }

    @Test
    fun `one mismatch suppresses every dex identity`() {
        val checksums = listOf(0x12345678L, 0x9abcdef0L)
        val analysis =
            parse(
                sectionedVdex(checksums),
                listOf("classes.dex" to checksums[0], "classes2.dex" to 7L),
            )!!

        assertFalse(analysis.identitiesVerified)
        assertTrue(analysis.dexRanges.all { "identity unverified" in it.name })
        assertTrue(analysis.dexRanges.none { it.name.startsWith("classes") })
    }

    @Test
    fun `modern sectioned vdex supports embedded dex payloads`() {
        val checksum = 0xaabbccddL
        val analysis = parse(sectionedVdex(listOf(checksum)), listOf("classes.dex" to checksum))!!

        assertEquals("027", analysis.version)
        assertTrue(analysis.identitiesVerified)
        assertEquals("classes.dex", analysis.dexRanges.single().name)
        assertEquals(64L, analysis.dexRanges.single().dataOffset)
    }

    @Test
    fun `modern sectioned vdex without dex payload still verifies identity`() {
        val checksum = 0xaabbccddL
        val analysis =
            parse(
                sectionedVdex(listOf(checksum), includeDex = false),
                listOf("classes.dex" to checksum),
            )!!

        assertTrue(analysis.identitiesVerified)
        assertTrue(analysis.dexRanges.isEmpty())
    }

    @Test
    fun `unknown versions and overlapping sections are rejected`() {
        assertNull(parse("vdex999\u0000".toByteArray() + ByteArray(128), null))
        val malformed = sectionedVdex(listOf(0xaabbccddL))
        val checksumOffset = malformed.u32(16)
        malformed.putU32(28, checksumOffset)
        assertNull(parse(malformed, listOf("classes.dex" to 0xaabbccddL)))
    }

    @Test
    fun `vdex identity uses only its matching installed apk`() {
        val artifacts =
            listOf(
                "/data/app/random/base.apk",
                "/data/app/random/split_feature.apk",
                "/data/app/other/split_feature.apk",
            )

        assertEquals(
            "/data/app/random/split_feature.apk",
            matchingApkForVdex("/data/app/random/oat/arm64/split_feature.vdex", artifacts),
        )
        assertNull(matchingApkForVdex("/data/app/random/oat/arm64/missing.vdex", artifacts))
        assertNull(
            matchingApkForVdex(
                "/data/app/random/oat/arm64/split_feature.vdex",
                artifacts + "/data/app/random/split_feature.APK",
            ),
        )
    }

    private fun parse(
        bytes: ByteArray,
        identities: List<Pair<String, Long>>?,
    ): VdexAnalysis? {
        val path = directory.resolve("base.vdex")
        Files.write(path, bytes)
        return Vdex.read(path, identities)
    }

    private fun android10Vdex(checksums: List<Long>): ByteArray {
        val dexPayloads =
            checksums.fold(ByteArray(0)) { bytes, _ ->
                bytes +
                    ByteBuffer
                        .allocate(4)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(0)
                        .array() + fakeDex()
            }
        return "vdex021\u0000002\u0000".toByteArray() +
            littleEndian(checksums.size.toLong(), 0, 0, 0) +
            littleEndian(*checksums.toLongArray()) +
            littleEndian(dexPayloads.size.toLong(), 0, 0) +
            dexPayloads
    }

    private fun sectionedVdex(
        checksums: List<Long>,
        includeDex: Boolean = true,
    ): ByteArray {
        val sectionCount = 4
        val tableEnd = 12 + sectionCount * 12
        val checksumBytes = littleEndian(*checksums.toLongArray())
        val dexBytes =
            if (includeDex) {
                checksums.fold(ByteArray(0)) { bytes, _ -> bytes + fakeDex() }
            } else {
                ByteArray(0)
            }
        val checksumOffset = tableEnd
        val dexOffset = checksumOffset + checksumBytes.size
        val verifierOffset = dexOffset + dexBytes.size
        return "vdex027\u0000".toByteArray() +
            littleEndian(sectionCount.toLong()) +
            littleEndian(
                0,
                checksumOffset.toLong(),
                checksumBytes.size.toLong(),
                1,
                if (dexBytes.isEmpty()) 0 else dexOffset.toLong(),
                dexBytes.size.toLong(),
                2,
                verifierOffset.toLong(),
                0,
                3,
                verifierOffset.toLong(),
                0,
            ) +
            checksumBytes +
            dexBytes
    }

    private fun fakeDex(): ByteArray =
        ByteArray(112).also {
            "dex\n035\u0000".toByteArray().copyInto(it)
            it.putU32(32, it.size.toLong())
        }

    private fun littleEndian(vararg values: Long): ByteArray =
        ByteBuffer
            .allocate(values.size * 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .also { buffer -> values.forEach { buffer.putInt(it.toInt()) } }
            .array()

    private fun ByteArray.u32(offset: Int): Long =
        ByteBuffer
            .wrap(this, offset, 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .int
            .toLong() and 0xffffffffL

    private fun ByteArray.putU32(
        offset: Int,
        value: Long,
    ) {
        ByteBuffer
            .wrap(this, offset, 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(value.toInt())
    }
}
