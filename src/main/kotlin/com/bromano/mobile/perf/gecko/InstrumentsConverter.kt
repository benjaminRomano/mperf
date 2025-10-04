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
    ): GeckoProfile {
        val timeProfilerSettings = InstrumentsParser.getInstrumentsSettings(input, runNum)

        // xctrace queries can be quite slow so parallelize them with coroutines
        val conversionInputs =
            Logger.timedLog("Loading Symbols, Samples and Load Addresses...") {
                runBlocking {
                    val samplesDeferred =
                        async(Dispatchers.IO) {
                            InstrumentsParser.loadSamples(TIME_PROFILE_SCHEMA, SAMPLE_TIME_TAG, input, runNum)
                        }

                    val loadedImageListDeferred =
                        async(Dispatchers.IO) {
                            InstrumentsParser.sortedImageList(input, runNum)
                        }

                    val threadIdSamplesDeferred =
                        if (timeProfilerSettings.hasThreadStates) {
                            async(Dispatchers.IO) { InstrumentsParser.loadIdleThreadSamples(input, runNum) }
                        } else {
                            null
                        }

                    val virtualMemorySamplesDeferred =
                        if (timeProfilerSettings.hasVirtualMemory) {
                            async(Dispatchers.IO) {
                                InstrumentsParser.loadSamples(VIRTUAL_MEMORY_SCHEMA, START_TIME_TAG, input, runNum)
                            }
                        } else {
                            null
                        }

                    val syscallSamplesDeferred =
                        if (timeProfilerSettings.hasSyscalls) {
                            async(Dispatchers.IO) {
                                InstrumentsParser.loadSamples(SYSCALL_SCHEMA, START_TIME_TAG, input, runNum)
                            }
                        } else {
                            null
                        }

                    ConversionInputs(
                        samples = samplesDeferred.await(),
                        loadedImageList = loadedImageListDeferred.await(),
                        threadIdSamples = threadIdSamplesDeferred?.await(),
                        virtualMemorySamples = virtualMemorySamplesDeferred?.await(),
                        syscallSamples = syscallSamplesDeferred?.await(),
                    )
                }
            }

        val concatenatedSamples =
            (conversionInputs.syscallSamples ?: emptyList()) +
                (conversionInputs.threadIdSamples ?: emptyList()) +
                (conversionInputs.virtualMemorySamples ?: emptyList()) +
                conversionInputs.samples

        return Logger.timedLog("Converting to Gecko format") {
            GeckoGenerator.createGeckoProfile(
                app,
                concatenatedSamples,
                conversionInputs.loadedImageList,
                timeProfilerSettings,
            )
        }
    }

    private data class ConversionInputs(
        val samples: List<InstrumentsSample>,
        val loadedImageList: List<Library>,
        val threadIdSamples: List<InstrumentsSample>?,
        val virtualMemorySamples: List<InstrumentsSample>?,
        val syscallSamples: List<InstrumentsSample>?,
    )
}
