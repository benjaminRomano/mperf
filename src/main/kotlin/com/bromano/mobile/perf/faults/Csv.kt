package com.bromano.mobile.perf.faults

import java.io.BufferedReader
import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Path

internal object Csv {
    fun read(path: Path): List<Map<String, String>> {
        if (!Files.exists(path)) return emptyList()
        Files.newBufferedReader(path).use { reader ->
            val records = sequence(reader).iterator()
            if (!records.hasNext()) return emptyList()
            val header = records.next()
            return buildList {
                records.forEachRemaining { values ->
                    add(header.mapIndexed { index, name -> name to values.getOrElse(index) { "" } }.toMap())
                }
            }
        }
    }

    fun write(
        path: Path,
        fields: List<String>,
        rows: Iterable<Map<String, Any?>>,
    ) {
        Files.createDirectories(path.parent)
        Files.newBufferedWriter(path).use { writer ->
            writeRecord(writer, fields)
            rows.forEach { row ->
                writeRecord(writer, fields.map { field -> row[field]?.toString().orEmpty() })
            }
        }
    }

    private fun sequence(reader: BufferedReader): Sequence<List<String>> =
        sequence {
            val record = mutableListOf<String>()
            val field = StringBuilder()
            var quoted = false
            while (true) {
                val value = reader.read()
                if (value < 0) {
                    if (quoted) throw IllegalArgumentException("Unterminated quoted CSV field")
                    if (field.isNotEmpty() || record.isNotEmpty()) {
                        record += field.toString()
                        yield(record.toList())
                    }
                    break
                }
                val character = value.toChar()
                when {
                    quoted && character == '"' -> {
                        reader.mark(1)
                        if (reader.read() == '"'.code) {
                            field.append('"')
                        } else {
                            quoted = false
                            reader.reset()
                        }
                    }
                    quoted -> field.append(character)
                    character == '"' && field.isEmpty() -> quoted = true
                    character == ',' -> {
                        record += field.toString()
                        field.clear()
                    }
                    character == '\n' -> {
                        record += field.toString().trimEnd('\r')
                        yield(record.toList())
                        record.clear()
                        field.clear()
                    }
                    else -> field.append(character)
                }
            }
        }

    private fun writeRecord(
        writer: BufferedWriter,
        values: List<String>,
    ) {
        writer.appendLine(
            values.joinToString(",") { value ->
                if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
                    "\"${value.replace("\"", "\"\"")}\""
                } else {
                    value
                }
            },
        )
    }
}
