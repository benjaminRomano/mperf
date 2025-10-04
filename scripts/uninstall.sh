#!/usr/bin/env bash
set -euo pipefail

INSTALL_DIR_DEFAULT="$HOME/.local/share/mperf"
BIN_DIR_DEFAULT="$HOME/.local/bin"

INSTALL_DIR="${INSTALL_DIR:-$INSTALL_DIR_DEFAULT}"
BIN_DIR="${BIN_DIR:-$BIN_DIR_DEFAULT}"

usage() {
  cat <<EOF
Usage: uninstall.sh [--install-dir DIR] [--bin-dir DIR]

Removes the mperf launcher and downloaded artifacts created by install.sh.

Options:
  --install-dir DIR   Location of downloaded JARs (default: $INSTALL_DIR_DEFAULT)
  --bin-dir DIR       Directory containing the mperf launchers (default: $BIN_DIR_DEFAULT)
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
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

remove_path() {
  local target="$1"
  if [[ -e "$target" || -L "$target" ]]; then
    rm -f "$target"
    echo "Removed: $target"
    return 0
  fi
  return 1
}

REMOVED_ANY=false

for launcher in "$BIN_DIR/mperf" "$BIN_DIR/aperf" "$BIN_DIR/iperf"; do
  if remove_path "$launcher"; then
    REMOVED_ANY=true
  fi
done

if [[ -d "$INSTALL_DIR" ]]; then
  shopt -s nullglob
  for artifact in "$INSTALL_DIR"/mperf-*-all.jar "$INSTALL_DIR"/mperf-*-all.jar.sha256; do
    if remove_path "$artifact"; then
      REMOVED_ANY=true
    fi
  done
  shopt -u nullglob

  if remove_path "$INSTALL_DIR/mperf-latest.jar"; then
    REMOVED_ANY=true
  fi

  if [[ -d "$INSTALL_DIR" && -z "$(ls -A "$INSTALL_DIR")" ]]; then
    rmdir "$INSTALL_DIR"
    echo "Removed empty directory: $INSTALL_DIR"
  fi
else
  echo "Install directory not found: $INSTALL_DIR" >&2
fi

if [[ "$REMOVED_ANY" == false ]]; then
  echo "No mperf artifacts were found using the provided paths." >&2
  exit 1
fi

echo "Uninstall complete."
