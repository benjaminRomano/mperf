# Android profiler integration fixture

This standalone Android project builds a small profileable target app and an AndroidX Macrobenchmark APK. It is intentionally separate from the root JVM build so the CLI does not acquire Android application build dependencies.

## Local run

Boot an API 29+ emulator, then build and install both APKs:

```bash
integration-fixtures/android/install.sh emulator-5554
```

Run every Android collector end to end:

```bash
./gradlew test \
  --tests 'com.bromano.mobile.perf.integration.AndroidProfilerIntegrationTest' \
  -Dmperf.integration.enabled=true \
  -Dmperf.integration.device=emulator-5554 \
  --console=plain
```

The tests remain skipped unless `mperf.integration.enabled=true` is set. Emulator results validate collection mechanics only; use a physical, non-debuggable, profileable device build for meaningful performance comparisons.

The fixture defaults are:

- Target package: `com.bromano.mperf.fixture`
- Activity: `com.bromano.mperf.fixture/.FixtureActivity`
- Runner: `com.bromano.mperf.fixture.benchmark/androidx.test.runner.AndroidJUnitRunner`
- Test: `com.bromano.mperf.fixture.benchmark.FixtureBenchmark#startup`
