package com.bromano.mobile.perf.profilers.perfetto

import com.bromano.mobile.perf.utils.Adb
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import perfetto.protos.PerfettoConfig
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefaultPerfettoConfigTest {
    @Test
    fun `createPerfettoConfig builds expected data sources and categories`() {
        val adb =
            mock<Adb> {
                on { shell(eq("atrace --list_categories | awk '{print $1;}'"), any(), any()) } doReturn (
                    "am\nwm\nMadeUp\n"
                )
            }

        val cfg = createPerfettoConfig("com.example.app", adb)

        // buffers: 2 (ring + discard)
        assertEquals(2, cfg.buffersCount)
        assertEquals(65536, cfg.getBuffers(0).sizeKb)
        assertEquals(PerfettoConfig.TraceConfig.BufferConfig.FillPolicy.RING_BUFFER, cfg.getBuffers(0).fillPolicy)
        assertEquals(PerfettoConfig.TraceConfig.BufferConfig.FillPolicy.RING_BUFFER, cfg.getBuffers(1).fillPolicy)

        val dataSourceNames = cfg.dataSourcesList.map { it.config.name }.toSet()
        assertTrue("linux.ftrace" in dataSourceNames)
        assertTrue("android.packages_list" in dataSourceNames)
        assertTrue("android.surfaceflinger.frametimeline" in dataSourceNames)
        assertTrue("android.power" in dataSourceNames)
        assertTrue("android.gpu.memory" in dataSourceNames)
        assertTrue("android.surfaceflinger.frame" in dataSourceNames)
        assertTrue("linux.sys_stats" in dataSourceNames)
        assertTrue("linux.process_stats" in dataSourceNames)
        assertTrue("linux.system_info" in dataSourceNames)
        assertTrue("track_event" in dataSourceNames)

        val ftrace = cfg.dataSourcesList.first { it.config.name == "linux.ftrace" }
        val atraceCfg = ftrace.config.ftraceConfig

        // categories should be intersection of ATRACE_CATEGORIES and returned set (am, wm)
        assertTrue(atraceCfg.atraceCategoriesList.contains("am"))
        assertTrue(atraceCfg.atraceCategoriesList.contains("wm"))
        // not present because not in ATRACE_CATEGORIES
        assertTrue(!atraceCfg.atraceCategoriesList.contains("MadeUp"))

        // atraceApps include lmkd and package
        assertTrue(atraceCfg.atraceAppsList.contains("lmkd"))
        assertTrue(atraceCfg.atraceAppsList.contains("com.example.app"))

        val trackEvent =
            cfg.dataSourcesList
                .first { it.config.name == "track_event" }
                .config
                .trackEventConfig
        assertEquals(listOf("rendering"), trackEvent.enabledCategoriesList)
        assertEquals(listOf("*"), trackEvent.disabledCategoriesList)
    }
}
