package com.bromano.mobile.perf.profilers.simpleperf

import com.bromano.mobile.perf.SimpleperfOptions
import com.bromano.mobile.perf.profilers.Profiler
import com.bromano.mobile.perf.profilers.buildBenchmarkInstrumentationCommand
import com.bromano.mobile.perf.profilers.findNewBenchmarkOutput
import com.bromano.mobile.perf.profilers.validateBenchmarkInstrumentationOutput
import com.bromano.mobile.perf.utils.Adb
import com.bromano.mobile.perf.utils.Logger
import com.bromano.mobile.perf.utils.Shell
import com.bromano.mobile.perf.utils.downloadVerified
import com.bromano.mobile.perf.utils.sha256
import com.bromano.mobile.perf.utils.shellQuote
import com.github.ajalt.clikt.core.PrintMessage
import java.io.File
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import kotlin.io.path.createTempFile

private const val SIMPLEPERF_SIDELOAD_PATH = "/data/local/tmp/simpleperf"
private const val SIMPLEPERF_PREBUILTS_COMMIT = "829c2351dfc33886743931721b67b959c50a40ab"
private const val SIMPLEPERF_SCRIPTS_ARCHIVE_COMMIT = "fc2494a2abd7ab21774d03deb09c1362bbb0bba8"
private const val SIMPLEPERF_SCRIPTS_TREE_SHA256 = "deec6c145f678bc72897e10bd2c1eba96fff1a3b7044d504db030eb37b6abf8e"
private const val BENCHMARK_STACK_SAMPLING_MODE = "StackSampling"

private data class SimpleperfBinaryInfo(
    val url: String,
    val md5: String,
    val sha256: String,
)

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
     * Sideload latest copy of simpleperf to backport `--user-buffer-size` and any other bug fixes
     */
    private fun sideloadSimpleperf(adb: Adb) {
        // https://android.googlesource.com/platform/prebuilts/simpleperf/+log/refs/heads/mirror-goog-main-prebuilts/bin/android/arm64/simpleperf
        val binaryInfoMap =
            mapOf(
                "arm64-v8a" to
                    SimpleperfBinaryInfo(
                        "https://android.googlesource.com/platform/prebuilts/simpleperf/+/$SIMPLEPERF_PREBUILTS_COMMIT/bin/android/arm64/simpleperf?format=TEXT",
                        "2dca6449abf98f651135f544ce46a1cd",
                        "bf6d50d8ece60f5bc9c21aa3559993a99f868c0501040aeab3af0b911cb6a200",
                    ),
                "armeabi-v7a" to
                    SimpleperfBinaryInfo(
                        "https://android.googlesource.com/platform/prebuilts/simpleperf/+/$SIMPLEPERF_PREBUILTS_COMMIT/bin/android/arm/simpleperf?format=TEXT",
                        "fb62560abbe05af5c5a25a7959ded6ba",
                        "6c337d680bf287d417a0371694eb273662587eae86676758999808eedf651496",
                    ),
                "x86_64" to
                    SimpleperfBinaryInfo(
                        "https://android.googlesource.com/platform/prebuilts/simpleperf/+/$SIMPLEPERF_PREBUILTS_COMMIT/bin/android/x86_64/simpleperf?format=TEXT",
                        "1cb22468f644d35abb4a137caa785250",
                        "3bf255f996fc80426fc1ab90cf3bd28d845f3f6fc69e02cd5b4f6a11f65ae54c",
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
    internal fun convertToGecko(
        simpleperfOptions: SimpleperfOptions,
        input: Path,
        output: Path,
    ) {
        val geckoConverter = getSimpleperfScripts().resolve("gecko_profile_generator.py")

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

        // TODO: We may want to ensure that user has recent enough version of NDK that contains this fix:
        //  https://android.googlesource.com/platform//system/extras/+/5cd09ef39d97a6332d12031ecafe2366f42220f7
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

    /**
     * We need the latest version of simpleperf scripts with the `--remove-method` functionality
     */
    private fun getSimpleperfScripts(): Path {
        val simpleperfHome = Path.of(System.getProperty("user.home")).resolve(".mperf/simpleperf")
        val versionFile = simpleperfHome.resolve(".mperf-version")

        if (versionFile
                .toFile()
                .takeIf { it.isFile }
                ?.readText()
                ?.trim() == SIMPLEPERF_SCRIPTS_ARCHIVE_COMMIT &&
            simpleperfHome.resolve("gecko_profile_generator.py").toFile().isFile
        ) {
            return simpleperfHome
        }

        val simpleperfArchive =
            "https://android.googlesource.com/platform/system/extras/+archive/$SIMPLEPERF_SCRIPTS_ARCHIVE_COMMIT/simpleperf/scripts.tar.gz"
        val tempArchive =
            File.createTempFile("simpleperf.tar.gz", null).apply {
                deleteOnExit()
            }

        Logger.info("Installing simpleperf scripts...")
        URI(simpleperfArchive).toURL().openStream().use { input ->
            Files.copy(input, tempArchive.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }

        val archiveEntries = shell.runCommand("tar -tzf ${shellQuote(tempArchive.toString())}").lineSequence()
        require(
            archiveEntries.all { entry ->
                entry.isBlank() || Path.of(entry).let { !it.isAbsolute && !it.normalize().startsWith("..") }
            },
        ) { "Simpleperf scripts archive contains an unsafe path" }
        require(
            shell
                .runCommand("tar -tvzf ${shellQuote(tempArchive.toString())}")
                .lineSequence()
                .filter { it.isNotBlank() }
                .all { it.first() == '-' || it.first() == 'd' },
        ) { "Simpleperf scripts archive contains links or unsupported entries" }

        Files.createDirectories(simpleperfHome.parent)
        val extractedScripts = Files.createTempDirectory(simpleperfHome.parent, "simpleperf-install-")
        try {
            shell.runCommand("tar -xzf ${shellQuote(tempArchive.toString())} -C ${shellQuote(extractedScripts.toString())}")
            val actualTreeSha256 = calculateTreeSha256(extractedScripts)
            require(actualTreeSha256 == SIMPLEPERF_SCRIPTS_TREE_SHA256) {
                "Checksum verification failed for Simpleperf scripts at $SIMPLEPERF_SCRIPTS_ARCHIVE_COMMIT: " +
                    "expected $SIMPLEPERF_SCRIPTS_TREE_SHA256, got $actualTreeSha256"
            }

            simpleperfHome.toFile().deleteRecursively()
            Files.move(extractedScripts, simpleperfHome)
            versionFile.toFile().writeText("$SIMPLEPERF_SCRIPTS_ARCHIVE_COMMIT\n")
        } finally {
            extractedScripts.toFile().deleteRecursively()
        }

        return simpleperfHome
    }

    private fun calculateTreeSha256(root: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val files =
            Files.walk(root).use { paths ->
                paths
                    .filter(Files::isRegularFile)
                    .toList()
                    .sortedBy { root.relativize(it).toString() }
            }
        files.forEach { file ->
            val relativePath = root.relativize(file).joinToString("/")
            val manifestLine = "${sha256(file)}  ./$relativePath\n"
            digest.update(manifestLine.toByteArray(StandardCharsets.UTF_8))
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
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
