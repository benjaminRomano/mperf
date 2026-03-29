package com.bromano.mobile.perf.profilers.method

import com.bromano.mobile.perf.profilers.Profiler
import com.bromano.mobile.perf.utils.Adb
import com.bromano.mobile.perf.utils.Logger
import com.github.ajalt.clikt.core.PrintMessage
import java.nio.file.Path

/**
 * Collects a simpleperf profile using NDK simpleperf scripts (app_profiler.py).
 *
 * Requirements:
 * - ANDROID_NDK_HOME must be set and contain simpleperf/app_profiler.py
 * - Device selection is controlled via ANDROID_SERIAL environment variable
 */
class MethodProfiler(
    private val adb: Adb,
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
                if (adb.sdkVersion >= 34) {
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

        adb.shell(
            buildString {
                append("am instrument -w -r ")
                append("-e class \"$testCase\" ")
                // Only perform a single iteration
                append("-e androidx.benchmark.profiling.mode MethodTracing ")
                append("-e androidx.benchmark.suppressErrors \"EMULATOR\" ")
                append("-e mperf.methodTrace true ")
                append(instrumentationRunner)
            },
        )

        Logger.info("Test complete. Pulling trace...")

        val outputDir = adb.getDirUsableByAppAndShell(instrumentationRunner.substringBefore("/"))
        val trace =
            adb.ls(outputDir).firstOrNull { it.contains("methodTracing") }
                ?: throw PrintMessage("No method trace found by instrumentation test in $outputDir", printError = true)

        adb.pull("$outputDir$trace", output.toString())
    }
}
