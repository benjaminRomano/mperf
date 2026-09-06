package com.bromano.mobile.perf.faults

import com.bromano.mobile.perf.utils.CommandResult
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidCompilationTest {
    @TempDir lateinit var output: Path

    @Test fun `compilation evidence retains actual filters without inferring AOT from artifact names`() {
        val dump =
            """
            arm64: [status=verify] [reason=install] [primary-abi]
            [location is /data/app/example/oat/arm64/base.odex]
            arm64: [status=speed] [reason=cmdline] [primary-abi]
            arm: [status=verify] [reason=install]
            """.trimIndent()
        assertEquals(listOf("speed", "verify"), compilationStatuses(dump, 0))
        assertEquals(emptyList(), compilationStatuses(dump, 1))
        assertEquals(emptyList(), compilationStatuses("base.odex exists", 0))
        assertEquals(listOf("speed-profile"), compilationStatuses("[status=speed-profile]", 0))
    }

    @Test fun `validation requires every code APK and correct ISA without accepting similarly named paths`() {
        val apks = setOf("/data/app/pkg/base.apk", "/data/app/pkg/split_feature.apk")

        fun state(
            path: String,
            isa: String,
            status: String,
        ) = "path: $path\n  $isa: [status=$status] [reason=cmdline]"
        val base = state(apks.first(), "arm64", "speed-profile")
        assertFalse(AndroidCompilation.isSpeedProfile(base, apks, "arm64"))
        val wrongIsa = base + "\n" + state(apks.last(), "arm", "speed-profile")
        assertFalse(AndroidCompilation.isSpeedProfile(wrongIsa, apks, "arm64"))
        assertFalse(AndroidCompilation.isSpeedProfile(base + "\n" + state(apks.last(), "arm64", "speed"), apks, "arm64"))
        assertFalse(
            AndroidCompilation.isSpeedProfile(
                state("/data/app/pkg-other/base.apk", "arm64", "speed-profile"),
                setOf(apks.first()),
                "arm64",
            ),
        )
        assertTrue(AndroidCompilation.isSpeedProfile(base + "\n" + state(apks.last(), "arm64", "speed-profile"), apks, "arm64"))
        assertFalse(AndroidCompilation.isSpeedProfile(base, emptySet(), "arm64"))
    }

    @Test fun `missing usable device profile installs extracted baseline then verifies actual compilation`() {
        val commands = mutableListOf<String>()
        val metadata = mutableMapOf<String, Any?>()
        var dumps = 0
        var stopped = 0
        val helper =
            AndroidCompilation(36, "arm64-v8a", "com.example.app", output, metadata, shell = { command, _ ->
                commands += command
                when {
                    command.startsWith("pm art dump") -> ok(dump(listOf("speed", "verify", "speed-profile")[dumps++]))
                    command.startsWith("unzip -l") -> ok(listing)
                    command.startsWith(
                        "set -o pipefail; unzip -p",
                    ) -> ok(Base64.getEncoder().encodeToString("pro\u0000010\u0000".toByteArray()))
                    command.startsWith("am broadcast") -> ok("Broadcast completed: result=1\n")
                    command.startsWith("cmd package compile") -> ok("Success\n")
                    else -> error(command)
                }
            }, stop = { stopped++ })
        helper.prepare("speed-profile", listOf(apk))
        assertEquals(2, commands.count { it.startsWith("cmd package compile") })
        assertEquals(1, stopped)
        assertTrue(metadata["compilation_profile_source"].toString().startsWith("APK baseline installed"))
        assertTrue(Files.isRegularFile(output.resolve("compilation-install-baseline.txt")))
        assertEquals(1, Files.list(output.resolve("profiles")).use { it.count().toInt() })
        assertTrue(commands.none { "-m speed " in it || "--reset" in it })
    }

    @Test fun `broadcast success exit code is insufficient and force stop happens even when baseline install fails`() {
        var stopped = false
        val helper =
            AndroidCompilation(29, "arm64-v8a", "com.example.app", output, mutableMapOf(), shell = { command, _ ->
                when {
                    command.startsWith("dumpsys package") -> ok(dump("verify"))
                    command.startsWith("unzip -l") -> ok(listing)
                    command.startsWith(
                        "set -o pipefail; unzip -p",
                    ) -> ok(Base64.getEncoder().encodeToString("pro\u0000010\u0000".toByteArray()))
                    command.startsWith("am broadcast") -> ok("Broadcast completed: result=0\n")
                    else -> ok("Success\n")
                }
            }, stop = { stopped = true })
        assertFailsWith<IllegalArgumentException> { helper.prepare("speed-profile", listOf(apk)) }
        assertTrue(stopped)
    }

    @Test fun `already profiled and as-is states do not request compilation or launch a receiver`() {
        for (mode in listOf("speed-profile", "as-is")) {
            val commands = mutableListOf<String>()
            val helper =
                AndroidCompilation(36, "arm64-v8a", "com.example.app", output, mutableMapOf(), shell = { command, _ ->
                    commands += command
                    when {
                        command.startsWith("pm art dump") -> ok(dump(if (mode == "as-is") "speed" else "speed-profile"))
                        command.startsWith("unzip -l") -> ok(listing)
                        else -> error("Unexpected mutation: $command")
                    }
                }, stop = { error("Unexpected stop") })
            helper.prepare(mode, listOf(apk))
            assertEquals(if (mode == "as-is") 1 else 2, commands.size)
        }
    }

    @Test fun `command Success with verify and no baseline fails closed`() {
        val helper =
            AndroidCompilation(36, "arm64-v8a", "com.example.app", output, mutableMapOf(), shell = { command, _ ->
                when {
                    command.startsWith("pm art dump") -> ok(dump("verify"))
                    command.startsWith("unzip -l") -> ok(manifestEntry + "\n  100  1981-01-01 01:01   classes.dex")
                    command.startsWith("cmd package compile") -> ok("Success")
                    else -> error(command)
                }
            }, stop = {})
        val error = assertFailsWith<IllegalArgumentException> { helper.prepare("speed-profile", listOf(apk)) }
        assertTrue(error.message.orEmpty().contains("no embedded"))
    }

    @Test fun `ambiguous APK entries are rejected`() {
        assertFailsWith<IllegalArgumentException> { AndroidCompilation.entries("$listing\n$manifestEntry") }
    }

    @Test fun `compilation drift blocks preflight but preserves post-capture diagnostics`() {
        val metadata = mutableMapOf<String, Any?>("compilation_mode" to "speed-profile", "compilation_code_apks" to listOf(apk))
        val helper =
            AndroidCompilation(
                36,
                "arm64-v8a",
                "com.example.app",
                output,
                metadata,
                shell = { _, _ -> ok(dump("verify")) },
                stop = {},
            )
        assertFailsWith<IllegalArgumentException> { helper.validate("before", strict = true) }
        helper.validate("after", strict = false)
        assertEquals(false, metadata["speed_profile_verified_before"])
        assertEquals(false, metadata["speed_profile_verified_after"])
        assertEquals(2, (metadata["warnings"] as List<*>).size)
    }

    private val apk = "/data/app/pkg/base.apk"
    private val manifestEntry = "  100  1981-01-01 01:01   AndroidManifest.xml"
    private val listing = manifestEntry + "\n  100  1981-01-01 01:01   classes.dex\n  8  1981-01-01 01:01   assets/dexopt/baseline.prof"

    private fun dump(filter: String) = "path: $apk\n arm64: [status=$filter] [reason=cmdline] [primary-abi]\n"

    private fun ok(text: String) = CommandResult(0, text, "")
}
