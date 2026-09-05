# Snapchat startup report verification

Verified on September 5, 2026 with the installed Snapchat app, a rooted Android 16 arm64 emulator,
and 16 KiB pages. The flow was cold process launch to the logged-out landing screen; no sign-in was performed.

```sh
build/install/mperf/bin/mperf faults android \
  -p com.snapchat.android -d emulator-5562 \
  --native-stacks --dwarf-stacks --reclaim-mapped-apks --io-evidence \
  --out output/snapchat-speed-profile-io \
  --label 'Snapchat · speed-profile · startup I/O' --no-open
```

- ART reported `speed-profile` before and after capture, using the existing device profile.
- Pre-launch residency was zero across all 84 checked app files. The strict threshold was not relaxed.
- The startup analysis window contained 6,902 emitted faults: 447 major and 6,455 minor.
  Of the major faults, 207 had identified file-backed sources. The default report excludes anonymous and unknown mappings.
- All 447 major faults matched DWARF stacks by exact raw event identity. Native collector loss,
  integrity and throttling counters were zero; Simpleperf reported zero lost samples; Perfetto reported no data-loss errors.
- The associated `faults.pftrace` contains startup context. Exports contain 11 ART advice spans,
  10,158 block events and 5,323 thread-state rows. Block events are system-wide guest activity,
  not per-file physical-storage latency or a count of I/O requests.
- A non-essential temporary file disappeared during post-launch residency diagnostics; the warning was retained.
  Snapchat's OAT method export exceeded its 120-second bound. Compiled-method content attribution is therefore
  unavailable for this capture; exact fault addresses, read sources and DWARF caller stacks remain available.

The updated HTML was regenerated from this capture and visually checked in Chromium. The selected-fault dock
supports pointer dragging, keyboard resizing, collapse/reopen height retention and internal scrolling.
Minimum/maximum bounds were checked at 1100 × 650 as well as the normal 1440 × 1000 viewport.
Clicking **Open in Perfetto** transferred the actual trace into the public Perfetto UI locally in the browser;
CPU scheduling, ftrace and the Snapchat process appeared in its timeline. No trace upload was used.

The same UI changes were replayed into the existing ChatGPT speed-profile report. Automated verification:
`./gradlew check installDist` (228 JVM tests, zero failures, eight opt-in integration tests skipped),
including 24 shared-viewer JavaScript tests. Local capture/report files and screenshots are under ignored `output/`.
