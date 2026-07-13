package com.bromano.mobile.perf.profilers.perfetto

import com.bromano.mobile.perf.utils.Adb
import perfetto.protos.FtraceConfigKt.compactSchedConfig
import perfetto.protos.PerfettoConfig
import perfetto.protos.PerfettoConfig.TraceConfig.BufferConfig.FillPolicy
import perfetto.protos.TraceConfigKt.bufferConfig
import perfetto.protos.TraceConfigKt.dataSource
import perfetto.protos.androidPowerConfig
import perfetto.protos.dataSourceConfig
import perfetto.protos.ftraceConfig
import perfetto.protos.processStatsConfig
import perfetto.protos.sysStatsConfig
import perfetto.protos.traceConfig
import perfetto.protos.trackEventConfig

val ATRACE_CATEGORIES =
    setOf(
        "adb",
        "aidl",
        "am",
        "audio",
        "bionic",
        "binder_driver",
        "camera",
        "core_services",
        "dalvik",
        "disk",
        "gfx",
        "freq",
        "hal",
        "input",
        "idle",
        "memreclaim",
        "network",
        "nnapi",
        "pdx",
        "pm",
        "power",
        "res",
        "rro",
        "rs",
        "sched",
        "sm",
        "ss",
        "sync",
        "vibrator",
        "video",
        "view",
        "webview",
        "wm",
    )

// Largely copied from the Perfetto config used by Macrobenchmark
fun createPerfettoConfig(
    packageName: String,
    adb: Adb,
): PerfettoConfig.TraceConfig =
    traceConfig {
        fileWritePeriodMs = 2500
        flushPeriodMs = 5000
        // reduce timeout to reduce trace capture overhead when devices have data source issues
        // See b/32601788 and b/307649002
        dataSourceStopTimeoutMs = 2500
        writeIntoFile = true

        // NOTE: Order matters for buffers

        buffers +=
            bufferConfig {
                sizeKb = 65536
                fillPolicy = FillPolicy.RING_BUFFER
            }
        // Used for storing processes
        buffers +=
            bufferConfig {
                sizeKb = 4096
                fillPolicy = FillPolicy.RING_BUFFER
            }

        dataSources +=
            dataSource {
                config =
                    dataSourceConfig {
                        name = "linux.ftrace"
                        targetBuffer = 0
                        ftraceConfig =
                            ftraceConfig {
                                ftraceEvents.addAll(
                                    listOf(
                                        "binder/binder_transaction",
                                        "binder/binder_transaction_received",
                                        "binder/binder_transaction_alloc_buf",
                                        "ftrace/print",
                                        "filemap/mm_filemap_add_to_page_cache",
                                        "filemap/mm_filemap_remove_from_page_cache",
                                        "ion/ion_stat",
                                        "kmem/ion_heap_grow",
                                        "kmem/ion_heap_shrink",
                                        "kmem/rss_stat",
                                        "lowmemorykiller/lowmemory_kill",
                                        "mm_event/mm_event_record",
                                        "oom/oom_score_adj_update",
                                        "power/suspend_resume",
                                        "power/cpu_frequency",
                                        "power/cpu_idle",
                                        "disk",
                                        "ufs/ufshcd_clk_gating",
                                        "sched/sched_process_exit",
                                        "sched/sched_process_free",
                                        "sched/sched_switch",
                                        "sched/sched_wakeup",
                                        "sched/sched_wakeup_new",
                                        "sched/sched_waking",
                                        "sched/sched_blocked_reason",
                                        "task/task_newtask",
                                        "task/task_rename",
                                    ),
                                )

                                atraceCategories.addAll(ATRACE_CATEGORIES.intersect(getAtraceCategories(adb)))
                                // Wildcard app matching is reliable on API 29+, and captures short-lived
                                // secondary processes without exhausting atrace's package allowlist.
                                atraceApps.addAll(if (adb.sdkVersion >= 29) listOf("*") else listOf(packageName))
                                compactSched = compactSchedConfig { enabled = true }
                            }
                    }
            }

        dataSources +=
            dataSource {
                config =
                    dataSourceConfig {
                        name = "android.packages_list"
                        targetBuffer = 1
                    }
            }

        dataSources +=
            dataSource {
                config =
                    dataSourceConfig {
                        name = "android.surfaceflinger.frametimeline"
                    }
            }

        dataSources +=
            dataSource {
                config =
                    dataSourceConfig {
                        name = "android.power"
                        androidPowerConfig =
                            androidPowerConfig {
                                batteryPollMs = 250
                                batteryCounters.addAll(
                                    listOf(
                                        PerfettoConfig.AndroidPowerConfig.BatteryCounters.BATTERY_COUNTER_CAPACITY_PERCENT,
                                        PerfettoConfig.AndroidPowerConfig.BatteryCounters.BATTERY_COUNTER_CHARGE,
                                        PerfettoConfig.AndroidPowerConfig.BatteryCounters.BATTERY_COUNTER_CURRENT,
                                    ),
                                )
                                collectPowerRails = true
                            }
                    }
            }

        dataSources +=
            dataSource {
                config =
                    dataSourceConfig {
                        name = "android.gpu.memory"
                    }
            }

        dataSources +=
            dataSource {
                config =
                    dataSourceConfig {
                        name = "android.surfaceflinger.frame"
                    }
            }

        dataSources +=
            dataSource {
                config =
                    dataSourceConfig {
                        name = "linux.sys_stats"
                        sysStatsConfig =
                            sysStatsConfig {
                                meminfoPeriodMs = 1000
                                meminfoCounters.addAll(
                                    listOf(
                                        PerfettoConfig.MeminfoCounters.MEMINFO_MEM_TOTAL,
                                        PerfettoConfig.MeminfoCounters.MEMINFO_MEM_FREE,
                                        PerfettoConfig.MeminfoCounters.MEMINFO_MEM_AVAILABLE,
                                        PerfettoConfig.MeminfoCounters.MEMINFO_BUFFERS,
                                        PerfettoConfig.MeminfoCounters.MEMINFO_CACHED,
                                        PerfettoConfig.MeminfoCounters.MEMINFO_SWAP_CACHED,
                                        PerfettoConfig.MeminfoCounters.MEMINFO_ACTIVE,
                                        PerfettoConfig.MeminfoCounters.MEMINFO_INACTIVE,
                                        PerfettoConfig.MeminfoCounters.MEMINFO_UNEVICTABLE,
                                        PerfettoConfig.MeminfoCounters.MEMINFO_SWAP_TOTAL,
                                        PerfettoConfig.MeminfoCounters.MEMINFO_SWAP_FREE,
                                        PerfettoConfig.MeminfoCounters.MEMINFO_DIRTY,
                                        PerfettoConfig.MeminfoCounters.MEMINFO_WRITEBACK,
                                        PerfettoConfig.MeminfoCounters.MEMINFO_ANON_PAGES,
                                        PerfettoConfig.MeminfoCounters.MEMINFO_MAPPED,
                                        PerfettoConfig.MeminfoCounters.MEMINFO_SHMEM,
                                    ),
                                )
                            }
                    }
            }

        dataSources +=
            dataSource {
                config =
                    dataSourceConfig {
                        name = "linux.process_stats"
                        targetBuffer = 1
                        processStatsConfig =
                            processStatsConfig {
                                scanAllProcessesOnStart = true
                                procStatsPollMs = 10000
                            }
                    }
            }

        dataSources +=
            dataSource {
                config =
                    dataSourceConfig {
                        name = "linux.system_info"
                        targetBuffer = 1
                    }
            }

        dataSources +=
            dataSource {
                config =
                    dataSourceConfig {
                        name = "track_event"
                        trackEventConfig =
                            trackEventConfig {
                                // androidx.tracing currently records SDK events in the rendering
                                // category, including coroutine-aware spans in 2.x.
                                enabledCategories.add("rendering")
                                disabledCategories.add("*")
                            }
                    }
            }
    }

private fun getAtraceCategories(adb: Adb): Set<String> = adb.shell("atrace --list_categories | awk '{print \$1;}'").lines().toSet()
