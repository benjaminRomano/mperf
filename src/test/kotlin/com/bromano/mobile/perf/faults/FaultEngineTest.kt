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
        assertTrue(first.resolve("shared/report.js").exists())
        assertTrue(first.resolve("android/native/page_fault_collector.c").exists())
        assertTrue(first.resolve("shared/stacks.js").exists())
        assertTrue(first.resolve("ios/native/residency.c").exists())
        assertTrue(first.resolve("ios/cache-pressure/CachePressure.xcodeproj/project.pbxproj").exists())
        assertTrue(Files.size(first.resolve("shared/plotly.min.js")) > 4_000_000)
        assertTrue(Files.readString(first.resolve(".complete")).trim().matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `repairs a corrupted cache even when its completion marker remains`() {
        val engine = BundledFaultEngine(temporaryDirectory)
        val first = engine.materialize()
        val report = first.resolve("shared/report.html")
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
                assertTrue(Files.size(result.resolve("shared/plotly.min.js")) > 4_000_000)
            }
        } finally {
            executor.shutdownNow()
        }
    }
}
