package com.bromano.mobile.perf.gecko

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.xml.sax.SAXParseException
import kotlin.test.assertEquals

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
}
