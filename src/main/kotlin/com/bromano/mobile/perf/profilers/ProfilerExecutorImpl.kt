package com.bromano.mobile.perf.profilers

import com.bromano.mobile.perf.ProfilerFormat
import com.bromano.mobile.perf.ProfilerOptionGroup
import com.bromano.mobile.perf.utils.ProfileOpener
import com.bromano.mobile.perf.utils.ProfileViewer
import com.bromano.mobile.perf.utils.Shell
import java.nio.file.Path

interface ProfilerExecutor {
    fun execute(
        profilerOptionGroup: ProfilerOptionGroup,
        shell: Shell,
        device: String,
        packageName: String,
        output: Path,
        profileViewerOverride: ProfileViewer? = null,
    )

    fun executeTest(
        profilerOptionGroup: ProfilerOptionGroup,
        shell: Shell,
        device: String,
        packageName: String,
        instrumentationPackageName: String,
        testCase: String,
        output: Path,
        profileViewerOverride: ProfileViewer? = null,
    )
}

class ProfilerExecutorImpl(
    private val profilerFactories: Map<
        ProfilerFormat,
        (
            shell: Shell,
            device: String,
            profilerOptionGroup: ProfilerOptionGroup,
        ) -> Profiler,
    >,
    private val profileOpener: ProfileOpener,
) : ProfilerExecutor {
    override fun execute(
        profilerOptionGroup: ProfilerOptionGroup,
        shell: Shell,
        device: String,
        packageName: String,
        output: Path,
        profileViewerOverride: ProfileViewer?,
    ) {
        val collector =
            profilerFactories[profilerOptionGroup.format]?.invoke(shell, device, profilerOptionGroup)
                ?: throw IllegalStateException("Invalid profiler: ${profilerOptionGroup.format}")

        collector.execute(packageName, output)

        profileOpener.openProfile(output, profilerOptionGroup.format)
    }

    override fun executeTest(
        profilerOptionGroup: ProfilerOptionGroup,
        shell: Shell,
        device: String,
        packageName: String,
        instrumentationPackageName: String,
        testCase: String,
        output: Path,
        profileViewerOverride: ProfileViewer?,
    ) {
        val collector =
            profilerFactories[profilerOptionGroup.format]?.invoke(shell, device, profilerOptionGroup)
                ?: throw IllegalStateException("Invalid profiler: ${profilerOptionGroup.format}")

        collector.executeTest(packageName, instrumentationPackageName, testCase, output)

        // TODO: Profiler can have multiple output formats
        // (e.g. Stack Sampling benchmark tests use either simpleperf or ART method trace format)
        // Alternatively, the trace could be opened in multiple viewers (e.g. Gecko can be opened in Perfetto or Firefox)
        profileOpener.openProfile(output, profilerOptionGroup.format, profileViewerOverride)
    }
}
