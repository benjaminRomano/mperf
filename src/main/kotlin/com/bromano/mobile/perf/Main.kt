package com.bromano.mobile.perf

import com.bromano.mobile.perf.commands.android.CollectCommand
import com.bromano.mobile.perf.commands.android.StartCommand
import com.bromano.mobile.perf.profilers.ProfilerExecutorImpl
import com.bromano.mobile.perf.profilers.method.MethodProfiler
import com.bromano.mobile.perf.profilers.perfetto.PerfettoProfiler
import com.bromano.mobile.perf.profilers.simpleperf.SimpleperfProfiler
import com.bromano.mobile.perf.utils.Adb
import com.bromano.mobile.perf.utils.ProfileOpener
import com.bromano.mobile.perf.utils.ShellExecutor
import com.charleskorn.kaml.Yaml
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import kotlin.io.path.readText

class MobilePerfCommand : CliktCommand() {
    override fun help(context: Context) = "A CLI for mobile performance testing"
    override fun run() = Unit
}

fun main(args: Array<String>) {
    val config = Yaml.default.decodeFromString(Config.serializer(), getConfig().readText())
    val shell = ShellExecutor()

    val profilerExecutor =
        ProfilerExecutorImpl(
            mapOf(
                ProfilerFormat.PERFETTO to { shell, device, options ->
                    PerfettoProfiler(shell, Adb(device, shell), options as PerfettoOptions)
                },
                ProfilerFormat.SIMPLEPERF to { shell, device, options ->
                    SimpleperfProfiler(shell, Adb(device, shell), options as SimpleperfOptions)
                },
                ProfilerFormat.METHOD to { shell, device, _ -> MethodProfiler(Adb(device, shell)) },
            ),
            ProfileOpener(shell),
        )

    MobilePerfCommand()
        .subcommands(
            StartCommand(shell, config, profilerExecutor),
            CollectCommand(shell, config, profilerExecutor),
        ).main(args)
}

/**
 * Run CLI from string
 */
fun runCli(args: String) {
    main(shlexSplit(args))
}

/**
 * Perform string splitting as shell would do
 * Inspired by https://docs.python.org/3/library/shlex.html
 */
private fun shlexSplit(input: String): Array<String> {
    val tokens = mutableListOf<String>()
    val sb = StringBuilder()
    var inSingle = false
    var inDouble = false
    var i = 0
    while (i < input.length) {
        val c = input[i]
        when (c) {
            '\'' ->
                if (!inDouble) {
                    inSingle = !inSingle
                } else {
                    sb.append(c)
                }
            '"' ->
                if (!inSingle) {
                    inDouble = !inDouble
                } else {
                    sb.append(c)
                }
            ' ' ->
                if (inSingle || inDouble) {
                    sb.append(c)
                } else if (sb.isNotEmpty()) {
                    tokens.add(sb.toString())
                    sb.clear()
                }
            else -> sb.append(c)
        }
        i++
    }
    if (sb.isNotEmpty()) tokens.add(sb.toString())
    return tokens.toTypedArray()
}
