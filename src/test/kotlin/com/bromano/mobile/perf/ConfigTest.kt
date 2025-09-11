package com.bromano.mobile.perf

import com.charleskorn.kaml.Yaml
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.test.assertEquals
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

            val cfg = Yaml.default.decodeFromString(Config.serializer(), content)
            assertEquals(null, cfg.android, "default android config should be null")
        } finally {
            System.setProperty("user.home", prevHome)
        }
    }
}
