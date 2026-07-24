# Third-Party Notices

The startup page-fault report bundles the following third-party components so generated HTML reports and Android trace
processing work without fetching scripts at report-view time.

## Plotly.js

`src/main/resources/faults-engine/ios/ios_fault_visualizer/assets/plotly.min.js`

Copyright 2012–2026 Plotly, Inc.

Licensed under the MIT License. The minified distribution retains its upstream license header.

## Perfetto Trace Processor bootstrap

`src/main/resources/faults-engine/android/trace_processor`

Copyright The Android Open Source Project.

Licensed under the Apache License, Version 2.0. The bootstrap script retains its upstream license header and downloads
the matching Trace Processor binary on first use.
