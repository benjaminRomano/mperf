#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: preflight.sh <SemVer version>" >&2
  exit 2
fi

VERSION=${1#v}
if [[ ! "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$ ]]; then
  echo "Usage: preflight.sh <SemVer version>" >&2
  exit 2
fi

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
cd "$REPO_ROOT"

for command in git java unzip; do
  command -v "$command" >/dev/null 2>&1 || { echo "Missing required command: $command" >&2; exit 1; }
done

if [[ -n "$(git status --porcelain)" ]]; then
  echo "Release preflight requires a clean working tree" >&2
  exit 1
fi

if [[ "$(git branch --show-current)" != "main" ]]; then
  echo "Release preflight must run from main" >&2
  exit 1
fi

git fetch origin main --tags
if [[ "$(git rev-parse HEAD)" != "$(git rev-parse origin/main)" ]]; then
  echo "Local main must exactly match origin/main" >&2
  exit 1
fi

if git show-ref --verify --quiet "refs/tags/v$VERSION"; then
  echo "Tag v$VERSION already exists" >&2
  exit 1
fi

scripts/test-install.sh
./gradlew --no-daemon --stacktrace --warning-mode=all \
  -PreleaseVersion="$VERSION" clean build shadowJar jmhClasses generateDocs
git diff --exit-code -- docs/cli.md

JAR_PATH="build/libs/mperf-$VERSION-all.jar"
test -f "$JAR_PATH"
java -jar "$JAR_PATH" --help >/dev/null

MANIFEST_VERSION=$(unzip -p "$JAR_PATH" META-INF/MANIFEST.MF | tr -d '\r' | sed -n 's/^Implementation-Version: //p')
if [[ "$MANIFEST_VERSION" != "$VERSION" ]]; then
  echo "Expected manifest version $VERSION, found $MANIFEST_VERSION" >&2
  exit 1
fi

if command -v sha256sum >/dev/null 2>&1; then
  sha256sum "$JAR_PATH"
else
  shasum -a 256 "$JAR_PATH"
fi

echo "Release v$VERSION passed preflight"
