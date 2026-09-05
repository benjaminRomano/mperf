package com.bromano.mobile.perf.faults

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedFaultReportTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `stack dictionaries preserve every event and full frame identity`() {
        val frame = mapOf("label" to "read", "file" to "/app/a", "kind" to "user")
        val other = frame + ("file" to "/app/b")
        val run =
            mapOf(
                "events" to
                    listOf(
                        mapOf("id" to 1, "stack" to listOf(frame)),
                        mapOf("id" to 2, "stack" to listOf(frame)),
                        mapOf("id" to 3, "stack" to listOf(other)),
                        mapOf("id" to 4, "stack" to emptyList<Any>()),
                    ),
            )
        val packed = SharedFaultReport(temporaryDirectory).internStacks(run)
        assertEquals(listOf(frame, other), packed["frames"])
        assertEquals(listOf(listOf(0), listOf(1), emptyList<Int>()), packed["stacks"])
        val events = packed["events"] as List<*>
        assertEquals(4, events.size)
        assertEquals(listOf(0, 0, 1, 2), events.map { (it as Map<*, *>)["stackId"] })
        assertEquals(listOf(1, 2, 3, 4), events.map { (it as Map<*, *>)["id"] })
    }

    @Test
    fun `offline report escapes trace data without recursively replacing templates`() {
        val engine = BundledFaultEngine(temporaryDirectory.resolve("cache")).materialize()
        val report = temporaryDirectory.resolve("report.html")
        val malicious = "</script><script>alert(1)</script>__SCRIPT__&"
        SharedFaultReport(engine).write(
            listOf(mapOf("label" to malicious, "events" to emptyList<Any>(), "sources" to emptyMap<String, Any>())),
            report,
            "<Trace> & report",
        )
        val html = Files.readString(report)
        assertTrue(html.contains("<title>&lt;Trace&gt; &amp; report</title>"))
        assertTrue(html.contains("\\u003c/script\\u003e"))
        assertTrue(html.contains("__SCRIPT__\\u0026"))
        assertFalse(html.contains(malicious))
        assertFalse(html.contains("<script src="))
        assertTrue(html.contains("const REPORT ="))
        assertTrue(html.contains("id=\"detailDock\""))
        assertTrue(html.contains("height: 100dvh"))
        assertTrue(html.contains("Callers at top; faulting frames below"))
    }

    @Test
    fun `bundled fault runtime contains no Python engines`() {
        val engine = BundledFaultEngine(temporaryDirectory).materialize()
        Files.walk(engine).use { paths ->
            assertFalse(paths.anyMatch { it.fileName.toString().endsWith(".py") })
        }
    }
}
