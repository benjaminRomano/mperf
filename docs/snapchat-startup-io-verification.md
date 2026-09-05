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

## Cache and thread-state follow-up

The small amount of visible I/O wait in the overview is not evidence of a failed cache flush:

- Residency fell from 7,324 pages before eviction to zero after eviction, and was still zero at
  the final pre-launch check. This proves the checked guest app-file pages were nonresident at
  those checks, not that the host/controller cache was cold or that files remained cold after launch.
- In the 3,915.049 ms startup window, the main thread had 86 I/O-wait intervals totaling
  141.831 ms (3.62% of startup). Median wait was 0.920 ms, maximum 9.581 ms; 49 waits were under 1 ms.
  Every uninterruptible state had a known I/O-wait flag. Other app threads accumulated 224.638 ms
  of I/O wait; concurrent thread times must not be added to claim startup wall-time cost.
- ART's 11 advice spans covered 90.891 MiB. Cache insertions totaling 89.063 MiB coincided
  with those spans, with no overlapping main-thread I/O wait. This is consistent with asynchronous
  prefetch, not proof of individual insertion causality. VDEX had 81.766 MiB of cache insertions
  but only 21 major demand faults (351 demand faults overall).
- After startup began but before the first app fault, system processes inserted another
  178 Snapchat APK pages (2.781 MiB). Post-launch warming is distinct from residual pre-launch cache.
- The guest block-device trace includes 119.645 MiB of read-ahead-tagged request-issue bytes on
  device 64800 during startup. This is system-wide guest-device context, not exact app-file I/O
  attribution or measured physical media traffic.

Queries use startup bounds `[2113662077132, 2117577125759)` nanoseconds and PID 4671.
For example, run this against the associated `faults.pftrace`:

```sql
SELECT s.state, s.io_wait, COUNT(*) AS spans,
       SUM(MIN(s.ts+s.dur,2117577125759)-MAX(s.ts,2113662077132))/1e6 AS ms
FROM thread_state s JOIN thread t USING(utid)
WHERE t.tid=4671 AND s.dur>=0 AND s.ts<2117577125759
  AND s.ts+s.dur>2113662077132
GROUP BY 1,2 ORDER BY ms DESC;
```

The main-thread states sum to 3,381.947 ms; approximately 533 ms of the startup interval precedes
the thread's appearance. The 3.62% figure uses the full startup interval, not just the thread's lifetime.
The reproducible query bundle and supplementary HTML audit remain beside the local capture as
`cache-wait-audit.sql` and `cache-audit.html`.

### Kernel blocked-function symbols

The original config omitted `symbolize_ksyms`, leaving blocked-function names unavailable.
mperf now enables `symbolize_ksyms: true` whenever it selects `sched/sched_blocked_reason`.
This fixes names, not I/O-wait classification or wait counts. As documented by
[Perfetto](https://perfetto.dev/docs/learning-more/symbolization), kernel symbols must be resolved
on-device during recording; regenerating the old report cannot recover them.

Full `./gradlew check installDist` passed after the fix. A fresh five-second scheduling smoke trace
on the rooted Android 16 emulator contained resolved blocked functions, including
`folio_wait_bit_common`, `worker_thread`, and `lock_sock_nested`. No manual kernel-security
setting changes were needed. This was a symbolization test, not a new cold Snapchat benchmark.

Four subsequent strict Snapchat capture attempts were rejected before launch for residual APK
residency (62, 62, 28, and 405 pages), including a reboot and a settled retry. Read-only APK mappings
in system processes were present, but this does not establish whether pages resisted reclamation
or were reread during the check interval. The collector preserved the failure evidence and did not
relax the zero-page threshold. The earlier successful capture above remains the report under review.
