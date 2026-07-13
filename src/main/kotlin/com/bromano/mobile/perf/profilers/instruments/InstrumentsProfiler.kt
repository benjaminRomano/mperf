package com.bromano.mobile.perf.profilers.instruments

import com.bromano.mobile.perf.profilers.Profiler
import com.bromano.mobile.perf.utils.Logger
import com.bromano.mobile.perf.utils.XcodeUtils
import com.github.ajalt.clikt.core.PrintMessage
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists

/**
 * Simple data class for Instruments profiler options
 */
data class InstrumentsProfilerOptions(
    val template: String = "Time Profiler",
    val instruments: List<String> = emptyList(),
    val timeLimit: String? = null,
)

/**
 * iOS Instruments profiler using xctrace
 */
class InstrumentsProfiler(
    private val xcodeUtils: XcodeUtils,
    private val options: InstrumentsProfilerOptions,
) : Profiler {
    override val targetProcessId: Long?
        get() = xcodeUtils.lastRecordedProcessId

    @OptIn(ExperimentalPathApi::class)
    override fun execute(
        packageName: String,
        output: Path,
    ) {
        // Ensure output directory exists
        output.parent?.toFile()?.mkdirs()
        if (output.exists()) {
            output.deleteRecursively()
        }

        recordWithTransientCrashRetry(packageName, output)

        if (!output.toFile().exists()) {
            throw PrintMessage("Trace file was not created at $output", printError = true)
        }

        println("Recording complete: $output")
    }

    private fun recordWithTransientCrashRetry(
        packageName: String,
        output: Path,
    ) {
        var attempt = 1
        while (true) {
            try {
                xcodeUtils.record(
                    template = options.template,
                    instruments = options.instruments,
                    bundleIdentifier = packageName,
                    outputPath = output.toString(),
                    timeLimit = options.timeLimit,
                )
                return
            } catch (error: IllegalStateException) {
                val isRetryableFinalizationFailure =
                    output.toFile().exists() && error.message in XCTRACE_RETRYABLE_FAILURES
                if (!isRetryableFinalizationFailure || attempt >= XCTRACE_MAX_ATTEMPTS) {
                    throw error
                }

                attempt++
                Logger.warning(
                    "Warning: xctrace failed while finalizing the trace; " +
                        "removing the partial output and retrying once.",
                )
                output.toFile().deleteRecursively()
            }
        }
    }

    override fun executeTest(
        packageName: String,
        instrumentationRunner: String,
        testCase: String,
        output: Path,
    ): Unit = throw UnsupportedOperationException("Instruments does not support Android instrumentation test collection")

    private companion object {
        const val XCTRACE_MAX_ATTEMPTS = 2
        val XCTRACE_RETRYABLE_FAILURES =
            setOf(
                "xctrace exited with code 1",
                "xctrace exited with code 139",
                "xctrace exited with code 141",
                "xctrace did not stop before the collection timeout; the trace may be unusable",
            )
    }
}
