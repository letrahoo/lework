#!/usr/bin/env bash
# Self-healing SSH reverse tunnel (Local 127.0.0.1:64450 -> Relay Host 127.0.0.1:16450).
# Configure target SSH alias via LEWORK_RELAY_SSH_HOST (e.g. a Linux relay host).
set -euo pipefail

RELAY_HOST="${LEWORK_RELAY_SSH_HOST:?LEWORK_RELAY_SSH_HOST must be set}"
LOCAL_PORT="${LEWORK_HUB_PORT:-64450}"
REMOTE_TUNNEL_PORT="${LEWORK_TUNNEL_PORT:-16450}"

SSH_OPTS=(
  -i "${LEWORK_SSH_KEY:-$HOME/.ssh/id_ed25519}"
  -o IdentitiesOnly=yes
  -o ControlMaster=no
  -o ControlPath=none
  -o ConnectTimeout=8
  -o ServerAliveInterval=15
  -o ServerAliveCountMax=2
  -o ExitOnForwardFailure=yes
)

# Clean up any half-open sshd port forward left behind after Wi-Fi/network roaming
/usr/bin/ssh "${SSH_OPTS[@]}" "$RELAY_HOST" "fuser -k ${REMOTE_TUNNEL_PORT}/tcp 2>/dev/null || true" || true
sleep 0.5

exec /usr/bin/ssh -N -T "${SSH_OPTS[@]}" -R "127.0.0.1:${REMOTE_TUNNEL_PORT}:127.0.0.1:${LOCAL_PORT}" "$RELAY_HOST"
