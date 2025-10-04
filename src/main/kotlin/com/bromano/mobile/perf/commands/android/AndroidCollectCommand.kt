package com.bromano.mobile.perf.commands.android

import com.bromano.mobile.perf.Config
import com.bromano.mobile.perf.ProfilerFormat
import com.bromano.mobile.perf.profilerOptions
import com.bromano.mobile.perf.profilers.ProfilerExecutor
import com.bromano.mobile.perf.utils.Adb
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

class AndroidCollectCommand(
    private val shell: Shell,
    private val config: Config,
    private val executor: ProfilerExecutor,
) : CliktCommand(name = "collect") {
    override fun help(context: Context) = "Collect performance data over single iteration of a performance test"

    private val outputPath by option("-o", "--out", help = "Output path for trace")
        .path(mustExist = false, canBeDir = false)

    private val packageName by option("-p", "--package", help = "Package name")

    private val instrumentationRunner by option(
        "-i",
        "--instrumentation",
        help = "Instrumentation runner (e.g. com.example.macrobenchmark/androidx.test.runner.AndroidJUnitRunner)",
    )

    private val device by option("-d", "--device", help = "Device serial")

    private val testCase by option("-t", "--test", help = "Performance test to run")

    // TODO: As-is, this will allow users to provide UI options which are not valid for certain profilers.
    // There doesn't seem to be an easy way to override this based on the ProfilerOptionGroup sub-class
    val profileViewerOverride by option("--ui", help = "Profile viewer to open trace in")
        .enum<ProfileViewer>()

    // TODO: Profiler Option values are not used for Benchmark command.
    // Re-think the approach here
    private val profilerOption by profilerOptions(
        setOf(ProfilerFormat.PERFETTO, ProfilerFormat.SIMPLEPERF, ProfilerFormat.METHOD),
        ProfilerFormat.PERFETTO,
    )

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

        val adb = Adb(finalDevice, shell)

        val finalInstrumentationRunner =
            instrumentationRunner ?: config.android?.instrumentationRunner ?: shell.selectChoice(adb.findInstrumentationRunners())
                ?: throw PrintMessage("Failed to find instrumentation test runners for package: $finalPackageName")

        // Test case can be provided either as either FQN or just the method name
        val testCases =
            adb
                .getTests(finalInstrumentationRunner)
                .filter { testCase == null || it == testCase || it.split("#")[1] == testCase!! }

        val finalTestCase =
            shell.selectChoice(testCases, "Select a test case:")
                ?: throw PrintMessage("Failed to find test case for instrumentation test runner: $finalInstrumentationRunner")

        executor.executeTest(
            profilerOption,
            shell,
            finalDevice,
            finalPackageName,
            finalInstrumentationRunner,
            finalTestCase,
            finalOutputPath,
            profileViewerOverride,
        )
    }
}
