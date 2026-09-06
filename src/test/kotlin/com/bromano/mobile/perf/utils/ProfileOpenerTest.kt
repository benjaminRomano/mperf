package com.bromano.mobile.perf.utils

import com.bromano.mobile.perf.ProfilerFormat
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfileOpenerTest {
    @TempDir lateinit var temporary: Path

    private fun trace(): Path = Files.writeString(temporary.resolve("trace + #é.perfetto-trace"), "trace-content")

    private fun target(url: String): URI =
        URI(
            URLDecoder.decode(
                if ("url=" in
                    url
                ) {
                    url.substringAfter("url=").substringBefore('&')
                } else {
                    url.substringAfter("/from-url/")
                },
                UTF_8,
            ),
        )

    private fun shell(onOpen: (String) -> Unit): Shell =
        object : FakeShell() {
            override fun open(url: String) = onOpen(url)
        }

    private var requestedPort = -1
    private var boundPort = -1

    private fun opener(
        shell: Shell,
        endpoint: String? = null,
        viewer: String? = null,
    ): ProfileOpener =
        ProfileOpener(shell, endpoint, viewer) { port ->
            requestedPort = port
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { boundPort = it.address.port }
        }

    private fun assertPortReleased() {
        HttpServer.create(InetSocketAddress("127.0.0.1", boundPort), 0).apply { start() }.stop(0)
    }

    @Test
    fun `local viewers serve only the exact file and release the port after fetching`() {
        for (format in listOf(ProfilerFormat.PERFETTO, ProfilerFormat.SIMPLEPERF)) {
            val file = trace()
            val shell =
                shell { url ->
                    val uri = target(url)
                    assertEquals(file.fileName.toString(), uri.path.substringAfterLast('/'))
                    assertEquals(if (format == ProfilerFormat.PERFETTO) 9001 else 0, requestedPort)
                    assertEquals(boundPort, uri.port)
                    HttpClient.newHttpClient().use { client ->
                        fun fetch(
                            path: String,
                            method: String = "GET",
                        ) = client.send(
                            HttpRequest.newBuilder(uri.resolve(path)).method(method, HttpRequest.BodyPublishers.noBody()).build(),
                            HttpResponse.BodyHandlers.ofString(),
                        )
                        assertEquals(404, fetch("/wrong-file").statusCode())
                        assertEquals(405, fetch(uri.rawPath, "POST").statusCode())
                        assertEquals(204, fetch(uri.rawPath, "OPTIONS").statusCode())
                        assertEquals(404, fetch("/status").statusCode())
                        val response = fetch(uri.rawPath)
                        assertEquals("trace-content", response.body())
                        val origin = if (format == ProfilerFormat.PERFETTO) "https://ui.perfetto.dev" else "https://profiler.firefox.com"
                        assertEquals(origin, response.headers().firstValue("Access-Control-Allow-Origin").orElseThrow())
                    }
                }
            assertTimeoutPreemptively(Duration.ofSeconds(5)) { opener(shell).openProfile(null, file, format) }
            assertPortReleased()
        }
    }

    @Test
    fun `failed browser opening releases local server`() {
        val opener = opener(shell { error("browser unavailable") })
        assertFailsWith<IllegalStateException> { opener.openProfile(null, trace(), ProfilerFormat.PERFETTO) }
        assertPortReleased()
    }

    @Test
    fun `missing trace is rejected before opening`() {
        assertFailsWith<IllegalArgumentException> {
            ProfileOpener(shell { error("must not open") }).openProfile(null, temporary.resolve("missing"), ProfilerFormat.PERFETTO)
        }
    }

    @Test
    fun `official Perfetto supports public HTTPS while local HTTP retains fallback`() {
        assertTrue(ProfileOpener.supportsRemoteTrace(ProfileViewer.PERFETTO, "https://traces.example/123", false))
        assertFalse(ProfileOpener.supportsRemoteTrace(ProfileViewer.PERFETTO, "http://traces.example/123", false))
        assertTrue(ProfileOpener.supportsRemoteTrace(ProfileViewer.PERFETTO, "http://traces.example/123", true))
        assertTrue(ProfileOpener.supportsRemoteTrace(ProfileViewer.FIREFOX, "http://traces.example/123", false))
    }

    @Test
    fun `uploads stream multipart and select remote or local viewer appropriately`() {
        for ((format, custom) in listOf(
            ProfilerFormat.SIMPLEPERF to null,
            ProfilerFormat.PERFETTO to null,
            ProfilerFormat.PERFETTO to "https://viewer.example",
        )) {
            uploadServer(200, """{"id":"shared + id"}""") { endpoint, uploaded ->
                val opener =
                    opener(
                        shell { url ->
                            val uri = target(url)
                            if (format == ProfilerFormat.PERFETTO && custom == null) {
                                assertEquals("trace-content", uri.toURL().readText())
                            } else {
                                assertEquals(endpoint + "/shared%20%2B%20id", uri.toASCIIString())
                                assertTrue(url.startsWith(custom ?: "https://profiler.firefox.com"))
                            }
                        },
                        endpoint,
                        custom,
                    )
                assertTimeoutPreemptively(Duration.ofSeconds(5)) { opener.openProfile(null, trace(), format) }
                assertTrue(uploaded().contains("name=\"file\""))
                assertTrue(uploaded().contains("trace-content"))
            }
        }
    }

    @Test
    fun `upload errors and malformed responses fall back to local trace`() {
        for ((status, body) in listOf(500 to "error", 200 to "not-json", 200 to "{}")) {
            uploadServer(status, body) { endpoint, _ ->
                val opener = opener(shell { assertEquals("trace-content", target(it).toURL().readText()) }, endpoint)
                assertTimeoutPreemptively(Duration.ofSeconds(5)) { opener.openProfile(null, trace(), ProfilerFormat.PERFETTO) }
            }
        }
    }

    private fun uploadServer(
        status: Int,
        response: String,
        test: (String, () -> String) -> Unit,
    ) {
        var uploaded = ""
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/upload") { exchange ->
            exchange.use {
                assertEquals("POST", exchange.requestMethod)
                uploaded = exchange.requestBody.readAllBytes().toString(UTF_8)
                val bytes = response.toByteArray(UTF_8)
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.write(bytes)
            }
        }
        server.start()
        try {
            test("http://127.0.0.1:" + server.address.port + "/upload") { uploaded }
        } finally {
            server.stop(0)
        }
    }
}
