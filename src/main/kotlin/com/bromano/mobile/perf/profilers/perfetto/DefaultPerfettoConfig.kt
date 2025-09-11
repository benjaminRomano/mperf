package com.bromano.mobile.perf.profilers.perfetto

import com.bromano.mobile.perf.utils.Adb
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

val ATRACE_CATEGORIES =
    setOf(
        "adb",
        "aidl",
        "am",
        "audio",
        "bionic",
        "camera",
        "core_services",
        "dalvik",
        "disk",
        "gfx",
        "hal",
        "input",
        "network",
        "nnapi",
        "pdx",
        "pm",
        "power",
        "res",
        "rro",
        "rs",
        "sm",
        "ss",
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
                fillPolicy = FillPolicy.DISCARD
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

                                // TODO: Should `*` be added here?
                                atraceCategories.addAll(ATRACE_CATEGORIES.intersect(getAtraceCategories(adb)))
                                atraceApps.addAll(listOf("lmkd", packageName))
                            }
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
                                collectPowerRails = true
                                collectEnergyEstimationBreakdown = true
                            }
                    }
            }

        dataSources +=
            dataSource {
                config =
                    dataSourceConfig {
                        name = "linux.sys_stats"
                        sysStatsConfig =
                            sysStatsConfig {
                                statPeriodMs = 1000
                                statCounters.addAll(
                                    listOf(
                                        PerfettoConfig.SysStatsConfig.StatCounters.STAT_CPU_TIMES,
                                        PerfettoConfig.SysStatsConfig.StatCounters.STAT_FORK_COUNT,
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
                                procStatsPollMs = 1000
                            }
                    }
            }
    }

private fun getAtraceCategories(adb: Adb): Set<String> = adb.shell("atrace --list_categories | awk '{print \$1;}'").lines().toSet()
