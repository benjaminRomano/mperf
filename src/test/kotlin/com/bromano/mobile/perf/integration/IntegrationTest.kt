package com.bromano.mobile.perf.integration

import com.bromano.mobile.perf.runCli
import com.bromano.mobile.perf.utils.ZipUtils
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists

/**
 * Set of run targets to quickly verify different scenarios
 *
 * TODO: Make this proper integration tests that can perform end-to-end validations
 */

object SimpleperfStart {
    @JvmStatic
    fun main(args: Array<String>) = runCli("start -f simpleperf -p com.android.chrome --simpleperfArgs \"-e cpu-clock -f 4000 -g\"")
}

object PerfettoStart {
    @JvmStatic
    fun main(args: Array<String>) = runCli("android start -f perfetto -p com.android.chrome")
}

object PerfettoCollect {
    @JvmStatic
    fun main(args: Array<String>) = runCli("android collect -f perfetto -p com.example.macrobenchmark.target -t loginByIntent")
}

object MethodCollect {
    @JvmStatic
    fun main(args: Array<String>) = runCli("android collect -f method -p com.example.macrobenchmark.target -t loginByIntent")
}

object MethodStart {
    @JvmStatic
    fun main(args: Array<String>) = runCli("android start -f method -p com.example.macrobenchmark.target")
}

// iOS: quick-run helpers for Instruments-based start command
object IosInstrumentsStart {
    @JvmStatic
    fun main(args: Array<String>) = runCli("ios start -b perftestexample")
}

object IosInstrumentsStartWithTemplate {
    @JvmStatic
    fun main(args: Array<String>) = runCli("ios start -b com.example.app --template \"Time Profiler\"")
}

object ConvertCommand {
    @JvmStatic
    fun main(args: Array<String>) {
        val input = Files.createTempFile("example", ".trace")
        input.deleteIfExists()
        ZipUtils.unzipInstruments(Path.of("src/test/resources/example.trace.zip"), input)
        runCli("convert -i $input -o artifacts/trace_out/example.json.gz --app perftestexample")
    }
}
