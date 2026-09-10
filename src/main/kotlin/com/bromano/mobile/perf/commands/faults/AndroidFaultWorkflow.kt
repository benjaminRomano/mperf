package com.bromano.mobile.perf.commands.faults

import com.bromano.mobile.perf.faults.AndroidFaultCollector
import com.bromano.mobile.perf.faults.AndroidFaultProcessor
import com.bromano.mobile.perf.faults.AndroidFaultReport
import com.bromano.mobile.perf.faults.FaultEngine
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

data class AndroidFaultRequest(
    val packageName: String?,
    val activity: String?,
    val device: String?,
    val output: Path,
    val settleMs: Int,
    val maxResidentPages: Int,
    val rebootBeforeCollect: Boolean,
    val nativeStacks: Boolean,
    val pullArtifacts: Boolean,
    val skipCollect: Boolean,
    val overwrite: Boolean,
    val comparison: Path?,
    val label: String,
    val comparisonLabel: String,
    val allowIncomparable: Boolean,
    val dwarfStacks: Boolean = false,
    val reclaimMappedApks: Boolean = false,
    val compilation: String = "speed-profile",
    val ioEvidence: Boolean = false,
    val dwarfKernelPages: Int = 4096,
    val dwarfUserBufferMb: Int = 256,
    val nativeMaxSamples: Int = 2_000_000,
    val nativeKernelPages: Int = 256,
    val nativeMaxMappings: Int = 200_000,
    val nativeMaxCallchainEntries: Int = 16_000_000,
    val perfettoMode: String = "full",
    val reportOnly: Boolean = false,
    val dwarfRecorder: Path? = null,
    val cohort: Path? = null,
    val symbolDirectory: Path? = null,
    val mappingFile: Path? = null,
    val mappingApkSha256: String? = null,
    val startupProfile: Path? = null,
    val baselineProfile: Path? = null,
)

fun interface AndroidFaultWorkflow {
    fun run(request: AndroidFaultRequest): Path
}

internal class DefaultAndroidFaultWorkflow(
    private val engine: FaultEngine,
) : AndroidFaultWorkflow {
    override fun run(request: AndroidFaultRequest): Path {
        val engineRoot = engine.materialize()
        val metadata = request.output.resolve("capture_metadata.json")
        val marker = request.output.resolve(".android-fault-visualizer-capture")

        fun snapshot(path: Path): List<Byte>? = runCatching { Files.readAllBytes(path).toList() }.getOrNull()
        val before = snapshot(metadata) to snapshot(marker)
        var collected = request.skipCollect || request.reportOnly
        try {
            if (!request.skipCollect && !request.reportOnly) {
                AndroidFaultCollector(engineRoot).collect(request)
                collected = true
            }
            if (!request.reportOnly) AndroidFaultProcessor().process(request.output)
        } catch (failure: Throwable) {
            val after = snapshot(metadata) to snapshot(marker)
            if (Files.isDirectory(request.output, LinkOption.NOFOLLOW_LINKS) &&
                (after.first != null || after.second != null) &&
                (collected || before != after)
            ) {
                runCatching {
                    AndroidFaultReport(engineRoot).buildHealth(
                        request.output,
                        request.output.resolve("capture-health.html"),
                        request.label,
                        failure,
                    )
                    System.err.println("Capture diagnostics: ${request.output.resolve("capture-health.html").toAbsolutePath()}")
                }.exceptionOrNull()?.let(failure::addSuppressed)
            }
            throw failure
        }
        val report = request.output.resolve("report.html")
        AndroidFaultReport(engineRoot).build(
            capture = request.output,
            output = report,
            label = request.label,
            comparison = request.comparison,
            comparisonLabel = request.comparisonLabel,
            allowIncomparable = request.allowIncomparable,
            attribution =
                com.bromano.mobile.perf.faults.AndroidReportAttribution.Options(
                    request.symbolDirectory,
                    request.mappingFile,
                    request.mappingApkSha256,
                    request.startupProfile,
                    request.baselineProfile,
                ),
        )
        return report
    }
}
