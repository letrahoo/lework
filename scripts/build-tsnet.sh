#!/usr/bin/env bash
# Builds the embedded Tailscale tsnet bridge for:
#   1) Host Hub helper binary (`tsnet-bridge/bin/lework-tsnet`)
#   2) iOS arm64 + Simulator arm64 static C-archives (`tsnet-bridge/build/ios-{device,sim}/libtsnetbridge.a`)
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TSNET_DIR="$REPO_ROOT/tsnet-bridge"
GO_BIN="${GO_BIN:-}"
if [[ -z "$GO_BIN" ]]; then
  for candidate in /opt/homebrew/bin/go /usr/local/go/bin/go "$HOME/go/bin/go" "$(command -v go 2>/dev/null || true)"; do
    if [[ -n "$candidate" && -x "$candidate" ]]; then
      GO_BIN="$candidate"
      break
    fi
  done
fi
if [[ -z "$GO_BIN" ]]; then
  echo "Error: Go toolchain not found." >&2
  exit 1
fi

MODE="${1:---all}"
if [[ -n "${LEWORK_GOPROXY:-}" ]]; then
  export GOPROXY="$LEWORK_GOPROXY"
elif [[ "${CI:-}" == "true" ]]; then
  export GOPROXY="https://proxy.golang.org,direct"
else
  export GOPROXY="https://goproxy.cn,https://proxy.golang.org,direct"
fi

if [[ -n "${LEWORK_GOSUMDB:-}" ]]; then
  export GOSUMDB="$LEWORK_GOSUMDB"
elif [[ "${CI:-}" == "true" ]]; then
  export GOSUMDB="sum.golang.org"
else
  export GOSUMDB="sum.golang.google.cn"
fi

cd "$TSNET_DIR"

echo "==> Testing tsnet-bridge Go module ($("$GO_BIN" version))..."
if [[ ! -f go.sum ]]; then
  "$GO_BIN" mod tidy
fi
"$GO_BIN" test ./internal/mesh/...

if [[ "$MODE" == "--all" || "$MODE" == "--host" ]]; then
  HOST_ARCH="$(uname -m)"
  GO_HOST_ARCH="amd64"
  [[ "$HOST_ARCH" == "arm64" || "$HOST_ARCH" == "aarch64" ]] && GO_HOST_ARCH="arm64"
  mkdir -p "$TSNET_DIR/bin"
  echo "==> Building Host binary tsnet-bridge/bin/lework-tsnet ($(uname -s | tr '[:upper:]' '[:lower:]')/$GO_HOST_ARCH)..."
  CGO_ENABLED=0 GOOS="$(uname -s | tr '[:upper:]' '[:lower:]')" GOARCH="$GO_HOST_ARCH" \
    "$GO_BIN" build -trimpath -ldflags="-s -w" -o "$TSNET_DIR/bin/lework-tsnet" ./cmd/lework-tsnet
fi

if [[ "$MODE" == "--all" || "$MODE" == "--ios" ]] && [[ "$(uname -s)" == "Darwin" ]]; then
  if ! xcrun --sdk iphoneos --show-sdk-path >/dev/null 2>&1; then
    if [[ "$MODE" == "--ios" ]]; then
      echo "Error: Xcode iOS SDK not found (install Xcode and run sudo xcode-select -s /Applications/Xcode.app)." >&2
      exit 1
    fi
    echo "==> [skip] Xcode iOS SDK not detected; skipping iOS static C-archives."
    exit 0
  fi

  IOS_SDK="$(xcrun --sdk iphoneos --show-sdk-path)"
  IOS_CC="$(xcrun --sdk iphoneos -f clang)"
  mkdir -p "$TSNET_DIR/build/ios-device"
  echo "==> Building iOS device static C-archive (ios/arm64)..."
  CGO_ENABLED=1 GOOS=ios GOARCH=arm64 \
    CC="$IOS_CC" \
    CGO_CFLAGS="-isysroot $IOS_SDK -miphoneos-version-min=15.0 -arch arm64" \
    CGO_LDFLAGS="-isysroot $IOS_SDK -miphoneos-version-min=15.0 -arch arm64" \
    "$GO_BIN" build -trimpath -buildmode=c-archive \
    -o "$TSNET_DIR/build/ios-device/libtsnetbridge.a" ./ios

  SIM_SDK="$(xcrun --sdk iphonesimulator --show-sdk-path)"
  SIM_CC="$(xcrun --sdk iphonesimulator -f clang)"
  mkdir -p "$TSNET_DIR/build/ios-sim"
  echo "==> Building iOS simulator static C-archive (iossimulator/arm64)..."
  CGO_ENABLED=1 GOOS=ios GOARCH=arm64 \
    CC="$SIM_CC" \
    CGO_CFLAGS="-isysroot $SIM_SDK -mios-simulator-version-min=15.0 -arch arm64 -target arm64-apple-ios15.0-simulator" \
    CGO_LDFLAGS="-isysroot $SIM_SDK -mios-simulator-version-min=15.0 -arch arm64 -target arm64-apple-ios15.0-simulator" \
    "$GO_BIN" build -trimpath -buildmode=c-archive \
    -o "$TSNET_DIR/build/ios-sim/libtsnetbridge.a" ./ios
fi

echo "==> tsnet-bridge build complete."
