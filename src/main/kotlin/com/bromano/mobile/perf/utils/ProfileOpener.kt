package com.bromano.mobile.perf.utils

import com.bromano.mobile.perf.ProfilerFormat
import com.bromano.mobile.perf.gecko.InstrumentsConverter
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Opens local traces without uploading, unless a trace host is explicitly configured. */
open class ProfileOpener(
    private val shell: Shell,
    private val traceHostUrl: String? = null,
    private val perfettoUrl: String? = null,
    private val createServer: (Int) -> HttpServer = { port -> HttpServer.create(InetSocketAddress("127.0.0.1", port), 0) },
) {
    open fun openProfile(
        packageName: String?,
        trace: Path,
        format: ProfilerFormat,
        profileViewerOverride: ProfileViewer? = null,
        targetProcessId: Long? = null,
    ) {
        require(Files.exists(trace)) { "Trace not found: $trace" }
        val viewer =
            profileViewerOverride ?: when (format) {
                ProfilerFormat.PERFETTO -> ProfileViewer.PERFETTO
                ProfilerFormat.INSTRUMENTS -> ProfileViewer.INSTRUMENTS
                ProfilerFormat.SIMPLEPERF, ProfilerFormat.METHOD -> ProfileViewer.FIREFOX
            }
        if (viewer == ProfileViewer.INSTRUMENTS) {
            shell.runCommand("open -a Instruments ${shellQuote(trace.toAbsolutePath().toString())}")
            return
        }

        val converted =
            if (format == ProfilerFormat.INSTRUMENTS && !isGzip(trace)) {
                Files.createTempFile("mperf-instruments-", ".json.gz")
            } else {
                null
            }
        try {
            if (converted != null) InstrumentsConverter.convert(packageName, trace, processId = targetProcessId).toFile(converted)
            val file = (converted ?: trace).toFile().absoluteFile
            require(file.isFile) { "Expected a trace file: $file" }
            val remote = traceHostUrl?.let { uploadTrace(file, it) }
            if (remote != null && supportsRemoteTrace(viewer, remote, perfettoUrl != null)) {
                val url = viewerUrl(viewer, remote)
                println("Shareable URL: $url")
                shell.open(url)
            } else {
                openLocal(file, viewer)
            }
        } finally {
            converted?.let(Files::deleteIfExists)
        }
    }

    private fun openLocal(
        file: File,
        viewer: ProfileViewer,
    ) {
        // Perfetto v54+ allows arbitrary HTTPS, but its CSP still limits local HTTP to this port.
        val port = if (viewer == ProfileViewer.PERFETTO && perfettoUrl == null) 9001 else 0
        val path = "/${UUID.randomUUID()}/${file.name}"
        val requested = CountDownLatch(1)
        val allowedOrigin =
            if (viewer == ProfileViewer.FIREFOX) {
                "https://profiler.firefox.com"
            } else {
                URI(perfettoUrl ?: "https://ui.perfetto.dev").let { "${it.scheme}://${it.rawAuthority}" }
            }
        val server = createServer(port)
        server.createContext("/") { exchange ->
            exchange.use {
                val headers = exchange.responseHeaders
                headers.set("Access-Control-Allow-Origin", allowedOrigin)
                headers.set("Access-Control-Allow-Methods", "GET, OPTIONS")
                headers.set("Access-Control-Allow-Private-Network", "true")
                headers.set("Cache-Control", "no-store")
                when {
                    exchange.requestMethod == "OPTIONS" -> exchange.sendResponseHeaders(204, -1)
                    exchange.requestMethod != "GET" -> exchange.sendResponseHeaders(405, -1)
                    exchange.requestURI.path != path -> exchange.sendResponseHeaders(404, -1)
                    else -> {
                        headers.set("Content-Type", "application/octet-stream")
                        exchange.sendResponseHeaders(200, file.length())
                        file.inputStream().use { input -> exchange.responseBody.use(input::copyTo) }
                        requested.countDown()
                    }
                }
            }
        }
        server.start()
        try {
            val traceUrl = URI("http", null, "127.0.0.1", server.address.port, path, null, null).toASCIIString()
            shell.open(viewerUrl(viewer, traceUrl))
            if (!requested.await(2, TimeUnit.MINUTES)) {
                Logger.warning("Viewer did not load the trace within 2 minutes. Open the file manually: $file")
            }
        } finally {
            server.stop(0)
        }
    }

    private fun viewerUrl(
        viewer: ProfileViewer,
        trace: String,
    ): String {
        val encoded = URLEncoder.encode(trace, UTF_8)
        return when (viewer) {
            ProfileViewer.PERFETTO -> "${(perfettoUrl ?: "https://ui.perfetto.dev").trimEnd('/')}/#!/?url=$encoded"
            ProfileViewer.FIREFOX -> "https://profiler.firefox.com/from-url/$encoded"
            ProfileViewer.INSTRUMENTS -> error("Instruments does not use a web viewer")
        }
    }

    private fun isGzip(path: Path): Boolean =
        Files.isRegularFile(path) &&
            Files.newInputStream(path).use {
                it.read() == 0x1f && it.read() == 0x8b
            }

    internal companion object {
        fun supportsRemoteTrace(
            viewer: ProfileViewer,
            trace: String,
            customPerfetto: Boolean,
        ): Boolean = viewer == ProfileViewer.FIREFOX || customPerfetto || URI(trace).scheme.equals("https", ignoreCase = true)
    }
}

/** The optional hosting contract is multipart POST followed by GET <endpoint>/<id>. */
private fun uploadTrace(
    file: File,
    endpoint: String,
): String? =
    try {
        val boundary = "mperf-${UUID.randomUUID()}"
        val filename = file.name.map { if (it in "\r\n\"\\") '_' else it }.joinToString("")
        val prefix =
            "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$filename\"\r\n" +
                "Content-Type: application/octet-stream\r\n\r\n"
        val body =
            HttpRequest.BodyPublishers.concat(
                HttpRequest.BodyPublishers.ofString(prefix),
                HttpRequest.BodyPublishers.ofFile(file.toPath()),
                HttpRequest.BodyPublishers.ofString("\r\n--$boundary--\r\n"),
            )
        val request =
            HttpRequest
                .newBuilder(URI(endpoint))
                .timeout(Duration.ofMinutes(2))
                .header("Content-Type", "multipart/form-data; boundary=$boundary")
                .POST(body)
                .build()
        val response =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build().use {
                it.send(request, HttpResponse.BodyHandlers.ofString())
            }
        check(response.statusCode() in 200..299) { "HTTP ${response.statusCode()}" }
        val id =
            JsonParser
                .parseString(response.body())
                .asJsonObject
                .get("id")
                ?.asString
        require(!id.isNullOrBlank()) { "Response has no trace id" }
        val url = endpoint.trimEnd('/') + "/" + URLEncoder.encode(id, UTF_8).replace("+", "%20")
        println("Trace uploaded to $url")
        url
    } catch (error: Exception) {
        Logger.warning("Trace upload failed: ${error.message}. Opening the local file instead.")
        null
    }
