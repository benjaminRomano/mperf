package com.bromano.mobile.perf.faults

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FaultEngineTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `materializes complete versioned engine and reuses matching cache`() {
        val engine = BundledFaultEngine(temporaryDirectory)

        val first = engine.materialize()
        val second = engine.materialize()

        assertEquals(first, second)
        assertTrue(first.resolve("android/faults.py").exists())
        assertTrue(first.resolve("android/native/page_fault_collector.c").exists())
        assertTrue(first.resolve("android/trace_processor").toFile().canExecute())
        assertTrue(first.resolve("ios/faults.py").exists())
        assertTrue(first.resolve("ios/cache-pressure/CachePressure.xcodeproj/project.pbxproj").exists())
        assertTrue(first.resolve("ios/ios_fault_visualizer/assets/plotly.min.js").exists())
        assertTrue(Files.size(first.resolve("ios/ios_fault_visualizer/assets/plotly.min.js")) > 4_000_000)
        assertTrue(Files.readString(first.resolve(".complete")).trim().matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `embedded ios runner bounds commands that do not terminate`() {
        val iosDirectory = BundledFaultEngine(temporaryDirectory).materialize().resolve("ios")
        val script =
            """
            import time
            from ios_fault_visualizer.subprocesses import run
            started = time.monotonic()
            result = run(["/bin/sleep", "5"], check=False, timeout=0.05)
            assert result.returncode == -9
            assert time.monotonic() - started < 1
            """.trimIndent()

        val process =
            ProcessBuilder("python3", "-c", script)
                .directory(iosDirectory.toFile())
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText()

        assertEquals(0, process.waitFor(), output)
    }

    @Test
    fun `repairs a corrupted cache even when its completion marker remains`() {
        val engine = BundledFaultEngine(temporaryDirectory)
        val first = engine.materialize()
        val report = first.resolve("android/report.py")
        val expected = Files.readString(report)
        Files.writeString(report, "corrupted")

        val second = engine.materialize()

        assertEquals(first, second)
        assertEquals(expected, Files.readString(report))
    }

    @Test
    fun `concurrent materialization publishes one complete engine`() {
        val executor = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        try {
            val futures =
                List(4) {
                    executor.submit<Path> {
                        start.await()
                        BundledFaultEngine(temporaryDirectory).materialize()
                    }
                }
            start.countDown()
            val results = futures.map { it.get(30, TimeUnit.SECONDS) }

            assertEquals(1, results.distinct().size)
            results.forEach { result ->
                assertTrue(result.resolve(".complete").exists())
                assertTrue(Files.size(result.resolve("ios/ios_fault_visualizer/assets/plotly.min.js")) > 4_000_000)
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `android engine refuses unowned output replacement`() {
        val androidDirectory = BundledFaultEngine(temporaryDirectory).materialize().resolve("android")
        val outputDirectory = temporaryDirectory.resolve("sentinel-output")
        val script =
            """
            import sys
            from pathlib import Path
            import faults

            output = Path(sys.argv[1])
            output.mkdir()
            sentinel = output / "keep.txt"
            sentinel.write_text("keep")
            try:
                faults.reset_output_directory(output, overwrite=False)
            except RuntimeError:
                pass
            else:
                raise AssertionError("non-empty output was accepted without --overwrite")
            assert sentinel.read_text() == "keep"

            (output / faults.CAPTURE_MARKER).write_text(faults.CAPTURE_MARKER_CONTENT)
            faults.reset_output_directory(output, overwrite=True)
            assert not sentinel.exists()
            assert (output / faults.CAPTURE_MARKER).read_text() == faults.CAPTURE_MARKER_CONTENT
            """.trimIndent()

        val process =
            ProcessBuilder("python3", "-c", script, outputDirectory.toString())
                .directory(androidDirectory.toFile())
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText()

        assertEquals(0, process.waitFor(), output)
    }

    @Test
    fun `ios report contains nested scrolling`() {
        val reporting =
            BundledFaultEngine(temporaryDirectory)
                .materialize()
                .resolve("ios/ios_fault_visualizer/reporting.py")
        val source = Files.readString(reporting)

        assertTrue(source.contains(".fault-list{height:560px;overflow:auto;overscroll-behavior:contain"))
        assertTrue(source.contains(".detail{height:560px;overflow:auto;overscroll-behavior:contain"))
        assertFalse(source.contains(".fault-list{height:560px;overflow:auto;position:relative"))
    }
}
