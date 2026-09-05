package com.bromano.mobile.perf.faults

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

/** Native prebuilts pinned to the same Perfetto v51.2 manifest as the former upstream Python launcher. */
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
            "https://commondatastorage.googleapis.com/perfetto-luci-artifacts/v51.2/" +
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

        // Source: Perfetto tools/roll-prebuilts v51.2, trace_processor_shell manifest.
        private val artifacts =
            listOf(
                Artifact("mac-amd64", 10934464, "6b6f74b6c0e1f67365f5099d5fcdded20612329733ee7aa893a8f5ad80876356"),
                Artifact("mac-arm64", 10081800, "f29d80cd9c9fb400ed29bc014ce573c85b69ca50ad3679b1ae3d2eab76d4f399"),
                Artifact("linux-amd64", 11160240, "9e70b7c057d906b25a44b42638f1ed87bc38ee11610e199375ec10b0fedcc7ff"),
                Artifact("linux-arm", 8242220, "68ed4b2cd721404d69580c911d5f0dc786f7dce280fea8b99830d9d8ab0eff71"),
                Artifact("linux-arm64", 10635832, "61f3341cbdb0282b85d4594ce2f8d9dfcea8173776d38d33cb5eb4b035f7b621"),
                Artifact("windows-amd64", 10905088, "b2ffcb1eb343662f55c95dcafeeeee7ff706e9ffa23713ffd73de79b1ce1baa5"),
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
