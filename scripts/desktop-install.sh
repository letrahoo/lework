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

for arg in "$@"; do
  case "$arg" in
    --no-launch) LAUNCH=0 ;;
    -h|--help)
      echo "Usage: $0 [--no-launch]"
      exit 0
      ;;
    *)
      echo "Unknown option: $arg" >&2
      exit 1
      ;;
  esac
done

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
export PATH="$JAVA_HOME/bin:/opt/homebrew/bin:$HOME/.local/bin:$PATH"

echo "==> Building Lework macOS Desktop app (incremental)..."
"$APP_DIR/gradlew" -p "$APP_DIR" :desktopApp:createDistributable

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
