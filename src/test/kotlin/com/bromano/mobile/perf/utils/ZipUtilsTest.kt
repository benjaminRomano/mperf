package com.bromano.mobile.perf.utils

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
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
}
