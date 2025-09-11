package com.bromano.mobile.perf.profilers

import java.nio.file.Path

interface Profiler {
    /**
     * Run Profiler over an arbitrary app session
     */
    fun execute(
        packageName: String,
        output: Path,
    )

    /**
     * Run Profiler over a single iteration of a performance test
     */
    fun executeTest(
        packageName: String,
        instrumentationRunner: String,
        testCase: String,
        output: Path,
    )
}
