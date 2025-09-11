package com.bromano.mobile.perf.profilers.simpleperf

import com.bromano.mobile.perf.SimpleperfOptions
import com.bromano.mobile.perf.profilers.Profiler
import com.bromano.mobile.perf.utils.Adb
import com.bromano.mobile.perf.utils.Logger
import com.bromano.mobile.perf.utils.Shell
import com.github.ajalt.clikt.core.PrintMessage
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempFile
import kotlin.io.path.exists

private const val SIMPLEPERF_SIDELOAD_PATH = "/data/local/tmp/simpleperf"

/**
 * Collects a simpleperf profile using NDK simpleperf scripts (app_profiler.py).
 *
 * Requirements:
 * - ANDROID_NDK_HOME must be set and contain simpleperf/app_profiler.py
 * - Device selection is controlled via ANDROID_SERIAL environment variable
 */
class SimpleperfProfiler(
    private val shell: Shell,
    private val adb: Adb,
    private val options: SimpleperfOptions,
    private val awaitStop: () -> Unit = { readln() },
    private val shutdownTimeoutMs: Long = TimeUnit.SECONDS.toMillis(60),
) : Profiler {
    override fun execute(
        packageName: String,
        output: Path,
    ) {
        var simpleperfBinary = "simpleperf"
        var bufferSize = ""

        // The default user buffer size for simpleperf can lead to corrupted stacktraces.
        // https://android.googlesource.com/platform/system/extras/+/ee0f808682635b1b5885585559f62a139b07ecfc
        // Unfortunately, `--user-buffer-size` can only be modified on a rooted device.
        if (adb.isRootable()) {
            // TODO: Can we use sideloaded binaries on non-rooted devices as well?
            sideloadSimpleperf(adb)
            bufferSize = "--user-buffer-size 1G"
            simpleperfBinary = SIMPLEPERF_SIDELOAD_PATH
        }

        val onDevicePerfData = "/data/local/tmp/perf.data"
        adb.delete(onDevicePerfData, ignoreErrors = true)

        // Complex simpleperf args require escaping when running with root
        val simpleperfCommand =
            "adb ${adb.deviceOpts} " +
                adb.getShellEscapedCommand(
                    "$simpleperfBinary record --app $packageName -o $onDevicePerfData $bufferSize ${options.simpleperfArgs}",
                    withRoot = true,
                )
        Logger.debug("Running simpleperf command: $simpleperfCommand")

        val proc = shell.startProcess(simpleperfCommand)

        Logger.info("Press any key to end tracing...")
        awaitStop()

        Logger.info("Waiting for simpleperf to shutdown...")

        val pid =
            adb.pidof("simpleperf")
                ?: throw IllegalStateException("Couldn't find pid for `simpleperf`. Check adb logs for simpleperf failures")
        adb.shell("kill -2 $pid", withRoot = true)

        proc.destroy()
        waitForSimpleperfShutdown()

        val perfData = createTempFile("tmp", "data")
        adb.pull(onDevicePerfData, perfData.toString())

        convertToGecko(options, perfData, output)
    }

    /**
     * Wait for simpleperf to exit
     *
     * For longer profiler sessions or larger user buffer sizes, simpleperf can take a while to finalize.
     */
    private fun waitForSimpleperfShutdown() {
        val startTime = System.currentTimeMillis()
        val timeoutTime = System.currentTimeMillis() + shutdownTimeoutMs
        while (System.currentTimeMillis() < timeoutTime) {
            if (adb.shell("pidof simpleperf", withRoot = true, ignoreErrors = true).isBlank()) {
                Logger.debug("simpleperf stopped in ${System.currentTimeMillis() - startTime}ms")
                return
            }

            Thread.sleep(500L)
        }
        throw IllegalStateException("Failed to shutdown simpleperf within timeout")
    }

    /**
     * Sideload latest copy of simpleperf to backport `--user-buffer-size` and any other bug fixes
     */
    private fun sideloadSimpleperf(adb: Adb) {
        // https://android.googlesource.com/platform/prebuilts/simpleperf/+/a63e5b546388f4b947d1b310ab3d9bada63bb242
        val binaryInfoMap =
            mapOf(
                "arm64-v8a" to
                    Pair(
                        "https://android.googlesource.com/platform/prebuilts/simpleperf/+/a63e5b546388f4b947d1b310ab3d9bada63bb242/bin/android/arm64/simpleperf?format=TEXT",
                        "e80b79d38160a9ec4d6cd8f06ab39e28",
                    ),
                "armeabi-v7a" to
                    Pair(
                        "https://android.googlesource.com/platform/prebuilts/simpleperf/+/a63e5b546388f4b947d1b310ab3d9bada63bb242/bin/android/arm/simpleperf?format=TEXT",
                        "e3c0c494c2164cdf4f80f43774d84037",
                    ),
                "x86_64" to
                    Pair(
                        "https://android.googlesource.com/platform/prebuilts/simpleperf/+/a63e5b546388f4b947d1b310ab3d9bada63bb242/bin/android/x86_64/simpleperf?format=TEXT",
                        "44d9c3c8db54db8968dfb5e99fe0cdcf",
                    ),
            )

        val binaryInfo = binaryInfoMap[adb.abi] ?: throw IllegalStateException("Unsupported ABI: ${adb.abi}")

        // Check if already up-to-date before downloading and sideloading.
        if (adb.shell("ls /data/local/tmp").contains("simpleperf") &&
            adb.shell("md5sum $SIMPLEPERF_SIDELOAD_PATH").split(" ")[0].trim() == binaryInfo.second
        ) {
            return
        }

        Logger.info("Sideloading simpleperf binary...")
        val tempFile =
            File.createTempFile("simpleperf", null).apply {
                deleteOnExit()
            }

        // Download from AOSP which returns base64 when using ?format=TEXT, then decode before writing.
        URI(binaryInfo.first).toURL().openStream().use { input ->
            val encoded = input.readAllBytes()
            val decoded = Base64.getDecoder().decode(encoded)
            Files.write(tempFile.toPath(), decoded)
        }

        adb.push(tempFile.toString(), SIMPLEPERF_SIDELOAD_PATH)
        adb.shell("chmod +x $SIMPLEPERF_SIDELOAD_PATH")
    }

    /**
     * Convert perf data to Gecko format used by Firefox Profiler.
     */
    private fun convertToGecko(
        simpleperfOptions: SimpleperfOptions,
        input: Path,
        output: Path,
    ) {
        val geckoConverter = getSimpleperfScripts().resolve("gecko_profile_generator.py")

        val symFsOption = simpleperfOptions.symfs?.let { "--symfs $it" } ?: ""
        val mappingOption = simpleperfOptions.mapping?.let { "--proguard-mapping-file $it" } ?: ""
        val showArtFrames = if (simpleperfOptions.showArtFrames) "--short_art_frames" else ""

        // Ensure regexes are escaped
        val removeMethodArgs =
            if (simpleperfOptions.removeMethods.isNotEmpty()) {
                "--remove-method ${simpleperfOptions.removeMethods.joinToString(" ") { "\"$it\"" }}"
            } else {
                ""
            }

        // TODO: We may want to ensure that user has recent enough version of NDK that contains this fix:
        //  https://android.googlesource.com/platform//system/extras/+/5cd09ef39d97a6332d12031ecafe2366f42220f7
        val command = "python3 $geckoConverter -i $input $symFsOption $mappingOption $showArtFrames $removeMethodArgs | gzip > $output"
        Logger.debug("Converting perf data to gecko (command: $command")

        shell.runCommand(command)
    }

    /**
     * We need the latest version of simpleperf scripts with the `--remove-method` functionality
     */
    private fun getSimpleperfScripts(): Path {
        val simpleperfHome = Path.of(System.getProperty("user.home")).resolve(".mperf/simpleperf")

        if (simpleperfHome.exists()) {
            return simpleperfHome
        }

        val simpleperfArchive = "https://android.googlesource.com/platform/system/extras/+archive/refs/heads/main/simpleperf/scripts.tar.gz"
        val tempFile =
            File.createTempFile("simpleperf.tar.gz", null).apply {
                deleteOnExit()
            }

        Logger.info("Installing simpleperf scripts...")

        URI(simpleperfArchive).toURL().openStream().use {
            Channels.newChannel(it).use { rbc ->
                FileOutputStream(tempFile).use { fos ->
                    fos.channel.transferFrom(rbc, 0, Long.MAX_VALUE)
                }
            }
        }

        simpleperfHome.toFile().mkdirs()
        shell.runCommand("tar -xzf $tempFile -C $simpleperfHome")

        return simpleperfHome
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
                // append("-e androidx.benchmark.profiling.sampleFrequency 4000 ")
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
                ?: throw PrintMessage("No simpleperf trace found by instrumentation test in $outputDir", printError = true)

        adb.pull("$outputDir$trace", output.toString())
    }
}
