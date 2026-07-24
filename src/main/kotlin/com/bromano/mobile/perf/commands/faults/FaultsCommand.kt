package com.bromano.mobile.perf.commands.faults

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context

class FaultsCommand : CliktCommand("faults") {
    override fun help(context: Context) = "Analyze startup page-fault patterns on Android or iOS"

    override fun run() = Unit
}
