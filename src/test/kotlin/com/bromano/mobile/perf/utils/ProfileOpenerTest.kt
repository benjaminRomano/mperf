package com.bromano.mobile.perf.utils

import com.bromano.mobile.perf.ProfilerFormat
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.createTempFile
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProfileOpenerTest {
    private class PerfettoFetchingShell : FakeShell() {
        override fun open(url: String) {
            val idx = url.indexOf("url=")
            require(idx >= 0) { "expected url param in $url" }
            val encoded = url.substring(idx + 4)
            val target =
                URLDecoder.decode(
                    encoded.substringBefore('&'),
                    StandardCharsets.UTF_8,
                )
            URI(target).toURL().openStream().use { it.readAllBytes() }
        }
    }

    private class SimpleperfFetchingShell : FakeShell() {
        override fun open(url: String) {
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

        assertTimeoutPreemptively(Duration.ofSeconds(5)) {
            opener.openProfile("test", tmp, ProfilerFormat.PERFETTO)
        }
    }

    @Test
    fun `openTrace SIMPLEPERF serves file and returns after first request`() {
        val tmp: Path = createTempFile("trace", ".trace")
        Files.writeString(tmp, "hello")
        val opener = ProfileOpener(SimpleperfFetchingShell())

        assertTimeoutPreemptively(Duration.ofSeconds(5)) {
            assertTimeoutPreemptively(Duration.ofSeconds(5)) {
                opener.openProfile("test", tmp, ProfilerFormat.SIMPLEPERF)
            }
        }
    }

    @Test
    fun `openTrace throws when trace missing`() {
        val opener = ProfileOpener(PerfettoFetchingShell())
        val missing = Path.of("does-not-exist.trace")
        assertFailsWith<IllegalArgumentException> {
            opener.openProfile("test", missing, ProfilerFormat.PERFETTO)
        }
    }

    @Test
    fun `openTrace uploads trace and opens remote url when configured`() {
        val tmp: Path = createTempFile("trace", ".trace")
        Files.writeString(tmp, "upload-me")

        val receivedBodies = mutableListOf<ByteArray>()
        val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/upload") { exchange ->
                    if (exchange.requestMethod.equals("POST", ignoreCase = true)) {
                        val body = exchange.requestBody.readAllBytes()
                        receivedBodies += body
                        val response = """{"id":"shared123"}"""
                        val bytes = response.toByteArray(StandardCharsets.UTF_8)
                        exchange.sendResponseHeaders(200, bytes.size.toLong())
                        exchange.responseBody.use { it.write(bytes) }
                    } else {
                        exchange.sendResponseHeaders(405, -1)
                    }
                    exchange.close()
                }
            }
        server.start()

        try {
            val openCalls = mutableListOf<String>()
            val shell =
                object : FakeShell() {
                    override fun open(url: String) {
                        openCalls += url
                    }
                }
            val uploadUrl = "http://127.0.0.1:${server.address.port}/upload"
            val opener = ProfileOpener(shell, uploadUrl)

            assertTimeoutPreemptively(Duration.ofSeconds(5)) {
                opener.openProfile("test", tmp, ProfilerFormat.SIMPLEPERF)
            }

            assertEquals(1, openCalls.size, "expected single open call")
            val opened = openCalls.first()
            assertTrue(opened.startsWith("https://profiler.firefox.com/from-url/"))
            val encoded = opened.substringAfter("/from-url/")
            val decoded = URLDecoder.decode(encoded, StandardCharsets.UTF_8)
            assertEquals("$uploadUrl/shared123", decoded)

            val bodyString = String(receivedBodies.single(), StandardCharsets.UTF_8)
            assertTrue(bodyString.contains("upload-me"), "uploaded body should include trace contents")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `openTrace PERFETTO uploads trace when configured but still serves locally`() {
        val tmp: Path = createTempFile("trace", ".perfetto-trace")
        Files.writeString(tmp, "perfetto-upload")

        val receivedBodies = mutableListOf<ByteArray>()
        val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/upload") { exchange ->
                    if (exchange.requestMethod.equals("POST", ignoreCase = true)) {
                        val body = exchange.requestBody.readAllBytes()
                        receivedBodies += body
                        val response = """{"id":"perfetto123"}"""
                        val bytes = response.toByteArray(StandardCharsets.UTF_8)
                        exchange.sendResponseHeaders(200, bytes.size.toLong())
                        exchange.responseBody.use { it.write(bytes) }
                    } else {
                        exchange.sendResponseHeaders(405, -1)
                    }
                    exchange.close()
                }
            }
        server.start()

        try {
            val opener =
                ProfileOpener(
                    PerfettoFetchingShell(),
                    "http://127.0.0.1:${server.address.port}/upload",
                )
            assertTimeoutPreemptively(Duration.ofSeconds(5)) {
                opener.openProfile("test", tmp, ProfilerFormat.PERFETTO)
            }

            val bodyString = String(receivedBodies.single(), StandardCharsets.UTF_8)
            assertTrue(bodyString.contains("perfetto-upload"), "uploaded body should include trace contents")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `openTrace PERFETTO uses perfettoUrl when configured`() {
        val tmp: Path = createTempFile("trace", ".perfetto-trace")
        Files.writeString(tmp, "perfetto-remote")

        val uploads = mutableMapOf<String, ByteArray>()
        val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/trace") { exchange ->
                    when (exchange.requestMethod.uppercase()) {
                        "POST" -> {
                            val body = exchange.requestBody.readAllBytes()
                            uploads["remote123"] = body
                            val response = """{"id":"remote123"}"""
                            val bytes = response.toByteArray(StandardCharsets.UTF_8)
                            exchange.sendResponseHeaders(200, bytes.size.toLong())
                            exchange.responseBody.use { it.write(bytes) }
                        }
                        "GET" -> {
                            val path =
                                exchange.requestURI.path
                                    .removePrefix("/trace/")
                                    .ifEmpty { null }
                            val bytes = path?.let { uploads[it] }
                            if (bytes != null) {
                                exchange.sendResponseHeaders(200, bytes.size.toLong())
                                exchange.responseBody.use { it.write(bytes) }
                            } else {
                                exchange.sendResponseHeaders(404, -1)
                            }
                        }
                        else -> exchange.sendResponseHeaders(405, -1)
                    }
                    exchange.close()
                }
            }
        server.start()

        try {
            val openedUrls = mutableListOf<String>()
            val shell =
                object : FakeShell() {
                    override fun open(url: String) {
                        openedUrls += url
                        super.open(url)
                    }
                }
            val uploadUrl = "http://127.0.0.1:${server.address.port}/trace"
            val customPerfettoUrl = "https://perfetto.internal.example.com"
            val opener = ProfileOpener(shell, uploadUrl, customPerfettoUrl)

            assertTimeoutPreemptively(Duration.ofSeconds(5)) {
                opener.openProfile("test", tmp, ProfilerFormat.PERFETTO)
            }

            assertEquals(1, openedUrls.size, "expected single open call to perfettoUrl")
            val opened = openedUrls.first()
            assertTrue(opened.startsWith("$customPerfettoUrl/#!/"), "should deep link into custom perfetto instance")

            val uploadedBody = uploads["remote123"]
            requireNotNull(uploadedBody) { "expected upload to succeed" }
            val bodyString = String(uploadedBody, StandardCharsets.UTF_8)
            assertTrue(bodyString.contains("perfetto-remote"), "uploaded body should include trace contents")
        } finally {
            server.stop(0)
        }
    }
}
