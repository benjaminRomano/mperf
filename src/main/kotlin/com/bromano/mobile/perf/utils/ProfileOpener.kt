package com.bromano.mobile.perf.utils

import com.bromano.mobile.perf.ProfilerFormat
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// Note: This is intentionally 9001 for Perfetto's CSP
private const val TRACE_HTTP_PORT = 9001

/**
 * Serves a given trace file locally and opens the appropriate web UI to load it.
 *
 * Generalizes Perfetto and Simpleperf openers by taking in [ProfilerFormat]
 * to determine the origin and URL format.
 */
open class ProfileOpener(
    private val shell: Shell,
) {
    /**
     * Serves [trace] over http://127.0.0.1:9001/<filename> with CORS and no-cache headers,
     * opens the appropriate UI (derived from [format]) pointing to that URL, and blocks until
     * the UI fetches the file once.
     *
     * @param customOrigin optional override for the UI origin (defaults per format)
     */
    open fun openProfile(
        trace: Path,
        format: ProfilerFormat,
        profileViewerOverride: ProfileViewer? = null,
        customOrigin: String? = null,
    ) {
        val file = trace.toFile().absoluteFile
        require(file.exists()) { "Trace not found: $file" }

        val profileViewer =
            profileViewerOverride ?: when (format) {
                ProfilerFormat.PERFETTO -> ProfileViewer.PERFETTO
                ProfilerFormat.SIMPLEPERF,
                ProfilerFormat.METHOD,
                -> ProfileViewer.FIREFOX
            }

        val (resolvedOrigin, openUrlBuilder) =
            when (profileViewer) {
                ProfileViewer.PERFETTO -> {
                    val origin = customOrigin ?: "https://ui.perfetto.dev"
                    val builder: (String) -> String = { filename ->
                        "$origin/#!/?url=http://127.0.0.1:$TRACE_HTTP_PORT/$filename&referrer=open_trace_in_ui"
                    }
                    origin to builder
                }
                ProfileViewer.FIREFOX -> {
                    val origin = customOrigin ?: "https://profiler.firefox.com"
                    val builder: (String) -> String = { filename ->
                        val encoded =
                            URLEncoder.encode(
                                "http://localhost:$TRACE_HTTP_PORT/$filename",
                                StandardCharsets.UTF_8,
                            )
                        "$origin/from-url/$encoded"
                    }
                    origin to builder
                }
            }

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", TRACE_HTTP_PORT), 0)
        val filename = file.name
        val requested = CountDownLatch(1)

        server.createContext("/") { exchange ->
            try {
                when (exchange.requestMethod.uppercase()) {
                    "GET" -> handleGet(exchange, file, filename, resolvedOrigin, requested)
                    else -> sendNotFound(exchange)
                }
            } finally {
                exchange.close()
            }
        }

        server.executor = Executors.newCachedThreadPool()
        server.start()

        shell.open(openUrlBuilder(filename))

        try {
            // Wait until the first successful GET of the exact filename.
            requested.await(2, TimeUnit.MINUTES)
        } finally {
            server.stop(0)
            (server.executor as? ExecutorService)?.shutdownNow()
        }
    }

    private fun handleGet(
        exchange: HttpExchange,
        file: File,
        expectedName: String,
        allowOrigin: String,
        requested: CountDownLatch,
    ) {
        // Only serve /<expectedName>
        if (exchange.requestURI.path != "/$expectedName") {
            sendNotFound(exchange)
            return
        }

        // Headers
        val headers = exchange.responseHeaders
        headers.add("Access-Control-Allow-Origin", allowOrigin)
        headers.add("Cache-Control", "no-cache")

        val bytes = Files.readAllBytes(file.toPath())
        headers.add("Content-Type", "application/octet-stream")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }

        // Signal completion after serving once
        if (requested.count > 0) requested.countDown()
    }

    private fun sendNotFound(exchange: HttpExchange) {
        val headers = exchange.responseHeaders
        headers.add("Cache-Control", "no-cache")
        exchange.sendResponseHeaders(404, 0)
    }
}
