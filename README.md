# mperf — Mobile Performance CLI

`mperf` is a CLI tool for collecting and visualizing profiler data from Android devices. It provides a unified interface over
various Android profilers and supports collection over both ad-hoc app sessions and single-iterations of **Macrobenchmark** tests.

## Features

**Supported Profilers**

- Android Runtime Method Traces
- Perfetto
- Simpleperf

**Collection Modes**

- Arbitrary app session profiling
- Single Macrobenchmark iteration profiling

**Visualization**

- Perfetto UI
- Firefox Profiler

## Requirements

- Java 21+
- Android SDK Platform‑Tools (`adb` on PATH)
- `python3`, `tar`, and `gzip` on PATH
- macOS or Linux

## Install

The CLI can be installed using the installation helper. Once ran `mperf` will be added to the PATH.

```
curl -fsSL https://raw.githubusercontent.com/benjaminromano/mperf/refs/heads/main/scripts/install.sh | bash
```

### Manual Installation

Alternatively, the CLI can be manually downloaded and installed from [GitHub Releases](https://github.com/benjaminromano/mperf/releases).

Add a shell alias or wrapper script named `mperf` that invokes the JAR:

```bash
# Example alias — update the path to the JAR you downloaded
alias mperf='java -jar $HOME/tools/mperf/mperf-<version>-all.jar'

# Verify
mperf --help
```

## Quickstart

**Ad‑hoc Perfetto session for an app**

```bash
# Defaults to Perfetto collection
mperf start -p com.example.app
```

Press any key to stop tracing. When done, the trace will be opened in Perfetto UI.

**Run a single performance test iteration and pull the trace**

```bash
mperf collect -p com.example.app -i com.example.macrobenchmark/androidx.test.runner.AndroidJUnitRunner -t LoginBenchmark#loginByIntent
```

**Note:** If you omit the device or instrumentation runner, the CLI lists choices interactively.

## Usage

Full usage details can be found in [CLI Reference Docs](/docs/cli.md)

### Top‑level commands

- `start` — record an ad‑hoc session for a running app
- `collect` — run a single Macrobenchmark test iteration and collect a trace

### Perfetto (Default)

```bash
# Ad-hoc session
mperf start -p com.example.app

# Single test iteration
mperf collect -p com.example.app -t SomeBenchmark#case
```

### Simpleperf

```bash
# Basic sampling, opens in Firefox Profiler
# Defaults to using `-e cpu-clock -f 4000 -g` with noisy frames removed (extraneous RxJava frames, kotlinx coroutines, DEDUPED frames and ART frames)
mperf start -f simpleperf -p com.example.app

# Advanced: custom simpleperf args, symbolization, and filtering
mperf start -f simpleperf -p com.example.app \
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
mperf start -f simpleperf -p com.example.app --ui perfetto

# Note: `mperf collect` does not support simpleperf due to Macrobenchmark limitations
```

### ART Method Tracing

```bash
mperf start -f method -p com.example.app

# Use Perfetto instead to view ART Method Trace
mperf start -p com.example.app -f method --ui perfetto

# NOTE: Due to Macrobenchmark limitations, the number of iterations specified in `measureRepeat(...)` will be performed before the method trace is collected.
mperf collect -p com.example.app -f method -t SomeBenchmark#case
```

## Configuration

On first run, a config file is created at `~/.mperf/config.yml` with defaults. You can set your app package and a default instrumentation runner:

```yaml
android:
  package: com.example.app
  instrumentationRunner: com.example.macrobenchmark/androidx.test.runner.AndroidJUnitRunner
```

With this in place, you can omit `-p/--package` and `-i/--instrumentation` in most commands.

## Development

- Build: `./gradlew build`
- Test: `./gradlew test`
- Lint: `./gradlew ktlintCheck` / `./gradlew ktlintFormat`
- Run: `./gradlew run --args "start -p com.example.app"`
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

## Future Works

In the future, iOS profiler collection support will be added.
