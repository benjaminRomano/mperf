package com.bromano.mobile.perf.faults

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFails

class AndroidOatTest {
    @TempDir lateinit var output: Path

    @Test fun `malformed optional manifest preserves processing and warns`() {
        Files.writeString(output.resolve("oatdump.json"), "{broken")
        val warnings = mutableListOf<String>()
        assertEquals(emptyMap(), AndroidOat.load(output, emptyMap(), warnings))
        assertEquals(1, warnings.size)
    }

    private val elf =
        AndroidBinary.Elf(
            segments = listOf(AndroidBinary.Region(0x100, 0x300, "PT_LOAD", 0x1000)),
            sections = listOf(AndroidBinary.Region(0x100, 0x300, ".text", 0x1000)),
        )
    private val dump =
        """
location: /app/base.apk
checksum: 0x123
  0: void example.One.run() (dex_method_idx=4)
    CODE: (code_offset=0x00001010 size=32)...
  1: void example.One.alias() (dex_method_idx=5)
    CODE: (code_offset=0x00001010 size=32)...
location: /app/base.apk!classes2.dex
checksum: 0x456
  0: void example.Two.run() (dex_method_idx=7)
    CODE: (code_offset=0x00001040 size=16)...
OAT FILE STATS:
        """.trimIndent()

    private fun parse(text: String) =
        AndroidOat.parse(
            text.lineSequence(),
            "/app/base.apk",
            listOf("classes.dex" to 0x123L, "classes2.dex" to 0x456L),
            elf,
        )

    @Test fun `verified ranges convert ELF addresses and retain deduplicated aliases`() {
        val methods = parse(dump)
        assertEquals(3, methods.size)
        assertEquals(0x110L, methods[0].start)
        assertEquals(listOf("classes.dex", "classes.dex"), AndroidOat.at(methods, 0x110, 0x111).map { it.dex })
        assertEquals(emptyList(), AndroidOat.at(methods, 0x130, 0x140))
        assertEquals(listOf("classes2.dex"), AndroidOat.at(methods, 0x140, 0x141).map { it.dex })
        assertEquals(emptyList(), AndroidOat.at(methods, 0x150, 0x151))
    }

    @Test fun `invalid identity incomplete dump and unsafe ranges suppress attribution`() {
        for (invalid in listOf(
            dump.replace("0x456", "0x457"),
            dump.replace("!classes2.dex", "!classes3.dex"),
            dump.substringBefore("OAT FILE STATS:"),
            dump.replace("size=16", "size=4096"),
            dump.replace("00001040", "00001020"),
            dump.replace("checksum: 0x456", ""),
            dump.replace("    CODE: (code_offset=0x00001040 size=16)...", ""),
        )) {
            assertFails { parse(invalid) }
        }
    }
}
