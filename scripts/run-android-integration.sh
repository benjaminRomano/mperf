#!/usr/bin/env bash
set -euo pipefail

# Explicit device selection is required: never fall back to somebody's attached phone.
device="${1:?Usage: run-android-integration.sh SERIAL EXPECTED_API EXPECTED_PAGE_SIZE [emulator|physical] [profilers|faults]}"
expected_api="${2:?Expected Android API level required}"
expected_page_size="${3:?Expected page size (4096 or 16384) required}"
mode="${4:-emulator}"
suite="${5:-profilers}"
[[ "$device" =~ ^[A-Za-z0-9_.:-]+$ ]] || { echo "Invalid device serial" >&2; exit 2; }
[[ "$expected_api" =~ ^[0-9]+$ ]] || { echo "Invalid API level" >&2; exit 2; }
[[ "$expected_page_size" == 4096 || "$expected_page_size" == 16384 ]] || exit 2
[[ "$mode" == emulator || "$mode" == physical ]] || exit 2
[[ "$suite" == profilers || "$suite" == faults ]] || exit 2

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_dir"
# A unique directory keeps local reruns and CI retries from replacing earlier evidence.
mkdir -p build/integration-artifacts
export MPERF_INTEGRATION_ARTIFACTS
MPERF_INTEGRATION_ARTIFACTS="$(mktemp -d "$repo_dir/build/integration-artifacts/android-run.XXXXXX")"

[[ "$(adb -s "$device" get-state)" == device ]]
actual_api="$(adb -s "$device" shell getprop ro.build.version.sdk | tr -d '\r')"
actual_page_size="$(adb -s "$device" shell getconf PAGE_SIZE | tr -d '\r')"
actual_abi="$(adb -s "$device" shell getprop ro.product.cpu.abi | tr -d '\r')"
is_emulator="$(adb -s "$device" shell getprop ro.kernel.qemu | tr -d '\r')"
# x86_64 ps16k images can expose 16K userspace on a 4K kernel. Fault ring buffers and
# filemap page indices use kernel pages, so getconf alone is not a coverage check.
kernel_page_kb="$(adb -s "$device" shell "awk '/^KernelPageSize:/ {print \$2; exit}' /proc/self/smaps" | tr -d '\r')"
case "$kernel_page_kb" in
  4) kernel_page_size=4096 ;;
  16) kernel_page_size=16384 ;;
  *) echo "Cannot establish a supported kernel page size from /proc/self/smaps: $kernel_page_kb" >&2; exit 1 ;;
esac
printf 'api=%s\nuserspace_page_size=%s\nkernel_page_size=%s\nabi=%s\nmode=%s\n' \
  "$actual_api" "$actual_page_size" "$kernel_page_size" "$actual_abi" "$mode" \
  | tee "$MPERF_INTEGRATION_ARTIFACTS/device-compatibility.txt"
[[ "$actual_api" == "$expected_api" ]] || { echo "Unexpected Android API level" >&2; exit 1; }
[[ "$actual_page_size" == "$expected_page_size" ]] || { echo "Unexpected device page size" >&2; exit 1; }
[[ "$kernel_page_size" == "$expected_page_size" ]] || { echo "Kernel and requested page sizes differ; simulated userspace is not kernel coverage" >&2; exit 1; }
if [[ "$expected_page_size" == 16384 && "$actual_abi" != arm64-v8a ]]; then
  echo "Genuine 16K kernel capture requires ARM64, not simulated x86_64 userspace" >&2
  exit 1
fi
if [[ "$mode" == physical ]]; then
  [[ "$is_emulator" != 1 ]] || { echo "Physical-device run requires real hardware" >&2; exit 1; }
  # Do not root/reboot a device or change its page-size configuration automatically.
  uid="$(adb -s "$device" shell id -u | tr -d '\r')"
  if [[ "$uid" != 0 ]]; then
    uid="$(adb -s "$device" shell "su 0 sh -c 'id -u'" 2>/dev/null | tr -d '\r')" || uid=""
  fi
  if [[ "$uid" != 0 ]]; then
    uid="$(adb -s "$device" shell "su -c 'id -u'" 2>/dev/null | tr -d '\r')" || uid=""
  fi
  [[ "$uid" == 0 ]] || { echo "Preconfigured root access is required" >&2; exit 1; }
else
  [[ "$is_emulator" == 1 ]] || { echo "Emulator run refuses physical hardware" >&2; exit 1; }
fi

integration-fixtures/android/install.sh "$device" 2>&1 | tee "$MPERF_INTEGRATION_ARTIFACTS/fixture-run.log"
export MPERF_TEST_FAULTS=false
tests=(--tests com.bromano.mobile.perf.integration.AndroidProfilerIntegrationTest)
if [[ "$suite" == faults ]]; then
  export MPERF_TEST_FAULTS=true
  tests+=(--tests com.bromano.mobile.perf.integration.AndroidFaultCaptureIntegrationTest)
  tests+=(--tests com.bromano.mobile.perf.faults.AndroidStartupWindowTest)
fi
./gradlew test --no-daemon --stacktrace --console=plain \
  -Dmperf.integration.enabled=true "-Dmperf.integration.device=$device" \
  "${tests[@]}" 2>&1 | tee -a "$MPERF_INTEGRATION_ARTIFACTS/fixture-run.log"
