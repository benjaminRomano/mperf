package com.bromano.mobile.perf.commands.android

import com.bromano.mobile.perf.Config
import com.bromano.mobile.perf.androidProfilerOptions
import com.bromano.mobile.perf.profilers.ProfilerExecutor
import com.bromano.mobile.perf.utils.ProfileViewer
import com.bromano.mobile.perf.utils.Shell
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.path
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class StartCommand(
    private val shell: Shell,
    private val config: Config,
    private val executor: ProfilerExecutor,
) : CliktCommand() {
    override fun help(context: Context) = "Run profiler over abitrary app session"

    private val profilerOption by androidProfilerOptions()
    private val outputPath by option("-o", "--out", help = "Output path for trace")
        .path(mustExist = false, canBeDir = false)

    private val packageName by option("-p", "--package", help = "Package name")

    private val device by option("-d", "--device", help = "Device serial")

    // TODO: As-is, this will allow users to provide UI options which are not valid for certain profilers.
    // There doesn't seem to be an easy way to override this based on the ProfilerOptionGroup sub-class
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

        val finalPackageName =
            packageName ?: config.android?.packageName
                ?: throw PrintMessage("Package name must be provided via --package or in config.yml", printError = true)

        val finalDevice =
            device
                ?: shell.selectChoice(shell.getConnectedAndroidDevices())
                ?: throw PrintMessage("No devices connected", printError = true)

        executor.execute(
            profilerOption,
            shell,
            finalDevice,
            finalPackageName,
            finalOutputPath,
            profileViewerOverride,
        )
    }
}
