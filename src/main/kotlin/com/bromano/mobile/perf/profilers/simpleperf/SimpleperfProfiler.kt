package com.bromano.mobile.perf.profilers.simpleperf

import com.bromano.mobile.perf.SimpleperfOptions
import com.bromano.mobile.perf.profilers.Profiler
import com.bromano.mobile.perf.utils.Adb
import com.bromano.mobile.perf.utils.Logger
import com.bromano.mobile.perf.utils.Shell
import com.bromano.mobile.perf.utils.downloadVerified
import com.github.ajalt.clikt.core.PrintMessage
import java.io.File
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempFile
import kotlin.io.path.exists

private const val SIMPLEPERF_SIDELOAD_PATH = "/data/local/tmp/simpleperf"
private const val SIMPLEPERF_PREBUILTS_COMMIT = "77a9d28fd775ac32868475335d5786fdf99a9b80"
private const val SIMPLEPERF_SCRIPTS_ARCHIVE_COMMIT = "fc2494a2abd7ab21774d03deb09c1362bbb0bba8"
private const val SIMPLEPERF_SCRIPTS_ARCHIVE_SHA256 = "fbb1228a74315941ef36c4510ba4b88726ea81fe29ee5e5e966b2ac85dd913c4"
private const val BENCHMARK_STACK_SAMPLING_MODE = "StackSampling"
private const val INSTRUMENTATION_ADDITIONAL_OUTPUT_PREFIX = "INSTRUMENTATION_STATUS: additionalTestOutputFile_"

private data class SimpleperfBinaryInfo(
    val url: String,
    val md5: String,
    val sha256: String,
)

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
    private val awaitStop: () -> Unit = { readlnOrNull() },
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
        // https://android.googlesource.com/platform/prebuilts/simpleperf/+log/refs/heads/mirror-goog-main-prebuilts/bin/android/arm64/simpleperf
        val binaryInfoMap =
            mapOf(
                "arm64-v8a" to
                    SimpleperfBinaryInfo(
                        "https://android.googlesource.com/platform/prebuilts/simpleperf/+/$SIMPLEPERF_PREBUILTS_COMMIT/bin/android/arm64/simpleperf?format=TEXT",
                        "545d135f070494bba7b5fe4b09046682",
                        "1cf95ed65cff9f6030f90b50dd0e2251725305d686af1b25a91eb51891074f76",
                    ),
                "armeabi-v7a" to
                    SimpleperfBinaryInfo(
                        "https://android.googlesource.com/platform/prebuilts/simpleperf/+/$SIMPLEPERF_PREBUILTS_COMMIT/bin/android/arm/simpleperf?format=TEXT",
                        "a3b40bbf6ff8d6e8b49facc41f96e890",
                        "aba36e7925dfd586501bc112a4b7e113c64e5af51b3d8ec91e61797e7b3d9951",
                    ),
                "x86_64" to
                    SimpleperfBinaryInfo(
                        "https://android.googlesource.com/platform/prebuilts/simpleperf/+/$SIMPLEPERF_PREBUILTS_COMMIT/bin/android/x86_64/simpleperf?format=TEXT",
                        "0923414737427e2db3b8aa5ae29af9e0",
                        "c35ac8e3ff043375396e5cf12ef825e4b4f76b6ed05a86d7ad8d89547ff5fad4",
                    ),
            )

        val binaryInfo = binaryInfoMap[adb.abi] ?: throw IllegalStateException("Unsupported ABI: ${adb.abi}")

        // Check if already up-to-date before downloading and sideloading.
        if (adb.shell("ls /data/local/tmp").contains("simpleperf") &&
            adb.shell("md5sum $SIMPLEPERF_SIDELOAD_PATH").split(" ")[0].trim() == binaryInfo.md5
        ) {
            return
        }

        Logger.info("Sideloading simpleperf binary...")
        val tempFile =
            File.createTempFile("simpleperf", null).apply {
                deleteOnExit()
            }

        downloadVerified(binaryInfo.url, tempFile.toPath(), binaryInfo.sha256) { input ->
            // googlesource ?format=TEXT endpoints return a base64-encoded file body.
            Base64.getDecoder().decode(input.readAllBytes())
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

        val simpleperfArchive =
            "https://android.googlesource.com/platform/system/extras/+archive/$SIMPLEPERF_SCRIPTS_ARCHIVE_COMMIT/simpleperf/scripts.tar.gz"
        val tempFile =
            File.createTempFile("simpleperf.tar.gz", null).apply {
                deleteOnExit()
            }

        Logger.info("Installing simpleperf scripts...")
        downloadVerified(simpleperfArchive, tempFile.toPath(), SIMPLEPERF_SCRIPTS_ARCHIVE_SHA256)

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

        val instrumentationOutput =
            adb.shell(
                buildString {
                    append("am instrument -w -r ")
                    append("-e class \"$testCase\" ")
                    append("-e androidx.benchmark.suppressErrors \"EMULATOR\" ")
                    append("-e androidx.benchmark.dryRunMode.enable true ")
                    append("-e androidx.benchmark.profiling.mode $BENCHMARK_STACK_SAMPLING_MODE ")
                    append(instrumentationRunner)
                },
            )

        Logger.info("Test complete. Pulling trace...")

        val outputDir = adb.getDirUsableByAppAndShell(instrumentationRunner.substringBefore("/"))
        val trace =
            findBenchmarkTracePath(instrumentationOutput)
                ?: adb
                    .ls(outputDir)
                    .firstOrNull { it.endsWith(".perfetto-trace") }
                    ?.let { "$outputDir$it" }
                ?: throw PrintMessage("No simpleperf trace found by instrumentation test in $outputDir", printError = true)

        adb.pull(trace, output.toString())
    }

    private fun findBenchmarkTracePath(instrumentationOutput: String): String? =
        instrumentationOutput
            .lineSequence()
            .filter { it.startsWith(INSTRUMENTATION_ADDITIONAL_OUTPUT_PREFIX) }
            .map { it.substringAfter("=").trim() }
            .firstOrNull { it.endsWith(".perfetto-trace") }
}
