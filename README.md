# mperf — Mobile Performance CLI

`mperf` is a CLI tool for collecting and visualizing profiler data from Android and iOS devices. It provides a unified interface over
platform profilers and supports collection over both ad-hoc app sessions and single-iterations of **Macrobenchmark** tests.

## Features

**Supported Profilers**

- Android Runtime Method Traces
- Perfetto
- Simpleperf
- Instruments
- Startup page faults on Android and iOS

**Collection Modes**

- Arbitrary app session profiling (Android & iOS)
- Single Macrobenchmark iteration profiling (Android)

**Visualization**

- Instruments
- Perfetto UI
- Firefox Profiler

## Requirements

- Java 21+
- Android SDK Platform‑Tools (`adb` on PATH)
- `tar` and `gzip` for installation
- Python 3 for the installer helper, Simpleperf Firefox conversion, and optional trace server; not needed by `faults`
- Full Xcode installation with an active developer directory (for `xctrace`, Instruments, and Simulator)
- macOS or Linux

## Install

The CLI can be installed using the installation helper. Once ran `mperf`, `aperf`, and `iperf` commands will be added to your PATH.

```
curl -fsSL https://raw.githubusercontent.com/benjaminromano/mperf/refs/heads/main/scripts/install.sh | bash
```

To remove the CLI and launcher aliases, run the companion script:

```
curl -fsSL https://raw.githubusercontent.com/benjaminromano/mperf/refs/heads/main/scripts/uninstall.sh | bash
```

### Manual Installation

Alternatively, download the latest JAR from [GitHub Releases](https://github.com/benjaminromano/mperf/releases) and add the following snippet to your POSIX shell profile (for example `~/.zshrc` or `~/.bashrc`). Update `MPERF_JAR` to the location where you stored the download.

```bash
# mperf CLI manual installation
MPERF_JAR="$HOME/tools/mperf/mperf-<version>-all.jar"

if [ -f "$MPERF_JAR" ]; then
  alias mperf="java -jar \"$MPERF_JAR\""
  alias aperf='mperf android'
  alias iperf='mperf ios'
fi
```

Reload your shell (`source ~/.zshrc`, `source ~/.bashrc`, etc.) and run `mperf --help` to verify.

## Quickstart

**Android: ad-hoc Perfetto session for an app**

```bash
# Defaults to Perfetto collection
aperf start -p com.example.app
```

Press any key to stop tracing. When done, the trace opens in Perfetto UI by default; use `--ui firefox` or `--ui instruments` (iOS) to pick a different viewer.

**Android: run a single performance test iteration and pull the trace**

```bash
aperf collect -p com.example.app -i com.example.macrobenchmark/androidx.test.runner.AndroidJUnitRunner -t LoginBenchmark#loginByIntent
```

**Note:** If you omit the device or instrumentation runner, the CLI lists choices interactively.

**iOS: collect an Instruments session for an app**

```bash
iperf start -b com.example.app --template "Time Profiler" --ui instruments

```

The trace can be opened directly in Instruments, or exported for Firefox Profiler / Perfetto via the `--ui` flag.

**Android: collect and visualize startup page faults**

```bash
mperf faults android -p com.example.app --reboot-before-collect
```

**iOS Simulator: collect startup VM faults and stacks**

```bash
mperf faults ios --app path/to/MyApp.app --require-cold-cache --allow-host-pressure
```

## Usage

Full usage details can be found in [CLI Reference Docs](/docs/cli.md)

### Top-level commands

- `ios start` — record an Instruments session for a running app
- `android start` — record an ad-hoc session for a running Android app
- `android collect` — run a single Macrobenchmark test iteration and collect a trace
- `faults android` — collect exact Android startup faults and generate an interactive report
- `faults ios` — collect iOS startup VM events and stacks and generate an interactive report

### Startup Page Faults

`mperf faults` captures fault order, file and section attribution, major/minor classification, cache evidence, and
interactive Plotly visualizations. Reports are written to `artifacts/faults/` by default. The first run extracts a
versioned analysis engine into `~/.mperf/cache/faults-engine/`. Capture orchestration, trace preprocessing, VDEX/DEX
validation, Instruments XML parsing, and report generation run in Kotlin. No Python or `uv` runtime is needed for
either fault command. Android and iOS use the same offline viewer and bundled Plotly assets.
Android preprocessing downloads the host's pinned Perfetto v58.2 native trace processor on first use, verifies its
size and SHA-256, and reuses the verified cache afterward. That first download requires network access.

The report stays within the browser viewport. Sources, plots, and the collapsible selected-fault dock scroll
independently. Stack charts start with all matching faults visible, with callers at the top and faulting frames below.
Pinch (or Ctrl+wheel) zooms around the pointer; WASD pans without changing selection; Escape resets the viewport.
The fault list retains one row per event: click a blue frame name to inspect its complete captured stack.
Android's all-source view excludes anonymous/unattributed pages from the analysis panels while retaining raw capture
totals. File-page coordinates are available only for an individual source; the Files axis compares separate files.

On Android, the collector uses kernel `perf_event_open` page-fault events and `PERF_RECORD_MMAP2` mappings. It
attributes each fault to the mapped file and file offset, including APK entries and whole ODEX/VDEX files when pulled
artifacts are available. Android 10 and modern sectioned VDEX files are supported. Original `classes*.dex` boundaries
are labeled only when every ART-stored DEX location checksum matches the corresponding APK entry; an unverified VDEX
is never guessed. Page-cache events include the app process and background or kernel-worker insertions targeting the
exact device/inode identities of app-owned files. These events are correlated I/O evidence, not proof that a specific
cache insertion caused a later fault.

The per-fault rows come from userspace events actually delivered by the perf subsystem. They are not reconstructed
from `/proc/<pid>/stat` or process-level `min_flt`/`maj_flt` counters, so the report can contain fewer rows than those
aggregate kernel counters. Conversely, the page-cache tracepoint is a different signal: it records cache insertions
for the app process and for background workers operating on the app's exact device/inode pairs. Those insertions are
not minor faults and are not added to the fault total.

Collection requires an emulator or device where the collector can run as root; a `userdebug` or `eng` build is
recommended. The command reads the exact online CPU list from sysfs, drops page cache through the privileged shell,
verifies app-file residency with `mincore` immediately before launch, and fails a strict cold-cache run when the
configured residency limit is exceeded. The default limit is zero. Some Android 16 emulator images keep a small,
repeatable set of APK pages resident even after global cache drop and file-scoped eviction; in that case the command
fails rather than claiming a fully cold run. Use an explicit small `--max-resident-pages` tolerance only when that
residual state is acceptable. The report shows the measured page count, threshold, per-phase residency, and warnings.
`--reboot-before-collect` waits for a new boot ID and completed boot before preparation. If another process retains
read-only installed APK mappings, `--reclaim-mapped-apks` opts into bounded page-out advice on those exact mappings.
This affects other processes and is not a guarantee of eviction: the same zero-residency check must still pass.
Cold here means the verified app-file page cache, not a guarantee about the emulator host cache, storage controller,
system libraries, or pages that other processes may refill after the final check.
Reprocess a saved capture with `--skip-collect` (the package identity is read from the capture), or compare captures
with `--compare`.

```bash
mperf faults android \
  --package com.example.app \
  --device emulator-5554 \
  --reboot-before-collect \
  --native-stacks
```

`--native-stacks` adds frame-pointer instruction-pointer callchains to the same perf record as each exact fault
address, timestamp, PID/TID, and major/minor classification. Because the system-wide collector is ready before the
app is created, these callchains include the beginning of startup. The report maps user frames to the file and offset
active at that timestamp; available ELF symbols enrich native names. Managed/JIT/interpreter frames and native code
built without usable frame pointers may be incomplete. Lost, throttled, overflowed, or malformed records invalidate
the capture rather than silently producing a clean report.
On Linux kernels that predate `PERF_FORMAT_LOST`, mperf records that only
ring-delivered loss records were available; the report calls out that weaker
completeness guarantee instead of claiming counter-backed zero loss.

For DWARF/ART stacks alongside exact fault addresses, use the optional system-wide companion recorder:

```bash
mperf faults android -p com.example.app --native-stacks --dwarf-stacks --reboot-before-collect
```

Both recorders start before launch. Simpleperf does not expose the fault address through its current CLI, so mperf
retains the native address/mapping stream. It enriches a native major fault only when the companion has a unique,
exact match on PID, TID, nanosecond timestamp, instruction address, and CPU, with verified clock, boot, capture hashes,
and zero loss. No nearest-time or ordinal stitching is performed. Invalid/unbound companions are omitted with an
explicit warning, without invalidating usable native data. A valid independent stack stream is not relabeled as exact
page attribution when native matches are unavailable. DWARF recording is intrusive and may lose samples on large apps.

A page fault is a memory exception, not necessarily a syscall. Captured kernel frames are retained when supplied;
mperf does not invent a syscall frame. Major-fault count is not a count of storage reads or all pages read. Readahead,
explicit reads, and ART advice can populate many pages before their later minor faults. Whole-file VDEX views include
all metadata, and modern VDEX files may contain no DEX payload at all: the original code can remain in APK DEX entries.

On iOS, the command uses Instruments' Virtual Memory Trace and includes symbolicated fault stacks in a chronological,
Firefox-Profiler-style stack view. For Simulator captures, Instruments observes the macOS host process: the report
filters to the app PID, and its storage behavior must not be interpreted as physical-device behavior. “Major” means a
file-backed page-in operation; “minor” groups cache hits, zero-fill, copy-on-write, and decompression events. These are
analysis buckets derived from Instruments operations, not Darwin kernel fault labels.

The recorder is started first and the app is not launched until `xctrace` emits its explicit
`--notify-tracing-started` notification. A bounded readiness timeout aborts and reaps the recorder on failure. The
capture records host-monotonic recorder-ready and launch timestamps so this ordering can be audited after the fact.
Every retained row is filtered by the numeric launch PID and process identity, guarding against PID reuse. The
report attributes frames to the installed application bundle root, including bundled frameworks and `.appex`
extensions; similarly structured binaries from another app are excluded. Code-ordering candidates require the
faulting binary itself—not merely a caller deeper in the stack—to be app-bundle owned.

Simulator cache verification inventories app-bundle files and checks their residency with `mincore` immediately before
launch. `auto` first attempts the host `purge` utility and can use bounded memory pressure when
`--allow-host-pressure` is supplied. `--require-cold-cache` rejects a run unless eviction is confirmed. iOS does not
provide a supported global page-cache drop on physical devices; physical-device runs can reboot or use the included
best-effort signed pressure helper, but cannot provide the same strict cache guarantee as a rooted Android target or
Simulator residency check.

```bash
mperf faults ios \
  --app path/to/MyApp.app \
  --device booted \
  --cache-policy auto \
  --allow-host-pressure \
  --require-cold-cache
```

The HTML reports are self-contained and show the all-file address/time pattern, per-file timelines, sequentiality,
APK/DEX and VDEX/ODEX attribution, major/minor evidence, comparison views, and ordered fault callchains. iOS read-source
and section attribution uses UUID-verified Mach-O images (for example, `__TEXT` and `__DATA`), not the caller binary.
Android retains timestamped native mappings and optional identity-verified DWARF enrichment. See the
[`faults` CLI reference](docs/cli.md#faults) or run either platform command with `--help` for the full option set.
The Android report's **Open in Perfetto** button opens the selected run's `faults.pftrace`, including startup
context collected in that session. Keep the capture directories alongside comparison reports and serve them
over HTTP for one-click loading. Trace bytes pass directly between browser windows, without uploading them;
local `file://` reports offer manual opening instead. The HTML itself remains usable without the trace file.
Drag the divider above **Selected fault** to resize its callstack panel. When the divider is focused,
Arrow Up/Down resize it; Home/End select the minimum/maximum height. Collapsing preserves the chosen height.

Android captures default to `--compilation speed-profile`: verify the actual ART filter for each code-bearing
installed APK, using the target instruction set. If necessary, request profile-guided compilation; command
`Success` alone is not accepted. If no usable device profile produces `speed-profile`, mperf inspects/extracts
the APK's `assets/dexopt/baseline.prof` and `.profm` as evidence and asks the app's AndroidX ProfileInstaller receiver
to install/transcode its embedded baseline. The app is force-stopped again before recompilation and cache eviction.
The capture fails if the receiver is absent/unsuccessful or ART still reports another filter; there is no full
`speed` fallback. See [Android's baseline installation workflow](https://developer.android.com/topic/performance/baselineprofiles/manually-create-measure#sideload-baseline).
This preparation can start the app process via a broadcast, before eviction, and retains app data and existing profiles.
For deliberate reset/full-AOT comparisons use `--compilation as-is`; replaying saved captures never changes device state.
Raw ART compilation dumps and profile-source evidence are retained before eviction and after recording.

Compiled ODEX code is resolved with the device's `oatdump`, matching every DEX location checksum and the
captured APK/ODEX/VDEX hashes. Selected faults show the DEX and compiled method containing the exact address,
separately from other methods sharing that page and from the captured caller stack. Shared-code aliases stay
ambiguous; obfuscated names require the app's R8 mapping to recover source names. Unsupported/malformed OAT
metadata leaves attribution unavailable without discarding the trace.

Add `--io-evidence` to record available block/scheduler events in the same Perfetto session and export ART advice,
system-wide guest block events, and app thread states as CSVs. These streams measure different layers; do not
equate page faults, advised bytes, read requests, or stall time. The native collector supplies exact fault addresses;
Perfetto supplies startup and scheduling context, and Simpleperf supplies optional DWARF stacks.

### Perfetto (Default)

```bash
# Ad-hoc session
aperf start -p com.example.app

# Single test iteration
aperf collect -p com.example.app -t SomeBenchmark#case
```

### Simpleperf

```bash
# Basic sampling, opens in Firefox Profiler
# Defaults to using `-e cpu-clock -f 4000 -g` with noisy frames removed (extraneous RxJava frames, kotlinx coroutines, DEDUPED frames and ART frames)
aperf start -f simpleperf -p com.example.app

# Advanced: off-CPU tracing with 4 kHz sampling, native symbols, R8 mappings, and custom filters.
# Paths are quoted so the command remains safe when a directory contains spaces.
aperf start -f simpleperf -p com.example.app \
  --simpleperfArgs "-e task-clock -g -f 4000 --trace-offcpu" \
  --symfs "$HOME/Android/Symbols" \
  --mapping app/build/outputs/mapping/release/mapping.txt \
  --remove-method "^io\.reactivex.*$" \
  --remove-method "^\[DEDUPED\].*$" \
  --no-show-art-frames

# View sampling profiler data in Perfetto instead
aperf start -f simpleperf -p com.example.app --ui perfetto

# Macrobenchmark collection uses AndroidX stack sampling and emits a Perfetto trace.
# `collect -f simpleperf` therefore opens in Perfetto by default.
aperf collect -f simpleperf -p com.example.app -t SomeBenchmark#case
```

### ART Method Tracing

```bash
aperf start -f method -p com.example.app

# Use Perfetto instead to view ART Method Trace
aperf start -p com.example.app -f method --ui perfetto

# Macrobenchmark runs its configured measurement iterations first, then captures one additional
# profiling iteration with method tracing enabled.
aperf collect -p com.example.app -f method -t SomeBenchmark#case
```

### Instruments (iOS)

```bash
# Time Profiler template, open results in Firefox Profiler
iperf start -b com.example.app --template "Time Profiler" --ui firefox

# Collect with multiple instruments and export to Perfetto
iperf start -b com.example.app --instrument "Time Profiler" --instrument "Core Animation" --ui perfetto
```

Instrument traces can be reviewed directly in Instruments, or converted for analysis in Firefox Profiler or Perfetto via the `--ui` flag.

Only booted simulators are offered by the interactive device picker. Boot a simulator first with Xcode or
`xcrun simctl boot <UDID>`. If multiple Xcode installations are present, select the intended one with
`sudo xcode-select --switch /Applications/Xcode.app`; Apple ships `xctrace` with the full Xcode app, not the
standalone Command Line Tools package.

Recent Xcode releases do not reliably accept a simulator process as an `xctrace` target. For simulator collection,
`mperf` therefore launches or finds the requested app, records host processes as a compatibility fallback, and emits
a warning. Converted Firefox Profiler and Perfetto output is filtered to the selected app PID; the raw `.trace` still
contains other host processes, can be substantially larger, and should be treated as host-wide diagnostic data.
Physical-device collection remains target-scoped.

To convert a saved Instruments trace into a Gecko profile for Firefox Profiler:

```bash
iperf convert --input MyTrace.trace --output my-trace.gecko.json --app MyApp
```

See the [CLI reference](docs/cli.md) for additional options, such as targeting a specific run or choosing a viewer.

The simulator integration test builds and installs its own minimal fixture app, exercises both launch and attach
collection, validates the trace table of contents, and converts the Time Profiler trace:

```bash
./gradlew test -Dmperf.integration.ios.enabled=true \
  --tests com.bromano.mobile.perf.integration.IosProfilerIntegrationTest
```

Pass `-Dmperf.integration.ios.device=<SIMULATOR_UDID>` to select a particular available simulator. The test restores
the simulator's original boot state and removes its fixture app when it finishes.

## Configuration

On first run, `~/.mperf/config.yml` is created. The following keys are supported:

| Field                           | Default | Description                                                                                                                                              |
| ------------------------------- | ------- | -------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `android.package`               | _unset_ | Default Android application ID for `android start / collect`; avoids `-p/--package`.                                                                     |
| `android.instrumentationRunner` | _unset_ | Default instrumentation runner for Macrobenchmark collection; avoids `-i/--instrumentation`.                                                             |
| `ios.bundleIdentifier`          | _unset_ | Preferred bundle identifier for `ios start`; avoids `-b/--bundle`.                                                                                       |
| `ios.deviceId`                  | _unset_ | Default iOS device/simulator UDID when no `--device` is provided.                                                                                        |
| `traceHostUrl`                  | _unset_ | HTTP endpoint handling multipart `POST /trace` uploads and `GET /trace/<id>` downloads from the same base path, enabling shareable performance data.     |
| `perfettoUrl`                   | _unset_ | Optional self-hosted Perfetto UI. Public HTTPS trace hosting works with the official UI without this setting. |

Example:

```yaml
android:
  package: com.example.app
  instrumentationRunner: com.example.macrobenchmark/androidx.test.runner.AndroidJUnitRunner
ios:
  bundleIdentifier: com.example.app
traceHostUrl: https://myserver.com/trace
perfettoUrl: https://perfetto.example.com
```

### Trace Hosting

Set `traceHostUrl` in `~/.mperf/config.yml` to have `mperf` push every collected trace to a remote host instead of only keeping it locally. After each run the CLI issues a multipart `POST` to the configured endpoint (for example `https://trace.example.com/trace`), expects a JSON body containing an `id` field, and echoes the fully qualified `GET /trace/<id>` URL so you can share it with Firefox Profiler, Perfetto UI, or teammates. When `traceHostUrl` is unset, `mperf` falls back to opening the trace from disk as it does today.

For local development there is a reference FastAPI implementation in `scripts/trace_server.py`:

```bash
pip install --upgrade fastapi uvicorn
python3 scripts/trace_server.py
```

This helper service persists uploads to `/tmp/mperf` and exposes the same shape expected by `traceHostUrl`:

- `POST /trace` with a multipart `file` field storing the bytes and returning `{"id":"<id>"}` (the download path lives at the `Location` header).
- `GET /trace/<id>` streaming the file back with permissive CORS headers so Firefox Profiler can fetch it directly.

Point `traceHostUrl` to `http://127.0.0.1:8080/trace` to test trace uploading locally.

#### Sharing Perfetto Traces

Since Perfetto v54, the official UI can open public HTTPS trace URLs directly. The host must allow unauthenticated
GET requests and CORS from `https://ui.perfetto.dev`; no custom UI is required. See
[Perfetto deep linking](https://perfetto.dev/docs/visualization/deep-linking-to-perfetto-ui).
Local files still use a short-lived loopback server on port 9001, which the official UI permits. Allow the browser's
local-network permission when prompted. Fault reports use
Perfetto's `postMessage` handoff so they can serve their associated trace from any local report port. Neither local
path uploads traces. `perfettoUrl` remains available for teams using their own viewer.

## Development

The Kotlin CLI is organized by responsibility under `src/main/kotlin/com/bromano/mobile/perf`:

- `profilers/`: Android and iOS recording workflows; `faults/`: startup fault capture, attribution, and reports.
- `tools/`: pinned Simpleperf, tracebox, and host Trace Processor provisioning.
- `utils/`: device/shell access, configuration, and browser opening; `gecko/`: profile conversion.
- `src/main/resources/faults-engine/`: native collectors and shared offline report assets.

Android 10+ uses the device's built-in Perfetto service. Older devices use pinned tracebox v58.2. Simpleperf CPU
and DWARF fault collection share the pinned NDK prebuilt; downloads and cached device binaries are SHA-256 verified.
Update tool revisions and checksums in `tools/`, not individual recording workflows.

- Build: `./gradlew build`
- Test: `./gradlew test`
- Lint: `./gradlew ktlintCheck` / `./gradlew ktlintFormat`
- Run: `./gradlew run --args "android start -p com.example.app"`
- Generate CLI docs: `./gradlew generateDocs` → `docs/cli.md`
- Compile performance benchmarks: `./gradlew jmhClasses`
- Benchmark Instruments-to-Gecko conversion (macOS with Xcode): `./gradlew jmh`
- Contributor workflow and coding conventions: see [`AGENTS.md`](AGENTS.md).

The Instruments benchmark uses the checked-in saved trace and reports average conversion time. On the same machine,
JDK, Xcode, trace, and JMH configuration, consolidating table exports and overlapping the table-of-contents query reduced
the measured average from 3,912.775 ms/op to 2,005.120 ms/op (48.8%). Treat local results as comparative measurements;
`xctrace`, Xcode, host load, and hardware materially affect absolute timings.

## Releasing

- Releases are created by pushing a SemVer Git tag such as `v1.2.3` or `v1.2.3-rc.1`.
- Use the repository's `$release-mperf` skill in [`.codex/skills/release-mperf`](.codex/skills/release-mperf/SKILL.md) to run the preflight, publish the tag, and verify the result.
- The release workflow requires successful build, Android emulator, and iOS Simulator CI jobs for the exact tagged
  source commit on `main`. It then validates the wrapper and tag, builds/tests on Linux, verifies docs and the packaged
  CLI, and sets the version from the tag. Device tests run in CI, not again during publication; live integration runs
  never reuse Gradle test results from another device session.
- Assets uploaded to the GitHub Release:
  - `mperf-<version>-all.jar` (fat JAR with `Implementation-Version` in the manifest)
  - `mperf-<version>-all.jar.sha256`
- GitHub artifact provenance is attested for each release JAR. Versions containing a prerelease suffix are published as prereleases.

Run the local preflight directly when needed:

```bash
.codex/skills/release-mperf/scripts/preflight.sh 1.2.3
```

Publishing requires `gh` authentication with tag-push access. The workflow uses only the repository-provided `GITHUB_TOKEN`; repository or organization policy must allow `contents`, `id-token`, and `attestations` write permissions.

Find published releases and download artifacts at:

https://github.com/benjaminromano/mperf/releases
