package com.bromano.mobile.perf.tools

import com.bromano.mobile.perf.utils.Adb
import com.bromano.mobile.perf.utils.Logger
import com.bromano.mobile.perf.utils.downloadVerified
import com.github.ajalt.clikt.core.PrintMessage
import java.nio.file.Files

/** Versions and SHA-256 pins from Perfetto's upstream prebuilt manifests. */
internal object PerfettoTools {
    const val VERSION = "v58.2"
    const val TRACEBOX_PATH = "/data/local/tmp/tracebox"

    private data class TraceboxArtifact(
        val url: String,
        val sha256: String,
    )

    /** Sideload the pinned standalone recorder for older Android releases. */
    fun sideload(adb: Adb) {
        val binaryArtifacts =
            mapOf(
                "arm64-v8a" to
                    TraceboxArtifact(
                        "https://commondatastorage.googleapis.com/perfetto-luci-artifacts/$VERSION/android-arm64/tracebox",
                        "51591371ae18dd141b596ff96bafa21b6ccb5918d4ef2078ddf9bbf4438c2a82",
                    ),
                "armeabi-v7a" to
                    TraceboxArtifact(
                        "https://commondatastorage.googleapis.com/perfetto-luci-artifacts/$VERSION/android-arm/tracebox",
                        "dde682d536bc7259aeb21aea8a2cd3f4cec6dad55d056512da9d0bf4c5cfd3cb",
                    ),
                "x86_64" to
                    TraceboxArtifact(
                        "https://commondatastorage.googleapis.com/perfetto-luci-artifacts/$VERSION/android-x64/tracebox",
                        "d9cb2f28334b48e499c9d6c0a3e06ac2e1b4db6f0f4f2b73720522eb79eee1cd",
                    ),
            )

        val artifact = binaryArtifacts[adb.abi] ?: throw PrintMessage("Unexpected ABI: ${adb.abi}", printError = true)
        if (adb.shell("sha256sum $TRACEBOX_PATH", ignoreErrors = true).substringBefore(" ").trim() == artifact.sha256
        ) {
            return
        }

        Logger.info("Sideloading Perfetto $VERSION onto device")

        val traceboxPath = Files.createTempFile("tracebox", "")
        try {
            downloadVerified(artifact.url, traceboxPath, artifact.sha256)
            adb.push(traceboxPath.toString(), TRACEBOX_PATH)
            adb.shell("chmod +x $TRACEBOX_PATH")
        } finally {
            Files.deleteIfExists(traceboxPath)
        }
    }
}
