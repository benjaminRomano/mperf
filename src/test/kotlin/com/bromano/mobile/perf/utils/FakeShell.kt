package com.bromano.mobile.perf.utils

import java.util.concurrent.TimeUnit

open class FakeShell : Shell {
    val runCommandCalls = mutableListOf<String>()
    val runCommandWithOptionsCalls = mutableListOf<RunCommandCall>()
    var runCommandResponses = mutableMapOf<String, String>()
    var runCommandHandler: ((String, Boolean, Boolean, ProcessBuilder.Redirect, ProcessBuilder.Redirect) -> String)? = null

    val startProcessCommands = mutableListOf<String>()
    var startProcessHandler: ((String) -> Process)? = null

    val newProcessBuilderCommands = mutableListOf<String>()
    var newProcessBuilderHandler: ((String) -> ProcessBuilder)? = null

    data class RunCommandCall(
        val command: String,
        val ignoreErrors: Boolean,
        val useShell: Boolean,
        val redirectOutput: ProcessBuilder.Redirect,
        val redirectError: ProcessBuilder.Redirect,
    )

    override fun runCommand(
        command: String,
        ignoreErrors: Boolean,
    ): String =
        runCommand(
            command,
            ignoreErrors,
            shell = true,
            redirectOutput = ProcessBuilder.Redirect.PIPE,
            redirectError = ProcessBuilder.Redirect.INHERIT,
        )

    override fun runCommand(
        command: String,
        ignoreErrors: Boolean,
        shell: Boolean,
        redirectOutput: ProcessBuilder.Redirect,
        redirectError: ProcessBuilder.Redirect,
    ): String {
        runCommandCalls += command
        runCommandWithOptionsCalls += RunCommandCall(command, ignoreErrors, shell, redirectOutput, redirectError)

        runCommandHandler?.let { handler ->
            return handler(command, ignoreErrors, shell, redirectOutput, redirectError)
        }

        return runCommandResponses[command] ?: ""
    }

    override fun selectChoice(
        choices: List<String>,
        prompt: String?,
    ): String? = null

    override fun open(url: String) {}

    override fun getConnectedAndroidDevices(): List<String> = emptyList()

    override fun startProcess(command: String): Process {
        startProcessCommands += command
        return startProcessHandler?.invoke(command) ?: FakeProcess()
    }

    override fun newProcessBuilder(command: String): ProcessBuilder {
        newProcessBuilderCommands += command
        return newProcessBuilderHandler?.invoke(command)
            ?: ProcessBuilder(listOf("bash", "-lc", "true"))
    }

    override fun waitFor(process: Process) { /* no-op */ }

    override fun waitFor(
        process: Process,
        timeout: Long,
        unit: TimeUnit,
    ): Boolean = true

    class FakeProcess(
        private val inputContent: String = "",
        private val errorContent: String = "",
        private val exitCode: Int = 0,
    ) : Process() {
        private val outputStream = java.io.ByteArrayOutputStream()
        private var alive = true

        override fun getOutputStream(): java.io.OutputStream = outputStream

        override fun getInputStream(): java.io.InputStream = inputContent.byteInputStream()

        override fun getErrorStream(): java.io.InputStream = errorContent.byteInputStream()

        override fun waitFor(): Int {
            alive = false
            return exitCode
        }

        override fun waitFor(
            timeout: Long,
            unit: TimeUnit,
        ): Boolean {
            alive = false
            return true
        }

        override fun exitValue(): Int = exitCode

        override fun destroy() {
            alive = false
        }

        override fun destroyForcibly(): Process {
            destroy()
            return this
        }

        override fun isAlive(): Boolean = alive
    }
}
