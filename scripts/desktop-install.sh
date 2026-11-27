#!/usr/bin/env bash
# Incrementally build the local Lework macOS Desktop app and install it into /Applications/Lework.app.
# Preserves all user settings, paired hosts, TLS pins, and tokens stored under ~/.lework/.
#
# Usage:
#   ./scripts/desktop-install.sh              # Build, install to /Applications/Lework.app, and relaunch
#   ./scripts/desktop-install.sh --no-launch  # Build and install without launching
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP_DIR="$REPO_ROOT/app"
BUILT_APP="$APP_DIR/desktopApp/build/compose/binaries/main/app/Lework.app"
TARGET_APP="/Applications/Lework.app"
LAUNCH=1
EXPLICIT_VERSION="${LEWORK_VERSION:-}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --no-launch) LAUNCH=0; shift ;;
    --version)
      EXPLICIT_VERSION="${2:-}"
      shift 2
      ;;
    -h|--help)
      echo "Usage: $0 [--no-launch] [--version X.Y.Z]"
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      exit 1
      ;;
  esac
done

if [[ -z "$EXPLICIT_VERSION" ]]; then
  LATEST_TAG="$(git -C "$REPO_ROOT" tag --merged HEAD --list 'v[0-9]*.[0-9]*.[0-9]*' --sort=-v:refname 2>/dev/null | head -n 1 || true)"
  if [[ -n "$LATEST_TAG" ]]; then
    EXPLICIT_VERSION="${LATEST_TAG#v}"
  fi
fi

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
export PATH="$JAVA_HOME/bin:/opt/homebrew/bin:$HOME/.local/bin:$PATH"

GRADLE_VERSION_ARGS=()
if [[ -n "$EXPLICIT_VERSION" ]]; then
  GRADLE_VERSION_ARGS+=("-PleworkVersion=$EXPLICIT_VERSION")
fi

echo "==> Building Lework macOS Desktop app (incremental, version=${EXPLICIT_VERSION:-auto})..."
"$APP_DIR/gradlew" -p "$APP_DIR" :desktopApp:createDistributable "${GRADLE_VERSION_ARGS[@]}"

if [[ ! -d "$BUILT_APP" ]]; then
  echo "Error: Built app bundle not found at $BUILT_APP" >&2
  exit 1
fi

echo "==> Stopping any running Lework Desktop instance..."
osascript -e 'tell application "Lework" to quit' >/dev/null 2>&1 || true
for _ in 1 2 3 4 5 6 7 8 9 10; do
  if ! pgrep -f "Lework.app/Contents/MacOS/Lework" >/dev/null 2>&1; then
    break
  fi
  sleep 0.3
done
if pgrep -f "Lework.app/Contents/MacOS/Lework" >/dev/null 2>&1; then
  pkill -f "Lework.app/Contents/MacOS/[L]ework" >/dev/null 2>&1 || true
  sleep 0.5
fi

echo "==> Installing to $TARGET_APP..."
mkdir -p "$TARGET_APP"
rsync -a --delete "$BUILT_APP/" "$TARGET_APP/"
xattr -dr com.apple.quarantine "$TARGET_APP" >/dev/null 2>&1 || true
touch "$TARGET_APP"

APP_VER="$(sed -n 's/.*-Dlework\.app\.version=//p' "$TARGET_APP/Contents/app/Lework.cfg" 2>/dev/null | head -n 1 || true)"
echo "==> Installed $TARGET_APP (version: ${APP_VER:-unknown})"

if [[ "$LAUNCH" -eq 1 ]]; then
  echo "==> Launching $TARGET_APP..."
  open "$TARGET_APP"
fi
