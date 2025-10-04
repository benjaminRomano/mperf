package com.bromano.mobile.perf.utils

import com.bromano.mobile.perf.ProfilerFormat
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempFile
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TraceOpenerTest {
    private class PerfettoFetchingShell : FakeShell() {
        override fun open(url: String) {
            // Extract the served file URL from the open URL (param `url=`)
            val idx = url.indexOf("url=")
            require(idx >= 0) { "expected url param in $url" }
            val encoded = url.substring(idx + 4)
            val target = encoded.substringBefore('&')
            // The target is already a full URL (http://127.0.0.1:9001/<filename>)
            URI(target).toURL().openStream().use { it.readAllBytes() }
        }
    }

    private class SimpleperfFetchingShell : FakeShell() {
        override fun open(url: String) {
            // URL looks like https://profiler.firefox.com/from-url/<encoded>
            val idx = url.indexOf("/from-url/")
            require(idx >= 0) { "expected /from-url/ in $url" }
            val encoded = url.substring(idx + "/from-url/".length)
            val target = URLDecoder.decode(encoded, StandardCharsets.UTF_8)
            URI(target).toURL().openStream().use { it.readAllBytes() }
        }
    }

    @Test
    fun `openTrace PERFETTO serves file and returns after first request`() {
        val tmp: Path = createTempFile("trace", ".perfetto-trace")
        Files.writeString(tmp, "hello")
        val opener = ProfileOpener(PerfettoFetchingShell())

        val start = System.currentTimeMillis()
        opener.openProfile("test", tmp, ProfilerFormat.PERFETTO)
        val durationMs = System.currentTimeMillis() - start
        // Should complete well under the 1 minute timeout
        assertTrue(durationMs < 10_000, "openTrace should return quickly after single GET")
    }

    @Test
    fun `openTrace SIMPLEPERF serves file and returns after first request`() {
        val tmp: Path = createTempFile("trace", ".trace")
        Files.writeString(tmp, "hello")
        val opener = ProfileOpener(SimpleperfFetchingShell())

        val start = System.currentTimeMillis()
        opener.openProfile("test", tmp, ProfilerFormat.SIMPLEPERF)
        val durationMs = System.currentTimeMillis() - start
        assertTrue(durationMs < 10_000, "openTrace should return quickly after single GET")
    }

    @Test
    fun `openTrace throws when trace missing`() {
        val opener = ProfileOpener(PerfettoFetchingShell())
        val missing = Path.of("does-not-exist.trace")
        assertFailsWith<IllegalArgumentException> {
            opener.openProfile("test", missing, ProfilerFormat.PERFETTO)
        }
    }
}
