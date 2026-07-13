package com.bromano.mobile.perf.gecko

import com.bromano.mobile.perf.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

object InstrumentsConverter {
    fun convert(
        app: String?,
        input: Path,
        runNum: Int = 1,
        processId: Long? = null,
    ): GeckoProfile {
        require(runNum > 0) { "Instruments run number must be at least 1" }
        // The table of contents and table data are independent, slow xctrace exports.
        // Run them concurrently and export all conversion tables with one union query.
        val (timeProfilerSettings, traceData) =
            Logger.timedLog("Loading Symbols, Samples and Load Addresses...") {
                runBlocking {
                    val settingsDeferred =
                        async(Dispatchers.IO) { InstrumentsParser.getInstrumentsSettings(input, runNum) }
                    val traceDataDeferred = async(Dispatchers.IO) { InstrumentsParser.loadTraceData(input, runNum) }
                    settingsDeferred.await() to traceDataDeferred.await()
                }
            }

        val concatenatedSamples =
            traceData.syscallSamples +
                traceData.threadIdSamples +
                traceData.virtualMemorySamples +
                traceData.samples
        val selectedSamples =
            processId?.let { selectedPid -> concatenatedSamples.filter { it.thread.pid.toLong() == selectedPid } }
                ?: concatenatedSamples
        require(selectedSamples.isNotEmpty()) {
            processId?.let { "Instruments trace does not contain samples for process $it" }
                ?: "Instruments trace does not contain samples"
        }
        val selectedLibraries =
            processId?.let { selectedPid -> traceData.loadedImageList.filter { it.pid.toLong() == selectedPid } }
                ?: traceData.loadedImageList

        return Logger.timedLog("Converting to Gecko format") {
            GeckoGenerator.createGeckoProfile(
                app,
                selectedSamples,
                selectedLibraries,
                timeProfilerSettings,
            )
        }
    }
}
