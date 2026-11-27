package mesh

import (
	"crypto/sha256"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
)

const (
	tailscaledStateFile       = "tailscaled.state"
	tailscaledStateBackupFile = "tailscaled.state.lkg"
)

// HasAuthenticatedTailscaledState verifies that raw bytes represent a non-corrupted
// Tailscale ipn.FileStore JSON object containing an active authenticated profile.
func HasAuthenticatedTailscaledState(data []byte) bool {
	if len(data) == 0 {
		return false
	}
	var obj map[string]json.RawMessage
	if err := json.Unmarshal(data, &obj); err != nil || len(obj) < 2 {
		return false
	}
	rawCur, ok := obj["_current-profile"]
	if !ok || len(rawCur) == 0 {
		return false
	}
	var currentProfile string
	if err := json.Unmarshal(rawCur, &currentProfile); err != nil {
		return false
	}
	return strings.TrimSpace(currentProfile) != ""
}

// RestoreTailscaledStateIfNeeded repairs a missing or crash-truncated tailscaled.state
// from tailscaled.state.lkg before tsnet.Server starts, preserving the existing Tailscale
// MachineKey/NodeKey identity instead of registering a duplicate device.
func RestoreTailscaledStateIfNeeded(stateDir string) (bool, error) {
	if strings.TrimSpace(stateDir) == "" {
		return false, nil
	}
	primaryPath := filepath.Join(stateDir, tailscaledStateFile)
	if primary, err := os.ReadFile(primaryPath); err == nil && HasAuthenticatedTailscaledState(primary) {
		return false, nil
	}
	backupPath := filepath.Join(stateDir, tailscaledStateBackupFile)
	backup, err := os.ReadFile(backupPath)
	if err != nil || !HasAuthenticatedTailscaledState(backup) {
		return false, nil
	}
	if err := writeFileAtomic0600(primaryPath, backup); err != nil {
		return false, err
	}
	return true, nil
}

// SnapshotTailscaledStateIfChanged atomically mirrors a valid authenticated tailscaled.state
// to tailscaled.state.lkg whenever its SHA-256 digest changes. It never overwrites an existing
// good backup with empty, unauthenticated, or corrupted primary data.
func SnapshotTailscaledStateIfChanged(stateDir string, prevHash [32]byte) ([32]byte, bool, error) {
	if strings.TrimSpace(stateDir) == "" {
		return prevHash, false, nil
	}
	primaryPath := filepath.Join(stateDir, tailscaledStateFile)
	primary, err := os.ReadFile(primaryPath)
	if err != nil || !HasAuthenticatedTailscaledState(primary) {
		return prevHash, false, nil
	}
	sum := sha256.Sum256(primary)
	backupPath := filepath.Join(stateDir, tailscaledStateBackupFile)
	if sum == prevHash {
		if _, statErr := os.Stat(backupPath); statErr == nil {
			return prevHash, false, nil
		}
	}
	if err := writeFileAtomic0600(backupPath, primary); err != nil {
		return prevHash, false, err
	}
	return sum, true, nil
}

// PurgeTailscaledStateBackup removes the last-known-good state mirror on intentional logout.
func PurgeTailscaledStateBackup(stateDir string) {
	if strings.TrimSpace(stateDir) == "" {
		return
	}
	_ = os.Remove(filepath.Join(stateDir, tailscaledStateBackupFile))
}

func writeFileAtomic0600(targetPath string, data []byte) error {
	dir := filepath.Dir(targetPath)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	tmp, err := os.CreateTemp(dir, ".tsnet-state-*.tmp")
	if err != nil {
		return err
	}
	tmpPath := tmp.Name()
	cleanup := true
	defer func() {
		if cleanup {
			_ = os.Remove(tmpPath)
		}
	}()
	if err := tmp.Chmod(0o600); err != nil {
		_ = tmp.Close()
		return err
	}
	if _, err := tmp.Write(data); err != nil {
		_ = tmp.Close()
		return err
	}
	if err := tmp.Sync(); err != nil {
		_ = tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	if err := os.Rename(tmpPath, targetPath); err != nil {
		return err
	}
	cleanup = false
	return nil
}
