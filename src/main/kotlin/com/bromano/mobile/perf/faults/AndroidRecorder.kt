package com.bromano.mobile.perf.faults

import java.io.InputStream
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Continuously drain output and stop only the PID announced by our exec wrapper. */
internal class AndroidRecorder private constructor(
    private val adb: AndroidFaultCollector.Device,
    val process: Process,
    private val output: StringBuffer,
    private val reader: Thread,
    val pid: Long,
) {
    fun text(): String = output.toString()

    fun await(
        pattern: Regex,
        seconds: Long = 15,
    ): MatchResult {
        val deadline = System.nanoTime() + Duration.ofSeconds(seconds).toNanos()
        while (System.nanoTime() < deadline) {
            pattern.find(text())?.let { return it }
            check(process.isAlive) { "Recorder exited before readiness: ${text()}" }
            Thread.sleep(25)
        }
        error("Recorder readiness timed out: ${text()}")
    }

    fun stop(timeoutSeconds: Long = 20): String {
        try {
            adb.rootShell("kill -INT $pid", check = false, timeout = Duration.ofSeconds(5))
            check(process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) { "Recorder $pid did not stop within $timeoutSeconds seconds" }
            reader.join(5_000)
            check(!reader.isAlive) { "Recorder output did not close" }
            return text()
        } catch (error: Exception) {
            abort(error)
            throw error
        }
    }

    fun abort(error: Throwable) {
        runCatching { adb.rootShell("kill -KILL $pid", check = false, timeout = Duration.ofSeconds(5)) }
            .exceptionOrNull()
            ?.let(error::addSuppressed)
        process.destroy()
        if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
        reader.join(2_000)
    }

    companion object {
        internal fun drain(
            input: InputStream,
            output: StringBuffer,
        ) {
            input.reader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(4096)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    synchronized(output) {
                        output.append(buffer, 0, count)
                        // Keep the latest diagnostics without allowing an unbounded recorder log.
                        if (output.length > 8 * 1024 * 1024) output.delete(0, output.length - 8 * 1024 * 1024)
                    }
                }
            }
        }

        fun start(
            adb: AndroidFaultCollector.Device,
            command: String,
            config: String? = null,
        ): AndroidRecorder {
            val process =
                ProcessBuilder(adb.rootCommand("echo MPERF_RECORDER_PID=\$\$; exec $command"))
                    .redirectErrorStream(true)
                    .start()
            val output = StringBuffer()
            val reader =
                thread(isDaemon = true, name = "mperf-android-recorder") {
                    drain(process.inputStream, output)
                }
            var ownedPid: Long? = null
            try {
                val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
                while (System.nanoTime() < deadline && process.isAlive) {
                    ownedPid =
                        Regex("MPERF_RECORDER_PID=(\\d+)\\r?\\n")
                            .find(output.toString())
                            ?.groupValues
                            ?.get(1)
                            ?.toLong()
                    if (ownedPid != null) break
                    Thread.sleep(25)
                }
                check(ownedPid != null) { "Recorder did not announce its PID: $output" }
                if (config != null) process.outputStream.bufferedWriter().use { it.write(config) }
                return AndroidRecorder(adb, process, output, reader, ownedPid)
            } catch (error: Throwable) {
                ownedPid?.let { runCatching { adb.rootShell("kill -KILL $it", check = false, timeout = Duration.ofSeconds(5)) } }
                process.destroy()
                if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
                throw error
            }
        }
    }
}
