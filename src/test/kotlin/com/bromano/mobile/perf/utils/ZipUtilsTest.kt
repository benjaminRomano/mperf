package com.bromano.mobile.perf.utils

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.exists
import kotlin.io.path.notExists
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ZipUtilsTest {
    @Test
    fun extractsTraceZipIntoTraceDirectory(
        @TempDir tempDir: Path,
    ) {
        val zipPath = Path.of(this::class.java.getResource("/example.trace.zip").path)
        assertTrue(Files.exists(zipPath))

        val destination = tempDir.resolve("extracted.trace")
        ZipUtils.unzipInstruments(zipPath, destination)

        assertTrue(destination.resolve("UI_state_metadata.bin").exists())
        assertTrue(destination.resolve("Trace1.run").exists())
    }

    @Test
    fun rejectsEntriesOutsideDestination(
        @TempDir tempDir: Path,
    ) {
        val zipPath = tempDir.resolve("malicious.zip")
        ZipOutputStream(Files.newOutputStream(zipPath)).use { zip ->
            zip.putNextEntry(ZipEntry("trace/../../escaped.txt"))
            zip.write("escaped".toByteArray())
            zip.closeEntry()
        }

        val destination = tempDir.resolve("trace")
        assertFailsWith<IllegalArgumentException> {
            ZipUtils.unzipInstruments(zipPath, destination)
        }
        assertTrue(destination.resolve("../escaped.txt").normalize().notExists())
    }
}
