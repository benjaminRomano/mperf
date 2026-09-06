const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { spawnSync } = require("node:child_process");
const test = require("node:test");

function runFixture(args, overrides = {}) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "mperf-compatibility-test-"));
  try {
    for (const dir of ["scripts", "bin", "integration-fixtures/android"]) {
      fs.mkdirSync(path.join(root, dir), { recursive: true });
    }
    fs.copyFileSync(path.resolve("scripts/run-android-integration.sh"), path.join(root, "scripts/run-android-integration.sh"));
    fs.writeFileSync(path.join(root, "bin/adb"), `#!/usr/bin/env bash
set -eu
[[ "$1" == -s && "$2" == fixture-device ]]
shift 2
case "$*" in
  get-state) echo device ;;
  'shell getprop ro.build.version.sdk') echo "$TEST_API" ;;
  'shell getconf PAGE_SIZE') echo "$TEST_PAGE_SIZE" ;;
  'shell getprop ro.product.cpu.abi') echo "$TEST_ABI" ;;
  'shell getprop ro.kernel.qemu') echo "$TEST_EMULATOR" ;;
  "shell awk '/^KernelPageSize:/ {print "*"; exit}' /proc/self/smaps") echo "$TEST_KERNEL_PAGE_KB" ;;
  'shell id -u') echo "$TEST_UID" ;;
  "shell su 0 sh -c 'id -u'") echo "$TEST_SU_UID" ;;
  "shell su -c 'id -u'") echo "$TEST_SU_UID" ;;
  *) echo "Unexpected adb mutation: $*" >&2; exit 99 ;;
esac
`, { mode: 0o755 });
    fs.writeFileSync(path.join(root, "integration-fixtures/android/install.sh"),
      '#!/usr/bin/env bash\necho fixture-install >> "$TEST_CALLS"\n', { mode: 0o755 });
    fs.writeFileSync(path.join(root, "gradlew"),
      '#!/usr/bin/env bash\necho "$*" >> "$TEST_CALLS"\necho fixture-test-output\nexit "${TEST_EXIT:-0}"\n', { mode: 0o755 });
    const result = spawnSync("bash", [path.join(root, "scripts/run-android-integration.sh"), ...args], {
      encoding: "utf8",
      timeout: 10000,
      env: {
        ...process.env,
        PATH: `${root}/bin:${process.env.PATH}`,
        TEST_CALLS: path.join(root, "calls"),
        TEST_API: "35",
        TEST_PAGE_SIZE: "4096",
        TEST_KERNEL_PAGE_KB: "4",
        TEST_ABI: "x86_64",
        TEST_EMULATOR: "1",
        TEST_UID: "0",
        TEST_SU_UID: "0",
        ...overrides,
      },
    });
    assert.ifError(result.error);
    const calls = fs.existsSync(path.join(root, "calls")) ? fs.readFileSync(path.join(root, "calls"), "utf8") : "";
    const artifacts = path.join(root, "build/integration-artifacts");
    const directories = fs.existsSync(artifacts) ? fs.readdirSync(artifacts) : [];
    const metadata = directories.map(dir => {
      const file = path.join(artifacts, dir, "device-compatibility.txt");
      return fs.existsSync(file) ? fs.readFileSync(file, "utf8") : "";
    }).join("");
    return { ...result, calls, metadata };
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
}

test("emulator compatibility validates the actual device and runs fixture tests", () => {
  const result = runFixture(["fixture-device", "35", "16384"], {
    TEST_PAGE_SIZE: "16384", TEST_KERNEL_PAGE_KB: "16", TEST_ABI: "arm64-v8a",
  });
  assert.equal(result.status, 0, result.stderr + result.stdout);
  assert.match(result.calls, /fixture-install/);
  assert.match(result.calls, /-Dmperf.integration.device=fixture-device/);
  assert.match(result.calls, /AndroidProfilerIntegrationTest/);
  assert.match(result.metadata, /userspace_page_size=16384/);
  assert.match(result.metadata, /kernel_page_size=16384/);
  assert.doesNotMatch(result.metadata, /fixture-device/);
});

test("mismatched API, page size, and device type fail before installing", () => {
  for (const overrides of [{ TEST_API: "34" }, { TEST_PAGE_SIZE: "16384" }, { TEST_EMULATOR: "0" }]) {
    const result = runFixture(["fixture-device", "35", "4096"], overrides);
    assert.notEqual(result.status, 0);
    assert.equal(result.calls, "");
  }
});

test("physical mode requires real hardware and existing root or su access", () => {
  for (const overrides of [
    { TEST_EMULATOR: "1" },
    { TEST_EMULATOR: "0", TEST_UID: "2000", TEST_SU_UID: "2000" },
  ]) {
    const result = runFixture(["fixture-device", "35", "4096", "physical"], overrides);
    assert.notEqual(result.status, 0);
    assert.equal(result.calls, "");
  }
  const result = runFixture(["fixture-device", "35", "4096", "physical"], {
    TEST_EMULATOR: "0", TEST_UID: "2000", TEST_SU_UID: "0",
  });
  assert.equal(result.status, 0, result.stderr + result.stdout);
  assert.match(result.calls, /fixture-install/);
});

test("invalid explicit selectors are rejected and Gradle failures are preserved", () => {
  for (const args of [[], ["bad;serial", "35", "4096"], ["fixture-device", "bad", "4096"],
    ["fixture-device", "35", "8192"], ["fixture-device", "35", "4096", "unknown"],
    ["fixture-device", "35", "4096", "emulator", "unknown"]]) {
    const result = runFixture(args);
    assert.notEqual(result.status, 0);
    assert.equal(result.calls, "");
  }
  const result = runFixture(["fixture-device", "35", "4096"], { TEST_EXIT: "7" });
  assert.equal(result.status, 7);
  assert.match(result.stdout, /fixture-test-output/);
});

test("fault capture is included only in the explicit compatibility suite", () => {
  const standard = runFixture(["fixture-device", "35", "4096"]);
  assert.equal(standard.status, 0);
  assert.doesNotMatch(standard.calls, /AndroidFaultCaptureIntegrationTest/);
  const compatibility = runFixture(["fixture-device", "35", "4096", "emulator", "faults"]);
  assert.equal(compatibility.status, 0);
  assert.match(compatibility.calls, /AndroidFaultCaptureIntegrationTest/);
});

test("16K userspace simulation cannot pass as real kernel page-size coverage", () => {
  for (const overrides of [
    { TEST_PAGE_SIZE: "16384" },
    { TEST_PAGE_SIZE: "16384", TEST_KERNEL_PAGE_KB: "16" },
    { TEST_PAGE_SIZE: "16384", TEST_ABI: "arm64-v8a" },
    { TEST_KERNEL_PAGE_KB: "" },
  ]) {
    const result = runFixture(["fixture-device", "35", "16384", "emulator", "faults"], overrides);
    assert.notEqual(result.status, 0);
    assert.equal(result.calls, "");
  }
});
