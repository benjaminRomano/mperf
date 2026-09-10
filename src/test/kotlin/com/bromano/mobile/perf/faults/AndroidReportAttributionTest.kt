package com.bromano.mobile.perf.faults

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidReportAttributionTest {
    @TempDir lateinit var directory: Path

    private fun elf(
        architecture: Short = 62,
        id: Byte = 1,
    ): ByteArray {
        val data = ByteArray(256)
        byteArrayOf(127, 69, 76, 70, 2, 1).copyInto(data)
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(18, architecture)
            putLong(32, 64)
            putShort(54, 56)
            putShort(56, 2)
            putInt(64, 1)
            putLong(64 + 32, 256)
            putInt(120, 4)
            putLong(120 + 8, 192)
            putLong(120 + 32, 20)
            putInt(192, 4)
            putInt(196, 4)
            putInt(200, 3)
        }
        byteArrayOf(71, 78, 85, 0, id, 2, 3, 4).copyInto(data, 204)
        return data
    }

    @Test fun `ELF identity checks architecture build ID and malformed notes`() {
        assertEquals(AndroidElfIdentity(62, 64, "01020304"), AndroidElfIdentity.read(elf()))
        assertEquals(183, AndroidElfIdentity.read(elf(183))?.architecture)
        assertEquals("09020304", AndroidElfIdentity.read(elf(id = 9))?.buildId)
        assertNull(AndroidElfIdentity.read(elf().copyOf(208)))
        assertNull(AndroidElfIdentity.read(ByteArray(256)))
        val corrupt = elf()
        ByteBuffer.wrap(corrupt).order(ByteOrder.LITTLE_ENDIAN).putInt(196, Int.MAX_VALUE)
        assertNull(AndroidElfIdentity.read(corrupt))
    }

    @Test fun `mapping retains kept methods inline origins and merged class ambiguity`() {
        val mapping =
            AndroidReportAttribution.Mapping(
                """
                original.One -> a:
                    1:3:void initialize():9:11 -> b
                    1:3:void original.Helper.work():4:6 -> b
                original.Two -> a:
                    void run() -> c
                """.trimIndent(),
            )
        assertEquals(listOf("original.One.initialize", "original.Helper.work"), mapping.origins("a.b"))
        assertEquals(listOf("original.One.kept", "original.Two.kept"), mapping.origins("a.kept"))
        assertEquals(listOf("original.Two.run"), mapping.origins("void a.c()"))
    }

    @Test fun `profile class roots do not masquerade as clinit method roots`() {
        val profile = AndroidReportAttribution.Profile("Loriginal/One;\nHSPLoriginal/Two;-><clinit>()V\n")
        assertEquals("class root only; no explicit class initializer method root", profile.audit("original.One.<clinit>"))
        assertTrue(profile.audit("original.Two.<clinit>").startsWith("method root candidate"))
        assertEquals("absent from supplied consumed profile", profile.audit("original.Missing.start"))
        assertEquals("class root only; no explicit method root", profile.audit("original.One.start"))
    }

    @Test fun `report symbolication accepts only architecture and build ID matched debug ELFs`() {
        val binary = directory.resolve("captured.so").also { Files.write(it, elf()) }
        val symbols = directory.resolve("symbols").also(Files::createDirectories)
        val tool =
            directory.resolve("llvm-symbolizer").also {
                Files.writeString(
                    it,
                    "#!/bin/sh\nwhile read address; do echo '{\"Symbol\":[{\"FunctionName\":\"exact_function\"}]}'; done\n",
                )
                assertTrue(it.toFile().setExecutable(true))
            }
        Json.write(directory.resolve("artifacts.json"), mapOf("/data/app/lib.so" to binary.fileName.toString()))

        fun run(): MutableMap<String, Any?> =
            mutableMapOf(
                "provenance" to mapOf("llvm_symbolizer" to tool.toString()),
                "events" to
                    listOf(mapOf("stack" to listOf(mapOf("file" to "/data/app/lib.so", "fileOffset" to "1", "label" to "unresolved")))),
            )

        fun label(run: Map<String, Any?>): Any? {
            val event = (run["events"] as List<*>).single() as Map<*, *>
            return ((event["stack"] as List<*>).single() as Map<*, *>)["label"]
        }
        for (bytes in listOf(elf(183), elf(id = 9), ByteArray(256))) {
            Files.write(symbols.resolve("same-name.so"), bytes)
            val run = run()
            AndroidReportAttribution.apply(directory, run, AndroidReportAttribution.Options(symbols = symbols))
            assertEquals("unresolved", label(run))
        }
        Files.write(symbols.resolve("same-name.so"), elf())
        val matched = run()
        AndroidReportAttribution.apply(directory, matched, AndroidReportAttribution.Options(symbols = symbols))
        assertEquals("exact_function", label(matched))
        Files.write(symbols.resolve("conflicting-debug.so"), elf().copyOf(257))
        assertFailsWith<IllegalArgumentException> {
            AndroidReportAttribution.apply(directory, run(), AndroidReportAttribution.Options(symbols = symbols))
        }
    }

    @Test fun `mapping without exact APK build binding is rejected`() {
        val mapping = directory.resolve("mapping.txt").also { Files.writeString(it, "Original -> a:\n") }
        val run = mutableMapOf<String, Any?>("provenance" to emptyMap<String, Any?>(), "events" to emptyList<Any>())
        assertFailsWith<IllegalArgumentException> {
            AndroidReportAttribution.apply(directory, run, AndroidReportAttribution.Options(mapping = mapping))
        }
        assertFailsWith<IllegalArgumentException> {
            AndroidReportAttribution.apply(directory, run, AndroidReportAttribution.Options(mapping = mapping, apkSha256 = "a".repeat(64)))
        }
    }
}
