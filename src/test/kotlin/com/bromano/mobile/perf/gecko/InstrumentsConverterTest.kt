package com.bromano.mobile.perf.gecko

import com.bromano.mobile.perf.utils.ZipUtils
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import kotlin.io.path.inputStream
import kotlin.test.assertEquals

class InstrumentsConverterTest {
    @Test
    fun convertsTraceToExpectedGeckoProfile(
        @TempDir tempDir: Path,
    ) {
        val zipPath = Path.of(this::class.java.getResource("/example.trace.zip").path)
        val input = tempDir.resolve("example.trace")
        ZipUtils.unzipInstruments(zipPath, input)
        val output = tempDir.resolve("profile.json.gz")

        val profile = InstrumentsConverter.convert("perftestexample", input)
        profile.toFile(output)

        val expectedJson = readGzipResource("example.json.gz")
        val actualJson = readGzipFile(output)

        assertEquals(expectedJson, actualJson)
    }

    private fun readGzipResource(resourceName: String): String {
        val stream =
            this::class.java.classLoader.getResourceAsStream(resourceName)
                ?: error("Missing resource $resourceName")
        return stream.use { resource ->
            GZIPInputStream(resource).bufferedReader(UTF_8).use { it.readText() }
        }
    }

    private fun readGzipFile(path: Path): String =
        path.inputStream().use { fileStream ->
            GZIPInputStream(fileStream).bufferedReader(UTF_8).use { it.readText() }
        }
}
