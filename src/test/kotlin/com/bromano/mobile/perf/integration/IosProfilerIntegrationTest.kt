package com.bromano.mobile.perf.integration

import com.bromano.mobile.perf.gecko.InstrumentsConverter
import com.bromano.mobile.perf.profilers.instruments.InstrumentsProfiler
import com.bromano.mobile.perf.profilers.instruments.InstrumentsProfilerOptions
import com.bromano.mobile.perf.utils.ShellExecutor
import com.bromano.mobile.perf.utils.XcodeUtils
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeBytes
import kotlin.test.assertTrue

class IosProfilerIntegrationTest {
    private val shell = ShellExecutor()

    @Test
    @Timeout(value = 8, unit = TimeUnit.MINUTES)
    fun collects_launch_and_attach_traces_and_converts_time_profile() {
        assumeTrue(
            java.lang.Boolean.getBoolean("mperf.integration.ios.enabled"),
            "Set -Dmperf.integration.ios.enabled=true to run iOS simulator integration tests",
        )
        assumeTrue(System.getProperty("os.name").startsWith("Mac"), "iOS integration tests require macOS")
        shell.runCommand("xcodebuild -version")

        val requestedDevice = System.getProperty("mperf.integration.ios.device")
        val simulator = requestedDevice ?: findSimulator()
        assumeTrue(simulator != null, "No available iOS simulator runtime found")
        val deviceId = requireNotNull(simulator)
        val wasBooted = isBooted(deviceId)
        val workspace = createTempDirectory("mperf-ios-integration")
        val app = workspace.resolve("MperfFixture.app")
        val bundleIdentifier = "com.bromano.mperf.integration.fixture"

        try {
            if (!wasBooted) {
                shell.runCommand("xcrun simctl boot ${quote(deviceId)}", ignoreErrors = true)
            }
            shell.runCommand("xcrun simctl bootstatus ${quote(deviceId)} -b")
            buildFixtureApp(app)
            shell.runCommand("xcrun simctl install ${quote(deviceId)} ${quote(app.toString())}")

            val xcodeUtils = XcodeUtils(deviceId, shell, awaitStop = { Thread.sleep(3_000) })
            assertTrue(xcodeUtils.isAppInstalled(bundleIdentifier))

            shell.runCommand("xcrun simctl terminate ${quote(deviceId)} ${quote(bundleIdentifier)}", ignoreErrors = true)
            val launchTrace = workspace.resolve("launch.trace")
            val profilerOptions =
                InstrumentsProfilerOptions(
                    instruments = listOf("Points of Interest"),
                    timeLimit = "5s",
                )
            InstrumentsProfiler(xcodeUtils, profilerOptions)
                .execute(bundleIdentifier, launchTrace)
            assertTraceCreated(launchTrace)
            assertSignpostSchemaCreated(launchTrace)

            xcodeUtils.launchApp(bundleIdentifier)
            Thread.sleep(1_000)
            assertTrue(xcodeUtils.isAppRunning(bundleIdentifier))
            val attachTrace = workspace.resolve("attach.trace")
            InstrumentsProfiler(xcodeUtils, profilerOptions)
                .execute(bundleIdentifier, attachTrace)
            assertTraceCreated(attachTrace)

            val profile =
                InstrumentsConverter.convert(
                    "MperfFixture",
                    launchTrace,
                    processId = requireNotNull(xcodeUtils.lastRecordedProcessId),
                )
            assertTrue(profile.threads.isNotEmpty(), "converted Gecko profile should contain sampled threads")
            assertTrue(
                profile.threads.any { it.name.contains("MperfFixture") && it.pid > 0 },
                "converted Gecko profile should contain fixture samples with a process ID",
            )
        } finally {
            shell.runCommand("xcrun simctl terminate ${quote(deviceId)} ${quote(bundleIdentifier)}", ignoreErrors = true)
            shell.runCommand("xcrun simctl uninstall ${quote(deviceId)} ${quote(bundleIdentifier)}", ignoreErrors = true)
            if (!wasBooted) {
                shell.runCommand("xcrun simctl shutdown ${quote(deviceId)}", ignoreErrors = true)
            }
            workspace.toFile().deleteRecursively()
        }
    }

    private fun buildFixtureApp(app: Path) {
        app.createDirectories()
        val source = resourceBytes("ios-fixture/main.m")
        app.resolve("main.m").writeBytes(source)
        app.resolve("Info.plist").writeBytes(resourceBytes("ios-fixture/Info.plist"))
        val architecture = shell.runCommand("uname -m")
        val target = "$architecture-apple-ios17.0-simulator"
        shell.runCommand(
            "xcrun --sdk iphonesimulator clang -fobjc-arc -target ${quote(target)} " +
                "-framework UIKit -framework Foundation ${quote(app.resolve("main.m").toString())} " +
                "-o ${quote(app.resolve("MperfFixture").toString())}",
        )
    }

    private fun resourceBytes(name: String): ByteArray =
        requireNotNull(javaClass.classLoader.getResourceAsStream(name)) { "Missing integration resource $name" }
            .use { it.readBytes() }

    private fun findSimulator(): String? {
        val root =
            JsonParser
                .parseString(shell.runCommand("xcrun simctl list devices available --json"))
                .asJsonObject
        val devices = root.getAsJsonObject("devices")
        val allDevices =
            devices.entrySet().flatMap { (_, values) ->
                values.asJsonArray.map { it.asJsonObject }
            }
        return allDevices
            .firstOrNull {
                it.get("isAvailable")?.asBoolean != false &&
                    it.get("name")?.asString?.startsWith("iPhone") == true &&
                    it.get("state")?.asString == "Booted"
            }?.get("udid")
            ?.asString
            ?: allDevices
                .firstOrNull {
                    it.get("isAvailable")?.asBoolean != false &&
                        it.get("name")?.asString?.startsWith("iPhone") == true
                }?.get("udid")
                ?.asString
    }

    private fun isBooted(deviceId: String): Boolean =
        shell
            .runCommand("xcrun simctl list devices ${quote(deviceId)} --json")
            .contains("\"state\" : \"Booted\"")

    private fun assertTraceCreated(path: Path) {
        assertTrue(path.exists() && path.isDirectory(), "expected Instruments trace directory at $path")
        assertTrue(path.listDirectoryEntries().isNotEmpty(), "expected non-empty Instruments trace at $path")
        val toc =
            shell.runCommand(
                "xcrun xctrace export --input ${quote(path.toString())} --toc",
                redirectOutput = ProcessBuilder.Redirect.PIPE,
                redirectError = ProcessBuilder.Redirect.PIPE,
            )
        assertTrue(toc.contains("time-profile"), "trace table of contents should include Time Profiler data")
    }

    private fun assertSignpostSchemaCreated(path: Path) {
        val toc =
            shell.runCommand(
                "xcrun xctrace export --input ${quote(path.toString())} --toc",
                redirectOutput = ProcessBuilder.Redirect.PIPE,
                redirectError = ProcessBuilder.Redirect.PIPE,
            )
        assertTrue(toc.contains("schema=\"os-signpost\""), "trace should include the os-signpost schema")
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"
}
