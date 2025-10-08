package com.bromano.mobile.perf.utils

import com.github.ajalt.clikt.core.PrintMessage
import java.io.InputStream
import java.util.concurrent.TimeUnit

class ShellCommandException(
    command: String,
    val exitCode: Int,
    stderr: String
) : RuntimeException("Command, `$command`, failed with exit code $exitCode: $stderr")

/**
 * Wrapper around operations that interact with shell.
 *
 * This utility class is designed to support testability of shell invocations.
 *
 * TODO: This abstraction is dubious.
 */
interface Shell {
    /**
     * Run command with output returned as a string
     *
     * @param ignoreErrors whether to ignore non-zero exit code
     * @return stdout output as string
     */
    fun runCommand(
        command: String,
        ignoreErrors: Boolean = false,
    ): String

    /**
     * Run a given command
     */
    fun runCommand(
        /**
         * Command to run
         */
        command: String,
        /**
         * Whether to ignore non-zero exit codes
         */
        ignoreErrors: Boolean = false,
        /**
         * Whether to run using shell (adds support for piping, env variables)
         */
        shell: Boolean = true,
        /**
         * Whether to print out to stdout or pipe into output
         */
        redirectOutput: ProcessBuilder.Redirect = ProcessBuilder.Redirect.PIPE,
        /**
         * Whether to print out to stderr or pipe into output
         */
        redirectError: ProcessBuilder.Redirect = ProcessBuilder.Redirect.INHERIT,
    ): String

    /**
     * Select choice from a set of options.
     *
     * If an empty list is provided
     * @return returns null if empty list was provided or the option selected
     */
    fun selectChoice(
        choices: List<String>,
        prompt: String? = null,
    ): String?

    /**
     * Open file or URL using user's preferred app
     */
    fun open(url: String)

    /**
     * Get list of connected devices
     */
    fun getConnectedAndroidDevices(): List<String>

    /** Start a shell process for long-running commands and return the Process handle. */
    fun startProcess(command: String): Process

    /** Wait for a process to complete. */
    fun waitFor(process: Process)

    /** Wait for a process to complete with timeout. */
    fun waitFor(
        process: Process,
        timeout: Long,
        unit: TimeUnit,
    ): Boolean

    /**
     * Create a Process Builder
     */
    fun newProcessBuilder(command: String): ProcessBuilder
}

open class ShellExecutor : Shell {
    private fun systemShell(): String = System.getenv("SHELL")?.takeIf { it.isNotBlank() } ?: "/bin/bash"

    override fun newProcessBuilder(command: String): ProcessBuilder = ProcessBuilder(listOf(systemShell(), "-c", command))

    override fun startProcess(command: String): Process =
        ProcessBuilder(listOf(systemShell(), "-c", command))
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()

    override fun waitFor(process: Process) {
        process.waitFor()
    }

    override fun waitFor(
        process: Process,
        timeout: Long,
        unit: TimeUnit,
    ): Boolean = process.waitFor(timeout, unit)

    override fun runCommand(
        command: String,
        ignoreErrors: Boolean,
    ): String = runCommand(command, ignoreErrors = ignoreErrors, redirectOutput = ProcessBuilder.Redirect.PIPE)

    /**
     * Run given command with output returned as a string
     */
    private fun runCommand(
        command: String,
        ignoreErrors: Boolean = false,
        shell: Boolean = true,
        redirectOutput: ProcessBuilder.Redirect = ProcessBuilder.Redirect.INHERIT,
        redirectError: ProcessBuilder.Redirect = ProcessBuilder.Redirect.INHERIT,
        outputParser: (InputStream) -> Unit,
    ) {
        val cmds =
            if (shell) {
                arrayOf(systemShell(), "-c", command)
            } else {
                arrayOf(command)
            }

        val proc =
            ProcessBuilder(*cmds)
                .apply {
                    redirectError(redirectError)
                    redirectOutput(redirectOutput)
                }.redirectInput(ProcessBuilder.Redirect.INHERIT)
                .start()

        proc.inputStream.use(outputParser)
        val exitCode = proc.waitFor()

        if (exitCode != 0 && !ignoreErrors) {
            // Note: This is only populated with `ProcessBuilder.Redirect.PIPE`
            val error = proc.errorStream.bufferedReader().readText().trim()
            throw ShellCommandException(command, exitCode, error)
        }
    }

    override fun runCommand(
        command: String,
        ignoreErrors: Boolean,
        shell: Boolean,
        redirectOutput: ProcessBuilder.Redirect,
        redirectError: ProcessBuilder.Redirect,
    ): String {
        var output = ""
        runCommand(command, ignoreErrors, shell, redirectOutput, redirectError) { inputStream ->
            output = inputStream.bufferedReader().readText().trim()
        }

        return if (redirectOutput == ProcessBuilder.Redirect.PIPE || redirectError == ProcessBuilder.Redirect.PIPE) {
            output
        } else {
            ""
        }
    }

    override fun open(url: String) {
        val os = System.getProperty("os.name")
        when {
            os.startsWith("Linux") -> runCommand("xdg-open \"$url\"")
            else -> runCommand("open \"$url\"")
        }
    }

    /**
     * Prompt user to select a choice from list of options
     */
    override fun selectChoice(
        choices: List<String>,
        prompt: String?,
    ): String? {
        if (choices.isEmpty()) {
            return null
        } else if (choices.size == 1) {
            return choices[0]
        }

        var choice = -1
        while (choice <= 0 || choice > choices.size) {
            println(choices.mapIndexed { i, item -> "[${i + 1}] $item" }.joinToString("\n"))
            print(prompt ?: "Select a choice: ")
            choice = readlnOrNull()?.toIntOrNull() ?: -1
        }

        return choices[choice - 1]
    }

    override fun getConnectedAndroidDevices(): List<String> {
        val output = runCommand("adb devices")
        return output
            .lines()
            .drop(1)
            .map { it.split("	").first() }
            .filter { it.isNotBlank() }
    }
}
