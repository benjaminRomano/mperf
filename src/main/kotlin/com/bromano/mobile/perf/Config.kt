package com.bromano.mobile.perf

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.encodeToStream
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.notExists
import kotlin.io.path.outputStream

/**
 * YAML-based config file for storing CLI preferences
 */
@Serializable
data class Config(
    /**
     * Android specific configs
     */
    @SerialName("android")
    val android: AndroidConfig? = null,
)

@Serializable
data class AndroidConfig(
    /**
     * The package name of app to profile
     */
    @SerialName("package")
    val packageName: String? = null,
    /**
     * The package name of instrumentation test runner
     */
    @SerialName("instrumentationRunner")
    val instrumentationRunner: String? = null,
)

fun getConfig(): Path =
    Paths.get(System.getProperty("user.home")).resolve(".mperf/config.yml").apply {
        parent.toFile().mkdirs()
        if (notExists()) {
            Files.createFile(this).outputStream().use { os ->
                Yaml.default.encodeToStream(Config.serializer(), Config(), os)
            }
        }
    }
