package com.bromano.mobile.perf.utils

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

fun sha256(bytes: ByteArray): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

fun sha256(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var bytesRead = input.read(buffer)
        while (bytesRead >= 0) {
            if (bytesRead > 0) {
                digest.update(buffer, 0, bytesRead)
            }
            bytesRead = input.read(buffer)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
