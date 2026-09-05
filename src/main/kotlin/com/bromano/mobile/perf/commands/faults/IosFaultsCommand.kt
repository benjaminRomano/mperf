package com.bromano.mobile.perf.commands.faults

import com.bromano.mobile.perf.Config
import com.bromano.mobile.perf.faults.FaultEngine
import com.bromano.mobile.perf.utils.Shell
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.validate
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import kotlin.math.ceil

class IosFaultsCommand(
    private val shell: Shell,
    private val config: Config,
    engine: FaultEngine,
    workflow: IosFaultWorkflow? = null,
) : CliktCommand("ios") {
    override fun help(context: Context) =
        "Collect iOS startup VM faults and stacks with Instruments and generate an interactive HTML report"

    private val workflow = workflow ?: DefaultIosFaultWorkflow(engine)

    private val bundleIdentifier by option("-b", "--bundle", help = "Bundle identifier")
    private val app by option("--app", help = "Built .app bundle to install before capture")
        .path(mustExist = true, canBeDir = true, canBeFile = false)
    private val appBinaryName by option("--app-binary-name", help = "Executable used to identify app stack frames")
    private val device by option("-d", "--device", help = "Simulator or physical-device name/UDID")
    private val output by option("-o", "--out", help = "Capture output directory")
        .path(mustExist = false, canBeFile = false)
    private val cachePolicy by option(
        "--cache-policy",
        help = "Cache policy: auto, purge, pressure, reboot, or none",
    ).default("auto")
        .validate { require(it in setOf("auto", "purge", "pressure", "reboot", "none")) { "unsupported policy" } }
    private val requireColdCache by option(
        "--require-cold-cache",
        help = "Fail unless Simulator app-file residency confirms eviction",
    ).flag(default = false)
    private val allowHostPressure by option(
        "--allow-host-pressure",
        help = "Allow Simulator auto policy to fall back to memory pressure",
    ).flag(default = false)
    private val allowUnconfirmedCache by option(
        "--allow-unconfirmed-cache",
        help = "Continue when Simulator mincore cannot confirm eviction",
    ).flag(default = false)
    private val residencyThreshold by option(
        "--residency-threshold",
        help = "Maximum post-action Simulator app-file residency fraction",
    ).double()
        .default(0.05)
        .validate { require(it in 0.0..1.0) { "must be between 0 and 1" } }
    private val developmentTeam by option("--development-team", help = "Apple development team for physical helper signing")
    private val pressureFraction by option("--pressure-fraction", help = "Physical-device memory pressure fraction")
        .double()
        .default(0.15)
        .validate { require(it in 0.1..0.9) { "must be between 0.1 and 0.9" } }
    private val pressureMegabytes by option("--pressure-megabytes", help = "Fixed helper allocation in MiB")
        .int()
        .validate { require(it >= 16) { "must be at least 16" } }
    private val pressureHoldSeconds by option("--pressure-hold-seconds", help = "How long the helper holds memory")
        .int()
        .default(6)
        .validate { require(it >= 1) { "must be positive" } }
    private val settleSeconds by option("--settle-seconds", help = "Analyzed startup window in seconds")
        .double()
        .default(3.0)
        .validate { require(it > 0) { "must be positive" } }
    private val timeLimit by option("--time-limit", help = "Maximum recording duration in seconds")
        .int()
        .validate { require(it > 0) { "must be positive" } }
    private val appArguments by option("--app-argument", help = "Argument passed to the target app")
        .multiple()
    private val overwrite by option("--overwrite", help = "Replace an owned mperf faults capture directory")
        .flag(default = false)
    private val skipCollect by option("--skip-collect", help = "Reprocess an existing capture")
        .flag(default = false)
    private val openReport by option("--open", help = "Open the generated HTML report")
        .flag("--no-open", default = true)

    override fun run() {
        val finalBundle =
            bundleIdentifier
                ?: config.ios?.bundleIdentifier?.takeIf { app == null }
        if (!skipCollect && finalBundle == null && app == null) {
            throw PrintMessage(
                "Bundle identifier must be provided via --bundle/config.yml, or supply --app",
                printError = true,
                statusCode = 1,
            )
        }
        if (requireColdCache && allowUnconfirmedCache) {
            throw PrintMessage(
                "--require-cold-cache cannot be combined with --allow-unconfirmed-cache",
                printError = true,
                statusCode = 1,
            )
        }
        val minimumTimeLimit = ceil(settleSeconds).toInt()
        val requestedTimeLimit = timeLimit
        if (requestedTimeLimit != null && requestedTimeLimit < minimumTimeLimit) {
            throw PrintMessage(
                "--time-limit must be at least ceil(--settle-seconds) " +
                    "($minimumTimeLimit seconds)",
                printError = true,
                statusCode = 1,
            )
        }
        val finalDevice = device ?: config.ios?.deviceId ?: "booted"
        val finalOutput = (output ?: defaultFaultOutput("ios")).absoluteNormalized()
        val report =
            workflow.run(
                IosFaultRequest(
                    bundleIdentifier = finalBundle,
                    app = app?.absoluteNormalized(),
                    appBinaryName = appBinaryName,
                    device = finalDevice,
                    output = finalOutput,
                    cachePolicy = cachePolicy,
                    requireColdCache = requireColdCache,
                    allowHostPressure = allowHostPressure,
                    allowUnconfirmedCache = allowUnconfirmedCache,
                    residencyThreshold = residencyThreshold,
                    developmentTeam = developmentTeam,
                    pressureFraction = pressureFraction,
                    pressureMegabytes = pressureMegabytes,
                    pressureHoldSeconds = pressureHoldSeconds,
                    settleSeconds = settleSeconds,
                    timeLimit = timeLimit,
                    appArguments = appArguments,
                    overwrite = overwrite,
                    skipCollect = skipCollect,
                ),
            )
        echo("iOS fault report: $report")
        if (openReport) shell.open(report.toUri().toString())
    }
}
