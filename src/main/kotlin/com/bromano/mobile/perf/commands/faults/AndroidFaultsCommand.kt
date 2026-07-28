package com.bromano.mobile.perf.commands.faults

import com.bromano.mobile.perf.Config
import com.bromano.mobile.perf.faults.FaultEngine
import com.bromano.mobile.perf.utils.Shell
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.validate
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path

class AndroidFaultsCommand(
    private val shell: Shell,
    private val config: Config,
    engine: FaultEngine,
) : CliktCommand("android") {
    override fun help(context: Context) = "Collect exact Android startup faults and generate an interactive HTML report"

    private val support = FaultCommandSupport(shell, engine)

    private val packageName by option("-p", "--package", help = "Package name")
    private val activity by option("--activity", help = "Launch activity; resolved automatically when omitted")
    private val device by option("-d", "--device", help = "ADB device serial")
    private val output by option("-o", "--out", help = "Capture output directory")
        .path(mustExist = false, canBeFile = false)
    private val settleMs by option("--settle-ms", help = "Collection time after startup completes")
        .int()
        .default(750)
        .validate { require(it in 0..10_000) { "must be between 0 and 10000" } }
    private val maxResidentPages by option(
        "--max-resident-pages",
        help = "Maximum verified resident app-file pages allowed before launch",
    ).int()
        .default(0)
        .validate { require(it >= 0) { "must be nonnegative" } }
    private val rebootBeforeCollect by option(
        "--reboot-before-collect",
        help = "Reboot the target before cache eviction and collection",
    ).flag(default = false)
    private val pullArtifacts by option("--pull-artifacts", help = "Pull APK and ART files for section attribution")
        .flag("--no-pull-artifacts", default = true)
    private val skipCollect by option(
        "--skip-collect",
        help = "Reprocess and report an existing exact capture",
    ).flag(default = false)
    private val overwrite by option(
        "--overwrite",
        help = "Replace a non-empty output owned by mperf faults",
    ).flag(default = false)
    private val compare by option("--compare", help = "Second capture directory")
        .path(mustExist = true, canBeFile = false)
    private val label by option("--label", help = "Primary capture label").default("Capture")
    private val compareLabel by option("--compare-label", help = "Comparison capture label").default("Comparison")
    private val allowIncomparable by option(
        "--allow-incomparable",
        help = "Allow an exploratory comparison despite provenance mismatches",
    ).flag(default = false)
    private val openReport by option("--open", help = "Open the generated HTML report")
        .flag("--no-open", default = true)

    override fun run() {
        val finalPackage = packageName ?: config.android?.packageName
        if (!skipCollect && finalPackage == null) {
            throw PrintMessage(
                "Package name must be provided via --package or in config.yml",
                printError = true,
                statusCode = 1,
            )
        }
        val finalOutput = (output ?: defaultFaultOutput("android")).absoluteNormalized()
        val engineDirectory = support.engineDirectory("android")

        val captureArguments =
            buildList {
                add("faults.py")
                finalPackage?.takeUnless { skipCollect }?.let {
                    add("--package")
                    add(it)
                }
                add("--output")
                add(finalOutput.toString())
                activity?.let {
                    add("--activity")
                    add(it)
                }
                device?.let {
                    add("--serial")
                    add(it)
                }
                add("--settle-ms")
                add(settleMs.toString())
                add("--max-resident-pages")
                add(maxResidentPages.toString())
                add(if (pullArtifacts) "--pull-apks" else "--no-pull-apks")
                if (rebootBeforeCollect) add("--reboot-before-collect")
                if (skipCollect) add("--skip-collect")
                if (overwrite) add("--overwrite")
            }
        support.run(engineDirectory, captureArguments)

        val report = finalOutput.resolve("report.html")
        val reportArguments =
            buildList {
                add("report.py")
                add(finalOutput.toString())
                add("--label")
                add(label)
                add("--output")
                add(report.toString())
                compare?.let {
                    add("--compare")
                    add(it.absoluteNormalized().toString())
                    add("--compare-label")
                    add(compareLabel)
                }
                if (allowIncomparable) add("--allow-incomparable")
            }
        support.run(engineDirectory, reportArguments)
        echo("Android fault report: $report")
        if (openReport) shell.open(report.toUri().toString())
    }
}
