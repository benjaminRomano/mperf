package com.bromano.mobile.perf.faults

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IosFaultProcessorTest {
    @TempDir
    lateinit var output: Path

    @Test
    fun `filters exact pid and recommends only bundle-owned faulting binaries`() {
        Json.write(
            output.resolve("capture_metadata.json"),
            mapOf(
                "schema_version" to 1,
                "target_pid" to 42,
                "app_binary_name" to "Example",
                "app_bundle_root" to "/tmp/Example.app",
                "settle_seconds" to 3.0,
            ),
        )
        Files.writeString(
            output.resolve("virtual-memory.xml"),
            """
            <?xml version="1.0"?>
            <trace-query-result><node>
              <row>
                <start-time id="t1">1000000</start-time><duration>100</duration>
                <vm-op id="major" fmt="File Backed Page In">1</vm-op><address>4294971392</address>
                <size-in-bytes id="size">16384</size-in-bytes>
                <thread id="thread" fmt="Main"><tid>7</tid><process id="app" fmt="Example (42)"><pid>42</pid></process></thread>
                <process ref="app"/>
                <tagged-backtrace><backtrace>
                  <frame name="systemLeaf" addr="0x1"><binary name="System" path="/System/System"/></frame>
                  <frame name="appCaller" addr="0x2"><binary name="Example" path="/tmp/Example.app/Example"/></frame>
                </backtrace></tagged-backtrace>
              </row>
              <row>
                <start-time>2000000</start-time><duration>200</duration><vm-op ref="major"/>
                <address>4294987776</address><size-in-bytes ref="size"/><thread ref="thread"/><process ref="app"/>
                <tagged-backtrace><backtrace>
                  <frame name="frameworkLeaf" addr="0x3"><binary name="Feature" path="/tmp/Example.app/Frameworks/Feature.framework/Feature"/></frame>
                  <frame name="appCaller" addr="0x4"><binary name="Example" path="/tmp/Example.app/Example"/></frame>
                </backtrace></tagged-backtrace>
              </row>
              <row>
                <start-time>3000000</start-time><duration>200</duration><vm-op fmt="Page Cache Hit">2</vm-op>
                <address>4295004160</address><size-in-bytes ref="size"/><thread ref="thread"/><process ref="app"/>
                <tagged-backtrace><backtrace>
                  <frame name="otherLeaf" addr="0x5"><binary name="Feature" path="/tmp/Other.app/Frameworks/Feature.framework/Feature"/></frame>
                </backtrace></tagged-backtrace>
              </row>
              <row>
                <start-time>4000000</start-time><duration>200</duration><vm-op ref="major"/>
                <address>4295020544</address><size-in-bytes ref="size"/>
                <thread fmt="Other"><tid>8</tid><process id="other" fmt="Example (420)"><pid>420</pid></process></thread>
                <process ref="other"/>
              </row>
            </node></trace-query-result>
            """.trimIndent(),
        )

        IosFaultProcessor().process(output)

        val events = Csv.read(output.resolve("page_fault_events.csv"))
        val summaries = Csv.read(output.resolve("major_page_fault_code_summary.csv"))
        assertEquals(3, events.size)
        assertEquals(setOf("42"), events.map { it["pid"] }.toSet())
        assertEquals(listOf("systemLeaf [System]", "appCaller [Example]"), events.first().getValue("stack").split(" ← "))
        assertEquals("false", events[0]["faulting_binary_is_bundle_owned"])
        assertEquals("true", events[1]["faulting_binary_is_bundle_owned"])
        assertEquals("false", events[2]["faulting_binary_is_bundle_owned"])
        assertEquals(1, summaries.size)
        assertEquals("frameworkLeaf", summaries.single()["faulting_frame"])
        assertFalse(summaries.any { it["faulting_frame"] == "systemLeaf" })
        assertTrue(Json.readMap(output.resolve("capture_metadata.json"))["processing_engine"] == "kotlin")
    }

    @Test
    fun `physical fallback does not claim an unrelated app framework`() {
        val capture = output.resolve("physical")
        Files.createDirectories(capture)
        Json.write(
            capture.resolve("capture_metadata.json"),
            mapOf(
                "schema_version" to 1,
                "target_pid" to 42,
                "app_binary_name" to "Example",
                "app_bundle_root" to "",
                "settle_seconds" to 1.0,
            ),
        )
        Files.writeString(
            capture.resolve("virtual-memory.xml"),
            """
            <?xml version="1.0"?>
            <trace-query-result><node><row>
              <start-time>1000000</start-time><duration>100</duration>
              <vm-op fmt="File Backed Page In">1</vm-op><address>4294971392</address>
              <size-in-bytes>16384</size-in-bytes>
              <thread fmt="Main"><tid>7</tid><process fmt="Example (42)"><pid>42</pid></process></thread>
              <process fmt="Example (42)"><pid>42</pid></process>
              <tagged-backtrace><backtrace>
                <frame name="unrelatedLeaf" addr="0x1"><binary name="Feature" path="/tmp/Other.app/Frameworks/Feature.framework/Feature"/></frame>
              </backtrace></tagged-backtrace>
            </row></node></trace-query-result>
            """.trimIndent(),
        )

        IosFaultProcessor().process(capture)

        val event = Csv.read(capture.resolve("page_fault_events.csv")).single()
        assertEquals("false", event["faulting_binary_is_bundle_owned"])
        assertTrue(Csv.read(capture.resolve("major_page_fault_code_summary.csv")).isEmpty())
    }
}
