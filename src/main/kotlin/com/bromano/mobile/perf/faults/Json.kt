package com.bromano.mobile.perf.faults

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path

internal object Json {
    val mapper: ObjectMapper = jacksonObjectMapper()

    fun readMap(path: Path): MutableMap<String, Any?> =
        Files.newBufferedReader(path).use { reader ->
            mapper.readValue(reader, object : TypeReference<MutableMap<String, Any?>>() {})
        }

    fun write(
        path: Path,
        value: Any?,
    ) {
        Files.createDirectories(path.parent)
        Files.newBufferedWriter(path).use { writer ->
            mapper.writerWithDefaultPrettyPrinter().writeValue(writer, value)
        }
    }
}
