package mesh

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"net/netip"
	"regexp"
	"strings"
	"syscall"
	"testing"
	"time"

	"tailscale.com/ipn/ipnstate"
	"tailscale.com/net/netmon"
	"tailscale.com/tsnet"
	"tailscale.com/types/key"
)

func TestForwarderDialClassifiesWithoutLoggingRawErrors(t *testing.T) {
	for _, tc := range []struct {
		err  error
		want string
	}{
		{nil, "dial.ok"}, {context.DeadlineExceeded, "dial.deadline"},
		{syscall.ECONNREFUSED, "dial.refused"}, {syscall.ETIMEDOUT, "dial.deadline"},
		{errors.New("operation timed out"), "dial.deadline"}, {errors.New("connection was refused"), "dial.refused"},
		{errors.New("owner@example.invalid: connection was refused"), "dial.other"}, {context.Canceled, "dial.other"},
	} {
		err := tc.err
		if err != nil {
			err = &net.OpError{Op: "sensitive payload", Net: "tcp", Addr: &net.TCPAddr{IP: net.IPv4(100, 64, 0, 2)}, Err: err}
		}
		// An unstarted real server has no cache/NetMon. Reading a snapshot must not
		// start it, make a status request or touch the network.
		f := &localForwarder{port: 1234, peerIP: "100.64.0.2"}
		var out bytes.Buffer
		emitDialResult(&out, &realTsnetBackend{srv: &tsnet.Server{}}, f, err, time.Second)
		want := "LEWORK_FWD_DIAG port=1234 event=" + tc.want + " ms=1000\n"
		if tc.want == "dial.deadline" {
			want += "LEWORK_FWD_DIAG port=1234 event=dial.snapshot status_age_ms=-1 backend=unknown peer=unknown handshake=unknown path_hint=unknown have_v4=unknown have_v6=unknown\n"
		}
		if got := out.String(); got != want {
			t.Errorf("got %q, want %q", got, want)
		}
	}
}

func TestDeadlineSnapshotOnlyEmitsObservedCategories(t *testing.T) {
	now := time.Unix(2_000_000, 0)
	addr := netip.MustParseAddr("100.64.0.12")
	for _, tc := range []struct {
		name, backend, cur, relay string
		handshake                 time.Time
		want                      string
	}{
		{"direct", "Running", "192.168.0.1:12345", "private-region", now.Add(-2 * time.Minute), "backend=Running peer=found handshake=recent path_hint=direct_addr"},
		{"relay", "NeedsLogin", "", "private-region", now.Add(-3 * time.Minute), "backend=NeedsLogin peer=found handshake=older path_hint=relay_region"},
		{"unreported", "tskey-secret", "", "", time.Time{}, "backend=unknown peer=found handshake=not_reported path_hint=none"},
		{"future", "Running", "", "", now.Add(time.Hour), "backend=Running peer=found handshake=unknown path_hint=none"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			st := &ipnstate.Status{BackendState: tc.backend, AuthURL: "https://private/token", Peer: map[key.NodePublic]*ipnstate.PeerStatus{
				{}: {HostName: "private-host", DNSName: "owner.example.invalid", TailscaleIPs: []netip.Addr{addr}, CurAddr: tc.cur, Relay: tc.relay, LastHandshake: tc.handshake},
			}}
			s := projectForwarderStatus(st, now.Add(-time.Second))
			var out bytes.Buffer
			writeDeadlineSnapshot(&out, 1234, addr, s, &netmon.State{HaveV6: true}, now)
			if want := "LEWORK_FWD_DIAG port=1234 event=dial.snapshot status_age_ms=1000 " + tc.want + " have_v4=false have_v6=true\n"; out.String() != want {
				t.Errorf("unexpected snapshot: %q", out.String())
			}
		})
	}
	for _, tc := range []struct {
		status *forwarderStatus
		want   string
	}{
		{nil, "status_age_ms=-1 backend=unknown peer=unknown"},
		{projectForwarderStatus(&ipnstate.Status{BackendState: "Stopped"}, now), "status_age_ms=0 backend=Stopped peer=missing"},
	} {
		var out bytes.Buffer
		writeDeadlineSnapshot(&out, 1234, addr, tc.status, nil, now)
		if want := "LEWORK_FWD_DIAG port=1234 event=dial.snapshot " + tc.want + " handshake=unknown path_hint=unknown have_v4=unknown have_v6=unknown\n"; out.String() != want {
			t.Errorf("unexpected snapshot: %q", out.String())
		}
	}
}

type diagnosticBackend struct{ fakeBackend }

func (*diagnosticBackend) Dial(context.Context, string, string) (net.Conn, error) {
	return nil, errors.New("secret dial error 100.64.0.1")
}

type diagLines chan string

func (lines diagLines) Write(p []byte) (int, error) { lines <- string(p); return len(p), nil }

func TestForwarderAuthAndDialStageLog(t *testing.T) {
	lines := make(diagLines, 8)
	mgr, err := NewManager(Config{StateDir: t.TempDir(), ForwarderSecret: "test-secret", ForwarderDiag: lines}, func(Config) (EngineBackend, error) { return &diagnosticBackend{}, nil })
	if err != nil {
		t.Fatal(err)
	}
	defer mgr.Close()
	port, err := mgr.ensureForwarder("100.64.0.1")
	if err != nil {
		t.Fatal(err)
	}
	for _, tc := range []struct{ secret, events string }{{"wrong-input", "preamble.reject"}, {"test-secret", "preamble.accept dial.start dial.other"}} {
		c, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", port), time.Second)
		if err != nil {
			t.Fatal(err)
		}
		_ = c.SetDeadline(time.Now().Add(2 * time.Second))
		if _, err := fmt.Fprintf(c, "LEWORK-FWD %s\n", tc.secret); err != nil {
			c.Close()
			t.Fatal(err)
		}
		_, err = io.Copy(io.Discard, c)
		c.Close()
		if err != nil {
			t.Fatal(err)
		}
		for _, event := range strings.Fields(tc.events) {
			select {
			case line := <-lines:
				pattern := fmt.Sprintf(`^LEWORK_FWD_DIAG port=%d event=%s ms=[0-9]+\n$`, port, regexp.QuoteMeta(event))
				if !regexp.MustCompile(pattern).MatchString(line) {
					t.Fatal("unexpected stage line:", line)
				}
			default:
				t.Fatalf("missing stage: %s", event)
			}
		}
		if len(lines) != 0 {
			t.Fatal("unexpected extra stage")
		}
	}
}
