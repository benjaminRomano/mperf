# Repository Guidelines

## Project Structure & Module Organization

- Source: `src/main/kotlin` (Kotlin CLI), proto: `src/main/proto`, tests: `src/test/kotlin`, resources: `src/test/resources`.
- Generated protobuf: `build/generated/source/proto/main/{kotlin,java}` (wired via Gradle `sourceSets`).
- Build outputs: `build/`. Gradle config in `build.gradle.kts`, settings in `settings.gradle.kts`.

## Architecture Overview

- CLI: Clikt-based with a root command and two top-level actions: `start` (ad‑hoc session) and `collect` (single test iteration). No `android` subgroup at present.
- Profilers: `PerfettoProfiler`, `SimpleperfProfiler`, and `MethodProfiler`, orchestrated by `ProfilerExecutorImpl`.
- Trace Opening: `ProfileOpener` starts a local HTTP server on `127.0.0.1:9001` and opens the appropriate UI (Perfetto or Firefox Profiler) to fetch the file once, then shuts down.
- Device I/O: thin `Adb` wrapper for shell calls, file pull/push, quoting, and root-aware shells.
- Config: YAML via `kaml`; values hydrate defaults for CLI options and are stored in `~/.mperf/config.yml` (auto-created on first run).
- Proto: Perfetto config defined in `src/main/proto` and compiled to Kotlin/Java.

## Build, Test, and Development Commands

- Build: `./gradlew build` — compiles, generates protobuf, runs checks.
- Test: `./gradlew test` — JUnit 5 + Mockito; tests run with a Mockito javaagent configured in Gradle.
- Lint (check/format): `./gradlew ktlintCheck` / `./gradlew ktlintFormat` — Kotlin style.
- Run: `./gradlew run --args "start -p com.example.app"` or `./gradlew run --args "collect -p com.example.app -t SomeTest#method"`.

## Coding Style & Naming Conventions

- Kotlin, JVM toolchain 21. Follow ktlint defaults (4‑space indent, 100–120 col).
- Packages: `com.bromano.mobile.perf`; classes `PascalCase`, functions/props `camelCase`.
- Files mirror class names (e.g., `PerfettoProfiler.kt`). Keep CLI commands in `com.bromano.mobile.perf.commands`.
- Protobuf edits go in `src/main/proto`; do not modify generated sources.
- Ktlint is disabled for generated sources via `.editorconfig` to avoid formatting generated code.

## Testing Guidelines

- Frameworks: JUnit 5 + Mockito (Kotlin). Place tests in `src/test/kotlin`.
- Naming: mirror source and suffix with `Test` (e.g., `PerfettoProfilerTest`).
- Scope: mock external tools (`adb`, profile opener, shell) and assert command strings/flows. Use `FakeShell` for lightweight shells.
- Mockito agent: tests run with `-javaagent` configured in Gradle; run via `./gradlew test`.
- Integration stubs exist under `src/test/kotlin/.../integration` for quick manual runs through `runCli` helpers.

## Android Tooling & Runtime Prereqs

- Android: Install Android SDK Platform-Tools; ensure `adb` is on PATH (`adb version`). Enable USB debugging, accept RSA prompt, verify with `adb devices`.
- Python and tools (Simpleperf flow): Requires `python3`, `tar`, and `gzip` on PATH for converting perf.data to Gecko format and extracting scripts.
- Network access: May be required to sideload Perfetto (tracebox) and download Simpleperf scripts into `~/.mperf/simpleperf/`.
- Desktop opener: Uses `open` (macOS) or `xdg-open` (Linux) to launch browser. Ensure firewall allows `127.0.0.1:9001` for a one-shot file fetch.
- Port usage: Local HTTP server binds to `127.0.0.1:9001` (chosen for Perfetto UI CSP compatibility).

## Commit & Pull Request Guidelines

- Commits: concise, imperative subject (e.g., "add perfetto collector"). Group related changes.
- Prefer Conventional Commits where helpful (feat, fix, test, chore).
- PRs: include a clear description, linked issues, and runnable examples (commands/output). Add notes on testing and any config impacts. Screenshots or logs for CLI behavior are welcome.

## Security & Configuration Tips

- Do not commit secrets or device identifiers. Keep local paths out of code.
- Android tooling (`adb`, perfetto, simpleperf) is expected to be on PATH when running profilers.
- JVM 21 is required (managed via Gradle toolchains).
- Config file lives at `~/.mperf/config.yml` and is created with defaults if missing.

## Usage Notes

- Device selection: pass `-d/--device` or select interactively when multiple devices are connected. The tool does not rely on `ANDROID_SERIAL`.
- Package/config: pass `-p/--package` or set `android.package` in `config.yml`. For `collect`, you can pass `-i/--instrumentation` or pick interactively from detected runners.
- Test selection (`collect`): tests are enumerated via `am instrument -r -w -e log true -e logOnly true <runner>`; passing a method name (`-t loginByIntent`) will prompt if multiple class matches exist.
- Output path: defaults to `artifacts/trace_out/<format>-YYYY-MM-dd-HH-mm.trace` unless `-o/--out` is provided.
- Viewer selection: by default, Perfetto traces open in Perfetto UI; Simpleperf and Method traces open in Firefox Profiler. Override with `--ui` when supported.

## Profiler Details

- Perfetto
  - Uses on-device `perfetto`. On SDK < 29, sideloads `tracebox` v51.2 to `/data/local/tmp/tracebox` from perfetto-luci artifacts and runs that instead.
  - Sets `persist.traced.enable=1` on some devices (SDK >= 28) to ensure tracing works on older/non-Pixel phones.
  - Default config is programmatically generated (intersection of supported atrace categories with a curated list). Config can be overridden via `--configPb`.
  - Stops by sending SIGTERM to the perfetto process and streams the trace to the local file via `adb shell cat ... > <output>`.

- Simpleperf
  - Records with `simpleperf record --app <package>` using customizable args (`--simpleperfArgs`). On rootable devices, sideloads the latest simpleperf binary and sets `--user-buffer-size 1G` to avoid truncated stacks.
  - Converts `perf.data` to Firefox Profiler (Gecko) JSON using `gecko_profile_generator.py` from scripts auto-installed into `~/.mperf/simpleperf/`.
  - Options: `--symfs`, `--mapping`, `--show-art-frames/--no-show-art-frames`, `--remove-method` (defaults strip common concurrency noise).

- Method tracing
  - Uses `am profile` to start/stop method tracing for running apps, or launches the resolved main activity with `--start-profiler` when not running.
  - Pulls `/data/local/tmp/method.trace` to the specified output and opens in Firefox Profiler by default.

## Known Caveats

- UI override: `--ui` is currently respected for `collect` path; `start` path uses default viewer selection in the executor.
- Simpleperf test mode: the `executeTest` path is wired to Macrobenchmark instrumentation and may not yet emit a Simpleperf trace (uses MethodTracing args and searches for a trace); consider `start` for Simpleperf sampling sessions.
- Sideload versions are pinned (Perfetto tracebox v51.2; Simpleperf commit a63e5b...), and require network access on first use.

## Workflow

- Refer to `TASKS.md` for ongoing work items and status when present. Keep it updated as you progress.
