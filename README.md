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
- Python 3.13+, [`uv`](https://docs.astral.sh/uv/), `tar`, and `gzip` on PATH
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
versioned analysis engine into `~/.mperf/cache/faults-engine/` and creates its locked Python environment with `uv`.

On Android, the collector uses kernel `perf_event_open` page-fault events and `PERF_RECORD_MMAP2` mappings. It
attributes each fault to the mapped file and file offset, including APK entries and whole ODEX/VDEX files when pulled
artifacts are available. Android 10 and modern sectioned VDEX files are supported. Original `classes*.dex` boundaries
are labeled only when every ART-stored DEX location checksum matches the corresponding APK entry; an unverified VDEX
is never guessed. Page-cache events include the app process and background or kernel-worker insertions targeting the
exact device/inode identities of app-owned files. These events are correlated I/O evidence, not proof that a specific
cache insertion caused a later fault.

Collection requires an emulator or device where the collector can run as root; a `userdebug` or `eng` build is
recommended. The command reads the exact online CPU list from sysfs, drops page cache through the privileged shell,
verifies app-file residency with `mincore` immediately before launch, and fails a strict cold-cache run when the
configured residency limit is exceeded. `--reboot-before-collect` provides the strongest reproducible setup.
Reprocess a saved capture with `--skip-collect` (the package identity is read from the capture), or compare captures
with `--compare`.

```bash
mperf faults android \
  --package com.example.app \
  --device emulator-5554 \
  --reboot-before-collect
```

For supplementary Android fault call stacks, use Simpleperf in a separate run:

```bash
mperf android start \
  --format simpleperf \
  --package com.example.app \
  --simpleperfArgs "-e minor-faults,major-faults -g"
```

This opens the ordered samples and stacks in Firefox Profiler. It is deliberately
separate from the authoritative low-overhead fault run: DWARF unwinding perturbs
startup, can miss early faults, and does not share the strict cache gate or exact
event identity of the `faults android` capture.

On iOS, the command uses Instruments' Virtual Memory Trace and includes symbolicated fault stacks in a chronological,
Firefox-Profiler-style stack view. For Simulator captures, Instruments observes the macOS host process: the report
filters to the app PID, and its storage behavior must not be interpreted as physical-device behavior. “Major” means a
file-backed page-in operation; “minor” groups cache hits, zero-fill, copy-on-write, and decompression events. These are
analysis buckets derived from Instruments operations, not Darwin kernel fault labels.

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
APK/DEX and VDEX/ODEX attribution, major/minor evidence, comparison views, and iOS fault stacks. Android's exact
fault collector records the instruction pointer but does not currently unwind a call stack; Simpleperf can collect
supplementary sampled fault stacks in a separate, more intrusive run. See the
[`faults` CLI reference](docs/cli.md#faults) or run either platform command with `--help` for the full option set.

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
| `perfettoUrl`                   | _unset_ | Base URL (for example `https://perfetto.example.com`) of a self-hosted Perfetto UI used when deep-linking uploaded traces; bypasses Perfetto CSP limits. |

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

To make uploaded traces open directly in Perfetto UI, configure `perfettoUrl` with the base URL of your self-hosted Perfetto instance. The official `https://ui.perfetto.dev` enforces a strict [Content Security Policy](https://perfetto.dev/docs/visualization/deep-linking-to-perfetto-ui#why-can-39-t-i-just-pass-a-url-) that blocks downloads from arbitrary origins, so a custom host is required for shared URLs to load successfully. A GitHub Pages reference setup with CSP modified can be found [here](https://github.com/benjaminRomano/perfetto).

## Development

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
- The GitHub Actions workflow validates the wrapper and tag, builds, tests, lints, verifies generated docs and the packaged CLI, and sets the Gradle project version from the tag.
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
