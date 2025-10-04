package com.bromano.mobile.perf.commands.ios

import com.bromano.mobile.perf.Config
import com.bromano.mobile.perf.iosProfilerOptions
import com.bromano.mobile.perf.profilers.ProfilerExecutor
import com.bromano.mobile.perf.utils.ProfileViewer
import com.bromano.mobile.perf.utils.Shell
import com.bromano.mobile.perf.utils.XcodeUtils
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.path
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.absolute
import kotlin.text.RegexOption

class IosStartCommand(
    private val shell: Shell,
    private val config: Config,
    private val executor: ProfilerExecutor,
) : CliktCommand("start") {
    override fun help(context: Context) = "Run iOS Instruments profiler over arbitrary app session"

    private val profilerOption by iosProfilerOptions()
    private val outputPath by option("-o", "--out", help = "Output path for trace")
        .path(mustExist = false, canBeDir = false)

    private val bundleIdentifier by option("-b", "--bundle", help = "Bundle identifier (e.g. com.example.app)")

    private val device by option("-d", "--device", help = "Device/Simulator UDID")

    val profileViewerOverride by option("--ui", help = "Profile viewer to open trace in")
        .enum<ProfileViewer>()

    override fun run() {
        val finalOutputPath =
            outputPath
                ?: Paths.get(
                    "artifacts",
                    "trace_out",
                    "${profilerOption.format.name.lowercase()}-${LocalDateTime.now().format(
                        DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm"),
                    )}.trace",
                )
        finalOutputPath.parent.toFile().mkdirs()
        println(finalOutputPath.absolute())

        val finalBundleIdentifier =
            bundleIdentifier ?: config.ios?.bundleIdentifier
                ?: throw PrintMessage(
                    message = "Bundle identifier must be provided via --bundle or in config.yml",
                    printError = true,
                    statusCode = 1,
                )

        // Query available devices without binding to a specific UDID yet
        val availableDevices = XcodeUtils(null, shell).getAvailableDevices()

        val finalDevice =
            device
                ?: shell
                    .selectChoice(availableDevices, "Select a device/simulator:")
                    ?.let { selection ->
                        // Extract UDID from selection format: "Device: Name (UDID)" or "Simulator: Name (UDID) [State]"
                        val regex = """\(([A-F0-9-]{36,40})\)""".toRegex(setOf(RegexOption.IGNORE_CASE))
                        regex.find(selection)?.groupValues?.get(1)
                    }
                ?: throw PrintMessage(
                    message = "No devices/simulators available",
                    printError = true,
                    statusCode = 1,
                )

        // Verify bundle identifier exists on the selected device/simulator
        val foundBundle =
            try {
                XcodeUtils(finalDevice, shell).findBundleIdentifier(finalBundleIdentifier.split(".").last())
            } catch (_: Throwable) {
                null
            }
        if (foundBundle == null) {
            println("Warning: Could not verify bundle identifier $finalBundleIdentifier on device")
            println("Proceeding anyway - ensure the app is installed and the bundle ID is correct")
        }

        executor.execute(
            profilerOption,
            shell,
            finalDevice,
            finalBundleIdentifier,
            finalOutputPath,
            profileViewerOverride,
        )
    }
}
