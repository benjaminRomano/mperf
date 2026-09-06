package com.bromano.mobile.perf.tools

import com.bromano.mobile.perf.utils.sha256
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Duration

/** Native prebuilts pinned to the upstream Perfetto prebuilt manifest. */
internal class NativeTraceProcessor(
    private val cache: Path = Path.of(System.getProperty("user.home"), ".mperf", "cache", "trace-processor"),
    private val artifact: Artifact = hostArtifact(),
    private val fetch: (Artifact) -> ByteArray = ::download,
) {
    data class Artifact(
        val platform: String,
        val size: Int,
        val checksum: String,
    ) {
        val url: String get() =
            "https://commondatastorage.googleapis.com/perfetto-luci-artifacts/${PerfettoTools.VERSION}/" +
                "$platform/trace_processor_shell" + if (platform.startsWith("windows")) ".exe" else ""
    }

    fun materialize(): Path =
        synchronized(downloadLock) {
            Files.createDirectories(cache)
            FileChannel.open(cache.resolve(".download.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                channel.lock().use {
                    val target =
                        cache.resolve(
                            "${artifact.platform}-${artifact.checksum.take(16)}" +
                                if (artifact.platform.startsWith("windows")) ".exe" else "",
                        )
                    if (!valid(target)) {
                        val bytes = fetch(artifact)
                        require(bytes.size == artifact.size && sha256(bytes) == artifact.checksum) {
                            "Perfetto native binary failed size/checksum verification: ${artifact.platform}"
                        }
                        val temporary = Files.createTempFile(cache, ".trace-processor-", ".tmp")
                        try {
                            Files.write(temporary, bytes)
                            makeExecutable(temporary)
                            try {
                                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                            } catch (_: AtomicMoveNotSupportedException) {
                                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
                            }
                        } finally {
                            Files.deleteIfExists(temporary)
                        }
                    }
                    makeExecutable(target)
                    check(valid(target)) { "Perfetto native binary cache verification failed" }
                    target
                }
            }
        }

    private fun valid(path: Path): Boolean =
        Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
            Files.size(path) == artifact.size.toLong() &&
            sha256(path) == artifact.checksum

    private fun makeExecutable(path: Path) {
        check(artifact.platform.startsWith("windows") || path.toFile().setExecutable(true)) {
            "Unable to make Perfetto native binary executable: $path"
        }
    }

    companion object {
        private val downloadLock = Any()

        // Source: Perfetto tools/release/roll-prebuilts v58.2, trace_processor_shell manifest.
        private val artifacts =
            listOf(
                Artifact("mac-amd64", 14854504, "3927a2767eadd140db3ff4fe0dfbf1bde35c1f56501149cd367f5cee898bef27"),
                Artifact("mac-arm64", 13597976, "d29864d1ba3b36855527bb1b0ca3aa7f703cdce338b9680bb922c5c151b358fa"),
                Artifact("linux-amd64", 14897560, "58042408e6cc861fb1a731c26bb082dc222285561eaa4e12a48a8b2b90dca7b9"),
                Artifact("linux-arm", 10962664, "09683fed93a3452d9dac1f5165a592ec9fec82fba90be2c5dd764c8f9a449e33"),
                Artifact("linux-arm64", 14086160, "0e6e0c5452c505c8d46fe472fd196a0d17d963460727e2ce2013b02aa1309555"),
                Artifact("windows-amd64", 14439936, "adfa6bad3d72be3ba9b83fa2b17b69fa13b3ab1cad0f42e52b86188bd5f0f997"),
            ).associateBy(Artifact::platform)

        internal fun hostArtifact(
            os: String = System.getProperty("os.name"),
            arch: String = System.getProperty("os.arch"),
        ): Artifact {
            val platform =
                when {
                    os.lowercase().startsWith("windows") -> "windows"
                    os.lowercase().contains("mac") || os.equals("darwin", ignoreCase = true) -> "mac"
                    os.lowercase().contains("linux") -> "linux"
                    else -> error("No pinned Perfetto native binary for $os/$arch")
                }
            val cpu =
                when (arch.lowercase()) {
                    "amd64", "x86_64" -> "amd64"
                    "aarch64", "arm64" -> "arm64"
                    "arm", "armv6l", "armv7l", "armv8l" -> "arm"
                    else -> error("No pinned Perfetto native binary for $os/$arch")
                }
            return artifacts["$platform-$cpu"] ?: error("No pinned Perfetto native binary for $os/$arch")
        }

        private fun download(artifact: Artifact): ByteArray {
            val request =
                HttpRequest
                    .newBuilder(URI(artifact.url))
                    .timeout(Duration.ofSeconds(90))
                    .GET()
                    .build()
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build().use { client ->
                val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
                check(response.statusCode() == 200) { "Perfetto download failed: HTTP ${response.statusCode()}" }
                return response.body()
            }
        }
    }
}
