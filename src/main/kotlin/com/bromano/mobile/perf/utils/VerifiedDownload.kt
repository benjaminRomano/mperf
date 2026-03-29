package com.bromano.mobile.perf.utils

import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

fun downloadVerified(
    url: String,
    output: Path,
    expectedSha256: String,
    transform: (InputStream) -> ByteArray = { it.readAllBytes() },
) {
    val bytes =
        URI(url).toURL().openStream().use { input ->
            transform(input)
        }

    val actualSha256 = sha256(bytes)
    require(actualSha256.equals(expectedSha256, ignoreCase = true)) {
        "Checksum verification failed for $url: expected $expectedSha256, got $actualSha256"
    }

    Files.write(output, bytes)
}
