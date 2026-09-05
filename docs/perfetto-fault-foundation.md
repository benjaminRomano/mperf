# Perfetto as the startup-analysis foundation

Perfetto should own the common timeline, scheduler state, ART spans, page-cache activity, and guest block-I/O context. That does not require reconstructing every fault from ftrace. The current mperf architecture is hybrid: Perfetto supplies context and startup boundaries; a small native perf recorder supplies exact fault addresses and event-time file mappings.

## What stock tools can capture

| Source | Exact virtual fault address | Fault kind | File identity | Stack |
|---|---|---|---|---|
| mperf native perf recorder | Yes | Separate emitted MIN/MAJ events | Timestamped MMAP2, including data mappings | Native/kernel callchain |
| Perfetto `linux.perf` (audited v51.2) | Not requested/serialized | MIN/MAJ counters, period 1 | Frame mappings for unwinding, not a complete data-mapping lifecycle | DWARF/native |
| Stock AOSP Simpleperf | ADDR absent from default attributes | Separate MIN/MAJ events | Mapping records; data-map coverage needs verification | DWARF/native |
| Upstream Linux `perf record -d` | Requested | Separate MIN/MAJ events | Enables data mappings | DWARF with the appropriate build dependencies |
| Observed F2FS ftrace fault event | No | Handler return flags | F2FS device/inode/page index | No stack in the event |

In Perfetto v51.2, the producer requests TID, time and counter reads, optionally adding user registers/stack bytes and kernel callchains. It does not request `PERF_SAMPLE_ADDR`; `PerfSample` has no fault-address field. A config change or newer host Trace Processor cannot recreate information absent from the on-device recording. [Producer](https://github.com/google/perfetto/blob/v51.2/src/profiling/perf/event_config.cc), [sample schema](https://github.com/google/perfetto/blob/v51.2/protos/perfetto/trace/profiling/profile_packet.proto)

This is a tool-contract limitation, not a fundamental incompatibility between DWARF and fault addresses. Upstream `perf` supports address sampling plus user-stack/register sampling in the same record. A compatible Android build of `perf`, a small Simpleperf extension, or a Perfetto producer/schema extension could replace the custom recorder. These alternatives have not been packaged or end-to-end qualified by this patch. [Linux perf attributes](https://github.com/torvalds/linux/blob/v6.12/tools/perf/util/evsel.c), [AOSP Simpleperf attributes](https://android.googlesource.com/platform/system/extras/+/refs/heads/main/simpleperf/event_attr.cpp)

## Why ftrace alone is not equivalent

On the inspected Android 16 ARM64 emulator, `perfetto --version` reports v49.0 and `linux.perf`, `linux.ftrace`, and `linux.process_stats` are registered. Its actual `available_events` has no generic userspace exception/page-fault event. It does expose `f2fs:f2fs_filemap_fault` with `dev`, `ino`, `index`, `flags`, and `ret`. This is useful filesystem-specific evidence, but it lacks a virtual address and does not cover all filesystems or all fault paths. Handler retries and fault-around also differ from emitted perf MIN/MAJ events.

The x86 `exceptions:page_fault_user` tracepoint includes address, IP and CPU exception error code. That error code is not the major/minor outcome, and this tracepoint is not a portable ARM64 interface. Page-cache insertion events are not substitute faults: prefetch and ordinary reads can insert pages without faulting. [x86 tracepoint](https://github.com/torvalds/linux/blob/v6.12/arch/x86/include/asm/trace/exceptions.h), [F2FS tracepoint](https://github.com/torvalds/linux/blob/v6.12/include/trace/events/f2fs.h)

Stock Perfetto kprobe configuration records function entry/return identity, not arbitrary argument/return-value payloads. Manual dynamic probes can expose more, but become custom kernel-ABI-dependent instrumentation and still need user-stack unwinding. That is not an automatic simplification over standard `perf_event_open`. [Kprobe config](https://github.com/google/perfetto/blob/main/protos/perfetto/config/ftrace/ftrace_config.proto), [kprobe record](https://github.com/google/perfetto/blob/main/protos/perfetto/trace/ftrace/generic.proto)

## Recommended direction

1. Keep Perfetto central. `--io-evidence` already adds available scheduler/block events to the same recording and exports synchronized thread states, advice, and block events. Reuse Perfetto for dependency and stall analysis; keep the fault reader for address/locality exploration.
2. Prototype one exact address + DWARF recorder using compatible upstream `perf` or minimally extended Simpleperf. Start it system-wide before launch, not by attaching after the app PID appears. Do not replace the current collector until earliest-event coverage, full data mappings, clock alignment, loss handling, and stack fidelity match on Android 10 and 16.
3. Prefer importing the exact events into the common Perfetto timeline over maintaining competing notions of startup time. Any producer/importer must preserve raw identity, address, event kind, mapping provenance, and incomplete-stack/loss diagnostics.
4. Keep strict cache residency verification separate. Neither Perfetto nor Simpleperf makes `drop_caches` a proof that all app pages are evicted. The native `mincore`/mapped-page reclamation helper still has a distinct job.

CPU samples near a fault can provide context, but must not be relabeled as that fault's exact stack. Ordinary page faults are exceptions caused by memory access, not syscalls. A syscall can initiate reads or readahead without generating the equivalent number of major faults.

Device permissions remain independent of recorder choice: general system tracing may be available on production devices, while full cross-process fault capture, stack access, and strict global cache eviction can require root/debuggable builds. Switching to Perfetto does not bypass those restrictions.
