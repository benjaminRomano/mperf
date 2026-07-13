#!/usr/bin/env bash
set -euo pipefail

REPO="${MPERF_REPOSITORY:-benjaminromano/mperf}"
GITHUB_API_URL="${GITHUB_API_URL:-https://api.github.com}"
INSTALL_DIR_DEFAULT="$HOME/.local/share/mperf"
BIN_DIR_DEFAULT="$HOME/.local/bin"

VERSION=""
INSTALL_DIR="${INSTALL_DIR:-$INSTALL_DIR_DEFAULT}"
BIN_DIR="${BIN_DIR:-$BIN_DIR_DEFAULT}"

usage() {
  cat <<EOF
Usage: install.sh [--version X.Y.Z] [--install-dir DIR] [--bin-dir DIR]

Downloads the latest mperf release (or a specific version) and installs a
wrapper executable named 'mperf' that runs the JAR.

Options:
  --version X.Y.Z     Install a specific version (defaults to latest)
  --install-dir DIR   Where to store downloaded JARs (default: $INSTALL_DIR_DEFAULT)
  --bin-dir DIR       Where to place the 'mperf' launcher (default: $BIN_DIR_DEFAULT)
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --version)
      [[ $# -ge 2 ]] || { echo "Missing value for --version" >&2; exit 1; }
      VERSION="${2#v}"; shift 2 ;;
    --install-dir)
      [[ $# -ge 2 ]] || { echo "Missing value for --install-dir" >&2; exit 1; }
      INSTALL_DIR="$2"; shift 2 ;;
    --bin-dir)
      [[ $# -ge 2 ]] || { echo "Missing value for --bin-dir" >&2; exit 1; }
      BIN_DIR="$2"; shift 2 ;;
    -h|--help)
      usage; exit 0 ;;
    *)
      echo "Unknown argument: $1" >&2; usage; exit 1 ;;
  esac
done

if [[ -n "$VERSION" && ! "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$ ]]; then
  echo "Version must be a SemVer value such as 1.2.3 or 1.2.3-rc.1" >&2
  exit 1
fi

need_cmd() { command -v "$1" >/dev/null 2>&1 || { echo "Missing required command: $1" >&2; exit 1; }; }

need_cmd curl
need_cmd java
need_cmd python3

calculate_sha256() {
  local file="$1"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$file" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$file" | awk '{print $1}'
  else
    echo "Missing required command: sha256sum or shasum" >&2
    exit 1
  fi
}

fetch_latest_json() {
  local endpoint="releases/latest"
  if [[ -n "$VERSION" ]]; then
    endpoint="releases/tags/v$VERSION"
  fi
  curl --fail --location --silent --show-error --retry 3 --retry-all-errors \
    --header "Accept: application/vnd.github+json" \
    --header "X-GitHub-Api-Version: 2022-11-28" \
    "$GITHUB_API_URL/repos/$REPO/$endpoint"
}

find_asset_url() {
  local json="$1"
  local suffix="$2"
  python3 -c 'import json, sys
data = json.load(sys.stdin)
suffix = sys.argv[1]
matches = [asset["browser_download_url"] for asset in data.get("assets", []) if asset.get("name", "").endswith(suffix)]
if len(matches) != 1:
    raise SystemExit(f"expected one release asset ending in {suffix!r}, found {len(matches)}")
print(matches[0])' "$suffix" <<< "$json"
}

JSON=$(fetch_latest_json)

JAR_URL=$(find_asset_url "$JSON" "-all.jar")
SUM_URL=$(find_asset_url "$JSON" "-all.jar.sha256")

JAR_NAME=$(basename "$JAR_URL")
VERSION_EXTRACTED=${JAR_NAME#mperf-}
VERSION_EXTRACTED=${VERSION_EXTRACTED%-all.jar}

echo "Installing mperf version: $VERSION_EXTRACTED"

mkdir -p "$INSTALL_DIR" "$BIN_DIR"

TARGET_JAR="$INSTALL_DIR/$JAR_NAME"
TEMP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/mperf-install.XXXXXX")
trap 'rm -rf "$TEMP_DIR"' EXIT
TEMP_JAR="$TEMP_DIR/$JAR_NAME"
TEMP_SUM="$TEMP_DIR/$JAR_NAME.sha256"

echo "Downloading: $JAR_URL"
curl --fail --location --silent --show-error --retry 3 --retry-all-errors -o "$TEMP_JAR" "$JAR_URL"

echo "Downloading checksum: $SUM_URL"
curl --fail --location --silent --show-error --retry 3 --retry-all-errors -o "$TEMP_SUM" "$SUM_URL"

echo "Verifying checksum..."
EXPECTED_SUM=$(awk 'NR == 1 { print $1 }' "$TEMP_SUM" | tr '[:upper:]' '[:lower:]')
ACTUAL_SUM=$(calculate_sha256 "$TEMP_JAR" | tr '[:upper:]' '[:lower:]')
if [[ ! "$EXPECTED_SUM" =~ ^[0-9a-f]{64}$ || "$EXPECTED_SUM" != "$ACTUAL_SUM" ]]; then
  echo "Checksum verification failed" >&2
  exit 1
fi

mv "$TEMP_JAR" "$TARGET_JAR"
cp "$TEMP_SUM" "$INSTALL_DIR/$JAR_NAME.sha256"

# Maintain a stable symlink to the latest jar
ln -sf "$TARGET_JAR" "$INSTALL_DIR/mperf-latest.jar"

# Create wrapper
LAUNCHER="$BIN_DIR/mperf"
cat > "$LAUNCHER" <<LAUNCH
#!/usr/bin/env bash
exec java -jar "$INSTALL_DIR/mperf-latest.jar" "\$@"
LAUNCH
chmod +x "$LAUNCHER"

# Create CLI aliases for platform-specific invocations
APERF_LAUNCHER="$BIN_DIR/aperf"
cat > "$APERF_LAUNCHER" <<'APERF'
#!/usr/bin/env bash
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
exec "$DIR/mperf" android "$@"
APERF
chmod +x "$APERF_LAUNCHER"

IPERF_LAUNCHER="$BIN_DIR/iperf"
cat > "$IPERF_LAUNCHER" <<'IPERF'
#!/usr/bin/env bash
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
exec "$DIR/mperf" ios "$@"
IPERF
chmod +x "$IPERF_LAUNCHER"

echo "Installed mperf to: $TARGET_JAR"
echo "Launcher created at: $LAUNCHER"
echo "Android alias created at: $APERF_LAUNCHER"
echo "iOS alias created at: $IPERF_LAUNCHER"

case ":$PATH:" in
  *":$BIN_DIR:"*) ;;
  *)
    echo "Note: $BIN_DIR is not on your PATH. Add it, e.g.:"
    echo "  export PATH=\"$BIN_DIR:\$PATH\""
    ;;
esac

printf '\n'

BOX_LINES=(
  "Installed commands for mperf $VERSION_EXTRACTED"
  "mperf   -> $LAUNCHER"
  "aperf   -> $APERF_LAUNCHER"
  "iperf   -> $IPERF_LAUNCHER"
  "Run 'mperf --help' to get started"
)

max_len=0
for line in "${BOX_LINES[@]}"; do
  if (( ${#line} > max_len )); then
    max_len=${#line}
  fi
done

border=$(printf '%*s' "$((max_len + 4))" '' | tr ' ' '#')
echo "$border"
for line in "${BOX_LINES[@]}"; do
  printf "# %-*s #\n" "$max_len" "$line"
done
echo "$border"

printf '\nDone. Try: mperf --help\n'
