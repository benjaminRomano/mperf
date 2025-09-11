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

        // dataSources: expect linux.ftrace + frametimeline + android.power + linux.sys_stats + linux.process_stats
        assertTrue(cfg.dataSourcesCount >= 5)

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
    }
}
