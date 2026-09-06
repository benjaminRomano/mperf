# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Capture-health diagnostics and an aligned Android I/O context view with explicit stream availability and no inferred request pairing.
- Configurable DWARF kernel/userspace buffers, loss-specific guidance, and retained rejected Simpleperf recordings.
- Weekly Android compatibility captures and an opt-in dedicated rooted-device workflow; CI failures retain fixture evidence.

### Changed

- Shared structured ADB/root transport across profiling and fault capture; reject mismatched kernel/userspace page sizes.

- Centralized Android tool provisioning, aligned tracebox and host Trace Processor to Perfetto v58.2, and reused
  the current pinned Simpleperf prebuilt for CPU and DWARF fault capture.
- Updated Gradle to 9.7.1 and removed the upload-only Ktor and Kotlin serialization dependencies in favor of JDK HTTP.
- Updated Kotlin to 2.4.10, Protobuf to 4.36.0, Jackson to 2.22.2, JUnit to 6.1.3, Shadow to 9.6.1,
  and GitHub Actions to current immutable pins.
- Updated the isolated Android fixture to AGP 9.3.0 and Tracing 2.0.0-beta01, removing its Gradle 10 deprecation warning.
- Open public HTTPS traces in the official Perfetto UI without requiring a custom deployment. Local trace serving
  uses a temporary, exact-file URL and cleans up when browser opening fails.
- Shared JVM setup across CI jobs and gated releases on exact-source CI, avoiding repeated Simulator recording
  during publication. Live integration tests no longer reuse cached Gradle results. Grouped dependency updates.

## [1.1.1] - 2026-09-05

### Added

- Added `mperf faults android` and `mperf faults ios` for cold-start page-fault collection, strict cache-residency
  evidence, file and section attribution, cross-capture comparison, symbolicated iOS fault stacks, and self-contained
  interactive HTML reports.
- Added support for Android 10 and modern sectioned VDEX files. VDEX sources remain whole in rankings and plots, with
  original DEX boundaries shown only after full ART/APK location-checksum verification.
- Added Android DWARF fault-stack correlation by exact event identity, verified `speed-profile` preparation,
  OAT method attribution, and optional Perfetto advice, block-I/O, and thread-state evidence.
- Added a shared offline fault explorer with time/address and fault-index/delta plots, chronological stacks,
  flame graphs, an ordered fault list, a resizable detail dock, and local opening of associated Perfetto traces.

### Changed

- Implemented fault capture orchestration, preprocessing, and report generation in Kotlin; fault commands no longer
  require Python. Native helpers remain for capabilities unavailable from the platform command-line tools.
- Removed obsolete stack paging code and its test, unused stack metadata and callbacks, redundant markup assertions,
  and obsolete Python-cache packaging exclusions. Updated fault-resource task registration for Gradle 9.6.

### Fixed

- Fixed Android collection on noncontiguous CPU topologies by parsing and validating the exact online CPU list from
  sysfs instead of assuming CPU identifiers are continuous.
- Fixed Android page-cache evidence omitting background and kernel-worker insertions for app-owned device/inode pairs,
  package-prefix attribution collisions, disappearing optional files, post-launch residency races, stopped-process
  `pidof` handling, and saved-capture reprocessing without a locally configured package.
- Fixed Android report source ordering to rank major faults first, retain whole VDEX sources, show verified embedded
  DEX boundaries, preserve full source paths in hover details, and avoid WebGL-only scatter rendering.
- Fixed missing kernel blocked-function symbols by enabling on-device symbolization for supported blocked-reason events.
- Fixed high-bit Android fault addresses failing report generation or losing exact DWARF matches after CSV serialization.
- Fixed iOS startup ordering to wait for recorder readiness before launch, explicit app/bundle selection, recording-window
  validation, and attribution of app-owned framework and extension code.

## [1.1.0] - 2026-07-13

### Added

- Added end-to-end Android emulator coverage for ad-hoc and Macrobenchmark Perfetto, Simpleperf, and ART method-trace collection. The standalone API 35 fixture uses Android Gradle Plugin 9.2.1, Macrobenchmark 1.4.1, and AndroidX Tracing 2.0.0-alpha09 without adding Android build dependencies to the CLI project.
- Added an iOS Simulator fixture and integration test that builds and installs a native app, emits `os_signpost` intervals and events, validates launch and attach collection with the Points of Interest schema, converts target-process Time Profiler samples to Gecko format, and restores the simulator's prior state.
- Added dedicated Linux build/package, Android emulator, and macOS iOS Simulator CI jobs with bounded timeouts and forwarded integration-test configuration.
- Added a bounded whole-scenario retry for the hosted iOS integration job after resetting Simulators, covering Xcode finalization variance that can outlast the CLI's guarded retry. Feature branches now use the pull-request matrix instead of launching duplicate push and PR emulator jobs.
- Added a JMH 1.37 benchmark harness backed by a saved Instruments trace for repeatable conversion measurements. On the same host and benchmark configuration, the optimized converter averaged 2,005.120 ms/op versus a 3,912.775 ms/op baseline, a 48.8% reduction.
- Added shared Macrobenchmark instrumentation helpers that validate runner results and select only output files created by the current test invocation.
- Added a repository-local `$release-mperf` Codex skill with a deterministic release preflight, publishing workflow, checksum verification, provenance verification, and release smoke-test guidance.
- Added Dependabot coverage for root Gradle dependencies, the Android integration fixture, and GitHub Actions.
- Added installer/uninstaller integration coverage for prerelease names, paths containing spaces, checksum rejection, argument validation, and cleanup.
- Added regression tests for CLI argument splitting, output naming, configuration side effects, benchmark output discovery, profiler startup and shutdown behavior, XML parsing, archive traversal, device discovery, and Instruments conversion across Xcode symbolication changes.

### Changed

- Updated the build to Gradle 9.5.1 with a pinned distribution checksum, Kotlin 2.4.0, Protobuf Gradle plugin 0.10.0, Shadow 9.5.1, ktlint 14.2.0, and reproducible archive ordering and timestamps.
- Updated runtime and test dependencies: Protobuf 4.35.1, Jackson 2.22.1, Ktor 3.5.1, coroutines and serialization 1.11.0, Gson 2.14.0, SLF4J 2.0.18, JUnit 6.1.2, Mockito Kotlin 6.3.0, and Mockito 5.23.0. Kotlin artifacts are aligned with the 2.4.0 BOM.
- Updated the sideloaded Perfetto tracebox from v54.0 to v57.2 and refreshed its per-ABI checksums. Existing device binaries are reused only when their checksum matches the expected artifact.
- Updated the sideloaded Simpleperf binaries to AOSP prebuilt commit `829c2351dfc33886743931721b67b959c50a40ab`, refreshed per-ABI identifiers and checksums, and made the host scripts cache commit-aware and atomically replaceable.
- Updated the default Perfetto configuration using current Macrobenchmark practices: broader supported atrace and ftrace coverage, wildcard app tracing on API 29+, compact scheduler events, battery counters, memory polling, AndroidX SDK tracing categories, and lower-overhead process polling.
- Changed Macrobenchmark collection to request an explicit additional-output directory, validate instrumentation completion, prefer runner-reported artifacts, and reject stale traces left by earlier runs.
- Changed default output suffixes to match their actual formats: `.perfetto-trace` for Perfetto and Macrobenchmark stack sampling, `.json.gz` for ad-hoc Simpleperf Gecko profiles, and `.trace` for ART method traces. Bare relative output filenames are now supported.
- Changed Simpleperf and Perfetto startup handling to poll for the profiler process with a bounded timeout instead of relying on fixed sleeps; method traces now wait for the on-device file to finish flushing before pulling it.
- Changed iOS discovery to consume structured `simctl` and `devicectl` JSON, distinguish simulator UUIDs from physical CoreDevice identifiers, validate exact bundle IDs, and support physical-device launching through `devicectl`.
- Changed simulator Instruments collection to launch with `simctl`, attach deterministically, record host processes where required by modern Xcode, expose `--time-limit`, and fail clearly on timeout or non-zero `xctrace` exit.
- Changed Instruments conversion to reject invalid run numbers, tolerate current Xcode backtrace nesting and leading command output, preserve process IDs in Gecko threads, and compare stable profile semantics in regression tests rather than unstable symbol-table IDs.
- Changed Instruments conversion to export all required schemas once and overlap that export with table-of-contents inspection, removing a duplicate Time Profiler export and cutting measured conversion latency by 48.8% on the checked-in benchmark trace.
- Changed configuration writes to use an atomic temporary-file replacement, CLI startup to close its HTTP client, and documentation generation to avoid creating or reading the user's `~/.mperf/config.yml`.
- Changed the installer to resolve release assets through structured GitHub API JSON, accept prerelease versions, retry transient downloads, verify checksums before installation, and stage downloads in a temporary directory.
- Changed the release workflow to require SemVer tags on `main`, run the complete build and iOS integration suite with one bounded Simulator-reset retry, verify generated docs and JAR metadata, publish prereleases correctly, and emit verified SHA-256 assets and GitHub build-provenance attestations.
- Updated all GitHub Actions to current immutable commit pins, including Checkout 7.0.0, Setup Java 5.5.0, Gradle Actions 6.2.0, setup-xcode 1.7.0, Android Emulator Runner 2.38.0, build provenance 4.1.1, and action-gh-release 3.0.1.
- Updated the README and generated CLI reference for current platform requirements, Android profiling behavior, secure path handling, iOS Simulator integration, `--time-limit`, development commands, and the release process.

### Fixed

- Fixed platform integration tests leaking into generic Linux unit-test execution: Android device discovery is now deferred until integration testing is explicitly enabled, while the saved Instruments trace conversion test is macOS-gated and runs in the dedicated iOS CI job.
- Fixed the Android emulator action passing shell line-continuation characters to Gradle as a literal task instead of starting the end-to-end profiling suite.
- Fixed cold iOS Simulator integration runs timing out while Xcode finalized host-wide traces on macOS 26; the test now allows bounded first-boot and trace-finalization overhead beneath the workflow-level timeout.
- Fixed transient Xcode 26 `xctrace` finalization failures leaving partial Instruments outputs and failing simulator collection; exit codes 1, 139, and 141 (`SIGPIPE`) now trigger one clean retry only when collection created a partial trace, while preflight and argument errors still fail immediately.
- Fixed hosted Xcode 26 finalization stalls exhausting the bounded collection wait after creating a partial trace; deadline overruns retain timeout classification after termination, the previous process must be confirmed reaped before the guarded clean retry, and unreapable processes fail without starting an overlapping collection.
- Fixed Xcode 26 instability while finalizing host-wide, multi-instrument Simulator traces by keeping the live Time Profiler launch capture focused on samples and validating Points of Interest in a separate Logging attach capture; the test also verifies the fixture markers through the Simulator unified log because Xcode's reliable host-wide fallback cannot include guest log events.
- Fixed timed Instruments recordings using a two-minute process deadline regardless of the requested duration. Collection now allows the requested `--time-limit` plus a bounded three-minute Xcode startup and finalization grace period before terminating a stuck process.
- Fixed Macrobenchmark failures being treated as successful collections and stale output files being selected when a run did not produce a new trace.
- Fixed ART method tracing using the wall-clock flag before its supported API level and pulling traces before Android finished writing them.
- Fixed Perfetto and Simpleperf sessions failing nondeterministically when profiler process startup took longer than a fixed delay.
- Fixed Simpleperf Gecko conversion arguments for symbol directories, mapping files, filters, ART frame display, and paths containing spaces or quotes.
- Fixed Simpleperf conversion failures being masked by a successful downstream `gzip` process; conversion and compression now fail independently, remove partial output, and the live test parses the resulting Gecko JSON and requires samples.
- Fixed iOS simulator collection hangs caused by unreliable `xctrace --launch` behavior, ambiguous UUID-based device classification, display-name bundle lookup, and incomplete process-registration timing.
- Fixed converted simulator profiles mixing unrelated host processes or resolving frames against another process's overlapping image addresses by preserving per-library PIDs and filtering converted output to the selected simulator app process. The unavoidable host-wide raw-trace fallback is now warned and documented explicitly.
- Fixed Instruments parser failures on current Xcode XML layouts, XML declarations or leading output, and run numbers below one.
- Fixed `xctrace export` crashes on large Instruments traces by writing XPath and table-of-contents XML to securely quoted temporary files instead of streaming large exports through stdout; partial files are cleared before retry and always removed afterward. Reported by @ldct in #9.
- Fixed generated docs mutating user configuration and fixed configuration writes that could leave partially written YAML after interruption.
- Fixed command-line splitting for escaped characters, empty quoted arguments, general whitespace, trailing escapes, and unterminated quotes.
- Fixed output-directory creation for bare filenames, stale Instruments output reuse, unsupported Instruments instrumentation-test calls silently succeeding, and the main HTTP client leaking resources.
- Fixed Shadow JAR packaging conflicts for duplicated Kotlin module metadata and made release JAR contents reproducible.

### Security

- Hardened Instruments XML parsing against DOCTYPE declarations, external entities, external schemas, and XInclude; removed the global XML settings that disabled JDK entity-expansion limits.
- Added POSIX-safe quoting for external commands, URLs, trace paths, device IDs, bundle IDs, Simpleperf conversion options, and viewer launches to prevent whitespace breakage and command injection.
- Prevented ZIP-slip extraction by normalizing destinations and rejecting entries outside the requested directory.
- Hardened Simpleperf script installation by rejecting absolute paths, parent traversal, links, and unsupported archive entries, then verifying a deterministic extracted-tree SHA-256 before atomic activation.
- Made the release installer require a valid checksum asset and reject mismatches before replacing an installed JAR.
- Pinned the Gradle distribution, profiler binaries, release artifacts, and third-party GitHub Actions to verified checksums or immutable revisions, and added GitHub artifact provenance attestations for published JARs.

[Unreleased]: https://github.com/benjaminromano/mperf/compare/v1.1.1...HEAD
[1.1.0]: https://github.com/benjaminromano/mperf/compare/v1.0.5...v1.1.0
