# Third-Party Notices

Generated startup page-fault reports bundle Plotly for offline viewing. Android profiling tools are downloaded
separately on first use and verified against pinned SHA-256 checksums.

## Plotly.js

`src/main/resources/faults-engine/shared/plotly.min.js`

Copyright 2012–2026 Plotly, Inc.

Licensed under the MIT License. The minified distribution retains its upstream license header.

## Perfetto and Simpleperf

Perfetto Trace Processor and tracebox: https://github.com/google/perfetto

Simpleperf binaries and conversion scripts: https://android.googlesource.com/platform/prebuilts/simpleperf/ and
https://android.googlesource.com/platform/system/extras/+/main/simpleperf/

Copyright The Android Open Source Project.

Licensed under the Apache License, Version 2.0. Versions, source URLs, and integrity checks are maintained in
`src/main/kotlin/com/bromano/mobile/perf/tools/`. These tools are not bundled in the CLI JAR.
