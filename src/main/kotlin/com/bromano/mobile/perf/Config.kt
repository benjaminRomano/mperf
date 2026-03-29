package com.bromano.mobile.perf

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.notExists
import kotlin.io.path.outputStream

/**
 * YAML-based config file for storing CLI preferences
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class Config(
    /**
     * Android specific configs
     */
    val android: AndroidConfig? = null,
    /**
     * iOS specific configs
     */
    val ios: IosConfig? = null,
    /**
     * Optional endpoint that accepts trace uploads via HTTP POST and serves GET /<id>.
     *
     * Example: https://myserver.com/trace
     */
    val traceHostUrl: String? = null,
    /**
     * Optional Perfetto UI base URL used when opening traces (must support url query param).
     *
     * Example: https://perfetto.mycompany.com
     */
    val perfettoUrl: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AndroidConfig(
    /**
     * The package name of app to profile
     */
    @field:JsonProperty("package")
    val packageName: String? = null,
    /**
     * The package name of instrumentation test runner
     */
    val instrumentationRunner: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class IosConfig(
    /**
     * The bundle identifier of app to profile
     */
    val bundleIdentifier: String? = null,
    /**
     * Default device/simulator UDID
     */
    val deviceId: String? = null,
)

private val configMapper =
    YAMLMapper
        .builder()
        .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
        .build()
        .registerKotlinModule()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .setDefaultPropertyInclusion(
            JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL),
        )

fun getConfig(): Path =
    Paths.get(System.getProperty("user.home")).resolve(".mperf/config.yml").apply {
        parent.toFile().mkdirs()
        if (notExists()) {
            writeConfig(this, Config())
        }
    }

fun readConfig(path: Path = getConfig()): Config =
    if (Files.readString(path).isBlank()) {
        Config()
    } else {
        configMapper.readValue(path.toFile())
    }

fun writeConfig(
    path: Path,
    config: Config,
) {
    path.parent?.toFile()?.mkdirs()
    if (path.notExists()) {
        Files.createFile(path)
    }
    path.outputStream().use { os ->
        configMapper.writeValue(os, config)
    }
}
