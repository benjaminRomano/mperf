package com.bromano.mobile.perf.profilers.perfetto

import com.bromano.mobile.perf.PerfettoOptions
import com.bromano.mobile.perf.profilers.Profiler
import com.bromano.mobile.perf.utils.Adb
import com.bromano.mobile.perf.utils.Logger
import com.bromano.mobile.perf.utils.Shell
import com.github.ajalt.clikt.core.PrintMessage
import java.io.FileOutputStream
import java.net.URI
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

private const val TRACEBOX_PATH = "/data/local/tmp/tracebox"

class PerfettoProfiler(
    val shell: Shell,
    val adb: Adb,
    val perfettoOptions: PerfettoOptions,
    private val awaitStop: () -> Unit = { readln() },
) : Profiler {
    override fun execute(
        packageName: String,
        output: Path,
    ) {
        // TODO: Always sideload latest Perfetto version
        // Right now, just sideloading current perfetto version (as of time of writing) onto older devices
        // that do not have Perfetto with adequate capabilities or not pre-installed.
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

        // TODO: An alternative methodology to communicate with perfetto process should be explored.
        // We attempt to print out the press any key statement after the Perfetto output
        Thread.sleep(1000)
        Logger.info("Press any key to end tracing...")
        awaitStop()

        val processName = perfettoBinary.split("/").last()
        val pid =
            adb.pidof(processName)
                ?: throw PrintMessage("Running Perfetto process was not found.", printError = true)

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
                append("-e androidx.benchmark.dryRunMode.enable true ")
                // Always allow emulators. This won't produce useful performance data but can be heplful for
                // debugging
                append("-e androidx.benchmark.suppressErrors \"EMULATOR\" ")
                append(instrumentationRunner)
            },
        )

        Logger.info("Test complete. Pulling trace...")

        val outputDir = adb.getDirUsableByAppAndShell(instrumentationRunner.substringBefore("/"))
        val trace =
            adb.ls(outputDir).firstOrNull { it.endsWith(".perfetto-trace") }
                ?: throw PrintMessage("No perfetto trace found by instrumentation test in $outputDir", printError = true)

        adb.pull("$outputDir$trace", output.toString())
    }
}

/**
 * Sideload Perfetto binary onto device
 */
private fun sideloadPerfetto(adb: Adb) {
    // TODO: Check if up-to-date in the future
    if (adb.ls("/data/local/tmp/").contains("tracebox")) {
        return
    }

    // TODO: Find a way to ensure we are sideloading latest available version
    Logger.info("Sideloading Perfetto v51.2 onto device")

    val binaryUrls =
        mapOf(
            "arm64-v8a" to "https://commondatastorage.googleapis.com/perfetto-luci-artifacts/v51.2/android-arm64/tracebox",
            "armeabi-v7a" to "https://commondatastorage.googleapis.com/perfetto-luci-artifacts/v51.2/android-arm/tracebox",
            "x86_64" to "https://commondatastorage.googleapis.com/perfetto-luci-artifacts/v51.2/android-x64/tracebox",
        )

    val binaryUrl = binaryUrls[adb.abi] ?: throw PrintMessage("Unexpected ABI: ${adb.abi}", printError = true)
    val traceboxPath = Files.createTempFile("tracebox", "")
    URI(binaryUrl).toURL().openStream().use {
        Channels.newChannel(it).use { rbc ->
            FileOutputStream(traceboxPath.toString()).use { fos ->
                fos.channel.transferFrom(rbc, 0, Long.MAX_VALUE)
            }
        }
    }
    adb.push(traceboxPath.toString(), TRACEBOX_PATH)
    adb.shell("chmod +x $TRACEBOX_PATH")
}
