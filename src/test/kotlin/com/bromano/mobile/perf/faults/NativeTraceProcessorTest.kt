package com.bromano.mobile.perf.faults

import com.bromano.mobile.perf.utils.sha256
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NativeTraceProcessorTest {
    @TempDir
    lateinit var directory: Path

    private val bytes = "verified native fixture".toByteArray()
    private val artifact = NativeTraceProcessor.Artifact("mac-arm64", bytes.size, sha256(bytes))

    @Test
    fun `maps host names to pinned architectures and rejects unsupported hosts`() {
        assertEquals("mac-arm64", NativeTraceProcessor.hostArtifact("Mac OS X", "aarch64").platform)
        assertEquals("mac-amd64", NativeTraceProcessor.hostArtifact("Darwin", "x86_64").platform)
        assertEquals("windows-amd64", NativeTraceProcessor.hostArtifact("Windows 11", "amd64").platform)
        assertEquals("linux-arm64", NativeTraceProcessor.hostArtifact("Linux", "aarch64").platform)
        assertFailsWith<IllegalStateException> { NativeTraceProcessor.hostArtifact("Linux", "riscv64") }
    }

    @Test
    fun `verified binary is reused and corrupted cache is repaired`() {
        var downloads = 0
        val installer =
            NativeTraceProcessor(directory, artifact) {
                downloads++
                bytes
            }
        val path = installer.materialize()
        assertTrue(Files.isExecutable(path))
        assertEquals(path, installer.materialize())
        assertEquals(1, downloads)
        Files.writeString(path, "corrupt")
        assertEquals(path, installer.materialize())
        assertEquals(2, downloads)
        assertEquals(artifact.checksum, sha256(path))
    }

    @Test
    fun `bad checksum or size is never published or marked executable`() {
        for (bad in listOf(ByteArray(bytes.size), byteArrayOf(1))) {
            assertFailsWith<IllegalArgumentException> { NativeTraceProcessor(directory, artifact) { bad }.materialize() }
        }
        Files.list(directory).use { paths -> assertEquals(listOf(".download.lock"), paths.map { it.fileName.toString() }.toList()) }
    }

    @Test
    fun `concurrent installers publish one verified artifact`() {
        val downloads = AtomicInteger()
        val executor = Executors.newFixedThreadPool(3)
        try {
            val futures =
                List(3) {
                    executor.submit<Path> {
                        NativeTraceProcessor(directory, artifact) {
                            downloads.incrementAndGet()
                            bytes
                        }.materialize()
                    }
                }
            val paths = futures.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, paths.distinct().size)
            assertEquals(1, downloads.get())
        } finally {
            executor.shutdownNow()
        }
    }
}
