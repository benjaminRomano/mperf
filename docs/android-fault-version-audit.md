# Android version and DEX-fault audit

Verified 2026-09-05 using the existing standalone collector and fresh Kotlin mperf CLI captures. Counts below distinguish the runs; this is compatibility evidence, not an assertion that separate launches have identical behavior. No application login or account action was performed. No cache threshold was relaxed. Unless otherwise stated, artifact paths below are relative to `output/version-audit/`.

## Minimum supported Android version

The supplied ChatGPT APKM's actual base manifest declares minSdkVersion **32**, targetSdkVersion 37, package `com.openai.chatgpt`, version 1.2026.237 (2623711). Selected arm64-v8a, English and xxhdpi splits installed on a new rooted Google APIs Android 12L/API32 emulator. Normal installation on Android10/API29 failed with `INSTALL_FAILED_OLDER_SDK` (requires32; current29); bypassing that requirement would not be a valid compatibility test.

Evidence: `chatgpt-base-badging.txt`, `chatgpt-manifest.txt`, `chatgpt-base-sha256.txt`, `chatgpt-api29-install.txt`, `chatgpt-api32-install.txt`.

## Capture results

| Target | Result | Emitted faults / major | File-backed major | Strict cache immediately before launch | Loss |
| --- | --- | ---: | ---: | --- | --- |
| API29, kernel4.14.175, 4KiB; fixture | Native success | 8,113 / 25 | 25 | 0/1,363 pages,4files | 0 |
| API32, kernel5.10.101, 4KiB; fixture | Native success | 9,775 / 81 | 77 | 0/1,331 pages,3files | 0 |
| API32; ChatGPT | Correctly rejected before launch | — | — | 331 pages after install/settling;3 after reboot | No capture |

API29 and32 native loss checks use ring loss records; those kernels do not expose the newer independent PERF_FORMAT_LOST counter. No raw-record integrity or Perfetto data-loss errors were reported in the successful captures. Fixture screenshots were visually inspected and show the expected fixture activity. HTML reports and raw traces are retained in `api29-fixture-native/` and `api32-fixture-native/`.

The first two API32 ChatGPT attempts found the same331 residual APK pages. Exact mapping records show the resource ranges simultaneously mapped read-only by system_server and NexusLauncher. The opt-in mapped-APK helper advised14 precise ranges and all calls returned their requested byte lengths, but this does not guarantee eviction. Linux5.10 deliberately skips multiply mapped pages in this path. [Linux MADV_PAGEOUT implementation](https://github.com/torvalds/linux/blob/v5.10/mm/madvise.c#L413)

A normal reboot left only3 resident baseAPK pages and no corresponding process mappings in the subsequent read-only inventory; their remaining pin/refill source has not been established. APK splits, VDEX and ODEX were fully evicted. The collector still rejected3>0. No services were killed/disabled, and no partially warm report was substituted. See `api32-chatgpt-native-reboot/cache_residency.csv` and metadata; earlier exact holder ranges are in `api32-chatgpt-native-settled/mapped-apk-reclaim-after_drop.tsv`.

### Android10 DWARF limitation found

The first fixture DWARF attempt was correctly rejected for2 residual APK pages. After reboot, cache verification passed0/1,363. Android10's system Simpleperf then rejected the `--no-cut-samples` option before application launch. The recorder cleanup left no native collector, Simpleperf or Perfetto process running. No DWARF report was generated. The capture metadata remains at the last successful phase (`cache_verified_after_drop`), rather than recording a final recorder-error status: this is a metadata-quality gap in the standalone workflow.

Saved help confirms Android10 lacks `--no-cut-samples`; Android12L supports it. Do not silently remove that safeguard. A compatible modern Simpleperf binary or an explicit unsupported-capability error is needed for the optional companion. The native frame-pointer/address capture remains usable on29.

## Why ChatGPT has few DEX major faults on Android16

Reconciled capture: `output/e2e-chatgpt-strict-pageout` in the standalone analysis worktree. It passed strict0/5,497 16KiBpages across6appfiles, with zero native loss and integrity errors. It contains7,191 emitted app faults,720 major overall, and2,702 file-backed faults/270 file-backed major.

The seven DEX payloads remain uncompressed inside base.apk. The modern base.vdex (version027,1,478,852bytes) contains **zero embedded DEX payloads**; it has seven ART location checksums that all match the corresponding APK DEX entry checksums. Therefore the absent VDEX DEX-boundary lines are correct for this build: there are no embedded DEX starts to mark. The 541,536byte ODEX is compiled-code metadata/code, not a replacement container for all APK bytecode. ART artifacts can vary with compilation state and platform version. [AOSP ART artifact overview](https://source.android.com/docs/core/runtime/configure)

| APK entry | Emitted faults | Major | Cache-insertion events (16KiB pages) |
| --- | ---: | ---: | ---: |
| classes.dex |147|1|585|
| classes2.dex |6|1|17|
| classes3.dex |93|1|533|
| classes4.dex |100|1|727|
| classes5.dex |83|1|690|
| classes6.dex |67|1|450|
| classes7.dex |71|1|568|
| Total |567|7|3,570|

The original Perfetto trace explicitly contains **seven ART `madvising` spans**, each covering the corresponding complete DEX range. Their summed duration is approximately59.17ms. All seven initial major faults precede these spans;553 subsequent DEX minor faults occur after their respective prefetch span begins. This is stronger evidence than guessing readahead from a low major count. It does not establish a byte-perfect causal link from each insertion to a particular call, nor is summed span duration CPU time or physical-storage latency. See `chatgpt-madvise-slices.csv` and `chatgpt-api36-reconciliation.txt`.

Cache insertions are not fault events or read syscalls. They may represent prefetch/readahead, buffered reads or demand paging, including workers targeting exact app dev/inode pairs. A physical APK page may straddle entry boundaries, so cache/fault correlation must use whole-file page identity, not only an entry label. Every recorded DEX fault has a same-page insertion at/before its perf timestamp, including major faults: **that alone does not prove a pre-existing cache hit**, because major/minor perf events are emitted after fault handling has completed. [Linux4.14 arm64 fault handling](https://github.com/torvalds/linux/blob/v4.14/arch/arm64/mm/fault.c#L435), [Linux5.10 fault accounting](https://github.com/torvalds/linux/blob/v5.10/mm/memory.c#L4237)

## ART and kernel differences to retain in interpretation

- Android10's old per-DEX layout-advice path is gated on low-memory mode; it is not the modern universal whole-DEX prefetch path. [ART Android10](https://android.googlesource.com/platform/art/+/0f2487027ebaeb42abaa7e86a267f1d6992696be/runtime/oat_file.cc#2055)
- Android12L prefetches read-only VDEX and ODEX ranges using configured byte limits. The actual API32 fixture trace contains full-VDEX `madvising` spans, and its VDEX027 has one checksum-verified embedded DEX. [ART12L VDEX](https://android.googlesource.com/platform/art/+/ab1f62ad90952006e0a47cabdb3e5a3bcd07124e/runtime/vdex_file.cc#152), [ART12L ODEX](https://android.googlesource.com/platform/art/+/ab1f62ad90952006e0a47cabdb3e5a3bcd07124e/runtime/oat_file.cc#1850)
- Android16 prefetches eligible primary/split DEX payloads directly with a shared total byte budget; `MadviseFileForRange` issues MADV_WILLNEED in128KiB chunks and avoids background-process prefetch where process-state reporting is sufficiently reliable. [ART16 eligibility and DEX loop](https://android.googlesource.com/platform/art/+/ed6c006bd06ae060bd9698fd2cb25c4865512ec3/runtime/oat/oat_file_manager.cc#250), [ART16 implementation](https://android.googlesource.com/platform/art/+/ed6c006bd06ae060bd9698fd2cb25c4865512ec3/runtime/runtime.cc#3367)
- Current device properties on API32 and36 configure100MiB VDEX/ODEX budgets; they were read during this audit, not preserved at the earlier API36 capture time. Current ART APEX versions were also saved (API32:311310000; API36:360499999). SDK alone does not identify the exact ART implementation.
- Linux5.10/6.6 process fault counters include certain GUP-triggered faults without a saved register context that do not emit major/minor perf records. Therefore the emitted-event totals need not equal `/proc` fault counters even with zero recorder loss. A major can also reflect a retried fault; it is not an exact physical read count. [Linux6.6 accounting](https://github.com/torvalds/linux/blob/v6.6/mm/memory.c#L4823)

## What this means for optimization

Major-fault count alone is a poor measure of DEX ordering opportunity when ART prefetched tens of MiB first. Use the complete demanded-page sequence (including minor), verified per-DEX attribution, first-touch timing and cache/prefetch context together. File-relative page jumps identify locality patterns, not automatically a measured startup saving. Symbols and exact fault-stack identity remain necessary to explain which caller/class caused an individual demand; merely reading a DEX page is not evidence that every class stored on that page executed.

Reproduce numeric reconciliation with `audit_chatgpt.py` and `summarize_captures.py` using the standalone worktree's uv environment. `capture-summary.jsonl` is generated output. Raw evidence and generated reports live under `output/version-audit/` in the mperf worktree (ignored, not shipped in the PR). This audit changed only this documentation.


## Fresh Kotlin mperf verification

Both commands exited successfully and wrote reports with `processing_engine=kotlin` and `processing_status=complete`:

```bash
build/install/mperf/bin/mperf faults android \
  --package com.bromano.mperf.fixture --device emulator-5564 \
  --native-stacks --out output/version-audit/mperf-api29-fixture-native --no-open

build/install/mperf/bin/mperf faults android \
  --package com.bromano.mperf.fixture --device emulator-5566 \
  --native-stacks --reclaim-mapped-apks --reboot-before-collect \
  --out output/version-audit/mperf-api32-fixture-native --no-open
```

| Kotlin CLI run | Emitted faults | Major | Prelaunch resident pages | Callchain rows attached |
| --- | ---: | ---: | ---: | ---: |
| API29 | 8,106 | 24 | 0/1,363 | 8,106 |
| API32 | 10,948 | 23 | 0/1,331 | 10,948 |

Both had zero collector loss, callchain overflow and Perfetto integrity errors. A present native callchain does not imply every frame was symbolized or every Java caller was recovered. Different launch totals are not a version performance comparison: these are separate emulator configurations and compilation/cache histories.

### Fresh Android16 dual-recorder verification

Two bounded attempts on the AOSP API36/4KiB fixture tested the new Kotlin path with both `--native-stacks` and `--dwarf-stacks`:

1. `mperf-api36-fixture-dwarf/`, with `--reboot-before-collect`: cache eviction passed 0/3,105 pages, but recording readiness timed out before application launch. This exposed a Kotlin reader bug: line-based draining hid Simpleperf's seven-byte, newline-free `STARTED` marker. The reader was changed to chunk-based draining and an open-pipe regression passed. [Simpleperf readiness implementation](https://android.googlesource.com/platform/system/extras/+/e474f50680b5c938d7462172b949578699df2854/simpleperf/cmd_record.cpp)
2. `mperf-api36-fixture-dwarf-ready-fix/`, on the same settled emulator after rebuilding: readiness succeeded and the final prelaunch check again passed **0/3,105 pages across three files**. The native collector then reported **112 lost events**; Simpleperf reported **78 recorded / 167 lost**, all reported loss in kernel buffers. The command rejected the capture with `collector_integrity_failed`; no HTML report or exact DWARF match coverage was produced. The raw native trace, recorder log, metadata and explicitly named `simpleperf-lost.data` diagnostic are retained.

The retry command was:

```bash
build/install/mperf/bin/mperf faults android \
  --package com.bromano.mperf.fixture --device emulator-5560 \
  --native-stacks --dwarf-stacks \
  --out output/version-audit/mperf-api36-fixture-dwarf-ready-fix --no-open
```

No loss or residency limit was weakened, and no further retries were performed. Cleanup left no owned recorder process running. The earlier saved loss-free capture replays with 26/26 exact matches, but this fresh dual-recorder run **does not establish a loss-free live Kotlin capture**. It establishes that readiness and strict failure gates work, while recording reliability under this run's load remains unverified. The temporary API29/API32 emulators were stopped gracefully after their successful captures; AVDs and artifacts were preserved.

The earlier standalone compatibility commands were:

```bash
# AUDIT_DIR is the mperf worktree's output/version-audit directory.
uv run faults.py --package com.bromano.mperf.fixture --serial emulator-5564 \
  --output "$AUDIT_DIR/api29-fixture-native"
uv run faults.py --package com.bromano.mperf.fixture --serial emulator-5566 \
  --output "$AUDIT_DIR/api32-fixture-native"
uv run faults.py --package com.openai.chatgpt --serial emulator-5566 \
  --reclaim-mapped-apks --reboot-before-collect \
  --output "$AUDIT_DIR/api32-chatgpt-native-reboot"
uv run faults.py --package com.bromano.mperf.fixture --serial emulator-5564 \
  --dwarf-stacks --reboot-before-collect \
  --output "$AUDIT_DIR/api29-fixture-dwarf-reboot"
```

The last two commands intentionally retained hard failures described above; no report is claimed for either.

## Physical and non-rooted devices

Root is not inherently required for every kind of app profiling. On Android10 and newer, a release app explicitly marked profileable-from-shell can be profiled by approved system tools; debuggable apps have another supported route. Arbitrary unmodified release apps require root for this general workflow. [AOSP Simpleperf application profiling](https://android.googlesource.com/platform/system/extras/+/refs/heads/main/simpleperf/doc/android_application_profiling.md)

That app-scoped access is not equivalent to this collector's system-wide prelaunch events plus strict cross-process cache eviction. The global drop_caches interface is owner-write-only in Linux, and reclaiming clean pages is advisory rather than a guarantee that all app pages are absent. [Linux permission definition](https://github.com/torvalds/linux/blob/v6.6/kernel/sysctl.c#L2021), [cache-drop semantics](https://www.kernel.org/doc/html/latest/admin-guide/sysctl/vm.html#drop-caches)

A rooted physical device is a plausible supported target when its kernel perf/tracefs features and SELinux policy permit the same probes. The collector must validate those capabilities, online CPU identifiers, page size, event loss and actual app-file residency on that device. These emulator tests do not verify a physical handset and do not make its storage timings representative. A stock non-rooted handset cannot currently provide the complete strict-cold capture contract; an app-only, cache-state-unknown mode would be a separate capability, not an equivalent fallback.
