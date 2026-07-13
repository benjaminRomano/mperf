package com.bromano.mobile.perf.gecko

import com.bromano.mobile.perf.utils.ZipUtils
import com.google.gson.Gson
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import kotlin.io.path.inputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class InstrumentsConverterTest {
    @Test
    fun rejectsNonPositiveRunNumber(
        @TempDir tempDir: Path,
    ) {
        val error = assertFailsWith<IllegalArgumentException> { InstrumentsConverter.convert(null, tempDir, 0) }

        assertEquals("Instruments run number must be at least 1", error.message)
    }

    @Test
    @EnabledOnOs(OS.MAC)
    fun convertsTraceToExpectedGeckoProfile(
        @TempDir tempDir: Path,
    ) {
        val zipPath = Path.of(this::class.java.getResource("/example.trace.zip").path)
        val input = tempDir.resolve("example.trace")
        ZipUtils.unzipInstruments(zipPath, input)
        val output = tempDir.resolve("profile.json.gz")

        val profile = InstrumentsConverter.convert("perftestexample", input, processId = 5376)
        profile.toFile(output)

        val expectedJson = readGzipResource("example.json.gz")
        val actualJson = readGzipFile(output)

        val expected = Gson().fromJson(expectedJson, GeckoProfile::class.java)
        val actual = Gson().fromJson(actualJson, GeckoProfile::class.java)

        // Xcode can improve symbolication of a saved trace over time, changing frame and string table IDs.
        // Raw sample timing and thread identity remain stable across Xcode releases.
        assertEquals(expected.meta.startTime, actual.meta.startTime)
        assertEquals(expected.meta.categories, actual.meta.categories)
        assertEquals(expected.threads.map { it.tid }.toSet(), actual.threads.map { it.tid }.toSet())
        assertTrue(actual.threads.all { it.pid > 0 }, "converted threads should preserve their process IDs")
        assertEquals(setOf(5376L), actual.threads.map { it.pid }.toSet())
        expected.threads.associateBy { it.tid }.forEach { (tid, expectedThread) ->
            val actualThread = requireNotNull(actual.threads.firstOrNull { it.tid == tid })
            assertEquals(
                expectedThread.name.substringAfterLast("(perftestexample"),
                actualThread.name.substringAfterLast("(perftestexample"),
            )
            assertEquals(
                expectedThread.samples.data.map { it[1] },
                actualThread.samples.data.map { it[1] },
                "sample timestamps changed for thread $tid",
            )
            assertEquals(expectedThread.samples.data.size, actualThread.samples.data.size)
            assertEquals(actualThread.frameTable.data.size, actualThread.stringTable.size)
        }
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
