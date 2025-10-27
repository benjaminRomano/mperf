# mperf — Mobile Performance CLI

`mperf` is a CLI tool for collecting and visualizing profiler data from Android and iOS devices. It provides a unified interface over
platform profilers and supports collection over both ad-hoc app sessions and single-iterations of **Macrobenchmark** tests.

## Features

**Supported Profilers**

- Android Runtime Method Traces
- Perfetto
- Simpleperf
- Instruments

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
- `python3`, `tar`, and `gzip` on PATH
- Xcode Command Line Tools (for iOS Instruments collection)
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

## Usage

Full usage details can be found in [CLI Reference Docs](/docs/cli.md)

### Top-level commands

- `ios start` — record an Instruments session for a running app
- `android start` — record an ad-hoc session for a running Android app
- `android collect` — run a single Macrobenchmark test iteration and collect a trace

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

# Advanced: custom simpleperf args, symbolization, and filtering
aperf start -f simpleperf -p com.example.app \
  # Off-CPU tracing with sampling every 4KHz (.25ms)
  --simpleperfArgs "e task-clock -g -f 4000 --trace-offcpu" \
  # Provide directory of symbols for symbolicating native frames
  --symfs $HOME/Android/Symbols \
  # Provide mappings for deobfuscating java frames
  --mapping app/build/outputs/mapping/release/mapping.txt \
  # Filter noisy RxJava frames and DEDUPED frames
  -- remove-method "^io\.reactivex.*$" --remove-method "^\[DEDUPED\].*$" \
  # Hide Android Runtime Frames
  --no-show-art-frames

# View sampling profiler data in Perfetto instead
aperf start -f simpleperf -p com.example.app --ui perfetto

# Note: `mperf collect` does not support simpleperf due to Macrobenchmark limitations
```

### ART Method Tracing

```bash
aperf start -f method -p com.example.app

# Use Perfetto instead to view ART Method Trace
aperf start -p com.example.app -f method --ui perfetto

# NOTE: Due to Macrobenchmark limitations, the number of iterations specified in `measureRepeat(...)` will be performed before the method trace is collected.
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

To convert a saved Instruments trace into a Gecko profile for Firefox Profiler:

```bash
iperf convert --input MyTrace.trace --output my-trace.gecko.json --app MyApp
```

See the [CLI reference](docs/cli.md) for additional options, such as targeting a specific run or choosing a viewer.

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
- Contributor workflow and coding conventions: see [`AGENTS.md`](AGENTS.md).

## Releasing

- Releases are created by pushing a Git tag matching `v*` (e.g., `v1.2.3`).
- The GitHub Actions workflow builds, tests, and sets the Gradle project version to the tag value (without the leading `v`).
- Assets uploaded to the GitHub Release:
  - `mperf-<version>-all.jar` (fat JAR with `Implementation-Version` in the manifest)
  - `mperf-<version>-all.jar.sha256`

Trigger a release from your terminal:

```
git tag v1.2.3
git push origin v1.2.3
```

Find published releases and download artifacts at:

https://github.com/benjaminromano/mperf/releases
