# Startup I/O: measure demand, traffic, and stalls separately

The fault viewer is a locality tool, not a disk-I/O profiler. Keep its exact addresses, source files, and stacks, but add a synchronized I/O timeline before using it to diagnose storage pressure or predict startup savings. Use verified `speed-profile` compilation for the representative app experiment; full `speed` remains a separate diagnostic condition.

## What the current capture establishes

Audited 2026-09-05: `android/ftrace.config` records ART trace spans and page-cache insertions, but no block or scheduling events. A read-only query of `output/chatgpt-compilation-reset-settled/faults.pftrace` found:

- Seven DEX `madvising` spans describing 58,523,648 bytes (55.8125 MiB), with 26.615 ms summed span duration, plus one ODEX span. These are the ranges ART says it advised, not measured read bytes or completion latency.
- 15,789 system-wide cache-insertion events in the complete trace; the existing app/startup-filtered CSV contains 4,770. These scopes must not be mixed.
- No block events and no `sched` rows. The missing evidence cannot be recovered by reprocessing this trace.

The existing cache query correctly includes application-thread insertions and any thread targeting exact app-owned device/inode pairs, retaining kernel workers through left joins. Do not replace that with a process-only I/O filter.

## Four synchronized layers

| Layer | Useful measurement | Does not establish |
|---|---|---|
| ART advice | Named range, requested bytes, call interval | Bytes read, completion time, or storage wait |
| Page cache | Inserted file-offset ranges, folio size, insertion time, emitting thread | Successful read completion or which caller caused I/O |
| Guest block device | Read issue/completion bytes over time, selected device, errors; qualified outstanding work | NAND reads, exact file identity, or app critical-path cost |
| Application demand and scheduling | Fault address/source/stack; thread running, runnable, sleeping and blocked intervals | Every memory access, or proof that every blocked interval is disk I/O |

For ordinary file mappings, `MADV_WILLNEED` requests kernel readahead into the page cache. Reads can be initiated without a major fault; later accesses can fault minor. Insertion precedes readiness, and readahead may fail or leave work to a later demand. Therefore, neither “minor” nor “insertion before the recorded fault timestamp” proves an I/O-free access. [Linux madvise](https://github.com/torvalds/linux/blob/v6.12/mm/madvise.c), [readahead](https://github.com/torvalds/linux/blob/v6.12/mm/readahead.c)

## Smallest useful implementation

The optional I/O-context helper extends the existing Perfetto session, not a second unsynchronized recorder, while preserving the fault CSV contract. It records event-format capability evidence and exports `io_advice_spans.csv`, `io_block_events.csv`, and `io_thread_states.csv`. Unknown interval ends remain incomplete with no invented duration. The following describes the capture contract and the next report layer:

1. Before cache eviction, probe available tracepoints and save the selected events and their `format` files. Request `sched/sched_switch`, `sched/sched_waking`, and `sched/sched_blocked_reason` where available. Add `block/block_rq_issue`, `block/block_rq_complete`, `block/block_rq_requeue`, and `block/block_rq_remap` where supported. Record unsupported versus denied versus enabled-but-empty distinctly. Additional `block_io_start`/`block_io_done` events can support a modern-kernel queue view after capability validation. Do not infer support solely from Android API level.
2. Export raw block events and separate per-device **read bytes issued** and **read bytes completed** time bins. Keep reads and writes distinct. Sectors in these block tracepoints represent 512-byte units, not the device's VM page size. Reissues count as attempts, not unique bytes. Collect system-wide activity: unrelated I/O can be the contention affecting this app.
3. Export app thread-state intervals on the same startup time axis. Show main-thread I/O-wait annotations only when available; `D` alone means uninterruptible sleep, not necessarily disk. Follow startup dependencies onto workers or Binder services before claiming critical-path cost. Running/runnable time separates CPU work from scheduling delay. [Perfetto scheduling](https://perfetto.dev/docs/data-sources/cpu-scheduling)
4. Reuse the existing time-aligned report with compact lanes: ART advice, cache insertions by file, guest-device read traffic, main-thread state, and fault demand. Keep the selected-fault/source inspection. Initially link to Perfetto for dependency analysis instead of inventing a single “I/O time” score.

The raw evidence exports are implemented; the synchronized I/O visualization and derived time bins remain proposed. Existing traces without these events cannot gain block/scheduler coverage by reprocessing. Event formats establish availability, not successful activation; actual observed events and recorder diagnostics must also be checked.

## Attribution and latency guardrails

Block events identify a device and sector range, not a device/inode pair. The issuing task may be a worker. A timestamp or PID match must not become an exact APK/DEX attribution. Device-mapper layers can report the same logical transfer more than once: select one layer for totals and preserve remap context. A later filesystem-specific probe or validated extent map could connect file offsets to sectors, but must account for changed extents, compression, encryption, metadata and remapping. FIEMAP flags can explicitly mark unsupported/unknown mappings. [Linux FIEMAP](https://www.kernel.org/doc/html/latest/filesystems/fiemap.html)

Do not pair each issue with the next completion sharing a sector. Requests can overlap, merge, split, requeue, and complete partially; the common event schema lacks a stable request identifier. `block_rq_complete` can describe only part of a request. Simple issue-count minus completion-count is not queue depth. [Linux block tracepoints](https://github.com/torvalds/linux/blob/v6.12/include/trace/events/block.h), [Perfetto block schema](https://github.com/google/perfetto/blob/v51.2/protos/perfetto/trace/ftrace/block.proto)

Perfetto v51.2, already pinned by mperf, provides `linux_active_block_io_operations_by_device` for `block_io_start`/`done` traces. Use it as qualified queue/device context only after checking completeness and trace-boundary effects, not as a source of exact per-request or per-file latency. Its parser constructs device-level slices without a unique request key. [SQL module](https://github.com/google/perfetto/blob/v51.2/src/trace_processor/perfetto_sql/stdlib/linux/block_io.sql), [parser](https://github.com/google/perfetto/blob/v51.2/src/trace_processor/importers/ftrace/ftrace_parser.cc#L3762)

## Verification and performance claims

- Capture readiness must precede launch. Keep a short tail for I/O completion, but clip headline demand and startup metrics to the same explicit startup window.
- Check per-CPU ftrace overruns, producer/central-buffer loss, parser errors, unmatched intervals and boundary truncation. Missing events mean unavailable/incomplete, not zero I/O. Adding scheduler/block events increases trace volume; validate buffers under the new workload. [Perfetto buffering](https://perfetto.dev/docs/concepts/buffers)
- Optional `/proc/pressure/io` total deltas quantify system-wide time affected by I/O stalls; they are not app-attributed latency. Do not use its 10-second average as a millisecond startup trace. [Linux PSI](https://www.kernel.org/doc/html/latest/accounting/psi.html)
- Guest cache eviction verifies guest residency only. Emulator block completions can be served by the host cache; physical devices still have controller caches and internal flash behavior. Label the layer measured, never “physical media bytes.”
- Repeat matched `speed-profile`, build/profile, device, app-data, cache and instrumentation conditions. Compare startup duration alongside guest read traffic and dependency-chain stalls. Fewer faults or bytes alone is an optimization hypothesis, not a measured startup saving.

Targeted tests for the extension should cover missing/empty event capabilities, 512-byte sectors on 16 KiB-page devices, read/write and device-layer separation, reissues/partial completions, unknown task owners, loss and truncated intervals, plus common time-window clipping.
