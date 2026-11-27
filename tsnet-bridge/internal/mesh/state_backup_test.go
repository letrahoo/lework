package mesh

import (
	"context"
	"os"
	"path/filepath"
	"testing"
)

const sampleValidTailscaledState = `{"_current-profile":"profile-abc123","_machinekey":"privkey:deadbeef","profile-abc123":"e30="}`

func TestHasAuthenticatedTailscaledStateValidation(t *testing.T) {
	cases := []struct {
		name string
		raw  string
		want bool
	}{
		{name: "empty", raw: "", want: false},
		{name: "truncated json", raw: `{"_current-profile":"abc"`, want: false},
		{name: "missing current profile", raw: `{"_machinekey":"privkey:123"}`, want: false},
		{name: "blank current profile", raw: `{"_current-profile":"   ","_machinekey":"privkey:123"}`, want: false},
		{name: "single key stub", raw: `{"_current-profile":"profile-1"}`, want: false},
		{name: "valid authenticated state", raw: sampleValidTailscaledState, want: true},
	}
	for _, tc := range cases {
		if got := HasAuthenticatedTailscaledState([]byte(tc.raw)); got != tc.want {
			t.Errorf("%s: got %v, want %v", tc.name, got, tc.want)
		}
	}
}

func TestSnapshotAndRestoreTailscaledStateLifecycle(t *testing.T) {
	dir := t.TempDir()
	primaryPath := filepath.Join(dir, tailscaledStateFile)
	backupPath := filepath.Join(dir, tailscaledStateBackupFile)

	// 1. Write valid primary state and snapshot it
	if err := os.WriteFile(primaryPath, []byte(sampleValidTailscaledState), 0o600); err != nil {
		t.Fatal(err)
	}
	hash1, written, err := SnapshotTailscaledStateIfChanged(dir, [32]byte{})
	if err != nil || !written {
		t.Fatalf("expected initial snapshot write, got written=%v err=%v", written, err)
	}

	// 2. Identical digest should be a no-op
	hash2, written, err := SnapshotTailscaledStateIfChanged(dir, hash1)
	if err != nil || written || hash2 != hash1 {
		t.Fatalf("expected deduped no-op snapshot, got written=%v err=%v", written, err)
	}

	// 3. Corrupted primary must NEVER overwrite the good backup
	if err := os.WriteFile(primaryPath, []byte("{corrupted"), 0o600); err != nil {
		t.Fatal(err)
	}
	hash3, written, err := SnapshotTailscaledStateIfChanged(dir, hash1)
	if err != nil || written || hash3 != hash1 {
		t.Fatalf("corrupted primary must not overwrite backup: written=%v err=%v", written, err)
	}

	// 4. Restore repairs corrupted primary from .lkg
	restored, err := RestoreTailscaledStateIfNeeded(dir)
	if err != nil || !restored {
		t.Fatalf("expected restore from .lkg, got restored=%v err=%v", restored, err)
	}
	gotPrimary, err := os.ReadFile(primaryPath)
	if err != nil || string(gotPrimary) != sampleValidTailscaledState {
		t.Fatalf("restored primary mismatch: %q err=%v", string(gotPrimary), err)
	}

	// 5. Restore must NOT clobber an already healthy newer primary
	newerState := `{"_current-profile":"profile-new","_machinekey":"privkey:cafebabe","profile-new":"e30="}`
	if err := os.WriteFile(primaryPath, []byte(newerState), 0o600); err != nil {
		t.Fatal(err)
	}
	restored, err = RestoreTailscaledStateIfNeeded(dir)
	if err != nil || restored {
		t.Fatalf("healthy primary must not be overwritten: restored=%v err=%v", restored, err)
	}
	if _, err := os.Stat(backupPath); err != nil {
		t.Fatalf("expected backup file to exist before purge: %v", err)
	}

	// 6. Purge removes backup cleanly
	PurgeTailscaledStateBackup(dir)
	if _, err := os.Stat(backupPath); !os.IsNotExist(err) {
		t.Fatalf("expected backup file removed after purge, got err=%v", err)
	}
}

func TestManagerSnapshotsOnRunningPurgesOnLogoutAndSkipsRedundantReconfigure(t *testing.T) {
	dir := t.TempDir()
	primaryPath := filepath.Join(dir, tailscaledStateFile)
	backupPath := filepath.Join(dir, tailscaledStateBackupFile)
	if err := os.WriteFile(primaryPath, []byte(sampleValidTailscaledState), 0o600); err != nil {
		t.Fatal(err)
	}

	starts := 0
	fb := &fakeBackend{
		dialAddr: "127.0.0.1:1",
		status: &RawTailnetStatus{
			BackendState: "Running",
			SelfUserID:   42,
			Peers: []RawPeerInfo{
				{UserID: 42, HostName: "lework-hub-mac", OS: "macOS", Online: true, TailscaleIPs: []string{"100.64.0.1"}},
				{UserID: 42, HostName: "lework-android-pjd110", OS: "android", Online: false, TailscaleIPs: []string{"100.64.0.2"}},
			},
		},
	}

	mgr, err := NewManager(Config{
		StateDir:   dir,
		Hostname:   "lework-ios-iphone",
		ControlURL: "https://control.example.com",
		HubPort:    64450,
	}, func(Config) (EngineBackend, error) {
		starts++
		return fb, nil
	})
	if err != nil {
		t.Fatal(err)
	}
	defer mgr.Close()

	// Running status should mirror tailscaled.state -> tailscaled.state.lkg
	snap := mgr.Status(context.Background())
	if _, err := os.Stat(backupPath); err != nil {
		t.Fatalf("expected .lkg snapshot after Running status, got %v", err)
	}

	// Client-mode status should allocate forwarder for Hub host, NOT stale mobile client peer
	if len(snap.Peers) != 2 {
		t.Fatalf("expected 2 peers, got %d", len(snap.Peers))
	}
	if snap.Peers[0].LocalProxyURL == "" {
		t.Fatalf("expected Hub peer to have LocalProxyURL, got %+v", snap.Peers[0])
	}
	if snap.Peers[1].LocalProxyURL != "" {
		t.Fatalf("expected mobile client peer to skip LocalProxyURL allocation, got %+v", snap.Peers[1])
	}

	// Identical Reconfigure should be a no-op (must NOT restart backend)
	if err := mgr.Reconfigure("https://control.example.com", ""); err != nil {
		t.Fatal(err)
	}
	if starts != 1 {
		t.Fatalf("expected redundant Reconfigure not to restart backend, got starts=%d", starts)
	}

	// Logout must purge tailscaled.state.lkg so a logged-out identity is never restored
	_ = mgr.Logout(context.Background())
	if _, err := os.Stat(backupPath); !os.IsNotExist(err) {
		t.Fatalf("expected .lkg snapshot purged on Logout, got err=%v", err)
	}
}
