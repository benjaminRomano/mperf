package com.bromano.mobile.perf.faults

import com.bromano.mobile.perf.commands.faults.AndroidFaultRequest
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import kotlin.io.path.isDirectory
import kotlin.io.path.name

internal class AndroidFaultCollector(
    private val engineRoot: Path,
) {
    private val remoteDirectory = "/data/local/tmp/android-fault-visualizer"
    private val remoteCollector = "$remoteDirectory/page_fault_collector"
    private val remoteFaults = "$remoteDirectory/fault_events.csv"
    private val remoteMappings = "$remoteDirectory/mapping_events.csv"
    private val remoteCallchains = "$remoteDirectory/fault_callchains.csv"
    private var capturePageSize = 0
    private var captureWarnings = mutableListOf<String>()

    fun collect(request: AndroidFaultRequest) {
        val packageName = requireNotNull(request.packageName)
        resetOutput(request.output, request.overwrite)
        val adb = Device.resolve(request.device)
        if (request.rebootBeforeCollect) adb.reboot()
        adb.ensureRoot()
        val sdk = adb.property("ro.build.version.sdk").toInt()
        val abi = adb.property("ro.product.cpu.abi")
        val pageSize =
            adb
                .shell("getconf PAGESIZE")
                .stdout
                .trim()
                .toInt()
        val apkPaths = adb.packagePaths(packageName)
        capturePageSize = pageSize
        val activity = request.activity?.let { if ("/" in it) it else "$packageName/$it" } ?: adb.resolveActivity(packageName)
        val build = buildCollector(adb, request.output, abi, sdk)
        val metadata =
            mutableMapOf<String, Any?>(
                "schema_version" to 5,
                "package" to packageName,
                "activity" to activity,
                "serial" to adb.serial,
                "sdk" to sdk,
                "release" to adb.property("ro.build.version.release"),
                "build_fingerprint" to adb.property("ro.build.fingerprint"),
                "device" to adb.property("ro.product.device"),
                "kernel" to adb.shell("uname -r").stdout.trim(),
                "abi" to abi,
                "page_size" to pageSize,
                "online_cpus_sysfs" to adb.shell("cat /sys/devices/system/cpu/online").stdout.trim(),
                "collector" to "perf-software-page-fault-events",
                "collector_version" to 5,
                "collector_clock" to "boottime",
                "capture_native_callchains" to request.nativeStacks,
                "simpleperf_status" to if (request.dwarfStacks) "requested" else "disabled",
                "cache_procedure" to "force-stop-wait+stable-target-set+sync+drop_caches+fadvise+mincore-v3",
                "cache_max_resident_pages" to request.maxResidentPages,
                "reboot_before_collect" to request.rebootBeforeCollect,
                "reclaim_mapped_apks" to request.reclaimMappedApks,
                "mapped_apk_reclaim" to mutableListOf<Map<String, Any?>>(),
                "boot_id" to adb.shell("cat /proc/sys/kernel/random/boot_id").stdout.trim(),
                "device_uptime_seconds" to
                    adb
                        .shell("cat /proc/uptime")
                        .stdout
                        .substringBefore(' ')
                        .toDouble(),
                "trace_config_sha256" to sha256(engineRoot.resolve("android/ftrace.config")),
                "capture_status" to "preparing",
                "warnings" to mutableListOf<String>(),
            )
        metadata.putAll(build)
        captureWarnings = warnings(metadata)
        Json.write(request.output.resolve("capture_metadata.json"), metadata)

        adb.shell("am force-stop ${quote(packageName)}")
        waitStopped(adb, packageName)
        var targets = adb.packageFiles(packageName, apkPaths)
        dumpInodes(adb, packageName, apkPaths, request.output, append = false)
        val residency = mutableListOf<Map<String, Any?>>()
        residency += residency(adb, targets, "before_drop", apkPaths)
        writeResidency(request.output, residency)
        adb.rootShell("sync")
        val direct = adb.rootShell("echo 3 > /proc/sys/vm/drop_caches", check = false)
        if (direct.exitCode != 0) {
            require(sdk >= 31) { "drop_caches failed: ${direct.stderr}" }
            adb.shell("setprop perf.drop_caches 3")
            await(Duration.ofSeconds(10), "perf.drop_caches") {
                adb.property("perf.drop_caches") == "0"
            }
        }
        collectorFileCommand(adb, "--evict", targets)

        fun reclaim(phase: String) {
            if (!request.reclaimMappedApks) return
            val diagnostic = AndroidCache.reclaim(adb, apkPaths, request.output, phase)
            @Suppress("UNCHECKED_CAST")
            (metadata["mapped_apk_reclaim"] as MutableList<Map<String, Any?>>).add(diagnostic)
            diagnostic["warning"]?.let { warnings(metadata).add(it.toString()) }
        }
        reclaim("after_drop")
        residency += residency(adb, targets, "after_drop", apkPaths)
        writeResidency(request.output, residency)
        verifyResidency(metadata, request.output, residency, "after_drop", request.maxResidentPages)

        val traceName = "afv_${ProcessHandle.current().pid()}_${System.currentTimeMillis()}"
        val remoteTrace = "/data/misc/perfetto-traces/$traceName.pftrace"
        var perfetto: Running? = null
        var collector: CollectorRunning? = null
        var dwarf: AndroidDwarf.Running? = null
        var primaryFailure: Throwable? = null
        try {
            perfetto = startPerfetto(adb, remoteTrace)
            collector = startCollector(adb, request.nativeStacks)
            metadata["collector_start_ns"] = collector.startNs
            metadata["collector_online_cpus"] = collector.onlineCpus
            if (request.dwarfStacks) dwarf = AndroidDwarf.start(adb)
            require(collector.onlineCpus == metadata["online_cpus_sysfs"]) {
                "CPU topology changed while starting collector: sysfs=${metadata["online_cpus_sysfs"]}, collector=${collector.onlineCpus}"
            }

            val currentApks = adb.packagePaths(packageName)
            val currentTargets = adb.packageFiles(packageName, currentApks)
            require(currentTargets.containsAll(apkPaths)) {
                "Installed APK cache targets disappeared before launch: ${apkPaths - currentTargets.toSet()}"
            }
            val added = currentTargets.toSet() - targets.toSet()
            if (added.isNotEmpty()) {
                warnings(metadata) += "Before launch, evicted newly discovered app files: ${added.joinToString()}"
                collectorFileCommand(adb, "--evict", added.sorted())
            }
            targets = currentTargets
            reclaim("before_launch")
            residency += residency(adb, targets, "before_launch", apkPaths)
            writeResidency(request.output, residency)
            verifyResidency(metadata, request.output, residency, "before_launch", request.maxResidentPages)

            val launch = adb.shell("am start -W -n ${quote(activity)}")
            Files.writeString(request.output.resolve("launch.txt"), launch.stdout)
            require("Status: ok" in launch.stdout) { "Activity launch failed:\n${launch.stdout}" }
            val pid = adb.pid(packageName) ?: error("Process $packageName exited before state capture")
            metadata["pid"] = pid
            Files.writeString(request.output.resolve("maps.txt"), adb.rootShell("cat /proc/$pid/maps").stdout)
            Files.writeString(
                request.output.resolve("process_stat.txt"),
                adb.rootShell("cat /proc/$pid/stat").stdout.trim() + "\n",
            )
            dumpInodes(adb, packageName, apkPaths, request.output, append = true)
            Thread.sleep(request.settleMs.toLong())
            try {
                residency +=
                    residency(adb, targets, "after_launch", emptyList()) { result ->
                        metadata["post_launch_residency_exit_codes"] = result.exitCodes
                        if (result.stderr.isNotBlank()) {
                            warnings(metadata) +=
                                "Post-launch residency diagnostics (exit ${result.exitCodes.joinToString()}): " +
                                result.stderr
                                    .trim()
                                    .lineSequence()
                                    .take(10)
                                    .joinToString(" ")
                        }
                    }
                writeResidency(request.output, residency)
            } catch (error: Exception) {
                warnings(metadata) +=
                    "Post-launch residency check failed after tracing completed (${error.message}); " +
                    "the strict pre-launch verification remains valid."
                metadata["post_launch_residency_error"] = error.message
            }
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            var cleanupFailure: Throwable? = null
            collector?.let { running ->
                try {
                    val terminal = stopCollector(adb, running)
                    terminal.forEach { (key, value) -> metadata["collector_$key"] = value }
                    metadata["collector_return_code"] = running.recorder.process.exitValue()
                    metadata["collector_loss_detection"] =
                        if (terminal["lost_counter_supported"] == 1L) "counter_and_ring" else "ring_records_only"
                } catch (error: Throwable) {
                    metadata["collector_stop_error"] = error.message
                    cleanupFailure = error
                }
            }
            perfetto?.let { running ->
                try {
                    stopPerfetto(adb, running)
                } catch (error: Throwable) {
                    metadata["perfetto_stop_error"] = error.message
                    cleanupFailure?.addSuppressed(error) ?: run { cleanupFailure = error }
                }
            }
            dwarf?.let { running ->
                try {
                    AndroidDwarf.finish(adb, running, metadata, request.output) { remote, local -> pull(adb, remote, local) }
                    metadata["simpleperf_status"] = "complete"
                } catch (error: Exception) {
                    metadata["simpleperf_status"] = "failed"
                    warnings(metadata) += "DWARF companion unavailable; raw capture preserved pending integrity checks: ${error.message}"
                }
            }
            Json.write(request.output.resolve("capture_metadata.json"), metadata)
            cleanupFailure?.let { cleanup ->
                primaryFailure?.addSuppressed(cleanup) ?: throw cleanup
            }
        }

        pull(adb, remoteFaults, request.output.resolve("fault_events.csv"))
        pull(adb, remoteMappings, request.output.resolve("mapping_events.csv"))
        if (request.nativeStacks) pull(adb, remoteCallchains, request.output.resolve("fault_callchains.csv"))
        pull(adb, remoteTrace, request.output.resolve("faults.pftrace"))
        adb.rootShell(
            "rm -f ${quote(remoteFaults)} ${quote(remoteMappings)} ${quote(remoteCallchains)} ${quote(remoteTrace)}",
        )
        if (request.pullArtifacts) pullArtifacts(adb, apkPaths, abi, request.output)
        if (request.nativeStacks || request.dwarfStacks) pullStackBinaries(adb, request.output, warnings(metadata))

        val integrity =
            listOf("lost", "integrity_errors", "throttled", "callchain_overflow")
                .associateWith { (metadata["collector_$it"] as? Number)?.toLong() ?: 0 }
        if (((metadata["collector_return_code"] as? Number)?.toInt() ?: 0) != 0 || integrity.values.any { it != 0L }) {
            metadata["capture_status"] = "collector_integrity_failed"
            Json.write(request.output.resolve("capture_metadata.json"), metadata)
            error("Fault collector integrity failure: $integrity")
        }
        metadata["capture_status"] = "collected"
        Json.write(request.output.resolve("capture_metadata.json"), metadata)
    }

    private data class Running(
        val recorder: AndroidRecorder,
    )

    private data class CollectorRunning(
        val recorder: AndroidRecorder,
        val startNs: Long,
        val onlineCpus: String,
    )

    private fun startPerfetto(
        adb: Device,
        remoteTrace: String,
    ): Running {
        require(adb.pid("perfetto") == null) { "Another Perfetto command is already running" }
        val recorder =
            AndroidRecorder.start(
                adb,
                "perfetto --txt -c - -o ${quote(remoteTrace)}",
                Files.readString(engineRoot.resolve("android/ftrace.config")),
            )
        try {
            await(Duration.ofSeconds(10), "Perfetto readiness") {
                check(recorder.process.isAlive) { "Perfetto exited before readiness: ${recorder.text()}" }
                val tracing =
                    adb
                        .rootShell(
                            "cat /sys/kernel/tracing/tracing_on 2>/dev/null || cat /sys/kernel/debug/tracing/tracing_on 2>/dev/null",
                            check = false,
                            timeout = Duration.ofSeconds(2),
                        ).stdout
                        .trim()
                val event =
                    adb
                        .rootShell(
                            "cat /sys/kernel/tracing/events/filemap/mm_filemap_add_to_page_cache/enable 2>/dev/null || " +
                                "cat /sys/kernel/debug/tracing/events/filemap/mm_filemap_add_to_page_cache/enable 2>/dev/null",
                            check = false,
                            timeout = Duration.ofSeconds(2),
                        ).stdout
                        .trim()
                tracing == "1" && event == "1"
            }
            return Running(recorder)
        } catch (error: Throwable) {
            recorder.abort(error)
            throw error
        }
    }

    private fun stopPerfetto(
        @Suppress("UNUSED_PARAMETER") adb: Device,
        running: Running,
    ) {
        val output = running.recorder.stop()
        require(running.recorder.process.exitValue() in setOf(0, 130)) { "Perfetto failed: $output" }
    }

    private fun startCollector(
        adb: Device,
        stacks: Boolean,
    ): CollectorRunning {
        require(adb.pid("page_fault_collector") == null) { "Another fault collector is already running" }
        val command =
            "$remoteCollector --output $remoteFaults --mappings-output $remoteMappings " +
                (if (stacks) "--callchains-output $remoteCallchains " else "") + "--duration-ms 60000"
        val recorder = AndroidRecorder.start(adb, command)
        try {
            val ready = recorder.await(Regex("READY pid=(\\d+) capture_start_ns=(\\d+) online_cpus=([0-9,-]+)"))
            require(ready.groupValues[1].toLong() == recorder.pid) { "Collector PID differs from owned exec PID" }
            return CollectorRunning(recorder, ready.groupValues[2].toLong(), ready.groupValues[3])
        } catch (error: Throwable) {
            recorder.abort(error)
            throw error
        }
    }

    private fun stopCollector(
        @Suppress("UNUSED_PARAMETER") adb: Device,
        running: CollectorRunning,
    ): Map<String, Long> = parseCollectorSummary(running.recorder.stop())

    internal fun parseCollectorSummary(output: String): Map<String, Long> {
        val line =
            output.lineSequence().lastOrNull { it.startsWith("capture_start_ns=") }
                ?: error("Invalid collector terminal metadata: $output")
        val parsed = Regex("([a-z_]+)=(\\d+)").findAll(line).associate { it.groupValues[1] to it.groupValues[2].toLong() }
        val required =
            listOf(
                "capture_start_ns",
                "capture_end_ns",
                "samples",
                "mappings",
                "lost",
                "integrity_errors",
                "throttled",
                "callchain_entries",
                "callchain_overflow",
                "lost_counter_supported",
            )
        require(parsed.keys.containsAll(required)) { "Incomplete collector terminal metadata: $line" }
        return parsed.mapKeys { (key, _) ->
            when (key) {
                "capture_start_ns" -> "start"
                "capture_end_ns" -> "end"
                else -> key
            }
        }
    }

    private fun buildCollector(
        adb: Device,
        output: Path,
        abi: String,
        sdk: Int,
    ): Map<String, String> {
        val ndk = findNdk()
        val compiler = findCompiler(ndk, abi, sdk)
        val source = engineRoot.resolve("android/native/page_fault_collector.c")
        val local = output.resolve("page_fault_collector")
        Processes.run(
            listOf(
                compiler.toString(),
                "-O2",
                "-Wall",
                "-Wextra",
                "-Werror",
                source.toString(),
                "-o",
                local.toString(),
            ),
        )
        adb.rootShell("mkdir -p ${quote(remoteDirectory)}")
        adb.run("push", local.toString(), remoteCollector)
        adb.rootShell("chmod 755 ${quote(remoteCollector)}")
        return mapOf(
            "ndk" to ndk.name,
            "compiler" to compiler.name,
            "collector_source_sha256" to sha256(source),
            "collector_binary_sha256" to sha256(local),
            "collector_cpu_list_header_sha256" to sha256(source.parent.resolve("cpu_list.h")),
            "collector_apk_reclaim_header_sha256" to sha256(source.parent.resolve("apk_cache_reclaim.h")),
            "llvm_symbolizer" to compiler.parent.resolve("llvm-symbolizer").toString(),
        )
    }

    private fun findNdk(): Path {
        listOfNotNull(System.getenv("ANDROID_NDK_HOME"), System.getenv("ANDROID_NDK_ROOT"))
            .map { Path.of(it) }
            .firstOrNull(Path::isDirectory)
            ?.let { return it }
        val sdk =
            listOfNotNull(System.getenv("ANDROID_HOME"), System.getenv("ANDROID_SDK_ROOT"))
                .firstOrNull()
                ?.let(Path::of)
                ?: Path.of(System.getProperty("user.home"), "Library", "Android", "sdk")
        val root = sdk.resolve("ndk")
        require(root.isDirectory()) { "Android NDK not found; install one or set ANDROID_NDK_HOME" }
        return Files.list(root).use { paths ->
            paths
                .filter { it.isDirectory() }
                .max { left, right -> compareVersions(versionKey(left.name), versionKey(right.name)) }
                .orElseThrow { IllegalStateException("No Android NDK installed under $root") }
        }
    }

    private fun versionKey(value: String): List<Int> = Regex("\\d+").findAll(value).map { it.value.toInt() }.toList()

    private fun compareVersions(
        left: List<Int>,
        right: List<Int>,
    ): Int {
        repeat(maxOf(left.size, right.size)) { index ->
            val comparison = (left.getOrElse(index) { 0 }).compareTo(right.getOrElse(index) { 0 })
            if (comparison != 0) return comparison
        }
        return 0
    }

    private fun findCompiler(
        ndk: Path,
        abi: String,
        sdk: Int,
    ): Path {
        val host =
            when {
                System.getProperty("os.name").startsWith("Mac") -> "darwin"
                System.getProperty("os.name").startsWith("Windows") -> "windows"
                else -> "linux"
            }
        val prebuilt =
            Files.list(ndk.resolve("toolchains/llvm/prebuilt")).use { paths ->
                paths.filter { it.fileName.toString().startsWith(host) }.findFirst().orElseThrow()
            }
        val prefix =
            mapOf(
                "arm64-v8a" to "aarch64-linux-android",
                "armeabi-v7a" to "armv7a-linux-androideabi",
                "x86_64" to "x86_64-linux-android",
                "x86" to "i686-linux-android",
            )[abi] ?: error("Unsupported Android ABI: $abi")
        val exact = prebuilt.resolve("bin/$prefix${maxOf(21, sdk)}-clang")
        if (Files.exists(exact)) return exact
        return Files.list(prebuilt.resolve("bin")).use { paths ->
            paths
                .filter { it.fileName.toString().matches(Regex("$prefix\\d+-clang")) }
                .max(Comparator.comparingInt { Regex("(\\d+)-clang$").find(it.name)!!.groupValues[1].toInt() })
                .orElseThrow { IllegalStateException("No $prefix compiler in $prebuilt") }
        }
    }

    private fun waitStopped(
        adb: Device,
        packageName: String,
    ) {
        await(Duration.ofSeconds(10), "package stop") {
            val names =
                adb
                    .shell("ps -A -o NAME")
                    .stdout
                    .lineSequence()
                    .drop(1)
                    .map(String::trim)
            names.none { it == packageName || it.startsWith("$packageName:") }
        }
    }

    private fun residency(
        adb: Device,
        files: List<String>,
        phase: String,
        required: List<String>,
        diagnostics: ((FileCommandOutput) -> Unit)? = null,
    ): List<Map<String, Any?>> {
        val result = collectorFileCommand(adb, "--residency", files, check = false)
        diagnostics?.invoke(result)
        require(result.exitCodes.all { it == 0 }) {
            "Residency helper failed with exit status ${result.exitCodes.joinToString()}: ${result.stderr.trim()}"
        }
        val temporary = Files.createTempFile("mperf-residency-", ".csv")
        try {
            Files.writeString(temporary, result.stdout)
            val rows = Csv.read(temporary)
            AndroidCache.validateResidency(rows, files, required, capturePageSize)
            val missing = files.toSet() - rows.map { it.getValue("file_name") }.toSet()
            if (missing.isNotEmpty()) {
                captureWarnings +=
                    "$phase: ignored ${missing.size} non-essential files that disappeared: ${missing.joinToString()}"
            }
            require(rows.map { it.getValue("file_name") }.toSet().containsAll(required)) {
                "Missing required installed APK residency rows"
            }
            return rows.map {
                mapOf(
                    "phase" to phase,
                    "file_name" to it["file_name"],
                    "size_bytes" to it.getValue("size_bytes").toLong(),
                    "total_pages" to it.getValue("total_pages").toLong(),
                    "resident_pages" to it.getValue("resident_pages").toLong(),
                )
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private data class FileCommandOutput(
        val stdout: String,
        val stderr: String,
        val exitCodes: List<Int>,
    )

    private fun collectorFileCommand(
        adb: Device,
        mode: String,
        files: List<String>,
        check: Boolean = true,
    ): FileCommandOutput {
        if (files.isEmpty()) return FileCommandOutput("", "", emptyList())
        val outputs = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val exitCodes = mutableListOf<Int>()
        val batches = mutableListOf<MutableList<String>>()
        files.forEach { file ->
            val batch =
                batches.lastOrNull()?.takeIf { values -> values.sumOf { it.length + 3 } + file.length < 23_000 }
                    ?: mutableListOf<String>().also(batches::add)
            batch += file
        }
        batches.forEachIndexed { index, batch ->
            val result =
                adb.rootShell(
                    "$remoteCollector $mode ${batch.joinToString(" ") { quote(it) }}",
                    check = false,
                )
            outputs += if (index == 0 || mode != "--residency") result.stdout else result.stdout.substringAfter('\n')
            if (result.stderr.isNotBlank()) errors += result.stderr
            exitCodes += result.exitCode
        }
        if (check && exitCodes.any { it != 0 }) {
            error("$mode helper failed with exit status ${exitCodes.joinToString()}: ${errors.joinToString("\n").trim()}")
        }
        return FileCommandOutput(outputs.joinToString(""), errors.joinToString("\n"), exitCodes)
    }

    private fun verifyResidency(
        metadata: MutableMap<String, Any?>,
        output: Path,
        rows: List<Map<String, Any?>>,
        phase: String,
        limit: Int,
    ) {
        val phaseRows = rows.filter { it["phase"] == phase }
        require(phaseRows.isNotEmpty()) { "No cache-residency evidence for $phase" }
        val resident = phaseRows.sumOf { it["resident_pages"] as Long }
        metadata["cache_verification"] =
            mapOf(
                "phase" to phase,
                "files_checked" to phaseRows.size,
                "resident_pages" to resident,
                "total_pages" to phaseRows.sumOf { it["total_pages"] as Long },
                "fully_evicted_files" to phaseRows.count { it["resident_pages"] == 0L },
            )
        if (resident > limit) {
            metadata["capture_status"] = "cache_verification_failed"
            Json.write(output.resolve("capture_metadata.json"), metadata)
            error("Page-cache eviction verification failed at $phase: $resident resident pages exceeds $limit")
        }
        metadata["capture_status"] = "cache_verified_$phase"
        Json.write(output.resolve("capture_metadata.json"), metadata)
    }

    private fun writeResidency(
        output: Path,
        rows: List<Map<String, Any?>>,
    ) {
        Csv.write(
            output.resolve("cache_residency.csv"),
            listOf("phase", "file_name", "size_bytes", "total_pages", "resident_pages"),
            rows,
        )
    }

    private fun dumpInodes(
        adb: Device,
        packageName: String,
        apks: List<String>,
        output: Path,
        append: Boolean,
    ) {
        val roots = (apks.map { Path.of(it).parent.toString() } + "/data/user/0/$packageName" + "/data/user_de/0/$packageName").distinct()
        val required =
            apks.joinToString(" ") {
                "stat -c '%d|%i|%s|%n' ${quote(it)} || { echo missing-apk >&2; exit 75; };"
            }
        val optional =
            roots.joinToString(" ") {
                "if [ -d ${quote(it)} ]; then find ${quote(it)} -type f -exec stat -c '%d|%i|%s|%n' {} \\; 2>/dev/null || true; fi;"
            }
        val text = adb.rootShell("$required $optional").stdout
        if (append) {
            Files.writeString(output.resolve("inodes.txt"), text, java.nio.file.StandardOpenOption.APPEND)
        } else {
            Files.writeString(output.resolve("inodes.txt"), text)
        }
    }

    private fun pullArtifacts(
        adb: Device,
        apks: List<String>,
        abi: String,
        output: Path,
    ) {
        val directory = output.resolve("artifacts")
        Files.createDirectories(directory)
        val arch = mapOf("armeabi-v7a" to "arm", "arm64-v8a" to "arm64", "x86" to "x86", "x86_64" to "x86_64")[abi] ?: abi
        val remotes = apks.toMutableList()
        apks.forEach { apk ->
            val parent = Path.of(apk).parent.resolve("oat/$arch")
            val stem =
                Path
                    .of(apk)
                    .fileName
                    .toString()
                    .substringBeforeLast('.')
            listOf(".odex", ".vdex", ".art").forEach { suffix ->
                val candidate = parent.resolve("$stem$suffix").toString()
                if (adb.rootShell("test -f ${quote(candidate)}", check = false).exitCode == 0) remotes += candidate
            }
        }
        val mapping =
            remotes.distinct().associateWith { remote ->
                val local = directory.resolve("${sha256(remote.toByteArray()).take(10)}-${Path.of(remote).fileName}")
                pull(adb, remote, local)
                output.relativize(local).toString()
            }
        Json.write(output.resolve("artifacts.json"), mapping)
    }

    private fun pull(
        adb: Device,
        remote: String,
        local: Path,
    ) {
        val result = adb.run("pull", remote, local.toString(), check = false)
        if (result.exitCode == 0) return
        val command = adb.rootCommand("cat ${quote(remote)}").toMutableList().apply { this[commandIndexForShell()] = "exec-out" }
        val process = ProcessBuilder(command).redirectOutput(local.toFile()).start()
        require(process.waitFor() == 0) { "Unable to pull $remote with root" }
    }

    private fun pullStackBinaries(
        adb: Device,
        output: Path,
        warnings: MutableList<String>,
    ) {
        val mappingPath = output.resolve("artifacts.json")
        val mapping = if (Files.exists(mappingPath)) Json.readMap(mappingPath) else mutableMapOf()
        val directory = output.resolve("artifacts")
        Files.createDirectories(directory)
        val binaries =
            Files
                .readAllLines(output.resolve("maps.txt"))
                .mapNotNull { line ->
                    line.trim().split(Regex("\\s+"), limit = 6).getOrNull(5)
                }.filter { path ->
                    path.startsWith('/') &&
                        !path.endsWith(" (deleted)") &&
                        (
                            path.endsWith(".so") ||
                                Path.of(path).fileName.toString() in listOf("linker", "linker64", "app_process32", "app_process64")
                        )
                }.distinct()
        binaries.filterNot(mapping::containsKey).forEach { remote ->
            val local = directory.resolve("${sha256(remote.toByteArray()).take(10)}-${Path.of(remote).fileName}")
            try {
                pull(adb, remote, local)
                mapping[remote] = output.relativize(local).toString()
            } catch (error: Exception) {
                Files.deleteIfExists(local)
                warnings += "Could not pull optional stack binary $remote: ${error.message}"
            }
        }
        Json.write(mappingPath, mapping)
    }

    private fun List<String>.commandIndexForShell(): Int = indexOf("shell")

    private fun resetOutput(
        output: Path,
        overwrite: Boolean,
    ) {
        val absolute = output.toAbsolutePath().normalize()
        val home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize()
        require(absolute.parent != null && absolute != home) { "Refusing unsafe output directory: $absolute" }
        require(!Files.isSymbolicLink(output)) { "Refusing symbolic-link output directory: $output" }
        if (Files.exists(output) && Files.list(output).use { it.findAny().isPresent }) {
            require(overwrite) { "Output directory is not empty: $output" }
            require(Files.readString(output.resolve(".android-fault-visualizer-capture")) == "android-fault-visualizer capture v1\n") {
                "Refusing to overwrite a directory not owned by mperf faults"
            }
            Files.walk(output).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
        Files.createDirectories(output)
        Files.writeString(output.resolve(".android-fault-visualizer-capture"), "android-fault-visualizer capture v1\n")
    }

    @Suppress("UNCHECKED_CAST")
    private fun warnings(metadata: MutableMap<String, Any?>): MutableList<String> = metadata.getValue("warnings") as MutableList<String>

    private fun sha256(path: Path): String = sha256(Files.readAllBytes(path))

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun quote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"

    private fun await(
        timeout: Duration,
        operation: String,
        predicate: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (System.nanoTime() < deadline) {
            if (predicate()) return
            Thread.sleep(50)
        }
        error("Timed out waiting for $operation")
    }

    internal class Device private constructor(
        val serial: String,
    ) {
        private var rootTemplate: String? = null
        private val base = listOf("adb", "-s", serial)

        fun command(vararg arguments: String): List<String> = base + arguments

        fun run(
            vararg arguments: String,
            check: Boolean = true,
            timeout: Duration = Duration.ofSeconds(30),
        ): CommandResult = Processes.run(base + arguments, check = check, timeout = timeout)

        fun shell(
            command: String,
            check: Boolean = true,
            timeout: Duration = Duration.ofSeconds(30),
        ): CommandResult = run("shell", command, check = check, timeout = timeout)

        fun property(name: String): String = shell("getprop ${quote(name)}").stdout.trim()

        fun ensureRoot() {
            run("root", check = false)
            run("wait-for-device")
            val candidates = listOf("sh -c %s", "su 0 sh -c %s", "su -c %s")
            rootTemplate =
                candidates.firstOrNull { template ->
                    val result = shell(template.format(quote("id")), check = false)
                    result.exitCode == 0 && "uid=0" in result.stdout
                }
            requireNotNull(rootTemplate) { "Unable to acquire a root shell" }
        }

        fun rootCommand(command: String): List<String> = base + listOf("shell", requireNotNull(rootTemplate).format(quote(command)))

        fun rootShell(
            command: String,
            check: Boolean = true,
            timeout: Duration = Duration.ofSeconds(30),
        ): CommandResult = Processes.run(rootCommand(command), check = check, timeout = timeout)

        fun packagePaths(packageName: String): List<String> =
            shell("pm path ${quote(packageName)}")
                .stdout
                .lineSequence()
                .filter { it.startsWith("package:") }
                .map { it.removePrefix("package:").trim() }
                .toList()
                .also { require(it.isNotEmpty()) { "No installed APK paths for $packageName" } }

        fun packageFiles(
            packageName: String,
            apks: List<String>,
        ): List<String> {
            apks.forEach { apk ->
                require(rootShell("test -f ${quote(apk)}", check = false).exitCode == 0) {
                    "Installed APK disappeared while enumerating cache targets: $apk"
                }
            }
            val roots =
                (
                    apks.map {
                        Path
                            .of(
                                it,
                            ).parent
                            .toString()
                    } + "/data/user/0/$packageName" + "/data/user_de/0/$packageName"
                ).distinct()
            val command =
                roots.joinToString(" ") {
                    "if [ -d ${quote(it)} ]; then find ${quote(it)} -type f -print0 2>/dev/null || true; fi;"
                }
            return (apks + rootShell(command).stdout.split('\u0000').filter(String::isNotBlank)).distinct().sorted()
        }

        fun resolveActivity(packageName: String): String =
            shell("cmd package resolve-activity --brief ${quote(packageName)}")
                .stdout
                .lineSequence()
                .lastOrNull { "/" in it }
                ?.trim()
                ?: error("Unable to resolve launcher activity for $packageName")

        fun pid(process: String): Long? = shell("pidof -s ${quote(process)}", check = false).stdout.trim().toLongOrNull()

        fun reboot() {
            val previous = shell("cat /proc/sys/kernel/random/boot_id", timeout = Duration.ofSeconds(5)).stdout.trim()
            require(Regex("[0-9a-fA-F-]{36}").matches(previous)) { "Unable to establish current boot identity" }
            run("reboot", timeout = Duration.ofSeconds(15))
            rootTemplate = null
            val deadline = System.nanoTime() + Duration.ofMinutes(3).toNanos()
            while (System.nanoTime() < deadline) {
                val current =
                    runCatching {
                        shell("cat /proc/sys/kernel/random/boot_id", check = false, timeout = Duration.ofSeconds(5))
                    }.getOrNull()
                val completed =
                    if (current?.exitCode == 0) {
                        runCatching {
                            shell("getprop sys.boot_completed", check = false, timeout = Duration.ofSeconds(5)).stdout.trim()
                        }.getOrDefault("")
                    } else {
                        ""
                    }
                if (current?.exitCode == 0 && AndroidCache.newBootReady(previous, current.stdout.trim(), completed)) return
                Thread.sleep(1_000)
            }
            error("Timed out waiting for Android boot")
        }

        private fun quote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"

        companion object {
            fun resolve(requested: String?): Device {
                val rows =
                    Processes
                        .run(listOf("adb", "devices"))
                        .stdout
                        .lineSequence()
                        .drop(1)
                val devices =
                    rows
                        .map {
                            it.trim().split(
                                Regex("\\s+"),
                            )
                        }.filter { it.size >= 2 && it[1] == "device" }
                        .map { it[0] }
                        .toList()
                val selected =
                    requested?.also { require(it in devices) { "ADB device $it is not connected" } }
                        ?: devices.singleOrNull()
                        ?: error(if (devices.isEmpty()) "No connected ADB device" else "Multiple ADB devices connected; use --device")
                return Device(selected)
            }
        }
    }
}
