package com.bromano.mobile.perf.utils

import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

data class CommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

internal class CommandFailure(
    val command: List<String>,
    val result: CommandResult,
) : RuntimeException(
        "${command.joinToString(" ")} failed with exit ${result.exitCode}: ${result.stderr.trim()}",
    )

internal object Processes {
    fun run(
        command: List<String>,
        directory: Path? = null,
        check: Boolean = true,
        timeout: Duration? = null,
    ): CommandResult {
        val process =
            ProcessBuilder(command)
                .apply { directory?.let { directory(it.toFile()) } }
                .start()
        var stdout = ""
        var stderr = ""
        val outThread = thread(isDaemon = true) { stdout = process.inputStream.bufferedReader().use { it.readText() } }
        val errThread = thread(isDaemon = true) { stderr = process.errorStream.bufferedReader().use { it.readText() } }
        val finished =
            timeout?.let { process.waitFor(it.toMillis(), TimeUnit.MILLISECONDS) }
                ?: run {
                    process.waitFor()
                    true
                }
        if (!finished) {
            process.destroy()
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
            throw IllegalStateException("${command.joinToString(" ")} timed out after $timeout")
        }
        outThread.join()
        errThread.join()
        val result = CommandResult(process.exitValue(), stdout, stderr)
        if (check && result.exitCode != 0) throw CommandFailure(command, result)
        return result
    }
}
