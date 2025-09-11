package com.bromano.mobile.perf.utils

import java.util.concurrent.TimeUnit

open class FakeShell : Shell {
    override fun runCommand(
        command: String,
        ignoreErrors: Boolean,
    ): String = ""

    override fun runCommand(
        command: String,
        ignoreErrors: Boolean,
        shell: Boolean,
        redirectOutput: ProcessBuilder.Redirect,
        redirectError: ProcessBuilder.Redirect,
    ): String = ""

    override fun selectChoice(
        choices: List<String>,
        prompt: String?,
    ): String? = null

    override fun open(url: String) {}

    override fun getConnectedAndroidDevices(): List<String> = emptyList()

    override fun startProcess(command: String): Process =
        throw UnsupportedOperationException("startProcess not implemented in FakeShell; override in test as needed")

    override fun waitFor(process: Process) { /* no-op */ }

    override fun waitFor(
        process: Process,
        timeout: Long,
        unit: TimeUnit,
    ): Boolean = true
}
