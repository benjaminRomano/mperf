package com.bromano.mobile.perf

import com.bromano.mobile.perf.commands.android.AndroidCollectCommand
import com.bromano.mobile.perf.commands.android.AndroidCommand
import com.bromano.mobile.perf.commands.android.AndroidStartCommand
import com.bromano.mobile.perf.commands.faults.AndroidFaultsCommand
import com.bromano.mobile.perf.commands.faults.FaultsCommand
import com.bromano.mobile.perf.commands.faults.IosFaultsCommand
import com.bromano.mobile.perf.commands.ios.ConvertCommand
import com.bromano.mobile.perf.commands.ios.IosCommand
import com.bromano.mobile.perf.commands.ios.IosStartCommand
import com.bromano.mobile.perf.faults.BundledFaultEngine
import com.bromano.mobile.perf.faults.FaultEngine
import com.bromano.mobile.perf.profilers.ProfilerExecutor
import com.bromano.mobile.perf.profilers.ProfilerExecutorImpl
import com.bromano.mobile.perf.profilers.instruments.InstrumentsProfiler
import com.bromano.mobile.perf.profilers.instruments.InstrumentsProfilerOptions
import com.bromano.mobile.perf.profilers.method.MethodProfiler
import com.bromano.mobile.perf.profilers.perfetto.PerfettoProfiler
import com.bromano.mobile.perf.profilers.simpleperf.SimpleperfProfiler
import com.bromano.mobile.perf.utils.Adb
import com.bromano.mobile.perf.utils.ProfileOpener
import com.bromano.mobile.perf.utils.Shell
import com.bromano.mobile.perf.utils.ShellExecutor
import com.bromano.mobile.perf.utils.XcodeUtils
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands

class MobilePerfCommand : CliktCommand() {
    override fun help(context: Context) = "A CLI for mobile performance testing"

    override fun run() = Unit
}

fun main(args: Array<String>) {
    val config = readConfig()
    val shell = ShellExecutor()
    val profileOpener = ProfileOpener(shell, config.traceHostUrl, config.perfettoUrl)
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
                ProfilerFormat.INSTRUMENTS to { shell, device, options ->
                    (options as InstrumentsOptions).let {
                        InstrumentsProfiler(
                            XcodeUtils(device, shell),
                            InstrumentsProfilerOptions(
                                it.template,
                                it.instruments,
                                it.timeLimit,
                            ),
                        )
                    }
                },
            ),
            profileOpener,
        )

    createRootCommand(shell, config, profilerExecutor, profileOpener).main(args)
}

/**
 * Run CLI from string
 */
fun runCli(args: String) {
    main(shlexSplit(args))
}

fun createRootCommand(
    shell: Shell,
    config: Config,
    profilerExecutor: ProfilerExecutor,
    profileOpener: ProfileOpener,
    faultEngine: FaultEngine = BundledFaultEngine(),
) = MobilePerfCommand()
    .subcommands(
        FaultsCommand().subcommands(
            AndroidFaultsCommand(shell, config, faultEngine),
            IosFaultsCommand(shell, config, faultEngine),
        ),
        IosCommand().subcommands(
            IosStartCommand(shell, config, profilerExecutor),
            ConvertCommand(profileOpener),
        ),
        AndroidCommand().subcommands(
            AndroidStartCommand(shell, config, profilerExecutor),
            AndroidCollectCommand(shell, config, profilerExecutor),
        ),
    )

/**
 * Perform string splitting as shell would do
 * Inspired by https://docs.python.org/3/library/shlex.html
 */
internal fun shlexSplit(input: String): Array<String> {
    val tokens = mutableListOf<String>()
    val token = StringBuilder()
    var inSingle = false
    var inDouble = false
    var escaped = false
    var tokenStarted = false
    var i = 0
    while (i < input.length) {
        val c = input[i]
        when {
            escaped -> {
                token.append(c)
                tokenStarted = true
                escaped = false
            }
            c == '\\' && !inSingle -> escaped = true
            c == '\'' && !inDouble -> {
                inSingle = !inSingle
                tokenStarted = true
            }
            c == '"' && !inSingle -> {
                inDouble = !inDouble
                tokenStarted = true
            }
            c.isWhitespace() && !inSingle && !inDouble -> {
                if (tokenStarted) {
                    tokens.add(token.toString())
                    token.clear()
                    tokenStarted = false
                }
            }
            else -> {
                token.append(c)
                tokenStarted = true
            }
        }
        i++
    }
    require(!escaped) { "Trailing escape in command line" }
    require(!inSingle && !inDouble) { "Unterminated quote in command line" }
    if (tokenStarted) tokens.add(token.toString())
    return tokens.toTypedArray()
}
