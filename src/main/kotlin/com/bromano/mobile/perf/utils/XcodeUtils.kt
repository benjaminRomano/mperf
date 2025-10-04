package com.bromano.mobile.perf.utils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Set of utility Xcode/iOS operations using xcrun commands
 */
class XcodeUtils(
    private val deviceId: String?,
    private val shell: Shell,
) {
    private val deviceOpts = deviceId?.let { "--device $it" } ?: ""

    /**
     * Get list of available devices (both physical devices and simulators)
     */
    fun getAvailableDevices(): List<String> {
        val devices = mutableListOf<String>()

        // Get simulators (only include Booted)
        try {
            val simulatorOutput = shell.runCommand("xcrun simctl list devices available", ignoreErrors = true)
            simulatorOutput
                .lines()
                .filter { it.contains("Booted") }
                .mapNotNull { line ->
                    val regex = """^\s+(.+?)\s+\(([A-F0-9-]{36})\)\s+\((Booted)\)""".toRegex()
                    regex.find(line)?.let { match ->
                        val name = match.groupValues[1]
                        val udid = match.groupValues[2]
                        val state = match.groupValues[3]
                        "$name ($udid) [$state]"
                    }
                }.forEach { devices.add("Simulator: $it") }
        } catch (_: Exception) {
            // Simulators not available
        }

        // Get physical devices (iOS 17+)
        try {
            val deviceOutput = shell.runCommand("xcrun devicectl list devices", ignoreErrors = true)
            deviceOutput
                .lines()
                .filter { it.contains("connected") }
                .mapNotNull { line ->
                    val regex = """^\s*(.+?)\s+\(([A-F0-9a-f]{40})\)\s+connected""".toRegex()
                    regex.find(line)?.let { match ->
                        val name = match.groupValues[1].trim()
                        val udid = match.groupValues[2]
                        "$name ($udid)"
                    }
                }.forEach { devices.add("Device: $it") }
        } catch (_: Exception) {
            // Physical devices not available or older Xcode
        }

        return devices
    }

    /**
     * Get bundle identifier for an app by name (searches installed apps)
     */
    fun findBundleIdentifier(appName: String): String? =
        try {
            if (deviceId?.let { isSimulator(it) } == true) {
                findBundleIdentifierOnSimulator(appName)
            } else {
                findBundleIdentifierOnDevice(appName)
            }
        } catch (_: Exception) {
            null
        }

    private fun findBundleIdentifierOnSimulator(appName: String): String? {
        val output = shell.runCommand("xcrun simctl listapps $deviceId", ignoreErrors = true)
        // Parse output to find bundle identifier for app name
        // This is a simplified implementation - real implementation would parse JSON output
        return output
            .lines()
            .find { it.contains(appName, ignoreCase = true) }
            ?.let {
                // Extract bundle identifier from output
                // Format: "com.example.app" = { ... "CFBundleName" = "AppName"; ... }
                val regex = """"([^"]+)"\s*=\s*\{.*"CFBundleName"\s*=\s*"$appName"""".toRegex(RegexOption.IGNORE_CASE)
                regex.find(output)?.groupValues?.get(1)
            }
    }

    private fun findBundleIdentifierOnDevice(appName: String): String? {
        // For physical devices, this would require more complex logic
        // potentially using devicectl or other tools
        throw NotImplementedError("Bundle identifier lookup on physical devices not yet implemented")
    }

    /**
     * Check if a device ID represents a simulator
     */
    fun isSimulator(deviceId: String): Boolean = deviceId.length == 36 && deviceId.contains("-")

    /**
     * Launch an app on the device/simulator
     */
    fun launchApp(bundleIdentifier: String) {
        if (deviceId?.let { isSimulator(it) } == true) {
            shell.runCommand("xcrun simctl launch $deviceId $bundleIdentifier")
        } else {
            // For physical devices, would use devicectl or other approach
            throw NotImplementedError("App launch on physical devices not yet implemented")
        }
    }

    /**
     * Check if app is running
     */
    fun isAppRunning(bundleIdentifier: String): Boolean =
        try {
            if (deviceId?.let { isSimulator(it) } == true) {
                val output = shell.runCommand("xcrun simctl spawn $deviceId launchctl list", ignoreErrors = true)
                output.contains(bundleIdentifier)
            } else {
                false // Physical device check not implemented
            }
        } catch (_: Exception) {
            false
        }

    /**
     * Get available Instruments templates
     */
    fun getInstrumentsTemplates(): List<String> {
        val output = shell.runCommand("xcrun xctrace list templates")
        return output
            .lines()
            .filter { it.trim().isNotEmpty() && !it.startsWith("== ") }
            .map { it.trim() }
    }

    /**
     * Start xctrace recording
     */
    fun record(
        template: String,
        instruments: List<String>,
        bundleIdentifier: String,
        outputPath: String,
    ) {
        val cmd =
            buildList {
                add("xcrun")
                add("xctrace")
                add("record")
                add("--template")
                add("\"$template\"")
                if (!deviceOpts.isBlank()) {
                    addAll(deviceOpts.split(" "))
                }
                add(if (isAppRunning(bundleIdentifier)) "--attach" else "--launch")
                add(bundleIdentifier)
                add("--no-prompt")
                add("--output")
                add("\"$outputPath\"")
                instruments.forEach {
                    add("--instrument")
                    add(it)
                }
            }.joinToString(" ")

        val process =
            shell
                .newProcessBuilder(cmd)
                .redirectOutput(ProcessBuilder.Redirect.PIPE)
                .start()

        // HACK: IntelliJ debug window doesn't support Ctrl-C, so replace with press any key to terminate.
        val inputProcessor =
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                process.inputStream.use {
                    it.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            if (line.contains("Ctrl-C to stop the recording")) {
                                println("Press any key to end tracing...")
                            } else {
                                println(line)
                            }
                        }
                    }
                }
            }

        readln()
        shell.startProcess("kill -INT ${process.pid()}")
        process.waitFor(30, TimeUnit.SECONDS)
        inputProcessor.cancel()
    }
}
