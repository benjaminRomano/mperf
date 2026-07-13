#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
TEST_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/mperf-install-test.XXXXXX")
trap 'rm -rf "$TEST_ROOT"' EXIT

FAKE_BIN="$TEST_ROOT/bin"
INSTALL_DIR="$TEST_ROOT/install dir"
BIN_DIR="$TEST_ROOT/command dir"
FIXTURE_JAR="$TEST_ROOT/mperf-1.2.3-rc.1-all.jar"
FIXTURE_SUM="$FIXTURE_JAR.sha256"
mkdir -p "$FAKE_BIN"
printf 'test release jar\n' > "$FIXTURE_JAR"

if command -v sha256sum >/dev/null 2>&1; then
  sha256sum "$FIXTURE_JAR" > "$FIXTURE_SUM"
else
  shasum -a 256 "$FIXTURE_JAR" > "$FIXTURE_SUM"
fi

cat > "$FAKE_BIN/curl" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

output=""
url=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    -o)
      output="$2"
      shift 2
      ;;
    --header)
      shift 2
      ;;
    --*)
      shift
      ;;
    *)
      url="$1"
      shift
      ;;
  esac
done

case "$url" in
  */repos/test/mperf/releases/latest)
    printf '%s\n' '{"assets":[' \
      '{"name":"mperf-1.2.3-rc.1-all.jar","browser_download_url":"https://example.test/mperf-1.2.3-rc.1-all.jar"},' \
      '{"name":"mperf-1.2.3-rc.1-all.jar.sha256","browser_download_url":"https://example.test/mperf-1.2.3-rc.1-all.jar.sha256"}' \
      ']}'
    ;;
  https://example.test/mperf-1.2.3-rc.1-all.jar)
    cp "$MPERF_TEST_JAR" "$output"
    ;;
  https://example.test/mperf-1.2.3-rc.1-all.jar.sha256)
    cp "$MPERF_TEST_SUM" "$output"
    ;;
  *)
    echo "Unexpected test URL: $url" >&2
    exit 1
    ;;
esac
EOF
chmod +x "$FAKE_BIN/curl"

export MPERF_TEST_JAR="$FIXTURE_JAR"
export MPERF_TEST_SUM="$FIXTURE_SUM"
PATH="$FAKE_BIN:$PATH" \
  MPERF_REPOSITORY="test/mperf" \
  GITHUB_API_URL="https://api.example.test" \
  "$REPO_ROOT/scripts/install.sh" --install-dir "$INSTALL_DIR" --bin-dir "$BIN_DIR"

test -f "$INSTALL_DIR/mperf-1.2.3-rc.1-all.jar"
test -f "$INSTALL_DIR/mperf-1.2.3-rc.1-all.jar.sha256"
test -L "$INSTALL_DIR/mperf-latest.jar"
test -x "$BIN_DIR/mperf"
test -x "$BIN_DIR/aperf"
test -x "$BIN_DIR/iperf"
cmp "$FIXTURE_JAR" "$INSTALL_DIR/mperf-1.2.3-rc.1-all.jar"

"$REPO_ROOT/scripts/uninstall.sh" --install-dir "$INSTALL_DIR" --bin-dir "$BIN_DIR"
test ! -e "$INSTALL_DIR"
test ! -e "$BIN_DIR/mperf"

BAD_SUM="$TEST_ROOT/bad.sha256"
printf '%064d  %s\n' 0 "$(basename "$FIXTURE_JAR")" > "$BAD_SUM"
export MPERF_TEST_SUM="$BAD_SUM"
if PATH="$FAKE_BIN:$PATH" \
  MPERF_REPOSITORY="test/mperf" \
  GITHUB_API_URL="https://api.example.test" \
  "$REPO_ROOT/scripts/install.sh" --install-dir "$INSTALL_DIR" --bin-dir "$BIN_DIR" >/dev/null 2>&1; then
  echo "install.sh accepted an invalid checksum" >&2
  exit 1
fi
test ! -e "$INSTALL_DIR/mperf-1.2.3-rc.1-all.jar"

if "$REPO_ROOT/scripts/install.sh" --version >/dev/null 2>&1; then
  echo "install.sh accepted a missing --version value" >&2
  exit 1
fi

echo "Install and uninstall script tests passed"
