package com.bromano.mobile.perf.faults

import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlin.io.path.exists

interface FaultEngine {
    fun materialize(): Path
}

class BundledFaultEngine(
    private val cacheDirectory: Path =
        Paths
            .get(System.getProperty("user.home"))
            .resolve(".mperf")
            .resolve("cache")
            .resolve("faults-engine"),
) : FaultEngine {
    override fun materialize(): Path {
        val resources = resourceText("/faults-engine/manifest.txt").lineSequence().filter { it.isNotBlank() }.toList()
        val contents = resources.associateWith { resourceBytes("/faults-engine/$it") }
        val digest =
            MessageDigest
                .getInstance("SHA-256")
                .apply {
                    resources.forEach { path ->
                        update(path.toByteArray())
                        update(0)
                        update(contents.getValue(path))
                    }
                }.digest()
                .joinToString("") { "%02x".format(it) }
        val destination = cacheDirectory.resolve(digest.take(16))
        Files.createDirectories(cacheDirectory)

        return synchronized(materializationLock) {
            FileChannel
                .open(
                    cacheDirectory.resolve(".extract.lock"),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                ).use { channel ->
                    channel.lock().use {
                        materializeLocked(destination, digest, contents)
                    }
                }
        }
    }

    private fun materializeLocked(
        destination: Path,
        digest: String,
        contents: Map<String, ByteArray>,
    ): Path {
        if (isComplete(destination, digest, contents)) {
            return destination
        }

        val staging = Files.createTempDirectory(cacheDirectory, ".${destination.fileName}.staging-")
        try {
            contents.forEach { (relativePath, bytes) ->
                val output = staging.resolve(relativePath)
                Files.createDirectories(output.parent)
                Files.write(output, bytes)
            }
            Files.writeString(staging.resolve(".complete"), "$digest\n")

            deleteRecursively(destination)
            try {
                Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(staging, destination)
            }
        } finally {
            deleteRecursively(staging)
        }
        check(isComplete(destination, digest, contents)) {
            "Bundled fault engine failed integrity verification after extraction: $destination"
        }
        return destination
    }

    private fun isComplete(
        destination: Path,
        digest: String,
        contents: Map<String, ByteArray>,
    ): Boolean {
        val completeMarker = destination.resolve(".complete")
        if (!completeMarker.exists() || Files.readString(completeMarker).trim() != digest) {
            return false
        }
        return contents.all { (relativePath, expected) ->
            val output = destination.resolve(relativePath)
            Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS) &&
                Files.readAllBytes(output).contentEquals(expected)
        }
    }

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return
        }
        Files.walk(path).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }
    }

    private fun resourceText(name: String): String = resourceBytes(name).toString(Charsets.UTF_8)

    private fun resourceBytes(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream(name)) {
            "Missing bundled fault-engine resource: $name"
        }.use { it.readAllBytes() }

    private companion object {
        val materializationLock = Any()
    }
}
