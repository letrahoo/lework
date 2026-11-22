#!/usr/bin/env bash
# Build, sign, verify, and install the Lework iOS client.
# Usage:
#   ./scripts/ios-install.sh --simulator              # Build for iOS Simulator (arm64)
#   ./scripts/ios-install.sh --device-compile         # Compile & link for iphoneos arm64 (unsigned check)
#   ./scripts/ios-install.sh --install [DEVICE_UDID]  # Build, auto-sign, verify & install to paired iPhone
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP_DIR="$REPO_ROOT/app"
IOS_DIR="$APP_DIR/iosApp"
DERIVED_DATA="$APP_DIR/build/ios-derived"
[[ -f "$HOME/.lework/ios.env" ]] && source "$HOME/.lework/ios.env"
BUNDLE_ID="${LEWORK_IOS_BUNDLE_ID:-dev.lework.app}"
TEAM_ID="${APPLE_TEAM_ID:-}"

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
export PATH="$JAVA_HOME/bin:/usr/local/go/bin:/opt/homebrew/bin:$HOME/.local/bin:$PATH"

if [[ ! -f "$REPO_ROOT/tsnet-bridge/build/ios-device/libtsnetbridge.a" || ! -f "$REPO_ROOT/tsnet-bridge/build/ios-sim/libtsnetbridge.a" ]]; then
  "$REPO_ROOT/scripts/build-tsnet.sh" --ios
fi

cd "$IOS_DIR"
xcodegen generate

MODE="${1:---simulator}"

if [[ "$MODE" == "--simulator" ]]; then
  echo "==> Building iosApp for iOS Simulator (arm64)..."
  xcodebuild \
    -project "$IOS_DIR/iosApp.xcodeproj" \
    -scheme iosApp \
    -configuration Debug \
    -sdk iphonesimulator \
    -arch arm64 \
    -derivedDataPath "$DERIVED_DATA" \
    CODE_SIGNING_ALLOWED=NO \
    build
  APP_BUNDLE="$DERIVED_DATA/Build/Products/Debug-iphonesimulator/iosApp.app"
  echo "==> Simulator app bundle ready: $APP_BUNDLE"
  exit 0
fi

if [[ "$MODE" == "--device-compile" ]]; then
  echo "==> Compiling & linking iosApp for iphoneos (arm64, unsigned link check)..."
  xcodebuild \
    -project "$IOS_DIR/iosApp.xcodeproj" \
    -scheme iosApp \
    -configuration Debug \
    -sdk iphoneos \
    -destination "generic/platform=iOS" \
    -derivedDataPath "$DERIVED_DATA" \
    ONLY_ACTIVE_ARCH=YES \
    ARCHS=arm64 \
    COMPILER_INDEX_STORE_ENABLE=NO \
    CODE_SIGNING_ALLOWED=NO \
    CODE_SIGNING_REQUIRED=NO \
    CODE_SIGN_IDENTITY="" \
    build
  APP_BUNDLE="$DERIVED_DATA/Build/Products/Debug-iphoneos/iosApp.app"
  echo "==> Device arm64 app bundle compiled & linked: $APP_BUNDLE"
  exit 0
fi

if [[ "$MODE" == "--install" ]]; then
  DEVICE_ID="${2:-${IOS_DEVICE_UDID:-}}"
  if [[ -z "$DEVICE_ID" ]]; then
    DEVICE_ID="$(xcrun devicectl list devices --timeout 8 2>/dev/null | awk '/iPhone/ && (/available/ || /connected/) {for(i=1;i<=NF;i++) if ($i ~ /^[0-9A-F-]{20,}$/) {print $i; exit}}' || true)"
  fi
  if [[ -z "$DEVICE_ID" ]]; then
    DEVICE_ID="$(xcrun xctrace list devices 2>/dev/null | awk '/^== Devices ==/{on=1; next} /^==/{on=0} on && /iPhone/ {if (match($0, /\([0-9A-F-]{20,}\)$/)) {print substr($0, RSTART+1, RLENGTH-2); exit}}' || true)"
  fi
  if [[ -z "$DEVICE_ID" ]]; then
    OFFLINE_HINT="$(xcrun xctrace list devices 2>/dev/null | awk '/^== Devices Offline ==/{on=1; next} /^==/{on=0} on && /iPhone/ {print; exit}' || true)"
    if [[ -n "$OFFLINE_HINT" ]]; then
      echo "Error: Paired iPhone detected offline ($OFFLINE_HINT)." >&2
      echo "Unlock your iPhone on the same Wi-Fi (with Xcode 'Connect via network' enabled once) and re-run." >&2
    else
      echo "Error: No paired physical iPhone detected. Pass <DEVICE_UDID> explicitly." >&2
    fi
    exit 1
  fi

  if [[ -z "$TEAM_ID" ]]; then
    TEAM_ID="$(security find-certificate -a -c "Apple Development" -p 2>/dev/null | openssl x509 -noout -subject -nameopt RFC2253 2>/dev/null | sed -n 's/.*OU=\([A-Z0-9]\{10\}\).*/\1/p' | head -n 1 || true)"
  fi
  if [[ -z "$TEAM_ID" ]]; then
    TEAM_ID="$(security find-identity -v -p codesigning 2>/dev/null | sed -n 's/.*Apple Development:.*(\([A-Z0-9]\{10\}\)).*/\1/p' | head -n 1 || true)"
  fi
  if [[ -z "$TEAM_ID" ]]; then
    echo "Error: APPLE_TEAM_ID is not set and could not be auto-detected from Keychain." >&2
    exit 1
  fi

  BOOTSTRAP_RELAY="${LEWORK_BOOTSTRAP_RELAY:-}"
  if [[ -z "$BOOTSTRAP_RELAY" && -f "$HOME/.lework/config.json" ]]; then
    BOOTSTRAP_RELAY="$(node -e '
      try {
        const fs = require("node:fs");
        const cfg = JSON.parse(fs.readFileSync(process.env.HOME + "/.lework/config.json", "utf8"));
        const raw = Array.isArray(cfg.relayUrls) && cfg.relayUrls[0] ? String(cfg.relayUrls[0]).trim() : "";
        process.stdout.write(raw.replace(/^https?:\/\//i, "").replace(/\/+$/, ""));
      } catch {}
    ')"
  fi

  echo "==> Building & signing Lework for physical iPhone ($DEVICE_ID, Team $TEAM_ID, $BUNDLE_ID)..."
  XCODE_ARGS=(
    -project "$IOS_DIR/iosApp.xcodeproj"
    -scheme iosApp
    -configuration Debug
    -sdk iphoneos
    -destination "generic/platform=iOS"
    -derivedDataPath "$DERIVED_DATA"
    DEVELOPMENT_TEAM="$TEAM_ID"
    PRODUCT_BUNDLE_IDENTIFIER="$BUNDLE_ID"
    LEWORK_BOOTSTRAP_RELAY="$BOOTSTRAP_RELAY"
  )
  if ! xcodebuild "${XCODE_ARGS[@]}" build; then
    echo "==> Cached provisioning profile unavailable; retrying with -allowProvisioningUpdates..."
    xcodebuild "${XCODE_ARGS[@]}" -allowProvisioningUpdates build
  fi

  APP_BUNDLE="$DERIVED_DATA/Build/Products/Debug-iphoneos/iosApp.app"
  echo "==> Verifying signature..."
  codesign --verify --deep --strict "$APP_BUNDLE"

  echo "==> Installing $APP_BUNDLE to device $DEVICE_ID..."
  xcrun devicectl device install app --device "$DEVICE_ID" "$APP_BUNDLE"
  echo "==> Verifying installed app on device..."
  xcrun devicectl device info apps --device "$DEVICE_ID" --bundle-id "$BUNDLE_ID"
  exit 0
fi

echo "Usage: $0 [--simulator | --device-compile | --install [DEVICE_UDID]]"
exit 1
