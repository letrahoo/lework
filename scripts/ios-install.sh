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

TSNET_DEVICE_LIB="$REPO_ROOT/tsnet-bridge/build/ios-device/libtsnetbridge.a"
TSNET_SIM_LIB="$REPO_ROOT/tsnet-bridge/build/ios-sim/libtsnetbridge.a"
TSNET_STALE=0
if [[ ! -f "$TSNET_DEVICE_LIB" || ! -f "$TSNET_SIM_LIB" ]]; then
  TSNET_STALE=1
elif [[ -n "$(find "$REPO_ROOT/tsnet-bridge" -maxdepth 3 -name '*.go' -newer "$TSNET_DEVICE_LIB" -print -quit 2>/dev/null || true)" ]]; then
  TSNET_STALE=1
fi
if [[ "$TSNET_STALE" -eq 1 ]]; then
  "$REPO_ROOT/scripts/build-tsnet.sh" --ios
fi

cd "$IOS_DIR"
xcodegen generate

# Resolve effective SemVer (`max(latest reachable vX.Y.Z tag, package.json baseline)`) and fresh UTC build number
# into a derived-data Info.plist copy so local installs reflect the real release version (e.g. 0.9.3) without
# dirtying tracked `app/iosApp/iosApp/Info.plist`.
RESOLVED_IOS_META="$(node -e '
  const { execFileSync } = require("node:child_process");
  const fs = require("node:fs");
  const path = require("node:path");
  const repoRoot = process.argv[1];
  const semverRe = /^v?(\d+)\.(\d+)\.(\d+)$/;
  const parse = raw => {
    const m = String(raw || "").trim().match(semverRe);
    return m ? { major: +m[1], minor: +m[2], patch: +m[3] } : null;
  };
  const cmp = (a, b) => (a.major - b.major) || (a.minor - b.minor) || (a.patch - b.patch);
  const fmt = v => `${v.major}.${v.minor}.${v.patch}`;
  const pkg = JSON.parse(fs.readFileSync(path.join(repoRoot, "package.json"), "utf8"));
  let best = parse(pkg.version) || { major: 0, minor: 9, patch: 0 };
  try {
    const tags = execFileSync("git", ["tag", "--merged", "HEAD", "--list", "v[0-9]*.[0-9]*.[0-9]*", "--sort=-v:refname"], {
      cwd: repoRoot, encoding: "utf8", stdio: ["ignore", "pipe", "ignore"]
    }).split("\n");
    for (const line of tags) {
      const t = parse(line);
      if (t) {
        if (cmp(t, best) > 0) best = t;
        break;
      }
    }
  } catch {}
  const now = new Date();
  const p = n => String(n).padStart(2, "0");
  const build = `${now.getUTCFullYear()}${p(now.getUTCMonth() + 1)}${p(now.getUTCDate())}.${p(now.getUTCHours())}${p(now.getUTCMinutes())}`;
  process.stdout.write(`${fmt(best)}\t${build}`);
' "$REPO_ROOT")"
EFFECTIVE_IOS_VERSION="${LEWORK_IOS_VERSION:-${RESOLVED_IOS_META%%$'\t'*}}"
EFFECTIVE_IOS_BUILD="${LEWORK_IOS_BUILD_VERSION:-${RESOLVED_IOS_META#*$'\t'}}"
mkdir -p "$DERIVED_DATA"
GENERATED_INFO_PLIST="$DERIVED_DATA/Generated-Info.plist"
cp "$IOS_DIR/iosApp/Info.plist" "$GENERATED_INFO_PLIST"
/usr/libexec/PlistBuddy -c "Set :CFBundleShortVersionString $EFFECTIVE_IOS_VERSION" "$GENERATED_INFO_PLIST"
/usr/libexec/PlistBuddy -c "Set :CFBundleVersion $EFFECTIVE_IOS_BUILD" "$GENERATED_INFO_PLIST"

MODE="${1:---simulator}"

if [[ "$MODE" == "--simulator" ]]; then
  echo "==> Building iosApp v$EFFECTIVE_IOS_VERSION ($EFFECTIVE_IOS_BUILD) for iOS Simulator (arm64)..."
  xcodebuild \
    -project "$IOS_DIR/iosApp.xcodeproj" \
    -scheme iosApp \
    -configuration Debug \
    -sdk iphonesimulator \
    -arch arm64 \
    -derivedDataPath "$DERIVED_DATA" \
    INFOPLIST_FILE="$GENERATED_INFO_PLIST" \
    CODE_SIGNING_ALLOWED=NO \
    build
  APP_BUNDLE="$DERIVED_DATA/Build/Products/Debug-iphonesimulator/iosApp.app"
  echo "==> Simulator app bundle ready: $APP_BUNDLE"
  exit 0
fi

if [[ "$MODE" == "--device-compile" ]]; then
  echo "==> Compiling & linking iosApp v$EFFECTIVE_IOS_VERSION ($EFFECTIVE_IOS_BUILD) for iphoneos (arm64, unsigned link check)..."
  xcodebuild \
    -project "$IOS_DIR/iosApp.xcodeproj" \
    -scheme iosApp \
    -configuration Debug \
    -sdk iphoneos \
    -destination "generic/platform=iOS" \
    -derivedDataPath "$DERIVED_DATA" \
    INFOPLIST_FILE="$GENERATED_INFO_PLIST" \
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

  IOS_BUILD_CONFIG="${LEWORK_IOS_CONFIGURATION:-Release}"
  echo "==> Building & signing Lework v$EFFECTIVE_IOS_VERSION ($EFFECTIVE_IOS_BUILD, $IOS_BUILD_CONFIG) for physical iPhone ($DEVICE_ID, Team $TEAM_ID, $BUNDLE_ID)..."
  XCODE_ARGS=(
    -project "$IOS_DIR/iosApp.xcodeproj"
    -scheme iosApp
    -configuration "$IOS_BUILD_CONFIG"
    -sdk iphoneos
    -destination "generic/platform=iOS"
    -derivedDataPath "$DERIVED_DATA"
    INFOPLIST_FILE="$GENERATED_INFO_PLIST"
    DEVELOPMENT_TEAM="$TEAM_ID"
    PRODUCT_BUNDLE_IDENTIFIER="$BUNDLE_ID"
    LEWORK_BOOTSTRAP_RELAY="$BOOTSTRAP_RELAY"
  )
  if ! xcodebuild "${XCODE_ARGS[@]}" build; then
    echo "==> Cached provisioning profile unavailable; retrying with -allowProvisioningUpdates..."
    xcodebuild "${XCODE_ARGS[@]}" -allowProvisioningUpdates build
  fi

  APP_BUNDLE="$DERIVED_DATA/Build/Products/${IOS_BUILD_CONFIG}-iphoneos/iosApp.app"
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
