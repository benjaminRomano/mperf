package com.bromano.mobile.perf.integration

import java.nio.file.Files
import java.nio.file.Path

/** Only fixture outputs go here; never copy global device logs or arbitrary temporary directories. */
internal object IntegrationArtifacts {
    fun directory(platform: String): Path? =
        System.getenv("MPERF_INTEGRATION_ARTIFACTS")?.takeIf(String::isNotBlank)?.let {
            Files.createDirectories(Path.of(it).resolve(platform))
        }

    fun androidTrace(
        prefix: String,
        suffix: String,
    ): Path {
        val directory = directory("android")
        return if (directory == null) {
            Files.createTempFile(prefix, suffix)
        } else {
            Files.createTempFile(directory, prefix, suffix)
        }
    }
}
