package com.bromano.mobile.perf.profilers.method

import com.bromano.mobile.perf.profilers.Profiler
import com.bromano.mobile.perf.profilers.buildBenchmarkInstrumentationCommand
import com.bromano.mobile.perf.profilers.findNewBenchmarkOutput
import com.bromano.mobile.perf.profilers.validateBenchmarkInstrumentationOutput
import com.bromano.mobile.perf.utils.Adb
import com.bromano.mobile.perf.utils.Logger
import com.github.ajalt.clikt.core.PrintMessage
import java.nio.file.Path

/**
 * Collects an Android Runtime streaming method trace for a running or cold-started app.
 */
class MethodProfiler(
    private val adb: Adb,
    private val flushTimeoutMs: Long = 5_000,
    private val flushPollMs: Long = 50,
    private val awaitStop: () -> Unit = { readlnOrNull() },
) : Profiler {
    override fun execute(
        packageName: String,
        output: Path,
    ) {
        val onDeviceTrace = "/data/local/tmp/method.trace"
        // Best effort cleanup of any previous trace
        adb.delete(onDeviceTrace, force = true, ignoreErrors = true)

        if (adb.isRunning(packageName)) {
            // App already running: start/stop method tracing using am profile
            Logger.info("App is running. Starting method tracing...")

            // https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/core/java/android/app/ProfilerInfo.java;l=115;drc=c58be09d9273485c54d6a16defc42d9f26182b73
            val clockType =
                if (adb.sdkVersion >= 35) {
                    "--clock-type wall"
                } else {
                    ""
                }

            adb.shell("am profile start --streaming $clockType $packageName $onDeviceTrace")
        } else {
            // App not running: resolve main activity and start with profiler
            Logger.info("App not running. Resolving launchable activity...")
            val resolved =
                adb.resolveLaunchableActivity(packageName)
                    ?: throw PrintMessage("Failed to resolve launchable activity for $packageName", printError = true)

            Logger.info("Starting app with method tracing: $resolved")
            adb.shell("am start --start-profiler $onDeviceTrace --streaming \"$resolved\"")
        }

        Logger.info("Press any key to end tracing...")
        awaitStop()

        Logger.info("Stopping method tracing...")
        adb.shell("am profile stop $packageName")
        waitForTraceFlush(onDeviceTrace)

        Logger.info("Pulling trace from device...")
        adb.pull(onDeviceTrace, output.toString())
    }

    override fun executeTest(
        packageName: String,
        instrumentationRunner: String,
        testCase: String,
        output: Path,
    ) {
        Logger.info("Running performance test: $testCase")

        val outputDir = adb.getDirUsableByAppAndShell(instrumentationRunner.substringBefore("/"))
        val filesBeforeRun = adb.ls(outputDir, ignoreErrors = true).toSet()
        val instrumentationOutput =
            adb.shell(
                buildBenchmarkInstrumentationCommand(
                    instrumentationRunner = instrumentationRunner,
                    testCase = testCase,
                    outputDirectory = outputDir,
                    arguments =
                        linkedMapOf(
                            "androidx.benchmark.profiling.mode" to "MethodTracing",
                            "androidx.benchmark.suppressErrors" to "EMULATOR,METHOD-TRACING-ENABLED",
                        ),
                ),
            )
        validateBenchmarkInstrumentationOutput(instrumentationOutput)

        Logger.info("Test complete. Pulling trace...")

        val trace =
            findNewBenchmarkOutput(
                instrumentationOutput = instrumentationOutput,
                filesBeforeRun = filesBeforeRun,
                filesAfterRun = adb.ls(outputDir),
                outputDirectory = outputDir,
                matches = { it.contains("methodTracing") && it.endsWith(".trace") },
            )
                ?: throw PrintMessage("No method trace found by instrumentation test in $outputDir", printError = true)

        adb.pull(trace, output.toString())
    }

    private fun waitForTraceFlush(path: String) {
        val deadline = System.currentTimeMillis() + flushTimeoutMs
        var previousSize = -1L
        var stablePolls = 0
        while (System.currentTimeMillis() < deadline) {
            val sizeOutput: String? = adb.shell("stat -c %s \"$path\"", ignoreErrors = true)
            val size = sizeOutput?.trim()?.toLongOrNull() ?: -1L
            stablePolls = if (size > 0 && size == previousSize) stablePolls + 1 else 0
            if (stablePolls >= 4) {
                return
            }
            previousSize = size
            Thread.sleep(flushPollMs)
        }
        throw PrintMessage("Method trace did not finish writing within ${flushTimeoutMs}ms: $path", printError = true)
    }
}
