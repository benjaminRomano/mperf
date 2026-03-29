package com.bromano.mobile.perf.utils

import com.bromano.mobile.perf.ProfilerFormat
import com.bromano.mobile.perf.gecko.InstrumentsConverter
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.forms.InputProvider
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.streams.asInput
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import com.sun.net.httpserver.Headers as ExchangeHeaders
import io.ktor.http.Headers as KtorHeaders

// Default Perfetto UI only permits localhost trace fetching from 127.0.0.1:9001 via CSP.
private const val TRACE_HTTP_PORT = 9001

/**
 * Serves a given trace file locally and opens the appropriate web UI to load it.
 *
 * Generalizes Perfetto and Simpleperf openers by taking in [ProfilerFormat]
 * to determine the origin and URL format.
 */
open class ProfileOpener(
    private val shell: Shell,
    private val traceHostUrl: String? = null,
    private val perfettoUrl: String? = null,
    private val httpClient: HttpClient = HttpClient(Java),
) {
    /**
     * Serves [trace] over http://127.0.0.1:<port>/<filename> with CORS and no-cache headers,
     * opens the appropriate UI (derived from [format]) pointing to that URL, and blocks until
     * the UI fetches the file once.
     *
     * TODO: This code is unnecessarily complex, simplify it
     */
    open fun openProfile(
        packageName: String?,
        trace: Path,
        format: ProfilerFormat,
        profileViewerOverride: ProfileViewer? = null,
    ) {
        var file = trace.toFile().absoluteFile
        require(file.exists()) { "Trace not found: $file" }

        val profileViewer =
            profileViewerOverride ?: when (format) {
                ProfilerFormat.PERFETTO -> ProfileViewer.PERFETTO
                ProfilerFormat.SIMPLEPERF,
                ProfilerFormat.METHOD,
                -> ProfileViewer.FIREFOX
                ProfilerFormat.INSTRUMENTS -> ProfileViewer.INSTRUMENTS
            }

        // Handle Instruments traces directly without HTTP server
        if (profileViewer == ProfileViewer.INSTRUMENTS) {
            shell.runCommand("open -a Instruments \"${file.absolutePath}\"")
            return
        }

        // If the file was collected by Instruments, we may need to convert into Gecko format, if not already done so.
        if (format == ProfilerFormat.INSTRUMENTS && !isGzipFile(file)) {
            val intermediateOutput = Files.createTempFile("instruments", ".tar.gz")
            InstrumentsConverter.convert(packageName, trace).toFile(intermediateOutput)
            file = intermediateOutput.toFile()
        }

        val openUrlBuilder =
            when (profileViewer) {
                ProfileViewer.PERFETTO -> perfettoUrlBuilder()
                ProfileViewer.FIREFOX -> firefoxUrlBuilder()
                ProfileViewer.INSTRUMENTS -> throw IllegalStateException("INSTRUMENTS should be handled directly")
            }

        // If the trace was successfully uploaded to trace hosting service open it using url; otherwise,
        // fallback to loading local file into profile viewer using local web server
        maybeUploadTrace(file)?.let {
            // Note: A custom perfetto instance is required to circumvent CSPs
            // Ref: https://perfetto.dev/docs/visualization/deep-linking-to-perfetto-ui#why-can-39-t-i-just-pass-a-url-
            if (profileViewer == ProfileViewer.FIREFOX || profileViewer == ProfileViewer.PERFETTO && perfettoUrl != null) {
                val shareableUrl = openUrlBuilder(it)
                println("Shareable URL: $shareableUrl")
                shell.open(shareableUrl)
                return
            }
        }

        val filename = file.name
        val requested = CountDownLatch(1)

        val listenPort = if (profileViewer == ProfileViewer.PERFETTO && perfettoUrl == null) TRACE_HTTP_PORT else 0
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", listenPort), 0)

        server.createContext("/") { exchange ->
            try {
                when (exchange.requestMethod.uppercase()) {
                    "GET" -> handleGet(exchange, file, filename, requested)
                    "OPTIONS" -> sendNoContent(exchange)
                    else -> sendNotFound(exchange)
                }
            } finally {
                exchange.close()
            }
        }

        val executor = Executors.newCachedThreadPool().also { server.executor = it }
        server.start()

        shell.open(openUrlBuilder("http://127.0.0.1:${server.address.port}/$filename"))

        try {
            // Wait until the first successful GET of the exact filename.
            requested.await(2, TimeUnit.MINUTES)
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private fun handleGet(
        exchange: HttpExchange,
        file: File,
        expectedName: String,
        requested: CountDownLatch,
    ) {
        if (exchange.requestURI.path == "/status") {
            sendStatus(exchange)
            return
        }

        // Only serve /<expectedName>
        if (exchange.requestURI.path != "/$expectedName") {
            sendNotFound(exchange)
            return
        }

        // Headers
        val headers = exchange.responseHeaders
        applyCorsHeaders(headers)
        headers.add("Cache-Control", "no-cache")
        headers.add("Content-Type", "application/octet-stream")
        exchange.sendResponseHeaders(200, file.length())
        Files.newInputStream(file.toPath()).use { input ->
            exchange.responseBody.use { output ->
                input.copyTo(output)
            }
        }

        // Signal completion after serving once
        if (requested.count > 0) requested.countDown()
    }

    private fun sendStatus(exchange: HttpExchange) {
        val body = """{"status":"ok"}""".toByteArray(StandardCharsets.UTF_8)
        applyCorsHeaders(exchange.responseHeaders)
        exchange.responseHeaders.add("Cache-Control", "no-cache")
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    private fun sendNoContent(exchange: HttpExchange) {
        applyCorsHeaders(exchange.responseHeaders)
        exchange.responseHeaders.add("Cache-Control", "no-cache")
        exchange.sendResponseHeaders(204, -1)
    }

    private fun sendNotFound(exchange: HttpExchange) {
        applyCorsHeaders(exchange.responseHeaders)
        exchange.responseHeaders.add("Cache-Control", "no-cache")
        exchange.sendResponseHeaders(404, 0)
    }

    private fun applyCorsHeaders(headers: ExchangeHeaders) {
        headers.add("Access-Control-Allow-Origin", "*")
        headers.add("Access-Control-Allow-Methods", "GET, OPTIONS")
        headers.add("Access-Control-Allow-Headers", "*")
    }

    private fun isGzipFile(file: File): Boolean {
        FileInputStream(file).use { input ->
            val b1 = input.read()
            val b2 = input.read()
            return b1 == 0x1F && b2 == 0x8B
        }
    }

    @Serializable
    private data class TraceUploadResponse(
        val id: String?,
    )

    // TODO: Generalize this to support direct uploads to Azure, GCS, etc.
    private fun maybeUploadTrace(file: File): String? {
        val uploadUrl = traceHostUrl ?: return null
        return try {
            val responseBody =
                runBlocking {
                    httpClient
                        .post(uploadUrl) {
                            setBody(
                                MultiPartFormDataContent(
                                    formData {
                                        append(
                                            "file",
                                            InputProvider { file.inputStream().asInput() },
                                            KtorHeaders.build {
                                                append(HttpHeaders.ContentType, "application/octet-stream")
                                                append(
                                                    HttpHeaders.ContentDisposition,
                                                    "form-data; name=\"file\"; filename=\"${file.name}\"",
                                                )
                                            },
                                        )
                                    },
                                ),
                            )
                        }.bodyAsText()
                }

            val id =
                Json
                    .decodeFromString<TraceUploadResponse>(responseBody)
                    .id
                    ?.takeIf { it.isNotBlank() }

            if (id == null) {
                Logger.warning(
                    "Warning: Trace upload failed (invalid response without id): $responseBody",
                )
                return null
            }
            val baseUrl = if (uploadUrl.endsWith("/")) uploadUrl else "$uploadUrl/"
            URI.create(baseUrl + id).toString().also {
                println("Trace uploaded to $it")
            }
        } catch (error: ResponseException) {
            val status = error.response.status
            val body =
                runBlocking {
                    error.response.bodyAsText()
                }
            Logger.warning(
                "Warning: Trace upload failed (HTTP ${status.value} ${status.description}): $body",
            )
            null
        } catch (error: Exception) {
            Logger.warning("Warning: Trace upload failed (${error::class.simpleName}): ${error.message}")
            null
        }
    }

    private fun perfettoUrlBuilder(): (String) -> String {
        val origin = (perfettoUrl ?: "https://ui.perfetto.dev").trimEnd('/')
        return { traceLocation ->
            val encoded = URLEncoder.encode(traceLocation, StandardCharsets.UTF_8)
            "$origin/#!/?url=$encoded&referrer=open_trace_in_ui"
        }
    }

    private fun firefoxUrlBuilder(): (String) -> String =
        { fileName ->
            val encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8)
            "https://profiler.firefox.com/from-url/$encoded"
        }
}
