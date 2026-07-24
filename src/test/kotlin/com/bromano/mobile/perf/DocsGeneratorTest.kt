package com.bromano.mobile.perf

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.io.path.createTempDirectory
import kotlin.io.path.notExists
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
        // Root commands table lists top-level command groups
        assertContains(md, "**Commands**\n\n| Name | Description |")
        assertContains(md, "| ios |  |")
        assertContains(md, "| android |  |")
        assertContains(md, "| faults | Analyze startup page-fault patterns on Android or iOS |")
    }

    @Test
    fun `generation does not create user config`() {
        val configPath = getConfigPathWithoutCreating()

        DocsGenerator.generateCliDocsMarkdown()

        assertTrue(configPath.notExists())
    }

    private fun getConfigPathWithoutCreating() =
        java.nio.file.Paths
            .get(System.getProperty("user.home"))
            .resolve(".mperf/config.yml")

    @Test
    fun `start command options include expected rows`() {
        val md = DocsGenerator.generateCliDocsMarkdown()

        // Ensure android group and start command section are present with table spacing
        assertContains(md, "## android")
        assertContains(md, "### start")
        assertContains(md, "**Options**\n\n| Name(s) | Metavar | Description |")
        // Format option with escaped pipes in metavar
        assertContains(md, "| --format, -f | (perfetto\\|simpleperf\\|method) | Profiler to use for collection |")
        // A few other representative options
        assertContains(md, "| --out, -o | path | Output path for trace |")
        assertContains(md, "| --package, -p | text | Package name |")
        assertContains(md, "| --ui | (PERFETTO\\|FIREFOX\\|INSTRUMENTS) | Profile viewer to open trace in |")
    }

    @Test
    fun `collect command options include instrumentation and test args`() {
        val md = DocsGenerator.generateCliDocsMarkdown()

        assertContains(md, "## android")
        assertContains(md, "### collect")
        assertContains(md, "**Options**\n\n| Name(s) | Metavar | Description |")
        assertContains(
            md,
            "| --instrumentation, -i | text | Instrumentation runner (e.g. com.example.macrobenchmark/androidx.test.runner.AndroidJUnitRunner) |",
        )
        assertContains(md, "| --test, -t | text | Performance test to run |")
    }

    @Test
    fun `ios convert command is included in generated docs`() {
        val md = DocsGenerator.generateCliDocsMarkdown()

        assertContains(md, "## ios")
        assertContains(md, "| convert | Convert Instruments Trace to Gecko Format (Firefox Profiler) |")
        assertContains(md, "### convert")
        assertContains(md, "| --input, -i | path | Input Instruments Trace |")
        assertContains(md, "| --output, -o | path | Output Path for gecko profile |")
    }

    @Test
    fun `fault commands expose platform-specific capture controls`() {
        val md = DocsGenerator.generateCliDocsMarkdown()

        assertContains(md, "## faults")
        assertContains(md, "| android | Collect exact Android startup faults and generate an interactive HTML report |")
        assertContains(md, "| ios | Collect iOS startup VM faults and stacks with Instruments")
        assertContains(md, "| --max-resident-pages | int | Maximum verified resident app-file pages allowed before launch |")
        assertContains(md, "| --reboot-before-collect |  | Reboot the target before cache eviction and collection |")
        assertContains(md, "| --overwrite |  | Replace a non-empty output owned by mperf faults |")
        assertContains(md, "| --cache-policy | text | Cache policy: auto, purge, pressure, reboot, or none |")
        assertContains(md, "| --require-cold-cache |  | Fail unless Simulator app-file residency confirms eviction |")
    }
}
