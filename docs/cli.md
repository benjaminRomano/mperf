# mperf
**Options**

| Name(s) | Metavar | Description |
|---|---|---|
| --help, -h |  | Show this message and exit |

**Commands**

| Name | Description |
|---|---|
| start | Run profiler over abitrary app session |
| collect | Collect performance data over single iteration of a performance test |

## start
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
| --ui | (PERFETTO\|FIREFOX) | Profile viewer to open trace in |
| --help, -h |  | Show this message and exit |

## collect
**Options**

| Name(s) | Metavar | Description |
|---|---|---|
| --out, -o | path | Output path for trace |
| --package, -p | text | Package name |
| --instrumentation, -i | text | Instrumentation runner (e.g. com.example.macrobenchmark/androidx.test.runner.AndroidJUnitRunner) |
| --device, -d | text | Device serial |
| --test, -t | text | Performance test to run |
| --ui | (PERFETTO\|FIREFOX) | Profile viewer to open trace in |
| --format, -f | (perfetto\|simpleperf\|method) | Profiler to use for collection |
| --configPb | path | Perfetto binary config |
| --simpleperfArgs | text | Custom options for simpleperf record command |
| --symfs | path | Directory to find binaries with symbols and debug info [Simpleperf only] |
| --mapping | path | Mapping file for simpleperf deobfuscation |
| --no-show-art-frames, --show-art-frames |  | Show Android Runtime Frames |
| --remove-method | text | Remove methods matched by provided regexes (e.g. "^io\.reactivex.$" |
| --help, -h |  | Show this message and exit |

