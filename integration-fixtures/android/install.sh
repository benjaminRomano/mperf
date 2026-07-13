#!/usr/bin/env bash
set -euo pipefail

fixture_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd "$fixture_dir/../.." && pwd)"
device="${1:-${ANDROID_SERIAL:-}}"

if [[ -z "$device" ]]; then
  device="$(adb devices | awk 'NR > 1 && $2 == "device" { print $1; exit }')"
fi
if [[ -z "$device" ]]; then
  echo "No booted Android device found. Pass a serial or set ANDROID_SERIAL." >&2
  exit 1
fi

"$repo_dir/gradlew" -p "$fixture_dir" :target:assembleRelease :benchmark:assembleBenchmark --console=plain

target_apk="$fixture_dir/target/build/outputs/apk/release/target-release.apk"
benchmark_apk="$fixture_dir/benchmark/build/outputs/apk/benchmark/benchmark-benchmark.apk"
adb -s "$device" install -r "$target_apk"
adb -s "$device" install -r "$benchmark_apk"

echo "Installed Android profiling fixture on $device"
echo "  package: com.bromano.mperf.fixture"
echo "  activity: com.bromano.mperf.fixture/.FixtureActivity"
echo "  instrumentation: com.bromano.mperf.fixture.benchmark/androidx.test.runner.AndroidJUnitRunner"
echo "  test: com.bromano.mperf.fixture.benchmark.FixtureBenchmark#startup"
