# ChatGPT: reset compilation versus full AOT

Two fresh mperf captures on 2026-09-05 show substantially more ODEX demand after full AOT compilation. Both passed strict zero-resident-page verification across 113 app files and reported zero collector loss. These are single-launch page-pattern observations, not evidence that AOT makes startup slower or faster.

## Preparation and verified state

The target was the supplied ChatGPT 1.2026.237 APK, Android 16/API 36 Google APIs arm64 emulator, with 16 KiB pages. App data was retained and no login was performed. The comparison resets **ART compilation state**, not the entire application; it is not a literal reinstall or a guarantee of identical application/network state.

```bash
adb -s "$SERIAL" shell am force-stop com.openai.chatgpt
adb -s "$SERIAL" shell pm compile --reset com.openai.chatgpt
adb -s "$SERIAL" shell pm art dump com.openai.chatgpt
# Capture reset state before changing compilation again.

adb -s "$SERIAL" shell am force-stop com.openai.chatgpt
adb -s "$SERIAL" shell pm compile -m speed -f --primary-dex -v com.openai.chatgpt
adb -s "$SERIAL" shell pm art dump com.openai.chatgpt
```

ART reported `verify`, reason `install`, before and after the reset capture. For AOT, the compile command reported `actualCompilerFilter=speed`, `status=PERFORMED`; the before/after capture dumps both reported `speed`, reason `cmdline`.

On modern Android, `--reset` can restore code from externally supplied profiles. It must not be assumed to mean no AOT code. Here, the actual `verify` state and an empty ODEX `.text` section independently confirm the absence of compiled code in that file. Full `speed` compilation is intentionally different from profile-guided `speed-profile` compilation. [AOSP compilation/reset semantics](https://source.android.com/docs/core/runtime/jit-compiler)

| Pulled artifact evidence | Reset (`verify`) | Full AOT (`speed`) |
| --- | ---: | ---: |
| `base.odex` bytes | 541,536 | 179,972,848 |
| ODEX ELF `.text` bytes | 0 | 144,866,888 |
| `base.vdex` bytes | 1,478,852 | 1,478,288 |

These sizes were read from pulled artifacts and their ELF section headers, not inferred from file extensions. The AOT compiler reported 181,451,136 combined artifact bytes. Original DEX payloads remain in `base.apk`; AOT adds native machine code and metadata in ODEX rather than converting VDEX into the primary bytecode container.

## Observed startup faults

| Measurement | Reset (`verify`) | Full AOT (`speed`) |
| --- | ---: | ---: |
| All emitted faults | 10,473 | 11,287 |
| All emitted major faults | 220 | 692 |
| File-backed faults | 2,734 | 5,021 |
| File-backed major faults | 172 | 374 |
| `base.odex` faults / major | 14 / 1 | 2,209 / 131 |
| ODEX `.text` faults / major | 0 / 0 | 1,519 / 93 |
| ODEX `.rodata` faults / major | 9 / 0 | 681 / 36 |
| `base.vdex` faults / major | 24 / 9 | 31 / 9 |
| APK DEX payload faults / major | 552 / 7 | 610 / 7 |
| Correlated page-cache insertions | 4,770 | 12,194 |
| Prelaunch resident pages | 0 / 6,111 | 0 / 17,062 |

The AOT ODEX touches 1,795 distinct file pages; 1,244 are touched by faults whose byte address falls in `.text`. Section counts classify the exact fault address; boundary-straddling pages are not treated as exclusive section ownership. The increase in ODEX accesses is expected evidence of the much larger compiled artifact being used. It does not by itself predict the benefit of code ordering.

All seven APK DEX payloads still have one major fault each in both runs. AOT does not eliminate bytecode/metadata reads. Interpret the full demanded-page order, not just major counts: prefetch can make later DEX demands minor. Cache insertions are correlated activity, not a one-to-one explanation of fault causality.

The observed Perfetto startup intervals were 1,244 ms and 1,378 ms. Do not interpret that single-pair difference as an AOT performance regression: emulator load, system cache state, reboot timing, background work, and retained app data are not controlled repetitions. No storage latency claim is made.

## Reports and reproducibility

Successful captures are local ignored artifacts:

- `output/chatgpt-compilation-reset-settled/` — reset run and combined two-run `report.html`.
- `output/chatgpt-compilation-speed-reboot-settled/` — AOT run and its individual `report.html`.

Both successful CLI captures used `--native-stacks --reclaim-mapped-apks` and a zero residency limit. Each was taken after a reboot followed by settling; reboot was outside the successful CLI invocation. Earlier attempts were rejected for retained APK pages (78 for reset, then 9 for AOT). Their diagnostics were retained, not relabeled as cold reports.

The captures save `compilation-before.txt`, `compilation-after.txt`, their hashes/statuses in `capture_metadata.json`, exact faults/mappings/callchains, cache residency, Perfetto, and pulled APK/ART artifacts. The `.text` evidence can be reproduced with `llvm-readelf -S` on each pulled ODEX.

```bash
mperf faults android --skip-collect \
  --out output/chatgpt-compilation-reset-settled \
  --label 'Reset install state (verify)' \
  --compare output/chatgpt-compilation-speed-reboot-settled \
  --compare-label 'AOT compiled (speed)'
```

In the report, switch **Run**, select `oat/arm64/base.odex`, then use **File page index** against **Recorded fault index**. In the AOT run, **Region → Section · .text** isolates compiled-code accesses. **Stack chart** and **Fault list** retain captured native callchains; these runs did not enable the optional DWARF companion. Use the APK source and DEX region filter for original bytecode payloads. Virtual addresses are ASLR-dependent and should not be compared as equivalent coordinates across runs.
