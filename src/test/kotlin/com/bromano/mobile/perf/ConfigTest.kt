package com.bromano.mobile.perf

import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigTest {
    @Test
    fun `getConfig creates default config in user home`() {
        val tmpHome = createTempDirectory("home").toFile()
        val prevHome = System.getProperty("user.home")
        try {
            System.setProperty("user.home", tmpHome.absolutePath)

            val path = getConfig()
            assertTrue(Files.exists(path), "config file should be created")

            val content = path.readText()
            assertTrue(content.isNotBlank(), "config content should not be blank")

            val cfg = readConfig(path)
            assertEquals(null, cfg.android, "default android config should be null")
        } finally {
            System.setProperty("user.home", prevHome)
        }
    }

    @Test
    fun `readConfig parses package key and ignores unknown fields`() {
        val tmpDir = createTempDirectory("config")
        val path = tmpDir.resolve("config.yml")
        Files.writeString(
            path,
            """
            android:
              package: com.example.app
              instrumentationRunner: com.example.benchmark/androidx.test.runner.AndroidJUnitRunner
              ignoredField: ignored
            ios:
              bundleIdentifier: com.example.ios
              deviceId: simulator-id
            unusedTopLevel: true
            """.trimIndent(),
        )

        val cfg = readConfig(path)

        assertNotNull(cfg.android)
        assertEquals("com.example.app", cfg.android.packageName)
        assertEquals(
            "com.example.benchmark/androidx.test.runner.AndroidJUnitRunner",
            cfg.android.instrumentationRunner,
        )
        assertNotNull(cfg.ios)
        assertEquals("com.example.ios", cfg.ios.bundleIdentifier)
        assertEquals("simulator-id", cfg.ios.deviceId)
    }

    @Test
    fun `writeConfig preserves legacy yaml field names`() {
        val tmpDir = createTempDirectory("config-write")
        val path = tmpDir.resolve("config.yml")

        writeConfig(
            path,
            Config(
                android =
                    AndroidConfig(
                        packageName = "com.example.app",
                        instrumentationRunner = "com.example.benchmark/androidx.test.runner.AndroidJUnitRunner",
                    ),
                ios =
                    IosConfig(
                        bundleIdentifier = "com.example.ios",
                        deviceId = "simulator-id",
                    ),
                traceHostUrl = "https://trace.example.com",
                perfettoUrl = "https://perfetto.example.com",
            ),
        )

        val yaml = path.readText()
        assertContains(yaml, "android:")
        assertContains(yaml, "package: \"com.example.app\"")
        assertContains(yaml, "instrumentationRunner: \"com.example.benchmark/androidx.test.runner.AndroidJUnitRunner\"")
        assertContains(yaml, "ios:")
        assertContains(yaml, "bundleIdentifier: \"com.example.ios\"")
        assertContains(yaml, "deviceId: \"simulator-id\"")
        assertContains(yaml, "traceHostUrl: \"https://trace.example.com\"")
        assertContains(yaml, "perfettoUrl: \"https://perfetto.example.com\"")
        assertFalse(yaml.contains("packageName:"), "serialized yaml should keep package field name")
    }

    @Test
    fun `readConfig returns default config for blank file`() {
        val tmpDir = createTempDirectory("config-blank")
        val path = tmpDir.resolve("config.yml")
        path.writeText("")

        val cfg = readConfig(path)

        assertNull(cfg.android)
        assertNull(cfg.ios)
        assertNull(cfg.traceHostUrl)
        assertNull(cfg.perfettoUrl)
    }
}
