package com.bromano.mobile.perf

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.io.path.createTempDirectory
import kotlin.test.assertContains
import kotlin.test.assertTrue

class DocsGeneratorTest {
    private var originalHome: String? = null

    @BeforeEach
    fun setUp() {
        // Point user.home to a temp directory so getConfig() initializes a local config
        originalHome = System.getProperty("user.home")
        val tmpHome = createTempDirectory("home_docs").toFile()
        System.setProperty("user.home", tmpHome.absolutePath)
    }

    @AfterEach
    fun tearDown() {
        originalHome?.let { System.setProperty("user.home", it) }
    }

    @Test
    fun `generates root tables with spacing`() {
        val md = DocsGenerator.generateCliDocsMarkdown()

        // Root title
        assertTrue(md.startsWith("# mperf"))
        // Root options section has blank line before the table
        assertContains(md, "**Options**\n\n| Name(s) | Metavar | Description |")
        // Root commands table lists subcommands
        assertContains(md, "**Commands**\n\n| Name | Description |")
        assertContains(md, "| start | Run profiler over abitrary app session |")
        assertContains(md, "| collect | Collect performance data over single iteration of a performance test |")
    }

    @Test
    fun `start command options include expected rows`() {
        val md = DocsGenerator.generateCliDocsMarkdown()

        // Ensure subcommand header present and options table has spacing
        assertContains(md, "## start")
        assertContains(md, "**Options**\n\n| Name(s) | Metavar | Description |")
        // Format option with escaped pipes in metavar
        assertContains(md, "| --format, -f | (perfetto\\|simpleperf\\|method) | Profiler to use for collection |")
        // A few other representative options
        assertContains(md, "| --out, -o | path | Output path for trace |")
        assertContains(md, "| --package, -p | text | Package name |")
        assertContains(md, "| --ui | (PERFETTO\\|FIREFOX) | Profile viewer to open trace in |")
    }

    @Test
    fun `collect command options include instrumentation and test args`() {
        val md = DocsGenerator.generateCliDocsMarkdown()

        assertContains(md, "## collect")
        assertContains(md, "**Options**\n\n| Name(s) | Metavar | Description |")
        assertContains(
            md,
            "| --instrumentation, -i | text | Instrumentation runner (e.g. com.example.macrobenchmark/androidx.test.runner.AndroidJUnitRunner) |",
        )
        assertContains(md, "| --test, -t | text | Performance test to run |")
    }
}
