package com.bromano.mobile.perf

/**
 * Supported Profiler formats
 */
enum class ProfilerFormat {
    /**
     * Perfetto Trace
     */
    PERFETTO,

    /**
     * Simpleperf-based Sampling Profiler data collection
     */
    SIMPLEPERF,

    /**
     * Android Runtime Method Tracing
     */
    METHOD,

    /**
     * iOS Instruments Trace using xctrace
     */
    INSTRUMENTS,
}
