package com.bromano.mobile.perf.profilers.simpleperf

import com.bromano.mobile.perf.SimpleperfOptions
import com.bromano.mobile.perf.profilers.Profiler
import com.bromano.mobile.perf.profilers.buildBenchmarkInstrumentationCommand
import com.bromano.mobile.perf.profilers.findNewBenchmarkOutput
import com.bromano.mobile.perf.profilers.validateBenchmarkInstrumentationOutput
import com.bromano.mobile.perf.tools.SimpleperfTools
import com.bromano.mobile.perf.utils.Adb
import com.bromano.mobile.perf.utils.Logger
import com.bromano.mobile.perf.utils.Shell
import com.bromano.mobile.perf.utils.shellQuote
import com.github.ajalt.clikt.core.PrintMessage
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import kotlin.io.path.createTempFile

private const val BENCHMARK_STACK_SAMPLING_MODE = "StackSampling"

/**
 * Collects a simpleperf profile on-device and converts it to a Gecko profile on the host.
 */
class SimpleperfProfiler(
    private val shell: Shell,
    private val adb: Adb,
    private val options: SimpleperfOptions,
    private val startupTimeoutMs: Long = TimeUnit.SECONDS.toMillis(10),
    private val shutdownTimeoutMs: Long = TimeUnit.SECONDS.toMillis(60),
    private val awaitStop: () -> Unit = { readlnOrNull() },
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
            SimpleperfTools(shell).sideload(adb)
            bufferSize = "--user-buffer-size 1G"
            simpleperfBinary = SimpleperfTools.DEVICE_PATH
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
        val pid = waitForSimpleperfStart()

        Logger.info("Press any key to end tracing...")
        awaitStop()

        Logger.info("Waiting for simpleperf to shutdown...")

        adb.shell("kill -2 $pid", withRoot = true)

        proc.destroy()
        waitForSimpleperfShutdown()

        val perfData = createTempFile("tmp", "data")
        adb.pull(onDevicePerfData, perfData.toString())

        convertToGecko(options, perfData, output)
    }

    private fun waitForSimpleperfStart(): String {
        val deadline = System.currentTimeMillis() + startupTimeoutMs
        while (System.currentTimeMillis() < deadline) {
            adb.pidof("simpleperf")?.let { return it }
            Thread.sleep(100L)
        }
        throw IllegalStateException("Couldn't start `simpleperf` within ${startupTimeoutMs}ms. Check adb logs for failures")
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
     * Convert perf data to Gecko format used by Firefox Profiler.
     */
    internal fun convertToGecko(
        simpleperfOptions: SimpleperfOptions,
        input: Path,
        output: Path,
    ) {
        val geckoConverter = SimpleperfTools(shell).scripts().resolve("gecko_profile_generator.py")

        val symFsOption = simpleperfOptions.symfs?.let { "--symfs ${shellQuote(it.toString())}" } ?: ""
        val mappingOption = simpleperfOptions.mapping?.let { "--proguard-mapping-file ${shellQuote(it.toString())}" } ?: ""
        val showArtFrames = if (simpleperfOptions.showArtFrames) "--show-art-frames" else ""

        // Ensure regexes are escaped
        val removeMethodArgs =
            if (simpleperfOptions.removeMethods.isNotEmpty()) {
                "--remove-method ${simpleperfOptions.removeMethods.joinToString(" ") { shellQuote(it) }}"
            } else {
                ""
            }

        val uncompressedProfile = Files.createTempFile("mperf-simpleperf", ".json")
        val command =
            "python3 ${shellQuote(geckoConverter.toString())} -i ${shellQuote(input.toString())} " +
                "$symFsOption $mappingOption $showArtFrames $removeMethodArgs > ${shellQuote(uncompressedProfile.toString())}"
        Logger.debug("Converting perf data to gecko (command: $command)")

        try {
            shell.runCommand(command)
            Files.newInputStream(uncompressedProfile).use { inputStream ->
                Files.newOutputStream(output).use { outputStream ->
                    GZIPOutputStream(outputStream).use(inputStream::copyTo)
                }
            }
        } catch (error: Exception) {
            Files.deleteIfExists(output)
            throw error
        } finally {
            Files.deleteIfExists(uncompressedProfile)
        }
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
                            "androidx.benchmark.suppressErrors" to "EMULATOR",
                            "androidx.benchmark.dryRunMode.enable" to "true",
                            "androidx.benchmark.profiling.mode" to BENCHMARK_STACK_SAMPLING_MODE,
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
                ?: throw PrintMessage("No simpleperf trace found by instrumentation test in $outputDir", printError = true)

        adb.pull(trace, output.toString())
    }
}
