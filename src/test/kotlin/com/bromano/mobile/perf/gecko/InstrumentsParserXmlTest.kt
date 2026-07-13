package com.bromano.mobile.perf.gecko

import com.bromano.mobile.perf.utils.FakeShell
import com.bromano.mobile.perf.utils.ShellCommandException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.xml.sax.SAXParseException
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstrumentsParserXmlTest {
    @Test
    fun `parses XML with prolog and leading xctrace diagnostics`() {
        val document =
            InstrumentsParser.processXCTraceOutput(
                "warning from xctrace\n<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<trace-toc><run number=\"1\"/></trace-toc>",
            )

        assertEquals("trace-toc", document.documentElement.tagName)
        val runNumber =
            document
                .getElementsByTagName("run")
                .item(0)
                .attributes
                .getNamedItem("number")
                .nodeValue
        assertEquals("1", runNumber)
    }

    @Test
    fun `parses XML without a prolog`() {
        val document = InstrumentsParser.processXCTraceOutput("<trace-toc/>")

        assertEquals("trace-toc", document.documentElement.tagName)
    }

    @Test
    fun `rejects document types and external entities`() {
        assertThrows<SAXParseException> {
            InstrumentsParser.processXCTraceOutput(
                """
                <!DOCTYPE trace-toc [<!ENTITY secret SYSTEM "file:///etc/passwd">]>
                <trace-toc>&secret;</trace-toc>
                """.trimIndent(),
            )
        }
    }

    @Test
    fun `exports xctrace XML through a temporary file`(
        @TempDir tempDir: Path,
    ) {
        val output = tempDir.resolve("export with spaces.xml")
        val shell = FakeShell()
        shell.runCommandHandler = { command, _, _, redirectOutput, redirectError ->
            assertTrue(command.contains("'--output' '$output'"))
            assertTrue(command.contains("'--input' '/tmp/input trace.trace'"))
            assertTrue(command.contains("'--toc'"))
            assertEquals(ProcessBuilder.Redirect.PIPE, redirectOutput)
            assertEquals(ProcessBuilder.Redirect.PIPE, redirectError)
            assertFalse(output.exists(), "stale temporary output should be removed before export")
            output.writeText("<trace-toc/>")
            "xctrace diagnostic output"
        }

        val xml =
            InstrumentsParser.exportXCTraceXml(
                Path.of("/tmp/input trace.trace"),
                "--toc",
                shell = shell,
                temporaryFile = { output },
            )

        assertEquals("<trace-toc/>", xml)
        assertFalse(output.exists(), "temporary export should be removed after it is read")
    }

    @Test
    fun `removes partial xctrace output before retrying a segfault`(
        @TempDir tempDir: Path,
    ) {
        val output = tempDir.resolve("export.xml")
        val shell = FakeShell()
        var attempts = 0
        shell.runCommandHandler = { command, _, _, _, _ ->
            attempts++
            assertFalse(output.exists(), "partial output should be removed before attempt $attempts")
            if (attempts == 1) {
                output.writeText("partial")
                throw ShellCommandException(command, 139, "segmentation fault")
            }
            output.writeText("<trace-query-result/>")
            ""
        }

        val xml =
            InstrumentsParser.exportXCTraceXml(
                Path.of("/tmp/input.trace"),
                "--xpath",
                "/trace-toc/run[1]",
                shell = shell,
                temporaryFile = { output },
            )

        assertEquals(2, attempts)
        assertEquals("<trace-query-result/>", xml)
        assertFalse(output.exists())
    }

    @Test
    fun `removes partial xctrace output after a terminal failure`(
        @TempDir tempDir: Path,
    ) {
        val output = tempDir.resolve("failed-export.xml")
        val shell = FakeShell()
        shell.runCommandHandler = { _, _, _, _, _ ->
            output.writeText("partial")
            throw IllegalStateException("xctrace failed")
        }

        assertThrows<IllegalStateException> {
            InstrumentsParser.exportXCTraceXml(
                Path.of("/tmp/input.trace"),
                "--toc",
                shell = shell,
                temporaryFile = { output },
            )
        }

        assertFalse(output.exists(), "partial output should be removed after a terminal failure")
    }
}
