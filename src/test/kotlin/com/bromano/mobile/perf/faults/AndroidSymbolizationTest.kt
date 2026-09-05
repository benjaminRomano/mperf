package com.bromano.mobile.perf.faults

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AndroidSymbolizationTest {
    @TempDir lateinit var directory: Path

    @Test fun `failed symbolization preserves exact frames and continues with other binaries`() {
        val data = ByteArray(128)
        byteArrayOf(127, 69, 76, 70, 2, 1).copyInto(data)
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).apply {
            putLong(32, 64)
            putShort(54, 56)
            putShort(56, 1)
            putInt(64, 1)
            putLong(96, 128)
        }
        val bad = directory.resolve("bad.so").also { Files.write(it, data) }
        val good = directory.resolve("good.so").also { Files.write(it, data) }
        val tool = directory.resolve("llvm-symbolizer")
        val csv = directory.resolve("resolved_fault_callchains.csv")
        val rows =
            listOf("bad.so", "good.so").mapIndexed { index, name ->
                mapOf("sequence" to "$index", "frame_kind" to "user", "file_name" to name, "file_offset" to "1", "label" to "$name+0x1")
            }
        for (failure in listOf("exit 1", "exit 0", "echo invalid-json")) {
            Files.writeString(
                tool,
                "#!/bin/sh\ncase \"\$2\" in *bad.so) $failure; exit;; esac\nwhile read line; do echo '{\"Symbol\":[{\"FunctionName\":\"resolved\"}]}'; done\n",
            )
            assertTrue(tool.toFile().setExecutable(true))
            Csv.write(csv, rows.first().keys.toList(), rows)
            val warnings = AndroidBinary.symbolize(directory, mapOf("bad.so" to bad, "good.so" to good), tool)
            assertEquals(1, warnings.size, failure)
            assertTrue(warnings.single().contains("bad.so"), failure)
            val result = Csv.read(csv)
            assertEquals(rows[0], result[0], failure)
            assertEquals(rows[1] + ("label" to "resolved"), result[1], failure)
        }
    }
}
