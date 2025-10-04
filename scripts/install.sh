#!/usr/bin/env bash
set -euo pipefail

REPO="benjaminromano/mperf"
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
      VERSION="$2"; shift; shift ;;
    --install-dir)
      INSTALL_DIR="$2"; shift; shift ;;
    --bin-dir)
      BIN_DIR="$2"; shift; shift ;;
    -h|--help)
      usage; exit 0 ;;
    *)
      echo "Unknown argument: $1" >&2; usage; exit 1 ;;
  esac
done

need_cmd() { command -v "$1" >/dev/null 2>&1 || { echo "Missing required command: $1" >&2; exit 1; }; }

need_cmd curl
need_cmd java

sha256_cmd() {
  if command -v sha256sum >/dev/null 2>&1; then
    echo sha256sum
  elif command -v shasum >/dev/null 2>&1; then
    echo "shasum -a 256"
  else
    echo "" # none
  fi
}

fetch_latest_json() {
  if [[ -n "$VERSION" ]]; then
    curl -fsSL "https://api.github.com/repos/$REPO/releases/tags/v$VERSION"
  else
    curl -fsSL "https://api.github.com/repos/$REPO/releases/latest"
  fi
}

find_asset_url() {
  # $1 = JSON, $2 = suffix to match
  echo "$1" | grep -Eo '"browser_download_url"\s*:\s*"[^"]+"' | \
    sed -E 's/.*"(https:[^"]+)"/\1/' | \
    grep -E -e "$2" | head -n1
}

JSON=$(fetch_latest_json)

JAR_URL=$(find_asset_url "$JSON" "-all.jar$")
SUM_URL=$(find_asset_url "$JSON" "-all.jar.sha256$")

if [[ -z "$JAR_URL" ]]; then
  echo "Failed to locate release JAR in GitHub API response." >&2
  exit 1
fi

JAR_NAME=$(basename "$JAR_URL")
VERSION_EXTRACTED=$(echo "$JAR_NAME" | sed -E 's/^mperf-([^-]+)-all\.jar/\1/')

echo "Installing mperf version: $VERSION_EXTRACTED"

mkdir -p "$INSTALL_DIR" "$BIN_DIR"

TARGET_JAR="$INSTALL_DIR/$JAR_NAME"
echo "Downloading: $JAR_URL"
curl -fL --progress-bar -o "$TARGET_JAR" "$JAR_URL"

if [[ -n "$SUM_URL" ]]; then
  echo "Downloading checksum: $SUM_URL"
  TARGET_SUM="$INSTALL_DIR/$JAR_NAME.sha256"
  curl -fL --progress-bar -o "$TARGET_SUM" "$SUM_URL"
  SUM_TOOL=$(sha256_cmd)
  if [[ -n "$SUM_TOOL" ]]; then
    echo "Verifying checksum..."
    pushd "$INSTALL_DIR" >/dev/null
    if ! $SUM_TOOL -c "$JAR_NAME.sha256" 2>/dev/null; then
      echo "Checksum verification failed" >&2
      exit 1
    fi
    popd >/dev/null
  else
    echo "Warning: sha256sum/shasum not found; skipping checksum verification"
  fi
fi

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

echo "\nDone. Try: mperf --help"
