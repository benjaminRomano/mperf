package com.bromano.mobile.perf.integration

import com.bromano.mobile.perf.runCli

/**
 * Set of run targets to quickly verify different scenarios
 */

object SimpleperfStart {
    @JvmStatic
    fun main(args: Array<String>) = runCli("start -f simpleperf -p com.android.chrome --simpleperfArgs \"-e cpu-clock -f 4000 -g\"")
}

object PerfettoStart {
    @JvmStatic
    fun main(args: Array<String>) = runCli("start -f perfetto -p com.android.chrome")
}

object PerfettoCollect {
    @JvmStatic
    fun main(args: Array<String>) = runCli("collect -f perfetto -p com.example.macrobenchmark.target -t loginByIntent")
}

object MethodCollect {
    @JvmStatic
    fun main(args: Array<String>) = runCli("collect -f method -p com.example.macrobenchmark.target -t loginByIntent")
}

object MethodStart {
    @JvmStatic
    fun main(args: Array<String>) = runCli("start -f method -p com.example.macrobenchmark.target")
}
