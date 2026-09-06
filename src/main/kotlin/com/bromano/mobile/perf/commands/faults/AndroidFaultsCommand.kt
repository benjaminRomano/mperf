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
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path

class AndroidFaultsCommand(
    private val shell: Shell,
    private val config: Config,
    private val engine: FaultEngine,
    workflow: AndroidFaultWorkflow? = null,
) : CliktCommand("android") {
    override fun help(context: Context) = "Collect exact Android startup faults and generate an interactive HTML report"

    private val workflow = workflow ?: DefaultAndroidFaultWorkflow(engine)

    private val packageName by option("-p", "--package", help = "Package name")
    private val activity by option("--activity", help = "Launch activity; resolved automatically when omitted")
    private val compilation by option(
        "--compilation",
        help = "Prepare verified profile-guided AOT before eviction, or preserve compilation for explicit experiments",
    ).choice("speed-profile", "as-is").default("speed-profile")
    private val device by option("-d", "--device", help = "ADB device serial")
    private val output by option("-o", "--out", help = "Capture output directory")
        .path(mustExist = false, canBeFile = false)
    private val settleMs by option("--settle-ms", help = "Collection time after startup completes")
        .int()
        .default(750)
        .validate { require(it in 0..10_000) { "must be between 0 and 10000" } }
    private val maxResidentPages by option(
        "--max-resident-pages",
        help = "Maximum verified resident app-file pages allowed before launch (strict default: 0)",
    ).int()
        .default(0)
        .validate { require(it >= 0) { "must be nonnegative" } }
    private val rebootBeforeCollect by option(
        "--reboot-before-collect",
        help = "Reboot the target before cache eviction and collection",
    ).flag(default = false)
    private val dwarfKernelPages by option("--dwarf-kernel-pages", help = "Simpleperf ring-buffer pages per CPU (power of two)")
        .int()
        .default(4096)
        .validate { require(it in 64..16384 && it and (it - 1) == 0) { "must be a power of two between 64 and 16384" } }
    private val dwarfUserBufferMb by option(
        "--dwarf-user-buffer-mb",
        help = "Simpleperf userspace buffer in MiB; larger buffers consume target RAM",
    ).int()
        .default(256)
        .validate { require(it in 16..2048) { "must be between 16 and 2048" } }
    private val nativeStacks by option(
        "--native-stacks",
        help = "Capture exact native/ART frame-pointer callchains with each fault",
    ).flag(default = false)
    private val dwarfStacks by option(
        "--dwarf-stacks",
        help = "Record system-wide major-fault DWARF/ART stacks; enrich only exact, verified event matches",
    ).flag(default = false)
    private val reclaimMappedApks by option(
        "--reclaim-mapped-apks",
        help = "Opt in to page-out advice on other processes' read-only installed APK mappings; strict cache checks remain",
    ).flag(default = false)
    private val pullArtifacts by option("--pull-artifacts", help = "Pull APK and ART files for section attribution")
        .flag("--no-pull-artifacts", default = true)
    private val ioEvidence by option(
        "--io-evidence",
        help = "Capture available block I/O and scheduler events; export separate startup context CSVs",
    ).flag(default = false)
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
        val report =
            workflow.run(
                AndroidFaultRequest(
                    packageName = finalPackage?.takeUnless { skipCollect },
                    activity = activity,
                    device = device,
                    output = finalOutput,
                    settleMs = settleMs,
                    maxResidentPages = maxResidentPages,
                    rebootBeforeCollect = rebootBeforeCollect,
                    nativeStacks = nativeStacks,
                    pullArtifacts = pullArtifacts,
                    skipCollect = skipCollect,
                    overwrite = overwrite,
                    comparison = compare?.absoluteNormalized(),
                    label = label,
                    comparisonLabel = compareLabel,
                    allowIncomparable = allowIncomparable,
                    dwarfStacks = dwarfStacks,
                    dwarfKernelPages = dwarfKernelPages,
                    dwarfUserBufferMb = dwarfUserBufferMb,
                    reclaimMappedApks = reclaimMappedApks,
                    ioEvidence = ioEvidence,
                    compilation = compilation,
                ),
            )
        echo("Android fault report: $report")
        if (openReport) shell.open(report.toUri().toString())
    }
}
