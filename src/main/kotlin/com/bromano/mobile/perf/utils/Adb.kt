package com.bromano.mobile.perf.utils

import java.time.Duration

private const val ANDROID_N_SDK_VERSION = 24

/**
 * Set of utility adb operations
 */
class Adb(
    device: String?,
    private val shell: Shell,
) {
    private val base = listOf("adb") + (device?.let { listOf("-s", it) } ?: emptyList())
    private var rootTemplate: String? = null
    val deviceOpts = device?.let { "-s ${quoteArgument(it)}" } ?: ""

    fun command(vararg arguments: String): List<String> = base + arguments

    fun run(
        vararg arguments: String,
        check: Boolean = true,
        timeout: Duration = Duration.ofSeconds(30),
    ): CommandResult = shell.runArguments(command(*arguments), check, timeout)

    fun shellResult(
        command: String,
        check: Boolean = true,
        timeout: Duration = Duration.ofSeconds(30),
    ): CommandResult = run("shell", command, check = check, timeout = timeout)

    fun clearRoot() {
        rootTemplate = null
    }

    fun ensureRoot() {
        clearRoot()
        run("root", check = false)
        run("wait-for-device")
        requireNotNull(findRootTemplate()) { "Unable to acquire a root shell" }
    }

    fun rootCommand(command: String): List<String> =
        this.command("shell", requireNotNull(rootTemplate) { "Root shell has not been established" }.format(shellQuote(command)))

    fun rootShell(
        command: String,
        check: Boolean = true,
        timeout: Duration = Duration.ofSeconds(30),
    ): CommandResult = shell.runArguments(rootCommand(command), check, timeout)

    private fun findRootTemplate(): String? {
        rootTemplate?.let { return it }
        rootTemplate =
            listOf("sh -c %s", "su 0 sh -c %s", "su -c %s").firstOrNull { template ->
                val result = shellResult(template.format(shellQuote("id")), check = false)
                result.exitCode == 0 && Regex("(?:^|\\s)uid=0(?:\\D|$)").containsMatchIn(result.stdout)
            }
        return rootTemplate
    }

    private fun quoteArgument(value: String): String =
        if (value.isNotEmpty() && value.all { it.isLetterOrDigit() || it in "_./:-" }) value else shellQuote(value)

    val sdkVersion by lazy {
        shell("getprop ro.build.version.sdk").trim().toIntOrNull() ?: Int.MAX_VALUE
    }

    val abi by lazy {
        shell("getprop ro.product.cpu.abi").trim()
    }

    fun runCommand(
        command: String,
        ignoreErrors: Boolean = false,
    ): String = shell.runCommand("adb $deviceOpts $command", ignoreErrors = ignoreErrors)

    fun shell(
        command: String,
        ignoreErrors: Boolean = false,
        withRoot: Boolean = false,
    ): String {
        val template = if (withRoot) findRootTemplate() else null
        val remoteCommand = template?.let { shellQuote(it.format(shellQuote(command))) } ?: command
        return runCommand("shell $remoteCommand", ignoreErrors = ignoreErrors)
    }

    // TODO: Should we always perform escaping?
    fun getShellEscapedCommand(
        command: String,
        withRoot: Boolean = false,
    ): String {
        val template = (if (withRoot) findRootTemplate() else null) ?: "sh -c %s"
        return "shell ${shellQuote(template.format(shellQuote(command)))}"
    }

    /**
     * Get read / writable directory path
     *
     * Depending on OS Version, what's readable and writable will differ.
     * Ref: https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:benchmark/benchmark-common/src/main/java/androidx/benchmark/Outputs.kt;l=69;drc=7cca76e55aaa9c2ff1a038bac0fa2b91cd04dcff;bpv=0;bpt=1
     */
    fun getDirUsableByAppAndShell(packageName: String) =
        when {
            sdkVersion >= 29 -> "/storage/emulated/0/Android/media/$packageName/"
            sdkVersion in 23..28 -> "/storage/emulated/0/Android/data/$packageName/cache/"
            else -> "/data/data/$packageName/cache/"
        }

    fun isRootable() = findRootTemplate() != null

    fun isRunning(packageName: String): Boolean =
        if (sdkVersion >= ANDROID_N_SDK_VERSION) {
            shell("pidof $packageName", ignoreErrors = true).isNotEmpty()
        } else {
            shell("ps", ignoreErrors = true).contains(packageName)
        }

    /**
     * Get pid of package
     *
     * Note: Run with root if applicable to ensure processes started with root are discoverable (e.g. simpleperf)
     *
     * @return null if process is not found
     */
    fun pidof(processName: String): String? =
        shell("pidof $processName", withRoot = true, ignoreErrors = true)
            .split("\\s+".toRegex())
            .firstOrNull { it.isNotBlank() }

    fun deleteSystemSetting(property: String): String = shell("settings delete system $property")

    fun setProp(
        property: String,
        value: String,
    ): String = shell("setprop $property ${value.ifBlank { """\'\'""" }}")

    fun putSystemSetting(
        property: String,
        value: String,
    ): String {
        // If value is blank, we need to escape it
        return shell("settings put system $property ${value.ifBlank { """\'\'""" }}")
    }

    fun getSystemSetting(property: String): String = shell("settings get system $property")

    fun pull(
        remotePath: String,
        localPath: String,
    ) {
        runCommand("pull ${quoteArgument(remotePath)} ${quoteArgument(localPath)}")
    }

    fun push(
        localPath: String,
        remotePath: String,
    ) {
        runCommand("push ${quoteArgument(localPath)} ${quoteArgument(remotePath)}")
    }

    fun delete(
        path: String,
        force: Boolean = false,
        ignoreErrors: Boolean = true,
    ) {
        // TODO: Check for existence before attempting to delete to avoid stderr message
        shell("rm${if (force) " -f" else ""} \"$path\"", ignoreErrors = ignoreErrors)
    }

    fun ls(path: String): List<String> = ls(path, ignoreErrors = false)

    fun ls(
        path: String,
        ignoreErrors: Boolean,
    ): List<String> =
        shell("ls -1 \"$path\"${if (ignoreErrors) " 2>/dev/null" else ""}", ignoreErrors = ignoreErrors)
            .lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }

    /**
     * Find all available instrumentation test runners.
     *
     * Example Output:
     * instrumentation:com.example.macrobenchmark/androidx.test.runner.AndroidJUnitRunner (target=com.example.macrobenchmark)
     *
     * For instrumentation tests, the target specified is not reliable. It may reference the instrumentation test
     * package itself or the instrumentation test may support multiple package names (e.g. Debug / Prod builds)
     */
    fun findInstrumentationRunners(): List<String> {
        val output = shell("pm list instrumentation")
        return output
            .lines()
            .mapNotNull {
                it
                    .substringAfter("instrumentation:")
                    .substringBefore(" ")
                    .trim()
                    .takeIf { t -> t.isNotBlank() }
            }.distinct()
    }

    /**
     * Resolve the package's main launchable activity in short form
     * (e.g. com.example/.MainActivity) using `cmd package resolve-activity --brief`.
     *
     * @return the resolved activity string, or null if none is found
     */
    fun resolveLaunchableActivity(packageName: String): String? {
        val output = shell("cmd package resolve-activity --brief $packageName", ignoreErrors = true)
        val resolved =
            output
                .lines()
                .lastOrNull()
                ?.trim()
                .orEmpty()
        return if (resolved.isBlank() || resolved.contains("no activity", ignoreCase = true)) null else resolved
    }

    /**
     * Enumerate available instrumentation tests for a given runner.
     *
     * Implementation details:
     * - Executes `am instrument -r -w -e log true -e logOnly true <runner>` on-device to list tests without running them.
     * - Parses standard instrumentation status lines, pairing
     *   `INSTRUMENTATION_STATUS: class=<FQN>` and `INSTRUMENTATION_STATUS: test=<method>`
     *   into entries of the form `FullyQualifiedClass#method`.
     */
    fun getTests(instrumentationPackageName: String): List<String> {
        // Enumerate tests using AndroidJUnitRunner log-only mode
        val output = shell("am instrument -r -w -e log true -e logOnly true $instrumentationPackageName")

        val tests = mutableListOf<String>()
        var currentClass: String? = null
        output.lines().forEach { line ->
            if (line.startsWith("INSTRUMENTATION_STATUS: class=")) {
                currentClass = line.substringAfter("class=").trim()
            } else if (line.startsWith("INSTRUMENTATION_STATUS: test=") && !line.contains("test=null")) {
                tests.add("$currentClass#${line.substringAfter("test=".trim())}")
            }
        }

        return tests.distinct()
    }
}
