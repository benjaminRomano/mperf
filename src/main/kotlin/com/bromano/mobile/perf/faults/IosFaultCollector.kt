package com.bromano.mobile.perf.faults

import com.bromano.mobile.perf.commands.faults.IosFaultRequest
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.io.path.extension

internal class IosFaultCollector(
    private val engineRoot: Path,
) {
    private data class Target(
        val kind: String,
        val identifier: String,
        val name: String,
        val runtime: String,
    ) {
        val simulator: Boolean
            get() = kind == "simulator"
    }

    private data class AppIdentity(
        val bundle: String,
        val executable: String,
    )

    private data class Recording(
        val process: Process,
        val listener: Process,
        val stdout: Path,
        val stderr: Path,
        val command: List<String>,
        val startedNs: Long,
        var readyNs: Long? = null,
    )

    private val markerName = ".ios-fault-visualizer-capture"
    private val marker = "ios-fault-visualizer capture v1\n"

    fun collect(request: IosFaultRequest) {
        IosRecordingLifecycle.validateWindow(request.settleSeconds, request.timeLimit)
        val output = request.output.toAbsolutePath().normalize()
        validateOutput(output, request.overwrite)
        Files.createDirectories(output.parent)
        val staging = Files.createTempDirectory(output.parent, ".${output.fileName}.staging-")
        Files.writeString(staging.resolve(markerName), marker)
        try {
            captureInto(request, staging, output)
            publish(staging, output)
        } finally {
            if (Files.exists(staging)) deleteTree(staging)
        }
    }

    private fun captureInto(
        request: IosFaultRequest,
        staging: Path,
        publishedOutput: Path,
    ) {
        val target = resolveTarget(request.device)
        var identity = request.app?.let(::appIdentity)
        if (request.app != null) {
            if (request.bundleIdentifier != null) {
                require(request.bundleIdentifier == identity!!.bundle) {
                    "--bundle ${request.bundleIdentifier} does not match ${identity.bundle} in ${request.app}"
                }
            }
            install(target, request.app)
        }
        val bundle = identity?.bundle ?: requireNotNull(request.bundleIdentifier)
        var executable = request.appBinaryName ?: identity?.executable.orEmpty()
        val installedBundle =
            if (target.simulator) {
                simulatorContainer(target, bundle).also {
                    identity = appIdentity(it)
                    if (executable.isBlank()) executable = requireNotNull(identity).executable
                }
            } else {
                null
            }
        require(executable.isNotBlank()) {
            "--app-binary-name is required for an already-installed physical-device app"
        }
        terminate(target, bundle, executable, staging)

        val residency = mutableListOf<Map<String, Any?>>()
        val cache = prepareCache(request, target, bundle, installedBundle, staging, residency)
        if (!target.simulator) {
            require(physicalProcessIds(target, bundle, executable, staging).isEmpty()) {
                "Target app relaunched during physical-device cache preparation"
            }
        }
        val timeLimit = request.timeLimit ?: kotlin.math.ceil(request.settleSeconds + 5).toInt()
        val trace = staging.resolve("faults.trace")
        val recording = startRecording(target, trace, timeLimit, staging)
        var targetPid: Long? = null
        var launchNs: Long? = null
        try {
            waitReady(recording, Duration.ofSeconds(20))
            if (target.simulator && installedBundle != null && cache["residency_gate"] == true) {
                val beforeLaunch = measureResidency(installedBundle, "immediately_before_target_launch", staging)
                residency += beforeLaunch
                val currentInventory = inventoryFingerprint(beforeLaunch)
                if (cache["inventory_manifest_sha256"] != currentInventory) {
                    cache["confidence"] = "unconfirmed-prelaunch"
                    cache["prelaunch_inventory_changed"] = true
                    if (!request.allowUnconfirmedCache) {
                        error("App-bundle file inventory changed between cache preparation and launch")
                    }
                }
                val residencyAccepted =
                    validateResidency(
                        beforeLaunch,
                        request,
                        "App-bundle cache residency changed before launch",
                    )
                if (!residencyAccepted) {
                    cache["confidence"] = "unconfirmed-prelaunch"
                    cache["prelaunch_residency_failed"] = true
                }
                cache["residency_immediately_before_launch"] = summarize(beforeLaunch)
            }
            check(recording.process.isAlive) { "xctrace exited between readiness and target launch" }
            launchNs = System.nanoTime()
            targetPid = launch(target, bundle, request.appArguments, staging)
            IosRecordingLifecycle.requirePostLaunchWindow(recording.process, request.settleSeconds) {
                Files.readString(recording.stderr).trim()
            }
            if (target.simulator && installedBundle != null && cache["residency_gate"] == true) {
                val afterLaunch = measureResidency(installedBundle, "after_target_launch", staging)
                residency += afterLaunch
                cache["residency_after_target_launch"] = summarize(afterLaunch)
            }
        } catch (error: Throwable) {
            abort(recording)
            throw error
        }
        finish(recording, Duration.ofSeconds(timeLimit.toLong() + 45))
        val capturedPid = requireNotNull(targetPid) { "Target launch did not return a PID" }
        val readyNs = requireNotNull(recording.readyNs) { "Recording readiness timestamp is missing" }
        val capturedLaunchNs = requireNotNull(launchNs) { "Target launch timestamp is missing" }
        writeResidency(staging, residency)
        export(trace, staging)

        val metadata =
            mutableMapOf<String, Any?>(
                "schema_version" to 1,
                "bundle_id" to bundle,
                "app_binary_name" to executable,
                "app_bundle_root" to
                    installedBundle
                        ?.toAbsolutePath()
                        ?.normalize()
                        ?.toString()
                        .orEmpty(),
                "target_pid" to capturedPid,
                "target_kind" to target.kind,
                "target_identifier" to target.identifier,
                "target_name" to target.name,
                "target_runtime" to target.runtime,
                "trace_scope" to if (target.simulator) "macOS host all-processes" else "physical device all-processes",
                "instrument" to "Virtual Memory Trace",
                "recording_time_limit_seconds" to timeLimit,
                "settle_seconds" to request.settleSeconds,
                "cache" to cache,
                "capture_quality_warnings" to
                    Files
                        .readAllLines(recording.stderr)
                        .filter(String::isNotBlank)
                        .take(50),
                "capture_lifecycle" to
                    mapOf(
                        "recorder_started_monotonic_ns" to recording.startedNs,
                        "recording_ready_monotonic_ns" to readyNs,
                        "target_launch_monotonic_ns" to capturedLaunchNs,
                        "readiness_preceded_launch" to (readyNs <= capturedLaunchNs),
                    ),
                "artifacts" to
                    mapOf(
                        "trace" to "faults.trace",
                        "trace_toc" to "trace-toc.xml",
                        "virtual_memory_export" to "virtual-memory.xml",
                        "events" to "page_fault_events.csv",
                        "major_events" to "major_page_fault_events.csv",
                        "major_summary" to "major_page_fault_code_summary.csv",
                        "database" to "page_faults.sqlite",
                        "report" to "report.html",
                    ),
                "tool_versions" to
                    mapOf(
                        "xctrace" to Processes.run(xctraceCommand("version")).stdout.trim(),
                        "xcode" to Processes.run(listOf("xcodebuild", "-version")).stdout.trim(),
                        "mperf_faults" to "kotlin",
                    ),
                "capture_time" to OffsetDateTime.now().toString(),
                "capture_status" to "collected",
                "published_output" to publishedOutput.toString(),
            )
        Json.write(staging.resolve("capture_metadata.json"), metadata)
        IosFaultProcessor().process(staging)
        IosFaultReport(engineRoot).build(staging, staging.resolve("report.html"))
    }

    private fun resolveTarget(selector: String): Target {
        @Suppress("UNCHECKED_CAST")
        val simulatorPayload =
            Json.mapper.readValue(
                Processes.run(listOf("xcrun", "simctl", "list", "devices", "--json")).stdout,
                Map::class.java,
            ) as Map<String, Any?>
        val simulators =
            ((simulatorPayload["devices"] as? Map<*, *>)?.entries.orEmpty()).flatMap { (runtime, values) ->
                (values as? List<*>).orEmpty().mapNotNull { value ->
                    val row = value as? Map<*, *> ?: return@mapNotNull null
                    if (row["isAvailable"] == false) return@mapNotNull null
                    Target(
                        "simulator",
                        row["udid"].toString(),
                        row["name"].toString(),
                        runtime.toString(),
                    ) to row["state"].toString()
                }
            }
        if (selector.equals("booted", ignoreCase = true)) {
            val booted = simulators.filter { it.second == "Booted" }
            require(booted.size == 1) {
                "Expected exactly one booted Simulator; select one with --device. Booted: " +
                    booted.joinToString { "${it.first.name} (${it.first.identifier})" }.ifBlank { "none" }
            }
            return booted.single().first
        }
        simulators
            .filter {
                it.first.identifier.equals(selector, ignoreCase = true) ||
                    it.first.name.equals(selector, ignoreCase = true)
            }.let {
                if (it.size == 1) return it.single().first
                require(it.isEmpty()) { "Multiple Simulators are named $selector; select one by UDID" }
            }
        val physical =
            Processes
                .run(xctraceCommand("list", "devices"))
                .stdout
                .lineSequence()
                .dropWhile { it.trim() != "== Devices ==" }
                .drop(1)
                .takeWhile { it.trim() != "== Simulators ==" }
                .mapNotNull {
                    Regex("^(.+?) \\(([0-9A-Fa-f-]{20,})\\)(?: \\((.+)\\))?$")
                        .matchEntire(it.trim())
                        ?.takeUnless { match -> "Mac" in match.groupValues[1] }
                        ?.let { match -> Target("physical", match.groupValues[2], match.groupValues[1], match.groupValues[3]) }
                }.filter { it.identifier.equals(selector, true) || it.name.equals(selector, true) }
                .toList()
        require(physical.size == 1) { "Device $selector was not found or was ambiguous" }
        return physical.single()
    }

    private fun appIdentity(app: Path): AppIdentity {
        require(Files.isDirectory(app) && app.extension == "app") { "Not an app bundle: $app" }
        val plist = app.resolve("Info.plist")
        val bundle = Processes.run(listOf("plutil", "-extract", "CFBundleIdentifier", "raw", "-o", "-", plist.toString())).stdout.trim()
        val executable = Processes.run(listOf("plutil", "-extract", "CFBundleExecutable", "raw", "-o", "-", plist.toString())).stdout.trim()
        require(bundle.isNotBlank() && executable.isNotBlank()) { "App bundle lacks identifier or executable: $app" }
        require(Files.isRegularFile(app.resolve(executable))) { "App executable does not exist: ${app.resolve(executable)}" }
        return AppIdentity(bundle, executable)
    }

    private fun install(
        target: Target,
        app: Path,
    ) {
        val command =
            if (target.simulator) {
                listOf("xcrun", "simctl", "install", target.identifier, app.toString())
            } else {
                listOf("xcrun", "devicectl", "device", "install", "app", "--device", target.identifier, app.toString())
            }
        Processes.run(command)
    }

    private fun simulatorContainer(
        target: Target,
        bundle: String,
    ): Path {
        val path =
            Path.of(
                Processes
                    .run(listOf("xcrun", "simctl", "get_app_container", target.identifier, bundle, "app"))
                    .stdout
                    .trim(),
            )
        require(Files.isDirectory(path)) { "Simulator app container does not exist: $path" }
        return path
    }

    private fun terminate(
        target: Target,
        bundle: String,
        executable: String,
        output: Path,
    ) {
        if (target.simulator) {
            val result =
                Processes.run(
                    listOf("xcrun", "simctl", "terminate", target.identifier, bundle),
                    check = false,
                )
            require(
                result.exitCode in setOf(0, 3) ||
                    "found nothing to terminate" in (result.stderr + result.stdout).lowercase(),
            ) { "Unable to terminate $bundle: ${result.stderr}" }
            return
        }
        physicalProcessIds(target, bundle, executable, output).forEach { pid ->
            Processes.run(
                listOf(
                    "xcrun",
                    "devicectl",
                    "device",
                    "process",
                    "terminate",
                    "--device",
                    target.identifier,
                    "--pid",
                    pid.toString(),
                ),
            )
        }
        require(physicalProcessIds(target, bundle, executable, output).isEmpty()) {
            "Target app is still running after termination"
        }
    }

    private fun physicalProcessIds(
        target: Target,
        bundle: String,
        executable: String,
        output: Path,
    ): Set<Long> {
        val json = Files.createTempFile(output, "processes-", ".json")
        return try {
            Processes.run(
                listOf(
                    "xcrun",
                    "devicectl",
                    "device",
                    "info",
                    "processes",
                    "--device",
                    target.identifier,
                    "--json-output",
                    json.toString(),
                ),
            )
            val payload = Json.mapper.readValue(Files.readString(json), Any::class.java)
            collectMatchingPids(payload, bundle, executable)
        } finally {
            Files.deleteIfExists(json)
        }
    }

    private fun collectMatchingPids(
        value: Any?,
        bundle: String,
        executable: String,
    ): Set<Long> =
        when (value) {
            is Map<*, *> -> {
                val strings = nestedStrings(value)
                val direct =
                    listOf("processIdentifier", "pid")
                        .firstNotNullOfOrNull { (value[it] as? Number)?.toLong() }
                        ?.takeIf {
                            strings.any {
                                it == bundle ||
                                    it == executable ||
                                    it.endsWith("/$executable")
                            }
                        }
                value.values.flatMap { collectMatchingPids(it, bundle, executable) }.toSet() + listOfNotNull(direct)
            }

            is List<*> -> value.flatMap { collectMatchingPids(it, bundle, executable) }.toSet()
            else -> emptySet()
        }

    private fun nestedStrings(value: Any?): List<String> =
        when (value) {
            is Map<*, *> -> value.values.flatMap(::nestedStrings)
            is List<*> -> value.flatMap(::nestedStrings)
            is String -> listOf(value)
            else -> emptyList()
        }

    private fun prepareCache(
        request: IosFaultRequest,
        target: Target,
        bundle: String,
        installedBundle: Path?,
        output: Path,
        residency: MutableList<Map<String, Any?>>,
    ): MutableMap<String, Any?> {
        if (!target.simulator) {
            return preparePhysicalCache(request, target, bundle, output)
        }
        requireNotNull(installedBundle)
        if (request.cachePolicy == "reboot") error("Simulator reboot does not clear host cache; use purge or pressure")
        if (request.cachePolicy == "none") {
            require(!request.requireColdCache) { "--require-cold-cache cannot be used with --cache-policy none" }
            return mutableMapOf("procedure" to "none", "confidence" to "none", "residency_gate" to false)
        }
        val before = measureResidency(installedBundle, "before_cache_action", output)
        residency += before
        val action =
            when (request.cachePolicy) {
                "pressure" -> hostPressure()
                "purge", "auto" -> {
                    val purge = Processes.run(listOf("/bin/sync"), check = false)
                    require(purge.exitCode == 0) { "sync failed: ${purge.stderr}" }
                    val result = Processes.run(listOf("/usr/sbin/purge"), check = false)
                    if (result.exitCode == 0) {
                        mapOf("procedure" to "macos-sync+purge", "command_succeeded" to true)
                    } else {
                        if (request.cachePolicy == "purge" || !request.allowHostPressure) {
                            error(
                                "macOS purge failed: ${(result.stderr + result.stdout).trim()}. " +
                                    "Pass --allow-host-pressure or use --cache-policy pressure.",
                            )
                        }
                        hostPressure()
                    }
                }

                else -> error("Unsupported cache policy: ${request.cachePolicy}")
            }
        val after = measureResidency(installedBundle, "after_cache_action", output)
        residency += after
        val accepted = validateResidency(after, request, "The host cache action did not meet the app-bundle residency gate")
        return mutableMapOf(
            "procedure" to action["procedure"],
            "actions" to listOf(action),
            "confidence" to
                if (accepted && after.sumOf { (it["resident_pages"] as Number).toLong() } == 0L) {
                    "confirmed-evicted"
                } else if (accepted) {
                    "threshold-met-partially-resident"
                } else {
                    "unconfirmed"
                },
            "residency_before" to summarize(before),
            "residency_after" to summarize(after),
            "confirmation_threshold" to if (request.requireColdCache) 0.0 else request.residencyThreshold,
            "residency_gate" to true,
            "inventory_manifest_sha256" to inventoryFingerprint(after),
        )
    }

    private fun preparePhysicalCache(
        request: IosFaultRequest,
        target: Target,
        bundle: String,
        output: Path,
    ): MutableMap<String, Any?> =
        when (request.cachePolicy) {
            "none" -> {
                require(!request.requireColdCache) { "--require-cold-cache is unavailable on stock physical devices" }
                mutableMapOf("procedure" to "none", "confidence" to "none", "residency_gate" to false)
            }

            "reboot" -> {
                require(!request.requireColdCache) { "A stock physical device cannot confirm cold app-file residency" }
                Processes.run(listOf("xcrun", "devicectl", "device", "reboot", "--device", target.identifier))
                val deadline = System.nanoTime() + Duration.ofMinutes(4).toNanos()
                while (System.nanoTime() < deadline) {
                    val devices = Processes.run(xctraceCommand("list", "devices"), check = false)
                    if (devices.exitCode == 0 && target.identifier in devices.stdout) break
                    Thread.sleep(3_000)
                }
                require(target.identifier in Processes.run(xctraceCommand("list", "devices")).stdout) {
                    "Physical device ${target.name} did not reconnect after reboot"
                }
                mutableMapOf("procedure" to "physical-device-reboot", "confidence" to "best-effort-reboot", "residency_gate" to false)
            }

            "auto", "pressure" -> {
                require(!request.requireColdCache) { "A stock physical device cannot confirm cold app-file residency" }
                val project = engineRoot.resolve("ios/cache-pressure/CachePressure.xcodeproj")
                val derived = output.resolve("cache-pressure-derived-data")
                val team =
                    requireNotNull(request.developmentTeam) {
                        "Physical-device cache pressure requires --development-team"
                    }
                Processes.run(
                    listOf(
                        "xcodebuild",
                        "-project",
                        project.toString(),
                        "-scheme",
                        "CachePressure",
                        "-configuration",
                        "Release",
                        "-derivedDataPath",
                        derived.toString(),
                        "-destination",
                        "platform=iOS,id=${target.identifier}",
                        "DEVELOPMENT_TEAM=$team",
                        "-allowProvisioningUpdates",
                    ),
                )
                val helper = derived.resolve("Build/Products/Release-iphoneos/CachePressure.app")
                install(target, helper)
                val json = output.resolve("cache-pressure-launch.json")
                val command =
                    mutableListOf(
                        "xcrun",
                        "devicectl",
                        "device",
                        "process",
                        "launch",
                        "--device",
                        target.identifier,
                        "--terminate-existing",
                        "--json-output",
                        json.toString(),
                        "--console",
                        "--timeout",
                        maxOf(60, request.pressureHoldSeconds + 45).toString(),
                        "com.bromano.ios-fault-visualizer.CachePressure",
                        "--fraction",
                        request.pressureFraction.toString(),
                        "--hold-seconds",
                        request.pressureHoldSeconds.toString(),
                    )
                request.pressureMegabytes?.let { command += listOf("--megabytes", it.toString()) }
                val result =
                    Processes.run(
                        command,
                        check = false,
                        timeout = Duration.ofSeconds(maxOf(90, request.pressureHoldSeconds + 60).toLong()),
                    )
                require(
                    result.exitCode == 0 &&
                        "CACHE_PRESSURE_READY" in result.stdout &&
                        "CACHE_PRESSURE_COMPLETE" in result.stdout &&
                        "CACHE_PRESSURE_ABORTED" !in result.stdout,
                ) { "Physical CachePressure did not complete: ${result.stderr}\n${result.stdout}" }
                mutableMapOf(
                    "procedure" to "cache-pressure-helper",
                    "confidence" to "best-effort",
                    "residency_gate" to false,
                    "completion_validated" to true,
                    "target_bundle" to bundle,
                )
            }

            "purge" -> error("macOS purge cannot flush a physical iOS device")
            else -> error("Unsupported cache policy: ${request.cachePolicy}")
        }

    private fun hostPressure(): Map<String, Any?> {
        val result =
            Processes.run(
                listOf("/usr/bin/memory_pressure", "-p", "50", "-s", "1", "-y", "1", "-Q"),
                check = false,
                timeout = Duration.ofSeconds(60),
            )
        require(result.exitCode in setOf(0, 137, 143)) { "memory_pressure failed: ${result.stderr}" }
        return mapOf("procedure" to "macos-memory-pressure", "return_code" to result.exitCode)
    }

    private fun measureResidency(
        app: Path,
        phase: String,
        output: Path,
    ): List<Map<String, Any?>> {
        val helper = output.resolve("residency")
        if (!Files.exists(helper)) {
            Processes.run(
                listOf(
                    "xcrun",
                    "clang",
                    "-O2",
                    "-Wall",
                    "-Wextra",
                    "-Werror",
                    engineRoot.resolve("ios/native/residency.c").toString(),
                    "-o",
                    helper.toString(),
                ),
            )
        }
        val files =
            Files.walk(app).use { paths ->
                paths
                    .filter { Files.isRegularFile(it) && !Files.isSymbolicLink(it) && Files.size(it) > 0 }
                    .sorted()
                    .toList()
            }
        require(files.isNotEmpty()) { "No regular files found in app bundle $app" }
        val all = mutableListOf<Map<String, Any?>>()
        files.chunked(250).forEachIndexed { index, chunk ->
            val result = Processes.run(listOf(helper.toString(), phase) + chunk.map(Path::toString))
            val temporary = Files.createTempFile(output, "residency-$index-", ".csv")
            try {
                Files.writeString(temporary, result.stdout)
                all +=
                    Csv.read(temporary).map {
                        mapOf(
                            "phase" to it.getValue("phase"),
                            "file_name" to it.getValue("file_name"),
                            "size_bytes" to it.getValue("size_bytes").toLong(),
                            "total_pages" to it.getValue("total_pages").toLong(),
                            "resident_pages" to it.getValue("resident_pages").toLong(),
                            "resident_fraction" to it.getValue("resident_fraction").toDouble(),
                        )
                    }
            } finally {
                Files.deleteIfExists(temporary)
            }
        }
        require(all.size == files.size) { "Incomplete app-bundle residency measurement: ${all.size}/${files.size}" }
        return all
    }

    private fun validateResidency(
        rows: List<Map<String, Any?>>,
        request: IosFaultRequest,
        message: String,
    ): Boolean {
        val pages = rows.sumOf { (it["total_pages"] as Number).toLong() }
        val resident = rows.sumOf { (it["resident_pages"] as Number).toLong() }
        val fraction = if (pages == 0L) 0.0 else resident.toDouble() / pages
        val accepted = fraction <= if (request.requireColdCache) 0.0 else request.residencyThreshold
        if (!accepted && !request.allowUnconfirmedCache) {
            error("$message (${String.format("%.1f%%", fraction * 100)} resident); refusing the capture")
        }
        return accepted
    }

    private fun summarize(rows: List<Map<String, Any?>>): Map<String, Any?> {
        val pages = rows.sumOf { (it["total_pages"] as Number).toLong() }
        val resident = rows.sumOf { (it["resident_pages"] as Number).toLong() }
        return mapOf(
            "phase" to rows.firstOrNull()?.get("phase"),
            "files" to rows.size,
            "total_pages" to pages,
            "resident_pages" to resident,
            "resident_fraction" to if (pages == 0L) null else resident.toDouble() / pages,
        )
    }

    private fun inventoryFingerprint(rows: List<Map<String, Any?>>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        rows
            .sortedBy { it["file_name"].toString() }
            .forEach { row ->
                val path = Path.of(row["file_name"].toString())
                val value =
                    "${row["file_name"]}\u0000${row["size_bytes"]}\u0000" +
                        "${Files.getLastModifiedTime(path).toMillis()}\n"
                digest.update(value.toByteArray())
            }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun writeResidency(
        output: Path,
        rows: List<Map<String, Any?>>,
    ) {
        Csv.write(
            output.resolve("cache_residency.csv"),
            listOf("phase", "file_name", "size_bytes", "total_pages", "resident_pages", "resident_fraction"),
            rows,
        )
    }

    private fun startRecording(
        target: Target,
        trace: Path,
        timeLimit: Int,
        output: Path,
    ): Recording {
        val instruments = Processes.run(xctraceCommand("list", "instruments")).stdout.lines()
        require(instruments.any { it.trim() == "Virtual Memory Trace" }) {
            "This Xcode installation does not expose Virtual Memory Trace"
        }
        require(Files.isExecutable(Path.of("/usr/bin/notifyutil"))) {
            "xctrace readiness requires /usr/bin/notifyutil"
        }
        val notification = "com.bromano.mperf.faults.${UUID.randomUUID()}"
        val listener =
            ProcessBuilder("/usr/bin/notifyutil", "-1", notification)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        val command =
            xctraceCommand(
                "record",
                "--instrument",
                "Virtual Memory Trace",
                "--all-processes",
                "--time-limit",
                "${timeLimit}s",
                "--output",
                trace.toString(),
                "--notify-tracing-started=$notification",
                "--no-prompt",
            ).toMutableList()
        if (!target.simulator) command += listOf("--device", target.identifier)
        val stdout = output.resolve("xctrace.stdout.log")
        val stderr = output.resolve("xctrace.stderr.log")
        val started = System.nanoTime()
        return try {
            val process =
                ProcessBuilder(command)
                    .redirectOutput(stdout.toFile())
                    .redirectError(stderr.toFile())
                    .start()
            Recording(process, listener, stdout, stderr, command, started)
        } catch (error: Throwable) {
            listener.destroyForcibly()
            throw error
        }
    }

    private fun waitReady(
        recording: Recording,
        timeout: Duration,
    ) {
        recording.readyNs =
            IosRecordingLifecycle.awaitReady(recording.process, recording.listener, timeout) {
                recordingLogs(recording)
            }
    }

    private fun launch(
        target: Target,
        bundle: String,
        arguments: List<String>,
        output: Path,
    ): Long {
        if (target.simulator) {
            val result =
                Processes.run(
                    listOf(
                        "xcrun",
                        "simctl",
                        "launch",
                        "--terminate-running-process",
                        target.identifier,
                        bundle,
                    ) + arguments,
                )
            return Regex(":\\s*(\\d+)\\s*$")
                .find(result.stdout)
                ?.groupValues
                ?.get(1)
                ?.toLong()
                ?: error("Could not parse Simulator launch PID from: ${result.stdout}")
        }
        val json = output.resolve("device-launch.json")
        Processes.run(
            listOf(
                "xcrun",
                "devicectl",
                "device",
                "process",
                "launch",
                "--device",
                target.identifier,
                "--terminate-existing",
                "--json-output",
                json.toString(),
                bundle,
            ) + arguments,
        )
        return recursivePid(Json.mapper.readValue(Files.readString(json), Any::class.java))
            ?: error("Could not find processIdentifier in $json")
    }

    private fun recursivePid(value: Any?): Long? =
        when (value) {
            is Map<*, *> ->
                (value["processIdentifier"] as? Number)?.toLong()
                    ?: (value["pid"] as? Number)?.toLong()
                    ?: value.values.firstNotNullOfOrNull(::recursivePid)

            is List<*> -> value.firstNotNullOfOrNull(::recursivePid)
            else -> null
        }

    private fun finish(
        recording: Recording,
        timeout: Duration,
    ) {
        try {
            if (!recording.process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                recording.process.destroy()
                if (!recording.process.waitFor(15, TimeUnit.SECONDS)) recording.process.destroyForcibly()
            }
            require(recording.process.exitValue() == 0) {
                "xctrace failed (${recording.process.exitValue()}): ${recordingLogs(recording)}"
            }
        } finally {
            if (recording.listener.isAlive) recording.listener.destroyForcibly()
        }
    }

    private fun abort(recording: Recording) {
        if (recording.process.isAlive) {
            Processes.run(listOf("/bin/kill", "-INT", recording.process.pid().toString()), check = false)
            if (!recording.process.waitFor(15, TimeUnit.SECONDS)) {
                recording.process.destroy()
                if (!recording.process.waitFor(5, TimeUnit.SECONDS)) recording.process.destroyForcibly()
            }
        }
        if (recording.listener.isAlive) recording.listener.destroyForcibly()
    }

    private fun recordingLogs(recording: Recording): String =
        listOf(recording.stdout, recording.stderr)
            .filter(Files::exists)
            .joinToString("\n") { Files.readString(it) }
            .trim()

    private fun export(
        trace: Path,
        output: Path,
    ) {
        Processes.run(
            xctraceCommand(
                "export",
                "--input",
                trace.toString(),
                "--toc",
                "--output",
                output.resolve("trace-toc.xml").toString(),
            ),
        )
        Processes.run(
            xctraceCommand(
                "export",
                "--input",
                trace.toString(),
                "--xpath",
                "/trace-toc/run[@number=\"1\"]/data/table[@schema=\"virtual-memory\"]",
                "--output",
                output.resolve("virtual-memory.xml").toString(),
            ),
        )
        require(Files.size(output.resolve("virtual-memory.xml")) >= 100) {
            "The trace exported no Virtual Memory rows"
        }
    }

    private fun xctraceCommand(vararg arguments: String): List<String> {
        val arm =
            Processes.run(
                listOf("/usr/sbin/sysctl", "-n", "hw.optional.arm64"),
                check = false,
            )
        val prefix = if (arm.exitCode == 0 && arm.stdout.trim() == "1") listOf("/usr/bin/arch", "-arm64") else emptyList()
        return prefix + listOf("xcrun", "xctrace") + arguments
    }

    private fun validateOutput(
        output: Path,
        overwrite: Boolean,
    ) {
        val home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize()
        require(output.parent != null && output != home) { "Refusing protected output directory: $output" }
        require(!Files.isSymbolicLink(output)) { "Refusing symbolic-link output directory: $output" }
        if (Files.exists(output)) {
            require(Files.isDirectory(output)) { "Output path is not a directory: $output" }
            val populated = Files.list(output).use { it.findAny().isPresent }
            if (populated) {
                require(overwrite) { "Output directory is not empty: $output" }
                require(Files.readString(output.resolve(markerName)) == marker) {
                    "Refusing to overwrite a directory not owned by mperf faults"
                }
            }
        }
    }

    private fun publish(
        staging: Path,
        output: Path,
    ) {
        val backup =
            if (Files.exists(output)) {
                output.resolveSibling(".${output.fileName}.replaced-${UUID.randomUUID()}").also {
                    Files.move(output, it, StandardCopyOption.ATOMIC_MOVE)
                }
            } else {
                null
            }
        try {
            Files.move(staging, output, StandardCopyOption.ATOMIC_MOVE)
            backup?.let(::deleteTree)
        } catch (error: Throwable) {
            if (backup != null && Files.exists(backup) && !Files.exists(output)) {
                Files.move(backup, output, StandardCopyOption.ATOMIC_MOVE)
            }
            throw error
        }
    }

    private fun deleteTree(path: Path) {
        Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    @Suppress("unused")
    private fun sha256(path: Path): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(Files.readAllBytes(path))
            .joinToString("") { "%02x".format(it) }
}
