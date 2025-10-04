package com.bromano.mobile.perf.profilers.instruments

import com.bromano.mobile.perf.profilers.Profiler
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
)

/**
 * iOS Instruments profiler using xctrace
 */
class InstrumentsProfiler(
    private val xcodeUtils: XcodeUtils,
    private val options: InstrumentsProfilerOptions,
) : Profiler {
    @OptIn(ExperimentalPathApi::class)
    override fun execute(
        packageName: String,
        output: Path,
    ) {
        // Ensure output directory exists
        output.parent.toFile().mkdirs()
        if (output.exists()) {
            output.deleteRecursively()
        }

        xcodeUtils.record(
            template = options.template,
            instruments = options.instruments,
            bundleIdentifier = packageName,
            outputPath = output.toString(),
        )

        if (!output.toFile().exists()) {
            throw PrintMessage("Trace file was not created at $output", printError = true)
        }

        println("Recording complete: $output")
    }

    override fun executeTest(
        packageName: String,
        instrumentationRunner: String,
        testCase: String,
        output: Path,
    ) {
        // TODO: Implement this next
    }
}
