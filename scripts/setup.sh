#!/usr/bin/env bash
# Idempotent one-command bootstrap for Lework (macOS & Linux):
#   1) Verifies Node.js >= 22 & pnpm
#   2) Installs workspace dependencies & generates TypeScript/Kotlin protocol models
#   3) Builds embedded Go `tsnet-bridge` (Host helper + iOS C-archives on macOS when Xcode is present)
#   4) Registers global `lework` and `hub` CLI launchers into `~/.local/bin`
#
# Usage:
#   pnpm setup
#   ./scripts/setup.sh [--no-cli-link] [--skip-tsnet] [--require-go]
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NO_CLI_LINK=false
SKIP_TSNET=false
REQUIRE_GO=false

for arg in "$@"; do
  case "$arg" in
    --no-cli-link) NO_CLI_LINK=true ;;
    --skip-tsnet)  SKIP_TSNET=true ;;
    --require-go)  REQUIRE_GO=true ;;
    -h|--help)
      echo "Usage: $0 [--no-cli-link] [--skip-tsnet] [--require-go]"
      exit 0
      ;;
    *)
      echo "Unknown option: $arg" >&2
      exit 1
      ;;
  esac
done

cd "$REPO_ROOT"

echo "==> [1/4] Checking Node.js & pnpm..."
if ! command -v node >/dev/null 2>&1; then
  echo "Error: Node.js (>= 22) is required but was not found in PATH." >&2
  exit 1
fi
NODE_MAJOR="$(node -p 'Number(process.versions.node.split(".")[0])')"
if (( NODE_MAJOR < 22 )); then
  echo "Error: Node.js >= 22 is required (found $(node -v))." >&2
  exit 1
fi
if ! command -v pnpm >/dev/null 2>&1; then
  echo "Error: pnpm is required (run 'corepack enable' or 'npm i -g pnpm')." >&2
  exit 1
fi
echo "    Node $(node -v) · pnpm $(pnpm -v)"

echo "==> [2/4] Installing workspace dependencies & generating protocol models..."
if ! pnpm install --frozen-lockfile; then
  pnpm install
fi
pnpm --filter @lework/protocol gen

echo "==> [3/4] Building embedded Tailscale tsnet bridge..."
if [[ "$SKIP_TSNET" == "true" ]]; then
  echo "    [skip] --skip-tsnet passed."
else
  GO_FOUND=""
  for candidate in "${GO_BIN:-}" /opt/homebrew/bin/go /usr/local/go/bin/go /usr/lib/go/bin/go "$HOME/go/bin/go" "$(command -v go 2>/dev/null || true)"; do
    if [[ -n "$candidate" && -x "$candidate" ]]; then
      GO_FOUND="$candidate"
      break
    fi
  done

  if [[ -n "$GO_FOUND" ]]; then
    if [[ "$(uname -s)" == "Darwin" ]]; then
      GO_BIN="$GO_FOUND" bash "$REPO_ROOT/scripts/build-tsnet.sh" --all
    else
      GO_BIN="$GO_FOUND" bash "$REPO_ROOT/scripts/build-tsnet.sh" --host
    fi
  elif [[ "$REQUIRE_GO" == "true" ]]; then
    echo "Error: Go toolchain (>= 1.24) is required by --require-go." >&2
    exit 1
  else
    echo "    [skip] Go toolchain not found — embedded tsnet mesh skipped (LAN mDNS & QR pairing remain available)."
  fi
fi

echo "==> [4/4] Linking global 'lework' & 'hub' CLI commands..."
if [[ "$NO_CLI_LINK" == "true" || "${CI:-}" == "true" ]]; then
  echo "    [skip] Skipped global ~/.local/bin symlink installation."
else
  node "$REPO_ROOT/hub/bin/hub.js" install-cli
  case ":$PATH:" in
    *":$HOME/.local/bin:"*) ;;
    *)
      echo ""
      echo "Note: ~/.local/bin is not yet in your current shell PATH. Add to ~/.zshrc or ~/.bashrc:"
      echo '  export PATH="$HOME/.local/bin:$PATH"'
      ;;
  esac
fi

echo ""
echo "Setup complete! Quick commands (runnable from any directory):"
echo "  lework engines       # Inspect installed local agent engines"
echo "  lework serve         # Start Host Hub daemon in foreground (HTTPS/WSS :64450)"
echo "  lework pair          # Generate single-use QR pairing code for mobile App"
echo "  lework tsnet login   # Enable zero-touch same-account Tailscale mesh"
