package com.bromano.mobile.perf.utils

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Set of utility Xcode/iOS operations using xcrun commands
 */
class XcodeUtils(
    private val deviceId: String?,
    private val shell: Shell,
    private val awaitStop: () -> Unit = { readlnOrNull() },
    private val temporaryFile: () -> Path = { Files.createTempFile("mperf-devicectl", ".json") },
    private val awaitSimulatorProcessRegistration: () -> Unit = { Thread.sleep(1_000) },
) {
    var lastRecordedProcessId: Long? = null
        private set

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

    /**
     * Get list of available devices (both physical devices and simulators)
     */
    fun getAvailableDevices(): List<String> {
        val devices = mutableListOf<String>()

        // Get simulators (only include Booted)
        try {
            val simulatorOutput = shell.runCommand(SIMULATOR_LIST_COMMAND, ignoreErrors = true)
            parseSimulatorDevices(simulatorOutput)
                .filter { it.state == "Booted" && it.isAvailable }
                .forEach { devices.add("Simulator: ${it.name} (${it.udid}) [${it.state}]") }
        } catch (_: Exception) {
            // Simulators not available
        }

        // Get physical devices (iOS 17+)
        try {
            withDeviceControlJson("xcrun devicectl list devices") { root ->
                root
                    .resultArray("devices")
                    .filter { device ->
                        val state = device.objectAt("connectionProperties")?.stringAt("tunnelState")
                        state == null || state.equals("connected", ignoreCase = true)
                    }.forEach { device ->
                        val identifier = device.stringAt("identifier") ?: return@forEach
                        val name = device.objectAt("deviceProperties")?.stringAt("name") ?: identifier
                        devices.add("Device: $name ($identifier)")
                    }
            }
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
        val output = shell.runCommand("xcrun simctl listapps ${shellQuote(requireNotNull(deviceId))}", ignoreErrors = true)
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
    fun isSimulator(deviceId: String): Boolean = isSimulatorIdentifier(deviceId)

    /**
     * Check whether a device identifier belongs to an available simulator. CoreDevice identifiers for physical
     * devices are UUIDs too, so identifier shape alone cannot distinguish the two.
     */
    fun isSimulatorIdentifier(deviceId: String): Boolean =
        try {
            parseSimulatorDevices(shell.runCommand(SIMULATOR_LIST_COMMAND, ignoreErrors = true))
                .any { it.udid.equals(deviceId, ignoreCase = true) }
        } catch (_: Exception) {
            false
        }

    private val boundDeviceIsSimulator: Boolean by lazy {
        deviceId?.let(::isSimulatorIdentifier) == true
    }

    /** Check that the exact bundle identifier is installed on the selected target. */
    fun isAppInstalled(bundleIdentifier: String): Boolean {
        val target = deviceId ?: return false
        return if (boundDeviceIsSimulator) {
            simulatorAppPath(target, bundleIdentifier) != null
        } else {
            try {
                withDeviceControlJson(
                    "xcrun devicectl device info apps --device ${shellQuote(target)} " +
                        "--bundle-id ${shellQuote(bundleIdentifier)}",
                ) { root -> root.resultArray("apps").isNotEmpty() }
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun simulatorAppPath(
        target: String,
        bundleIdentifier: String,
    ): String? =
        shell
            .runCommand(
                "xcrun simctl get_app_container ${shellQuote(target)} ${shellQuote(bundleIdentifier)} app",
                ignoreErrors = true,
            ).trim()
            .takeIf { it.isNotEmpty() }

    /**
     * Launch an app on the device/simulator
     */
    fun launchApp(bundleIdentifier: String): Long? {
        val target = requireNotNull(deviceId) { "A device is required to launch an iOS app" }
        return if (boundDeviceIsSimulator) {
            shell
                .runCommand("xcrun simctl launch ${shellQuote(target)} ${shellQuote(bundleIdentifier)}")
                .substringAfterLast(":", "")
                .trim()
                .toLongOrNull()
        } else {
            shell.runCommand(
                "xcrun devicectl device process launch --device ${shellQuote(target)} ${shellQuote(bundleIdentifier)}",
            )
            null
        }
    }

    /**
     * Check if app is running
     */
    fun isAppRunning(bundleIdentifier: String): Boolean = simulatorProcessId(bundleIdentifier) != null

    private fun simulatorProcessId(bundleIdentifier: String): Long? =
        try {
            if (boundDeviceIsSimulator) {
                val target = requireNotNull(deviceId)
                val output =
                    shell.runCommand(
                        "xcrun simctl spawn ${shellQuote(target)} launchctl list",
                        ignoreErrors = true,
                    )
                val appLabel = Regex("(?:^|:)${Regex.escape(bundleIdentifier)}(?:\\[|$)")
                output.lineSequence().firstNotNullOfOrNull { line ->
                    val columns = line.split('\t', limit = 3)
                    columns
                        .takeIf { it.size == 3 && appLabel.containsMatchIn(it[2]) }
                        ?.get(0)
                        ?.toLongOrNull()
                }
            } else {
                null // Physical device process discovery is not available on older Xcode releases.
            }
        } catch (_: Exception) {
            null
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
        timeLimit: String? = null,
    ) {
        var simulatorProcessId = simulatorProcessId(bundleIdentifier)
        if (simulatorProcessId == null && boundDeviceIsSimulator) {
            // xctrace launch can stall indefinitely for a simulator bundle identifier. simctl is the supported,
            // deterministic app launcher; once it returns a PID, xctrace can attach reliably.
            simulatorProcessId = launchApp(bundleIdentifier)
            if (simulatorProcessId != null) {
                // CoreSimulator reports its guest PID before Instruments' host-side process catalog is updated.
                awaitSimulatorProcessRegistration()
            }
        }
        val attachToRunningApp = simulatorProcessId != null
        val attachTarget = simulatorProcessId?.toString() ?: bundleIdentifier
        lastRecordedProcessId = simulatorProcessId
        val cmd =
            "exec " +
                buildList {
                    add("xcrun")
                    add("xctrace")
                    add("record")
                    add("--template")
                    add(template)
                    if (deviceId != null && !boundDeviceIsSimulator) {
                        add("--device")
                        add(deviceId)
                    }
                    add("--no-prompt")
                    add("--output")
                    add(outputPath)
                    instruments.forEach {
                        add("--instrument")
                        add(it)
                    }
                    if (timeLimit != null) {
                        add("--time-limit")
                        add(timeLimit)
                    }
                    if (boundDeviceIsSimulator) {
                        // Simulator applications are host processes. Recording the host avoids the CoreSimulator
                        // remote-target handshake, which can hang indefinitely on recent Xcode versions.
                        add("--all-processes")
                        Logger.warning(
                            "Warning: recent Xcode versions cannot target a simulator process reliably; " +
                                "recording uses a host-wide fallback. The converted profile is filtered to PID " +
                                "$simulatorProcessId, but the raw Instruments trace contains other host processes " +
                                "and unified-log instruments do not include Simulator guest events.",
                        )
                    } else {
                        add(if (attachToRunningApp) "--attach" else "--launch")
                        if (last() == "--launch") {
                            // xctrace requires the launched command to be last and separated from its own arguments.
                            add("--")
                        }
                        add(attachTarget)
                    }
                }.joinToString(" ") { shellQuote(it) }

        val process =
            shell
                .newProcessBuilder(cmd)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.PIPE)
                .start()

        // HACK: IntelliJ debug window doesn't support Ctrl-C, so replace with press any key to terminate.
        val inputProcessor =
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                process.inputStream.forwardToStdout()
            }

        var stopped =
            if (timeLimit == null) {
                awaitStop()
                shell.startProcess("kill -INT ${process.pid()}")
                shell.waitFor(process, 30, TimeUnit.SECONDS)
            } else {
                shell.waitFor(process, 2, TimeUnit.MINUTES)
            }
        if (!stopped) {
            process.destroy()
            stopped = shell.waitFor(process, 10, TimeUnit.SECONDS)
        }
        inputProcessor.cancel()

        if (!stopped) {
            process.destroyForcibly()
            shell.waitFor(process, 10, TimeUnit.SECONDS)
            throw IllegalStateException("xctrace did not stop before the collection timeout; the trace may be unusable")
        }
        if (process.exitValue() != 0) {
            throw IllegalStateException("xctrace exited with code ${process.exitValue()}")
        }
    }

    private fun <T> withDeviceControlJson(
        command: String,
        block: (JsonObject) -> T,
    ): T {
        val output = temporaryFile()
        return try {
            shell.runCommand("$command --json-output ${shellQuote(output.toString())} --quiet", ignoreErrors = true)
            require(Files.isRegularFile(output)) { "devicectl did not create JSON output" }
            block(JsonParser.parseString(Files.readString(output)).asJsonObject)
        } finally {
            Files.deleteIfExists(output)
        }
    }

    private fun JsonObject.resultArray(name: String): List<JsonObject> =
        objectAt("result")
            ?.getAsJsonArray(name)
            ?.mapNotNull { it.takeIf { element -> element.isJsonObject }?.asJsonObject }
            .orEmpty()

    private fun JsonObject.objectAt(name: String): JsonObject? = get(name)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.stringAt(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString

    private fun parseSimulatorDevices(json: String): List<SimulatorDevice> {
        if (json.isBlank()) return emptyList()
        val devices = JsonParser.parseString(json).asJsonObject.objectAt("devices") ?: return emptyList()
        return devices.entrySet().flatMap { (_, runtimeDevices) ->
            runtimeDevices.asJsonArray.mapNotNull { element ->
                val device = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                SimulatorDevice(
                    name = device.stringAt("name") ?: return@mapNotNull null,
                    udid = device.stringAt("udid") ?: return@mapNotNull null,
                    state = device.stringAt("state") ?: return@mapNotNull null,
                    isAvailable = device.get("isAvailable")?.asBoolean ?: true,
                )
            }
        }
    }

    private data class SimulatorDevice(
        val name: String,
        val udid: String,
        val state: String,
        val isAvailable: Boolean,
    )

    private companion object {
        const val SIMULATOR_LIST_COMMAND = "xcrun simctl list devices available --json"
    }

    private fun InputStream.forwardToStdout() {
        use {
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
}
