package com.bromano.mobile.perf.utils

private const val ANDROID_N_SDK_VERSION = 24

/**
 * Set of utility adb operations
 */
class Adb(
    device: String?,
    private val shell: Shell,
) {
    val deviceOpts = device?.let { "-s $it" } ?: ""

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
        val escapedCommand =
            if (withRoot && isRootable()) {
                innerQuoteForShell(command)
            } else {
                command
            }

        return runCommand(
            "shell${if (withRoot && isRootable()) " " + getRunAsRootCommand() else ""} $escapedCommand",
            ignoreErrors = ignoreErrors,
        )
    }

    // TODO: Should we always perform escaping?
    fun getShellEscapedCommand(
        command: String,
        withRoot: Boolean = false,
    ): String {
        val quotedCommand = innerQuoteForShell(command)
        val prefix =
            if (withRoot && isRootable()) {
                getRunAsRootCommand().ifBlank { "sh -c" }
            } else {
                "sh -c"
            }

        return "shell $prefix $quotedCommand"
    }

    /**
     * Perform escaping for necessary inputs
     */
    private fun innerQuoteForShell(str: String): String {
        val escaped =
            str
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\"", "\\\"")
                .replace("\t", "\\\t")
                .replace("\n", "\\\n")
        return "\"'\"$escaped\"\'\""
    }

    /**
     * Best-effort get run as root command
     *
     * Thi invocation of su will differ depending on device
     */
    private fun getRunAsRootCommand(): String {
        try {
            if (!isRootable()) {
                return ""
            }

            val suOutput = shell("\"su --help 2>&1\"")
            return if (suOutput.contains("usage: su [WHO [COMMAND...]]") ||
                suOutput.contains("usage: su [UID[,GID[,GID2]...]] [COMMAND [ARG...]]")
            ) {
                "su 0 sh -c"
            } else {
                "su -c"
            }
        } catch (_: ShellCommandException) {
            return ""
        }
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

    fun isRootable() = shell("which su", ignoreErrors = true).isNotBlank()

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
        shell("pidof $processName", withRoot = true).split("\\s+".toRegex()).firstOrNull { it.isNotBlank() }

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
        runCommand("pull $remotePath $localPath")
    }

    fun push(
        localPath: String,
        remotePath: String,
    ) {
        runCommand("push $localPath $remotePath")
    }

    fun delete(
        path: String,
        force: Boolean = false,
        ignoreErrors: Boolean = true,
    ) {
        // TODO: Check for existence before attempting to delete to avoid stderr message
        shell("rm${if (force) " -f" else ""} \"$path\"", ignoreErrors = ignoreErrors)
    }

    fun ls(path: String): List<String> = runCommand("ls $path").split("\\s".toRegex())

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
