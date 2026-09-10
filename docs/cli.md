# mperf
**Options**

| Name(s) | Metavar | Description |
|---|---|---|
| --help, -h |  | Show this message and exit |

**Commands**

| Name | Description |
|---|---|
| faults | Analyze startup page-fault patterns on Android or iOS |
| ios |  |
| android |  |

## faults
**Options**

| Name(s) | Metavar | Description |
|---|---|---|
| --help, -h |  | Show this message and exit |

**Commands**

| Name | Description |
|---|---|
| android | Collect exact Android startup faults and generate an interactive HTML report |
| ios | Collect iOS startup VM faults and stacks with Instruments and generate an interactive HTML report |

### android
**Options**

| Name(s) | Metavar | Description |
|---|---|---|
| --package, -p | text | Package name |
| --activity | text | Launch activity; resolved automatically when omitted |
| --compilation | (speed-profile\|as-is) | Prepare verified profile-guided AOT before eviction, or preserve compilation for explicit experiments |
| --device, -d | text | ADB device serial |
| --out, -o | path | Capture output directory |
| --settle-ms | int | Collection time after initial display; increase to capture delayed reportFullyDrawn markers |
| --max-resident-pages | int | Maximum verified resident app-file pages allowed before launch (strict default: 0) |
| --reboot-before-collect |  | Reboot the target before cache eviction and collection |
| --dwarf-kernel-pages | int | Simpleperf ring-buffer pages per CPU (power of two) |
| --dwarf-user-buffer-mb | int | Simpleperf userspace buffer in MiB; larger buffers consume target RAM |
| --native-kernel-pages | int | Native ring pages per CPU per event (major and minor) |
| --native-max-samples | int | Native fault sample capacity (system-wide) |
| --native-max-mappings | int | Native mapping record capacity |
| --native-max-callchain-entries | int | Native callchain address capacity |
| --perfetto-mode | (full\|lean) | Lean retains startup markers but omits page-cache and I/O evidence |
| --report-only |  | Render existing processed inputs without collection or preprocessing |
| --cohort | path | JSON array of {capture, label, cohort} rows for repeated/control-return comparisons |
| --dwarf-recorder | path | Custom Android Simpleperf ELF with same-sample PERF_SAMPLE_ADDR support |
| --symbol-dir | path | ELF debug files; only architecture/build-ID matches are used |
| --r8-mapping | path | Exact-build R8 mapping (requires --mapping-apk-sha256) |
| --mapping-apk-sha256 | text | SHA-256 of the APK built with the supplied mapping/profiles |
| --startup-profile | path | Consumed startup profile in original-name text format |
| --baseline-profile | path | Consumed baseline profile in original-name text format |
| --native-stacks |  | Capture exact native/ART frame-pointer callchains with each fault |
| --dwarf-stacks |  | Record system-wide major-fault DWARF/ART stacks; enrich only exact, verified event matches |
| --reclaim-mapped-apks |  | Opt in to page-out advice on other processes' read-only installed APK mappings; strict cache checks remain |
| --no-pull-artifacts, --pull-artifacts |  | Pull APK and ART files for section attribution |
| --io-evidence |  | Capture available block I/O and scheduler events; export separate startup context CSVs |
| --skip-collect |  | Reprocess and report an existing exact capture |
| --overwrite |  | Replace a non-empty output owned by mperf faults |
| --compare | path | Second capture directory |
| --label | text | Primary capture label |
| --compare-label | text | Comparison capture label |
| --allow-incomparable |  | Allow an exploratory comparison despite provenance mismatches |
| --no-open, --open |  | Open the generated HTML report |
| --help, -h |  | Show this message and exit |

### ios
**Options**

| Name(s) | Metavar | Description |
|---|---|---|
| --bundle, -b | text | Bundle identifier |
| --app | path | Built .app bundle to install before capture |
| --app-binary-name | text | Executable used to identify app stack frames |
| --device, -d | text | Simulator or physical-device name/UDID |
| --out, -o | path | Capture output directory |
| --cache-policy | text | Cache policy: auto, purge, pressure, reboot, or none |
| --require-cold-cache |  | Fail unless Simulator app-file residency confirms eviction |
| --allow-host-pressure |  | Allow Simulator auto policy to fall back to memory pressure |
| --allow-unconfirmed-cache |  | Continue when Simulator mincore cannot confirm eviction |
| --residency-threshold | float | Maximum post-action Simulator app-file residency fraction |
| --development-team | text | Apple development team for physical helper signing |
| --pressure-fraction | float | Physical-device memory pressure fraction |
| --pressure-megabytes | int | Fixed helper allocation in MiB |
| --pressure-hold-seconds | int | How long the helper holds memory |
| --settle-seconds | float | Analyzed startup window in seconds |
| --time-limit | int | Maximum recording duration in seconds |
| --app-argument | text | Argument passed to the target app |
| --overwrite |  | Replace an owned mperf faults capture directory |
| --skip-collect |  | Reprocess an existing capture |
| --no-open, --open |  | Open the generated HTML report |
| --help, -h |  | Show this message and exit |

## ios
**Options**

| Name(s) | Metavar | Description |
|---|---|---|
| --help, -h |  | Show this message and exit |

**Commands**

| Name | Description |
|---|---|
| start | Run iOS Instruments profiler over arbitrary app session |
| convert | Convert Instruments Trace to Gecko Format (Firefox Profiler) |

### start
**Options**

| Name(s) | Metavar | Description |
|---|---|---|
| --format, -f | (instruments) | Profiler to use for collection |
| --template | text | Instruments template to use for profiling |
| --instrument | text | Instruments to include |
| --time-limit | text | Stop automatically after a duration such as 30s or 2m |
| --out, -o | path | Output path for trace |
| --bundle, -b | text | Bundle identifier (e.g. com.example.app) |
| --device, -d | text | Device/Simulator UDID |
| --ui | (PERFETTO\|FIREFOX\|INSTRUMENTS) | Profile viewer to open trace in |
| --help, -h |  | Show this message and exit |

### convert
**Options**

| Name(s) | Metavar | Description |
|---|---|---|
| --input, -i | path | Input Instruments Trace |
| --app | text | Name of app (e.g. YourApp) |
| --run | int | Which run within the trace file to analyze |
| --output, -o | path | Output Path for gecko profile |
| --ui | (PERFETTO\|FIREFOX\|INSTRUMENTS) | Profile viewer to open trace in |
| --help, -h |  | Show this message and exit |

## android
**Options**

| Name(s) | Metavar | Description |
|---|---|---|
| --help, -h |  | Show this message and exit |

**Commands**

| Name | Description |
|---|---|
| start | Run profiler over abitrary app session |
| collect | Collect performance data over single iteration of a performance test |

### start
**Options**

| Name(s) | Metavar | Description |
|---|---|---|
| --format, -f | (perfetto\|simpleperf\|method) | Profiler to use for collection |
| --configPb | path | Perfetto binary config |
| --simpleperfArgs | text | Custom options for simpleperf record command |
| --symfs | path | Directory to find binaries with symbols and debug info [Simpleperf only] |
| --mapping | path | Mapping file for simpleperf deobfuscation |
| --no-show-art-frames, --show-art-frames |  | Show Android Runtime Frames |
| --remove-method | text | Remove methods matched by provided regexes (e.g. "^io\.reactivex.$" |
| --out, -o | path | Output path for trace |
| --package, -p | text | Package name |
| --device, -d | text | Device serial |
| --ui | (PERFETTO\|FIREFOX\|INSTRUMENTS) | Profile viewer to open trace in |
| --help, -h |  | Show this message and exit |

### collect
**Options**

| Name(s) | Metavar | Description |
|---|---|---|
| --out, -o | path | Output path for trace |
| --package, -p | text | Package name |
| --instrumentation, -i | text | Instrumentation runner (e.g. com.example.macrobenchmark/androidx.test.runner.AndroidJUnitRunner) |
| --device, -d | text | Device serial |
| --test, -t | text | Performance test to run |
| --ui | (PERFETTO\|FIREFOX\|INSTRUMENTS) | Profile viewer to open trace in |
| --format, -f | (perfetto\|simpleperf\|method) | Profiler to use for collection |
| --configPb | path | Perfetto binary config |
| --simpleperfArgs | text | Custom options for simpleperf record command |
| --symfs | path | Directory to find binaries with symbols and debug info [Simpleperf only] |
| --mapping | path | Mapping file for simpleperf deobfuscation |
| --no-show-art-frames, --show-art-frames |  | Show Android Runtime Frames |
| --remove-method | text | Remove methods matched by provided regexes (e.g. "^io\.reactivex.$" |
| --help, -h |  | Show this message and exit |

