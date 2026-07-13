package com.bromano.mobile.perf.profilers

import com.github.ajalt.clikt.core.PrintMessage

private const val ADDITIONAL_OUTPUT_MARKER = "additionalTestOutputFile_"

internal fun buildBenchmarkInstrumentationCommand(
    instrumentationRunner: String,
    testCase: String,
    outputDirectory: String,
    arguments: Map<String, String>,
): String =
    buildString {
        append("am instrument -w -r ")
        append("-e class \"")
        append(testCase)
        append("\" ")
        append("-e additionalTestOutputDir \"")
        append(outputDirectory)
        append("\" ")
        arguments.forEach { (name, value) ->
            append("-e ")
            append(name)
            append(" \"")
            append(value)
            append("\" ")
        }
        append(instrumentationRunner)
    }

internal fun validateBenchmarkInstrumentationOutput(output: String) {
    val failureLine =
        output
            .lineSequence()
            .map { it.trim() }
            .firstOrNull {
                it.startsWith("INSTRUMENTATION_FAILED:") ||
                    it.startsWith("INSTRUMENTATION_ABORTED:") ||
                    it.contains("shortMsg=Process crashed") ||
                    it.contains("FAILURES!!!")
            }
    if (failureLine != null) {
        throw PrintMessage("Benchmark instrumentation failed: $failureLine", printError = true)
    }

    val resultCode =
        output
            .lineSequence()
            .map { it.trim() }
            .lastOrNull { it.startsWith("INSTRUMENTATION_CODE:") }
            ?.substringAfter(":")
            ?.trim()
            ?.toIntOrNull()
    if (resultCode != -1) {
        val detail = output.lineSequence().takeLast(8).joinToString("\n")
        throw PrintMessage(
            "Benchmark instrumentation did not complete successfully (code=${resultCode ?: "missing"}).\n$detail",
            printError = true,
        )
    }
}

internal fun findNewBenchmarkOutput(
    instrumentationOutput: String,
    filesBeforeRun: Set<String>,
    filesAfterRun: List<String>,
    outputDirectory: String,
    matches: (String) -> Boolean,
): String? {
    val reportedPath =
        instrumentationOutput
            .lineSequence()
            .map { it.trim() }
            .filter {
                (it.startsWith("INSTRUMENTATION_STATUS:") || it.startsWith("INSTRUMENTATION_RESULT:")) &&
                    it.contains(ADDITIONAL_OUTPUT_MARKER)
            }.map { it.substringAfter("=").trim() }
            .lastOrNull { it.isNotBlank() && matches(it.substringAfterLast('/')) }
    if (reportedPath != null) {
        return reportedPath
    }

    return filesAfterRun
        .asReversed()
        .firstOrNull { it !in filesBeforeRun && matches(it) }
        ?.let { "$outputDirectory$it" }
}

private fun <T> Sequence<T>.takeLast(count: Int): List<T> {
    val values = toList()
    return values.takeLast(count)
}
