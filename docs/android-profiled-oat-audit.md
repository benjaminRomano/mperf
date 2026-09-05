# Profile-guided compilation and ODEX attribution

Verified on 2026-09-05 with ChatGPT 1.2026.237 (2623711), Android 16/API 36, arm64, 16 KiB pages, rooted Google APIs emulator `emulator-5562`. This is guest-VM evidence, not physical-device storage performance.

## Compilation: check the result, not the request

The prior full-`speed` experiment compiled far more code than the intended profile-guided scenario. The live preparation probe also demonstrated that `cmd package compile -f -m speed-profile` could print `Success` while actual ART state was only `verify`.

The installed base APK contained `assets/dexopt/baseline.prof` (46,291 bytes) and `baseline.profm` (2,884 bytes). Its explicit AndroidX ProfileInstaller broadcast returned result `1`. After force-stop and recompilation, ART reported `speed-profile`. The ODEX was 25,138,704 bytes, versus 179,972,848 bytes in the earlier full-speed experiment. This is a compilation-footprint comparison, not a controlled startup-speed result.

New captures default to `--compilation speed-profile`. Preparation checks each code-bearing APK for the target ISA, tries an existing profile, and then uses the embedded baseline through ProfileInstaller if necessary. ProfileInstaller performs version-specific profile transcoding; blindly copying the APK's profile into ART's profile directory is not portable. Missing/failed installation or an unexpected actual compiler filter stops collection. There is no full-speed fallback. `--compilation as-is` explicitly permits other compilation experiments.

Profile preparation can start an application process. It happens before the final force-stop and strict cache eviction, never inside the measurement window. Actual compilation state is checked again before eviction and after capture. Comparison reports require an override when preparation or actual filters differ. Existing profiles are retained: this is not a claim to isolate the baseline from all previously learned profile entries.

## ODEX code → DEX method

The collector runs the device's version-matched `oatdump` after recording. It extracts explicit method declarations and code sizes, then:

1. Matches the complete set of ART DEX locations/checksums to corresponding APK DEX entries.
2. Converts ELF load addresses to file offsets and requires each range to lie in stored `.text`.
3. Binds the dump to captured APK, ODEX, and VDEX hashes, checked against the device before and after extraction.
4. Matches the exact fault offset against half-open code ranges. Padding, metadata and gaps are not assigned the nearest method.

ART can deduplicate compiled code across methods and DEX files. Identical-range aliases are retained; a unique DEX is not invented when aliases disagree. Page-overlapping methods are separate from the method containing the exact address. Both describe **code content**, not proof that the method executed or caused a data fault. Caller evidence still comes from the captured stack. ODEX compiled layout is not identical to R8 DEX layout. Friendly original names require a matching R8 mapping when the APK is obfuscated.

The compact JSON method/offset mode lacks explicit sizes and DEX identity; it is not used for nearest-symbol guessing. Normal OAT dumps can contain gigabytes of verifier text even with native disassembly disabled. Fixed-string filtering keeps the recorded metadata bounded (64 MiB, 120-second tool timeout). In this test it retained 25,466 compiled-method ranges. Unsupported ABI/format, missing tools, checksum mismatches or extraction failures leave a warning and preserve the fault capture. See [ART oatdump implementation](https://android.googlesource.com/platform/art/+/master/oatdump/oatdump.cc).

## Capture and report verification

Local capture: `output/chatgpt-speed-profile-io`, generated with:

```sh
build/install/mperf/bin/mperf faults android \
  --package com.openai.chatgpt --device emulator-5562 \
  --native-stacks --reclaim-mapped-apks --io-evidence \
  --out output/chatgpt-speed-profile-io \
  --label 'Baseline speed-profile · I/O evidence' --no-open
```

The capture passed zero-residency verification across 112 app files immediately before launch, and verified `speed-profile` before and after. Native collector loss, integrity and throttling counters were zero; Perfetto reported no integrity/data-loss errors. There were 9,789 emitted faults, including 3,073 file-backed faults (199 major, 2,874 minor). All emitted faults had native callchains; this does not guarantee complete Java/DWARF unwinding.

Of 305 `base.odex` faults, 226 addresses fell inside verified compiled-method ranges. **All 226 were minor in this run.** The five ODEX major faults were in metadata/data or outside named sections, not compiled-method bodies. For example, capture sequence 2890 mapped to `classes.dex #40780: void papa.PerfAppComponentFactory.<init>()`. This result is consistent with code prefetching and is not a reason to fabricate major code faults. Enable major + minor in the report to inspect code demands.

The first OAT extraction timed out. A post-capture retry with the fixed-string extractor succeeded only after the same captured APK/ODEX/VDEX hashes were revalidated. The historical timeout is preserved under `oat_attribution_retry`; recording and fault identities were not replaced. Two subsequent startup attempts refused to collect because 64 and 67 app pages remained resident. Those failed preflights are retained separately; the cache threshold was not relaxed.

Optional I/O exports contained eight ART advice spans, 6,588 block-event rows and 10,179 app thread-state rows. The main thread had 247.476 ms of startup-overlapping intervals marked `io_wait`. One guest block-device ID (64800) issued 92.711 MiB of read requests during the 4,646.706 ms startup window. These are system-wide requests on that device, not bytes attributed to ChatGPT, unique file bytes, or physical-media reads. Do not sum across block layers or sum overlapping thread waits into startup latency.

The existing fault reader remains the demand/locality view. `--io-evidence` adds the raw synchronized evidence needed to investigate advice, cache fills, read traffic and stalls in Perfetto. A combined I/O-lane visualization is a next step, not part of this patch.
