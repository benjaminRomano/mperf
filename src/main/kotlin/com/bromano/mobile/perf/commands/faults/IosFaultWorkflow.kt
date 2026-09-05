package com.bromano.mobile.perf.commands.faults

import com.bromano.mobile.perf.faults.FaultEngine
import com.bromano.mobile.perf.faults.IosFaultCollector
import com.bromano.mobile.perf.faults.IosFaultProcessor
import com.bromano.mobile.perf.faults.IosFaultReport
import java.nio.file.Path

data class IosFaultRequest(
    val bundleIdentifier: String?,
    val app: Path?,
    val appBinaryName: String?,
    val device: String,
    val output: Path,
    val cachePolicy: String,
    val requireColdCache: Boolean,
    val allowHostPressure: Boolean,
    val allowUnconfirmedCache: Boolean,
    val residencyThreshold: Double,
    val developmentTeam: String?,
    val pressureFraction: Double,
    val pressureMegabytes: Int?,
    val pressureHoldSeconds: Int,
    val settleSeconds: Double,
    val timeLimit: Int?,
    val appArguments: List<String>,
    val overwrite: Boolean,
    val skipCollect: Boolean,
)

fun interface IosFaultWorkflow {
    fun run(request: IosFaultRequest): Path
}

internal class DefaultIosFaultWorkflow(
    private val engine: FaultEngine,
) : IosFaultWorkflow {
    override fun run(request: IosFaultRequest): Path {
        val engineRoot = engine.materialize()
        if (!request.skipCollect) {
            IosFaultCollector(engineRoot).collect(request)
        } else {
            IosFaultProcessor().process(request.output)
            IosFaultReport(engineRoot).build(request.output, request.output.resolve("report.html"))
        }
        return request.output.resolve("report.html")
    }
}
