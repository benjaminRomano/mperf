package com.bromano.mobile.perf.faults

import com.bromano.mobile.perf.utils.CommandResult
import com.bromano.mobile.perf.utils.sha256
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Base64

/** All preparation happens before cache eviction; requested compiler filters are not evidence of actual filters. */
internal class AndroidCompilation(
    private val sdk: Int,
    abi: String,
    private val packageName: String,
    private val output: Path,
    private val metadata: MutableMap<String, Any?>,
    private val shell: (String, Duration) -> CommandResult,
    private val stop: () -> Unit,
) {
    private val instructionSet =
        when (abi) {
            "arm64-v8a" -> "arm64"
            "armeabi-v7a", "armeabi" -> "arm"
            else -> abi
        }

    fun prepare(
        mode: String,
        apkPaths: List<String>,
    ) {
        require(mode in setOf("speed-profile", "as-is")) { "Unknown compilation mode: $mode" }
        metadata["compilation_mode"] = mode
        val initial = record("initial")
        if (mode == "as-is") {
            metadata["compilation_profile_source"] = "as-is; no compilation requested"
            persist()
            return
        }
        val inventory =
            apkPaths.associateWith { apk ->
                val result = shell("unzip -l ${quote(apk)}", Duration.ofSeconds(60))
                require(result.exitCode == 0) { "Cannot inspect installed APK for DEX/profile entries: $apk" }
                entries(result.stdout).also {
                    require("AndroidManifest.xml" in it) { "Unrecognized installed APK inventory: $apk" }
                }
            }
        val codeApks = inventory.filterValues { entries -> entries.keys.any { DEX.matches(it) } }.keys
        require(codeApks.isNotEmpty()) { "No primary DEX found in installed APKs; use --compilation as-is for a code-free package" }
        metadata["compilation_code_apks"] = codeApks.toList()
        if (verified(initial, codeApks)) {
            metadata["compilation_profile_source"] = "existing speed-profile artifacts"
            persist()
            return
        }
        metadata["compilation_profile_source"] = "existing device profile requested"
        compile("device-profile")
        if (verified(record("device-profile"), codeApks)) {
            metadata["compilation_profile_source"] = "existing device or externally supplied profile"
            persist()
            return
        }

        val profiles =
            inventory.flatMap { (apk, entries) ->
                entries.filterKeys { it == BASELINE || it == BASELINE_META }.map { (entry, size) ->
                    require(size in 8..MAX_PROFILE_BYTES) { "Invalid embedded profile size in $apk: $entry ($size)" }
                    val result = shell("set -o pipefail; unzip -p ${quote(apk)} ${quote(entry)} | base64", Duration.ofSeconds(60))
                    require(result.exitCode == 0) { "Cannot extract embedded profile: $apk!$entry" }
                    val bytes = Base64.getMimeDecoder().decode(result.stdout)
                    require(bytes.size.toLong() == size) { "Embedded profile changed while reading: $apk!$entry" }
                    require(bytes.take(4).toByteArray().contentEquals(if (entry == BASELINE) PROFILE_MAGIC else META_MAGIC)) {
                        "Invalid embedded profile header: $apk!$entry"
                    }
                    val directory = Files.createDirectories(output.resolve("profiles"))
                    val file = directory.resolve("${sha256(apk.toByteArray()).take(12)}-${Path.of(entry).fileName}")
                    Files.write(file, bytes)
                    mapOf(
                        "apk" to apk,
                        "entry" to entry,
                        "size" to size,
                        "sha256" to sha256(file),
                        "file" to output.relativize(file).toString(),
                        "version" to String(bytes.copyOfRange(4, 7), Charsets.US_ASCII),
                    )
                }
            }
        metadata["compilation_baseline_profiles"] = profiles
        persist()
        require(profiles.any { it["entry"] == BASELINE }) {
            "speed-profile was not produced and no embedded assets/dexopt/baseline.prof was found. " +
                "Install a matching profile or explicitly use --compilation as-is; no full-speed fallback was used."
        }
        // ProfileInstaller reads these APK assets and transcodes .prof/.profm for the running ART version.
        // Copying baseline.prof directly into ART's profile directory is not portable (010 vs 015 formats).
        metadata["compilation_profile_source"] = "APK baseline via ProfileInstaller (pending validation)"
        val broadcast =
            try {
                command(
                    "install-baseline",
                    "am broadcast --include-stopped-packages " +
                        "-a androidx.profileinstaller.action.INSTALL_PROFILE " +
                        "-n ${quote("$packageName/androidx.profileinstaller.ProfileInstallReceiver")}",
                    Duration.ofSeconds(60),
                )
            } finally {
                stop()
            }
        require(broadcast.exitCode == 0 && Regex("Broadcast completed: result=1(?:\\s|$)").containsMatchIn(broadcast.stdout)) {
            "Embedded baseline profile installation did not succeed. This APK needs a working AndroidX " +
                "ProfileInstallReceiver, or a matching device-format profile installed via profgen/DexMetadata. " +
                "See compilation-install-baseline.txt; use --compilation as-is only for an intentional unprofiled capture."
        }
        compile("baseline-profile")
        require(verified(record("baseline-profile"), codeApks)) {
            "ART did not report speed-profile for every code APK on $instructionSet after baseline installation. " +
                "See compilation-baseline-profile.txt; capture was not started and no full-speed fallback was used."
        }
        metadata["compilation_profile_source"] = "APK baseline installed by ProfileInstaller; may include existing device profile"
        persist()
    }

    fun validate(
        phase: String,
        strict: Boolean,
    ) {
        val result = record(phase)
        if (metadata["compilation_mode"] != "speed-profile") return
        val codeApks = (metadata["compilation_code_apks"] as? List<*>)?.map { it.toString() }?.toSet().orEmpty()
        val valid = verified(result, codeApks)
        metadata["speed_profile_verified_$phase"] = valid
        if (!valid) warn("speed-profile state was not verified $phase capture; compilation may have changed.")
        persist()
        require(valid || !strict) { "speed-profile validation failed before cache eviction; capture not started" }
    }

    fun record(phase: String): CommandResult? {
        val prefix = if (sdk >= 34) "pm art dump" else "dumpsys package"
        return try {
            val result = command(phase, "$prefix ${quote(packageName)}")
            metadata["compilation_$phase"] =
                mapOf(
                    "command" to prefix,
                    "exit_code" to result.exitCode,
                    "file" to "compilation-$phase.txt",
                    "sha256" to sha256(output.resolve("compilation-$phase.txt")),
                    "statuses" to compilationStatuses(result.stdout, result.exitCode),
                )
            if (result.exitCode != 0) warn("Compilation state ($phase) unavailable: exit ${result.exitCode}")
            persist()
            result
        } catch (error: Exception) {
            warn("Compilation state ($phase) unavailable: ${error.message}")
            persist()
            null
        }
    }

    private fun verified(
        result: CommandResult?,
        codeApks: Set<String>,
    ) = result != null && result.exitCode == 0 && isSpeedProfile(result.stdout, codeApks, instructionSet)

    private fun compile(phase: String) {
        val result = command("compile-$phase", "cmd package compile -f -m speed-profile ${quote(packageName)}", Duration.ofMinutes(10))
        if (result.exitCode != 0) warn("Profile-guided compilation ($phase) returned exit ${result.exitCode}; checking actual ART state")
        persist()
    }

    private fun command(
        phase: String,
        command: String,
        timeout: Duration = Duration.ofSeconds(30),
    ): CommandResult {
        val result = shell(command, timeout)
        Files.writeString(output.resolve("compilation-$phase.txt"), result.stdout + result.stderr)
        return result
    }

    private fun persist() = Json.write(output.resolve("capture_metadata.json"), metadata)

    @Suppress("UNCHECKED_CAST")
    private fun warn(message: String) {
        (metadata.getOrPut("warnings") { mutableListOf<String>() } as MutableList<String>).add(message)
    }

    companion object {
        private const val BASELINE = "assets/dexopt/baseline.prof"
        private const val BASELINE_META = "assets/dexopt/baseline.profm"
        private const val MAX_PROFILE_BYTES = 64L * 1024 * 1024
        private val PROFILE_MAGIC = byteArrayOf(112, 114, 111, 0)
        private val META_MAGIC = byteArrayOf(112, 114, 109, 0)
        private val DEX = Regex("classes(?:[2-9][0-9]*|1[0-9]+)?\\.dex")
        private val ZIP_ENTRY = Regex("^\\s*(\\d+)\\s+\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}\\s+(.+)$")

        internal fun entries(listing: String): Map<String, Long> {
            val entries =
                listing
                    .lineSequence()
                    .mapNotNull { line ->
                        ZIP_ENTRY.matchEntire(line)?.let { it.groupValues[2] to it.groupValues[1].toLong() }
                    }.toList()
            require(entries.map { it.first }.distinct().size == entries.size) { "Duplicate APK entry names" }
            return entries.toMap()
        }

        internal fun isSpeedProfile(
            dump: String,
            codeApks: Set<String>,
            instructionSet: String,
        ): Boolean {
            var path: String? = null
            val statuses = mutableMapOf<String, MutableList<String>>()
            for (line in dump.lineSequence()) {
                val trimmed = line.trim()
                if (trimmed.startsWith("path: ")) path = trimmed.removePrefix("path: ").trim()
                val match = Regex("${Regex.escape(instructionSet)}: \\[status=([^] ]+)](?:.*)").matchEntire(trimmed)
                if (path in codeApks && match != null) statuses.getOrPut(path!!) { mutableListOf() }.add(match.groupValues[1])
            }
            return codeApks.isNotEmpty() && codeApks.all { statuses[it] == listOf("speed-profile") }
        }

        private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
    }
}
