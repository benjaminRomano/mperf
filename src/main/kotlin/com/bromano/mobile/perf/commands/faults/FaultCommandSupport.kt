package com.bromano.mobile.perf.commands.faults

import com.bromano.mobile.perf.faults.FaultEngine
import com.bromano.mobile.perf.utils.Shell
import com.bromano.mobile.perf.utils.shellQuote
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

internal class FaultCommandSupport(
    private val shell: Shell,
    private val engine: FaultEngine,
) {
    fun engineDirectory(platform: String): Path {
        shell.runCommand("uv --version")
        return engine.materialize().resolve(platform)
    }

    fun run(
        engineDirectory: Path,
        arguments: List<String>,
    ) {
        val command =
            buildString {
                append("cd ")
                append(shellQuote(engineDirectory.toString()))
                append(" && uv run --no-dev ")
                append(arguments.joinToString(" ") { shellQuote(it) })
            }
        shell.runCommand(
            command,
            redirectOutput = ProcessBuilder.Redirect.INHERIT,
            redirectError = ProcessBuilder.Redirect.INHERIT,
        )
    }
}

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
