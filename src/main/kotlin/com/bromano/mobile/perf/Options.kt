package com.bromano.mobile.perf

import com.github.ajalt.clikt.core.ParameterHolder
import com.github.ajalt.clikt.parameters.groups.ChoiceGroup
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.groups.defaultByName
import com.github.ajalt.clikt.parameters.groups.groupChoice
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path

abstract class ProfilerOptionGroup(
    val format: ProfilerFormat,
) : OptionGroup(
        format.name.lowercase(),
        help = "Arguments for ${format.name.lowercase()} format",
    )

class PerfettoOptions : ProfilerOptionGroup(ProfilerFormat.PERFETTO) {
    val configPb by option("--configPb", help = "Perfetto binary config")
        .path()
}

class MethodOptions : ProfilerOptionGroup(ProfilerFormat.METHOD)

class SimpleperfOptions : ProfilerOptionGroup(ProfilerFormat.SIMPLEPERF) {
    val simpleperfArgs by option("--simpleperfArgs", help = "Custom options for simpleperf record command")
        .default("-e cpu-clock -f 4000 -g")

    val symfs by option("--symfs", help = "Directory to find binaries with symbols and debug info [Simpleperf only]")
        .path(mustExist = true, canBeFile = false)

    val mapping by option("--mapping", help = "Mapping file for simpleperf deobfuscation")
        .path(mustExist = true, canBeDir = false)

    val showArtFrames by option("--show-art-frames", help = "Show Android Runtime Frames")
        .flag("--no-show-art-frames", default = false)

    // Many concurrency-related methods are just noise. Strip them out by default
    val removeMethods by option("--remove-method", help = "Remove methods matched by provided regexes (e.g. \"^io\\.reactivex.$\"")
        .multiple(listOf("^io\\.reactivex.*$", "^\\[DEDUPED\\].*$", "^kotlinx?\\.coroutines.*$"))
}

fun ParameterHolder.profilerOptions(
    profilerFormats: Set<ProfilerFormat>,
    default: ProfilerFormat,
): ChoiceGroup<ProfilerOptionGroup, ProfilerOptionGroup> {
    val profilerOptionsMap =
        mapOf(
            ProfilerFormat.PERFETTO to PerfettoOptions(),
            ProfilerFormat.SIMPLEPERF to SimpleperfOptions(),
            ProfilerFormat.METHOD to MethodOptions(),
        )

    return option("-f", "--format", help = "Profiler to use for collection")
        .groupChoice(
            profilerOptionsMap
                .filterKeys { profilerFormats.contains(it) }
                .mapKeys { (k, _) -> k.name.lowercase() },
        ).defaultByName(default.name.lowercase())
}

fun ParameterHolder.androidProfilerOptions() =
    profilerOptions(
        setOf(
            ProfilerFormat.PERFETTO,
            ProfilerFormat.SIMPLEPERF,
            ProfilerFormat.METHOD,
        ),
        ProfilerFormat.PERFETTO,
    )
