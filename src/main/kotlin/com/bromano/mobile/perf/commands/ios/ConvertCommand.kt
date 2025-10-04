package com.bromano.mobile.perf.commands.ios

import com.bromano.mobile.perf.ProfilerFormat
import com.bromano.mobile.perf.gecko.InstrumentsConverter
import com.bromano.mobile.perf.utils.Logger
import com.bromano.mobile.perf.utils.ProfileOpener
import com.bromano.mobile.perf.utils.ProfileViewer
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path

class ConvertCommand(
    val profileOpener: ProfileOpener,
) : CliktCommand("convert") {
    override fun help(context: Context) = "Convert Instruments Trace to Gecko Format (Firefox Profiler)"

    private val input by option(
        "-i",
        "--input",
        help = "Input Instruments Trace",
    ).path(mustExist = true, canBeDir = true)
        .required()

    private val app by option("--app", help = "Name of app (e.g. YourApp)")

    private val runNum by option(
        "--run",
        help = "Which run within the trace file to analyze",
    ).int().default(1)

    private val output by option(
        "-o",
        "--output",
        help = "Output Path for gecko profile",
    ).path(mustExist = false, canBeDir = false)
        .required()

    val profileViewerOverride by option("--ui", help = "Profile viewer to open trace in")
        .enum<ProfileViewer>()
        .default(ProfileViewer.FIREFOX)

    override fun run() {
        val profile = InstrumentsConverter.convert(app, input, runNum)

        Logger.timedLog("Gzipping and writing to disk") {
            profile.toFile(output)
        }

        profileOpener.openProfile(
            app,
            output,
            ProfilerFormat.INSTRUMENTS,
            profileViewerOverride,
        )
    }
}
