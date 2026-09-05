package com.bromano.mobile.perf.commands.faults

import com.bromano.mobile.perf.faults.AndroidFaultCollector
import com.bromano.mobile.perf.faults.AndroidFaultProcessor
import com.bromano.mobile.perf.faults.AndroidFaultReport
import com.bromano.mobile.perf.faults.FaultEngine
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
)

fun interface AndroidFaultWorkflow {
    fun run(request: AndroidFaultRequest): Path
}

internal class DefaultAndroidFaultWorkflow(
    private val engine: FaultEngine,
) : AndroidFaultWorkflow {
    override fun run(request: AndroidFaultRequest): Path {
        val engineRoot = engine.materialize()
        if (!request.skipCollect) {
            AndroidFaultCollector(engineRoot).collect(request)
        }
        AndroidFaultProcessor().process(request.output)
        val report = request.output.resolve("report.html")
        AndroidFaultReport(engineRoot).build(
            capture = request.output,
            output = report,
            label = request.label,
            comparison = request.comparison,
            comparisonLabel = request.comparisonLabel,
            allowIncomparable = request.allowIncomparable,
        )
        return report
    }
}
