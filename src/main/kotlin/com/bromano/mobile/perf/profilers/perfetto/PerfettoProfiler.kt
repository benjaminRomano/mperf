package com.bromano.mobile.perf.profilers.perfetto

import com.bromano.mobile.perf.PerfettoOptions
import com.bromano.mobile.perf.profilers.Profiler
import com.bromano.mobile.perf.profilers.buildBenchmarkInstrumentationCommand
import com.bromano.mobile.perf.profilers.findNewBenchmarkOutput
import com.bromano.mobile.perf.profilers.validateBenchmarkInstrumentationOutput
import com.bromano.mobile.perf.utils.Adb
import com.bromano.mobile.perf.utils.Logger
import com.bromano.mobile.perf.utils.Shell
import com.bromano.mobile.perf.utils.downloadVerified
import com.github.ajalt.clikt.core.PrintMessage
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

private const val TRACEBOX_PATH = "/data/local/tmp/tracebox"
private const val PERFETTO_TRACEBOX_VERSION = "v57.2"

private data class TraceboxArtifact(
    val url: String,
    val sha256: String,
)

class PerfettoProfiler(
    val shell: Shell,
    val adb: Adb,
    val perfettoOptions: PerfettoOptions,
    private val startupTimeoutMs: Long = TimeUnit.SECONDS.toMillis(10),
    private val awaitStop: () -> Unit = { readlnOrNull() },
) : Profiler {
    override fun execute(
        packageName: String,
        output: Path,
    ) {
        // Older devices rely on a sideloaded tracebox to pick up newer tracing capabilities.
        var perfettoBinary = "perfetto"

        val fileOnDevice =
            if (adb.sdkVersion < 29) {
                sideloadPerfetto(adb)
                perfettoBinary = TRACEBOX_PATH
                "/data/local/tmp/trace.perfetto-trace"
            } else {
                "/data/misc/perfetto-traces/trace2.perfetto-trace"
            }

        if (adb.sdkVersion >= 28) {
            // Needed only on Android 9 (P) and 10 (Q) on non-Pixel phones.
            adb.setProp("persist.traced.enable", "1")
        }

        adb.delete(fileOnDevice, force = true, ignoreErrors = true)

        val providedConfig =
            try {
                perfettoOptions.configPb
            } catch (_: IllegalStateException) {
                null
            }
        val configToPush =
            providedConfig ?: Files.createTempFile("perfetto_config", ".pb").apply {
                toFile().writeBytes(createPerfettoConfig(packageName, adb).toByteArray())
            }
        adb.push(configToPush.toString(), "/data/local/tmp/perfetto_config.pb")

        val perfettoCommand = "adb ${adb.deviceOpts} shell 'cat /data/local/tmp/perfetto_config.pb | $perfettoBinary -c - -o $fileOnDevice'"
        val perfettoProc = shell.startProcess(perfettoCommand)
        val processName = perfettoBinary.substringAfterLast("/")
        val pid = waitForPerfettoStart(processName)

        Logger.info("Press any key to end tracing...")
        awaitStop()

        // Some device support `kill` and others `killall`
        try {
            adb.shell("kill -TERM $pid")
            shell.waitFor(perfettoProc)
        } catch (_: Exception) {
            try {
                adb.shell("killall $processName")
                shell.waitFor(perfettoProc)
            } catch (_: Exception) {
                shell.waitFor(perfettoProc, 5, TimeUnit.SECONDS)
            }
        }

        Logger.info("Pulling trace from device...")
        // TODO: Can this use pull instead? I recall, there are some older OS versions that have some security restrictions.
        adb.shell("cat $fileOnDevice > $output")
    }

    private fun waitForPerfettoStart(processName: String): String {
        val deadline = System.currentTimeMillis() + startupTimeoutMs
        while (System.currentTimeMillis() < deadline) {
            adb.pidof(processName)?.let { return it }
            Thread.sleep(100L)
        }
        throw PrintMessage("Perfetto did not start within ${startupTimeoutMs}ms.", printError = true)
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
                            // Macrobenchmark uses dry-run mode to run one measurement iteration.
                            "androidx.benchmark.dryRunMode.enable" to "true",
                            // Emulator traces validate collection, but aren't useful for performance comparisons.
                            "androidx.benchmark.suppressErrors" to "EMULATOR",
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
                matches = { it.endsWith(".perfetto-trace") },
            )
                ?: throw PrintMessage("No perfetto trace found by instrumentation test in $outputDir", printError = true)

        adb.pull(trace, output.toString())
    }
}

/**
 * Sideload Perfetto binary onto device
 */
private fun sideloadPerfetto(adb: Adb) {
    val binaryArtifacts =
        mapOf(
            "arm64-v8a" to
                TraceboxArtifact(
                    "https://commondatastorage.googleapis.com/perfetto-luci-artifacts/$PERFETTO_TRACEBOX_VERSION/android-arm64/tracebox",
                    "1f3fdf7c23134eb6ef7393ea914b3ea8c0acb74c46230282366b3cb4502b6b7c",
                ),
            "armeabi-v7a" to
                TraceboxArtifact(
                    "https://commondatastorage.googleapis.com/perfetto-luci-artifacts/$PERFETTO_TRACEBOX_VERSION/android-arm/tracebox",
                    "d53456f9c857c58e2410eeda3710aac7596059d5c9f509d66f962f41280b7f3a",
                ),
            "x86_64" to
                TraceboxArtifact(
                    "https://commondatastorage.googleapis.com/perfetto-luci-artifacts/$PERFETTO_TRACEBOX_VERSION/android-x64/tracebox",
                    "eace8af8734d420d6e8245f968bfb41ed334364d29bc8ac3be15dc1206714239",
                ),
        )

    val artifact = binaryArtifacts[adb.abi] ?: throw PrintMessage("Unexpected ABI: ${adb.abi}", printError = true)
    if (adb.ls("/data/local/tmp/").contains("tracebox") &&
        adb.shell("sha256sum $TRACEBOX_PATH", ignoreErrors = true).substringBefore(" ").trim() == artifact.sha256
    ) {
        return
    }

    Logger.info("Sideloading Perfetto $PERFETTO_TRACEBOX_VERSION onto device")

    val traceboxPath = Files.createTempFile("tracebox", "")
    downloadVerified(artifact.url, traceboxPath, artifact.sha256)
    adb.push(traceboxPath.toString(), TRACEBOX_PATH)
    adb.shell("chmod +x $TRACEBOX_PATH")
}
