package com.bromano.mobile.perf.tools

import com.bromano.mobile.perf.utils.Adb
import com.bromano.mobile.perf.utils.Logger
import com.bromano.mobile.perf.utils.Shell
import com.bromano.mobile.perf.utils.downloadVerified
import com.bromano.mobile.perf.utils.sha256
import com.bromano.mobile.perf.utils.shellQuote
import java.io.File
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64

private const val SIMPLEPERF_SIDELOAD_PATH = "/data/local/tmp/simpleperf"
private const val SIMPLEPERF_PREBUILTS_COMMIT = "829c2351dfc33886743931721b67b959c50a40ab"
private const val SIMPLEPERF_SCRIPTS_ARCHIVE_COMMIT = "fc2494a2abd7ab21774d03deb09c1362bbb0bba8"
private const val SIMPLEPERF_SCRIPTS_TREE_SHA256 = "deec6c145f678bc72897e10bd2c1eba96fff1a3b7044d504db030eb37b6abf8e"

private data class SimpleperfBinaryInfo(
    val url: String,
    val sha256: String,
)

/** Pinned Android recorder and upstream host-side converters, shared by profiling commands. */
internal class SimpleperfTools(
    private val shell: Shell,
) {
    companion object {
        const val DEVICE_PATH = SIMPLEPERF_SIDELOAD_PATH
    }

    /**
     * Sideload the pinned upstream copy of simpleperf to backport `--user-buffer-size` and any other bug fixes
     */
    fun sideload(adb: Adb) {
        // https://android.googlesource.com/platform/prebuilts/simpleperf/+log/refs/heads/mirror-goog-main-prebuilts/bin/android/arm64/simpleperf
        val binaryInfoMap =
            mapOf(
                "arm64-v8a" to
                    SimpleperfBinaryInfo(
                        "https://android.googlesource.com/platform/prebuilts/simpleperf/+/$SIMPLEPERF_PREBUILTS_COMMIT/bin/android/arm64/simpleperf?format=TEXT",
                        "bf6d50d8ece60f5bc9c21aa3559993a99f868c0501040aeab3af0b911cb6a200",
                    ),
                "armeabi-v7a" to
                    SimpleperfBinaryInfo(
                        "https://android.googlesource.com/platform/prebuilts/simpleperf/+/$SIMPLEPERF_PREBUILTS_COMMIT/bin/android/arm/simpleperf?format=TEXT",
                        "6c337d680bf287d417a0371694eb273662587eae86676758999808eedf651496",
                    ),
                "x86_64" to
                    SimpleperfBinaryInfo(
                        "https://android.googlesource.com/platform/prebuilts/simpleperf/+/$SIMPLEPERF_PREBUILTS_COMMIT/bin/android/x86_64/simpleperf?format=TEXT",
                        "3bf255f996fc80426fc1ab90cf3bd28d845f3f6fc69e02cd5b4f6a11f65ae54c",
                    ),
            )

        val binaryInfo = binaryInfoMap[adb.abi] ?: throw IllegalStateException("Unsupported ABI: ${adb.abi}")

        // Check if already up-to-date before downloading and sideloading.
        if (adb.shell("sha256sum $SIMPLEPERF_SIDELOAD_PATH", ignoreErrors = true).substringBefore(" ").trim() == binaryInfo.sha256
        ) {
            return
        }

        Logger.info("Sideloading simpleperf binary...")
        val tempFile =
            File.createTempFile("simpleperf", null).apply {
                deleteOnExit()
            }

        downloadVerified(binaryInfo.url, tempFile.toPath(), binaryInfo.sha256) { input ->
            // googlesource ?format=TEXT endpoints return a base64-encoded file body.
            Base64.getDecoder().decode(input.readAllBytes())
        }

        adb.push(tempFile.toString(), SIMPLEPERF_SIDELOAD_PATH)
        adb.shell("chmod +x $SIMPLEPERF_SIDELOAD_PATH")
    }

    /**
     * Install the checksum-pinned upstream converter scripts.
     */
    fun scripts(): Path {
        val simpleperfHome = Path.of(System.getProperty("user.home")).resolve(".mperf/simpleperf")
        val versionFile = simpleperfHome.resolve(".mperf-version")

        if (versionFile
                .toFile()
                .takeIf { it.isFile }
                ?.readText()
                ?.trim() == SIMPLEPERF_SCRIPTS_ARCHIVE_COMMIT &&
            simpleperfHome.resolve("gecko_profile_generator.py").toFile().isFile
        ) {
            return simpleperfHome
        }

        val simpleperfArchive =
            "https://android.googlesource.com/platform/system/extras/+archive/$SIMPLEPERF_SCRIPTS_ARCHIVE_COMMIT/simpleperf/scripts.tar.gz"
        val tempArchive =
            File.createTempFile("simpleperf.tar.gz", null).apply {
                deleteOnExit()
            }

        Logger.info("Installing simpleperf scripts...")
        URI(simpleperfArchive).toURL().openStream().use { input ->
            Files.copy(input, tempArchive.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }

        val archiveEntries = shell.runCommand("tar -tzf ${shellQuote(tempArchive.toString())}").lineSequence()
        require(
            archiveEntries.all { entry ->
                entry.isBlank() || Path.of(entry).let { !it.isAbsolute && !it.normalize().startsWith("..") }
            },
        ) { "Simpleperf scripts archive contains an unsafe path" }
        require(
            shell
                .runCommand("tar -tvzf ${shellQuote(tempArchive.toString())}")
                .lineSequence()
                .filter { it.isNotBlank() }
                .all { it.first() == '-' || it.first() == 'd' },
        ) { "Simpleperf scripts archive contains links or unsupported entries" }

        Files.createDirectories(simpleperfHome.parent)
        val extractedScripts = Files.createTempDirectory(simpleperfHome.parent, "simpleperf-install-")
        try {
            shell.runCommand("tar -xzf ${shellQuote(tempArchive.toString())} -C ${shellQuote(extractedScripts.toString())}")
            val actualTreeSha256 = calculateTreeSha256(extractedScripts)
            require(actualTreeSha256 == SIMPLEPERF_SCRIPTS_TREE_SHA256) {
                "Checksum verification failed for Simpleperf scripts at $SIMPLEPERF_SCRIPTS_ARCHIVE_COMMIT: " +
                    "expected $SIMPLEPERF_SCRIPTS_TREE_SHA256, got $actualTreeSha256"
            }

            simpleperfHome.toFile().deleteRecursively()
            Files.move(extractedScripts, simpleperfHome)
            versionFile.toFile().writeText("$SIMPLEPERF_SCRIPTS_ARCHIVE_COMMIT\n")
        } finally {
            extractedScripts.toFile().deleteRecursively()
        }

        return simpleperfHome
    }

    private fun calculateTreeSha256(root: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val files =
            Files.walk(root).use { paths ->
                paths
                    .filter(Files::isRegularFile)
                    .toList()
                    .sortedBy { root.relativize(it).toString() }
            }
        files.forEach { file ->
            val relativePath = root.relativize(file).joinToString("/")
            val manifestLine = "${sha256(file)}  ./$relativePath\n"
            digest.update(manifestLine.toByteArray(StandardCharsets.UTF_8))
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
