package com.bromano.mobile.perf.profilers.instruments

import com.bromano.mobile.perf.utils.XcodeUtils
import com.github.ajalt.clikt.core.PrintMessage
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.notExists

class InstrumentsProfilerTest {
    private val mockXcodeUtils = mock<XcodeUtils>()

    @Test
    fun `execute records trace and ensures output file created`() {
        val tempDir: Path = createTempDirectory("instruments-profiler-success")
        val output = tempDir.resolve("nested/trace.trace")
        val options =
            InstrumentsProfilerOptions(
                template = "Custom Template",
                instruments = listOf("time-profiler", "allocations"),
            )

        doAnswer { invocation ->
            val outputPath = Path.of(invocation.getArgument<String>(3))
            assertTrue(outputPath.parent.toFile().exists(), "Output directory should exist before recording")
            assertTrue(outputPath.notExists(), "Existing outputs should be removed before recording")
            Files.createDirectories(outputPath.parent)
            Files.createFile(outputPath)
            null
        }.whenever(mockXcodeUtils).record(any<String>(), any<List<String>>(), any<String>(), any<String>())

        val profiler = InstrumentsProfiler(mockXcodeUtils, options)

        profiler.execute("com.example.app", output)

        verify(mockXcodeUtils).record(
            options.template,
            options.instruments,
            "com.example.app",
            output.toString(),
        )
        assertTrue(output.exists(), "Trace file should be present after recording")
    }

    @Test
    fun `execute deletes existing output before recording`() {
        val tempDir: Path = createTempDirectory("instruments-profiler-delete")
        val output = tempDir.resolve("trace.trace")
        Files.createDirectories(output)

        doAnswer { invocation ->
            val outputPath = Path.of(invocation.getArgument<String>(3))
            assertTrue(outputPath.notExists(), "Existing output should be deleted before recording starts")
            Files.createFile(outputPath)
            null
        }.whenever(mockXcodeUtils).record(any<String>(), any<List<String>>(), any<String>(), any<String>())

        val profiler = InstrumentsProfiler(mockXcodeUtils, InstrumentsProfilerOptions())

        profiler.execute("com.example.app", output)

        assertTrue(output.exists(), "Trace file should be recreated after recording")
    }

    @Test
    fun `execute throws when trace file not created`() {
        val tempDir: Path = createTempDirectory("instruments-profiler-failure")
        val output = tempDir.resolve("trace.trace")

        doNothing().whenever(mockXcodeUtils).record(any<String>(), any<List<String>>(), any<String>(), any<String>())

        val profiler = InstrumentsProfiler(mockXcodeUtils, InstrumentsProfilerOptions())

        val error =
            assertThrows(PrintMessage::class.java) {
                profiler.execute("com.example.app", output)
            }

        assertTrue(error.message!!.contains("Trace file was not created"))
    }
}
