package com.bromano.mobile.perf.utils

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.io.path.createDirectories
import kotlin.use

object ZipUtils {
    /**
     * Unzip Instruments file
     *
     * An Instruments file is actually a directory internally.
     * To properly unzip requires detecting the file
     *
     */
    fun unzipInstruments(
        zip: Path,
        dest: Path,
    ) {
        dest.createDirectories()

        ZipFile(zip.toFile()).use { zf ->
            val entries = zf.entries().toList()
            val archiveRoot = detectArchiveRoot(entries)

            val flatten = shouldAutoFlatten(dest, archiveRoot)

            for (e in entries) {
                val raw = e.name.replace('\\', '/')
                if (raw.isBlank() || raw.startsWith("__MACOSX/")) continue

                val rel = if (flatten) stripRoot(raw, archiveRoot!!) else raw
                if (rel.isBlank()) continue

                val out = dest.resolve(rel).normalize()

                if (e.isDirectory || rel.endsWith("/")) {
                    out.createDirectories()
                } else {
                    out.parent?.createDirectories()
                    zf.getInputStream(e).use { ins ->
                        Files.copy(ins, out, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            }
        }
    }

    private fun detectArchiveRoot(entries: List<ZipEntry>): String? {
        // Find common first path segment for all non-empty entries
        val firsts =
            entries
                .asSequence()
                .map { it.name.replace('\\', '/').trimStart('/') }
                .filter { it.isNotBlank() && !it.startsWith("__MACOSX/") }
                .map { it.substringBefore('/') }
                .toSet()

        return if (firsts.size == 1) firsts.first() else null
    }

    private fun stripRoot(
        path: String,
        root: String,
    ): String {
        val p = path.trimStart('/').replace('\\', '/')
        val prefix = "$root/"
        return when {
            p == root -> "" // root itself
            p.startsWith(prefix) -> p.removePrefix(prefix)
            else -> p // safety: entry outside root, keep as-is
        }
    }

    private fun shouldAutoFlatten(
        dest: Path,
        root: String?,
    ): Boolean {
        if (root == null) return false
        val destName = dest.fileName?.toString() ?: return false

        // Heuristics:
        // 1) Destination already ends with the root folder name.
        // 2) Destination ends with the same extension as root (e.g., *.trace).
        val rootExt = root.substringAfterLast('.', "")
        val destExt = destName.substringAfterLast('.', "")

        return destName == root || (rootExt.isNotEmpty() && destExt.equals(rootExt, ignoreCase = true))
    }

    // Small helper to iterate ZipFile entries
    private fun <T> java.util.Enumeration<T>.toList(): List<T> =
        buildList {
            while (hasMoreElements()) add(nextElement())
        }
}
