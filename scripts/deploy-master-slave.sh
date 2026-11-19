#!/usr/bin/env bash
# Installs and verifies:
#   1) macOS hubd Slave LaunchAgent (0.0.0.0:64450)
#   2) Optional macOS -> Linux Relay SSH reverse tunnel LaunchAgent (when LEWORK_RELAY_SSH_HOST is set)
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
UID_NUM="$(id -u)"
HUB_PORT="${LEWORK_HUB_PORT:-64450}"
RELAY_SSH_HOST="${LEWORK_RELAY_SSH_HOST:-}"
RELAY_PUBLIC_URL="${LEWORK_RELAY_URL:-}"
LAUNCH_DIR="$HOME/Library/LaunchAgents"
LOG_DIR="$HOME/Library/Logs"
mkdir -p "$LAUNCH_DIR" "$LOG_DIR" "$HOME/.lework"

# 1. Mac hubd LaunchAgent
HUB_PLIST="$LAUNCH_DIR/dev.lework.hub-slave.plist"
cat > "$HUB_PLIST" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>dev.lework.hub-slave</string>
  <key>ProgramArguments</key>
  <array>
    <string>$HOME/.local/bin/node</string>
    <string>--experimental-transform-types</string>
    <string>$REPO_ROOT/hub/src/cli.ts</string>
    <string>serve</string>
    <string>--port</string>
    <string>$HUB_PORT</string>
  </array>
  <key>WorkingDirectory</key><string>$REPO_ROOT</string>
  <key>EnvironmentVariables</key>
  <dict>
    <key>LANG</key><string>en_US.UTF-8</string>
    <key>PATH</key><string>$HOME/.local/bin:/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin</string>
  </dict>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>ThrottleInterval</key><integer>5</integer>
  <key>StandardOutPath</key><string>$LOG_DIR/lework-hub.log</string>
  <key>StandardErrorPath</key><string>$LOG_DIR/lework-hub.log</string>
</dict>
</plist>
EOF

launchctl bootout "gui/$UID_NUM/dev.lework.hub-slave" 2>/dev/null || true
launchctl bootstrap "gui/$UID_NUM" "$HUB_PLIST"

# 2. Optional SSH Reverse Tunnel + Remote Relay systemd service
if [[ -n "$RELAY_SSH_HOST" ]]; then
  TUN_PLIST="$LAUNCH_DIR/dev.lework.ssh-tunnel.plist"
  cat > "$TUN_PLIST" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>dev.lework.ssh-tunnel</string>
  <key>ProgramArguments</key>
  <array>
    <string>/bin/bash</string>
    <string>$REPO_ROOT/scripts/ssh-tunnel-keepalive.sh</string>
  </array>
  <key>EnvironmentVariables</key>
  <dict>
    <key>LEWORK_RELAY_SSH_HOST</key><string>$RELAY_SSH_HOST</string>
    <key>LEWORK_HUB_PORT</key><string>$HUB_PORT</string>
  </dict>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>ThrottleInterval</key><integer>10</integer>
  <key>StandardOutPath</key><string>$LOG_DIR/lework-ssh-tunnel.log</string>
  <key>StandardErrorPath</key><string>$LOG_DIR/lework-ssh-tunnel.log</string>
</dict>
</plist>
EOF

  launchctl bootout "gui/$UID_NUM/dev.lework.ssh-tunnel" 2>/dev/null || true
  launchctl bootstrap "gui/$UID_NUM" "$TUN_PLIST"

  ssh -o ControlMaster=no "$RELAY_SSH_HOST" 'mkdir -p ~/.lework ~/.config/systemd/user'
  scp -q -o ControlMaster=no "$REPO_ROOT/scripts/tcp-relay.mjs" "$RELAY_SSH_HOST:~/.lework/tcp-relay.mjs"
  ssh -o ControlMaster=no "$RELAY_SSH_HOST" 'cat > ~/.config/systemd/user/lework-relay.service <<UNIT
[Unit]
Description=Lework Stateless Relay (0.0.0.0:64450 -> 127.0.0.1:16450)
After=network.target

[Service]
ExecStart=/usr/bin/env node %h/.lework/tcp-relay.mjs
Restart=always
RestartSec=2

[Install]
WantedBy=default.target
UNIT
systemctl --user daemon-reload
systemctl --user enable --now lework-relay.service
systemctl --user restart lework-relay.service'
fi

sleep 2
echo "==> Verifying local Mac Hub (127.0.0.1:$HUB_PORT):"
# Self-signed Hub certificate (pinned by the app); -k is fine for this unauthenticated health probe
curl -kfsS --max-time 4 "https://127.0.0.1:$HUB_PORT/v1/auth/discover" && echo ""
if [[ -n "$RELAY_PUBLIC_URL" ]]; then
  echo "==> Verifying Relay Entry ($RELAY_PUBLIC_URL):"
  curl -kfsS --max-time 5 "${RELAY_PUBLIC_URL/http:\/\//https://}/v1/auth/discover" && echo ""
fi
