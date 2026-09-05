package com.bromano.mobile.perf.commands.faults

import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

internal fun defaultFaultOutput(platform: String): Path =
    Path
        .of(
            "artifacts",
            "faults",
            buildString {
                append(platform)
                append("-")
                append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss-SSS")))
                append("-")
                append(UUID.randomUUID().toString().take(8))
            },
        ).toAbsolutePath()
        .normalize()

internal fun Path.absoluteNormalized(): Path = toAbsolutePath().normalize()
