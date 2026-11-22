package mesh

import (
	"context"
	"io"
	"net"
	"strings"
	"testing"
	"time"
)

type fakeBackend struct {
	status          *RawTailnetStatus
	whoisMap        map[string]*VerifiedInboundPeer
	listener        net.Listener
	dialAddr        string
	linkChangeCalls int
}

func (f *fakeBackend) Start() error { return nil }
func (f *fakeBackend) Close() error {
	if f.listener != nil {
		_ = f.listener.Close()
	}
	return nil
}
func (f *fakeBackend) Listen(network, _ string) (net.Listener, error) {
	return f.listener, nil
}
func (f *fakeBackend) Dial(_ context.Context, network, _ string) (net.Conn, error) {
	return net.Dial(network, f.dialAddr)
}
func (f *fakeBackend) QueryStatus(_ context.Context) (*RawTailnetStatus, error) {
	return f.status, nil
}
func (f *fakeBackend) StartInteractiveLogin(_ context.Context) (string, error) {
	return "https://login.tailscale.com/a/test1234", nil
}
func (f *fakeBackend) Logout(_ context.Context) error { return nil }
func (f *fakeBackend) NotifyLinkChange(_ context.Context) error {
	f.linkChangeCalls++
	return nil
}
func (f *fakeBackend) WhoIs(_ context.Context, remoteAddr string) (*VerifiedInboundPeer, error) {
	host, _, _ := net.SplitHostPort(remoteAddr)
	if v, ok := f.whoisMap[host]; ok {
		return v, nil
	}
	return &VerifiedInboundPeer{Found: false, SameUser: false}, nil
}

func TestSameAccountDiscoveryAndForwarding(t *testing.T) {
	// Upstream mock Hub TCP echo server
	hubLn, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer hubLn.Close()
	go func() {
		for {
			c, aErr := hubLn.Accept()
			if aErr != nil {
				return
			}
			go func(conn net.Conn) {
				defer conn.Close()
				_, _ = io.Copy(conn, conn)
			}(c)
		}
	}()

	fb := &fakeBackend{
		dialAddr: hubLn.Addr().String(),
		status: &RawTailnetStatus{
			BackendState: "Running",
			SelfUserID:   42,
			SelfHostName: "lework-ios",
			SelfIPs:      []string{"100.64.0.9"},
			LoginName:    "owner@example.com",
			DisplayName:  "Owner",
			Peers: []RawPeerInfo{
				// 1. Same user, lework host -> MUST be discovered first with LocalProxyURL!
				{UserID: 42, HostName: "lework-hub-mac", DNSName: "lework-hub-mac.ts.net", Online: true, TailscaleIPs: []string{"100.64.0.1"}},
				// 2. Different user on shared tailnet -> MUST be rejected!
				{UserID: 99, HostName: "lework-hub-colleague", DNSName: "colleague.ts.net", Online: true, TailscaleIPs: []string{"100.64.0.2"}},
				// 3. Tagged shared server -> MUST be rejected!
				{UserID: 42, IsTagged: true, HostName: "lework-tagged-ci", DNSName: "ci.ts.net", Online: true, TailscaleIPs: []string{"100.64.0.3"}},
			},
		},
	}

	mgr, err := NewManager(Config{
		StateDir: t.TempDir(),
		Hostname: "lework-ios",
		HubPort:  64450,
	}, func(Config) (EngineBackend, error) { return fb, nil })
	if err != nil {
		t.Fatal(err)
	}
	defer mgr.Close()

	snap := mgr.Status(context.Background())
	if len(snap.Peers) != 1 {
		t.Fatalf("expected exactly 1 same-account untagged peer, got %d: %+v", len(snap.Peers), snap.Peers)
	}
	peer := snap.Peers[0]
	if peer.HostName != "lework-hub-mac" || !strings.HasPrefix(peer.LocalProxyURL, "https://127.0.0.1:") {
		t.Fatalf("unexpected peer candidate: %+v", peer)
	}

	// Verify local TCP forwarder pipes bytes end-to-end
	proxyAddr := strings.TrimPrefix(peer.LocalProxyURL, "https://")
	conn, err := net.DialTimeout("tcp", proxyAddr, 2*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	_, _ = conn.Write([]byte("TLS_CLIENT_HELLO"))
	buf := make([]byte, 16)
	_, err = io.ReadFull(conn, buf)
	if err != nil || string(buf) != "TLS_CLIENT_HELLO" {
		t.Fatalf("forwarder echo mismatch: %q, err=%v", string(buf), err)
	}
}

func TestHostInboundProxyVerifiesSameUserAndRecordsSourcePort(t *testing.T) {
	tsLn, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}

	seenVerified := make(chan VerifiedInboundPeer, 1)

	// Upstream mock hubd inspecting the local client port in `LookupInboundByLocalPort`
	hubLn, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer hubLn.Close()

	fb := &fakeBackend{
		listener: tsLn,
		status: &RawTailnetStatus{
			BackendState: "Running",
			SelfUserID:   77,
		},
		whoisMap: map[string]*VerifiedInboundPeer{
			"127.0.0.1": {
				Found:       true,
				SameUser:    true,
				UserID:      77,
				LoginName:   "owner@example.com",
				DisplayName: "Owner",
				NodeName:    "lework-ios",
				TailscaleIP: "100.64.1.8",
			},
		},
	}

	mgr, err := NewManager(Config{
		StateDir:        t.TempDir(),
		Hostname:        "lework-hub",
		HubUpstreamAddr: hubLn.Addr().String(),
		HubPort:         64450,
	}, func(Config) (EngineBackend, error) { return fb, nil })
	if err != nil {
		t.Fatal(err)
	}
	defer mgr.Close()

	go func(m *Manager) {
		c, aErr := hubLn.Accept()
		if aErr != nil {
			return
		}
		defer c.Close()
		remotePort := c.RemoteAddr().(*net.TCPAddr).Port
		seenVerified <- m.LookupInboundByLocalPort(remotePort)
		_, _ = c.Write([]byte("OK"))
	}(mgr)

	client, err := net.DialTimeout("tcp", tsLn.Addr().String(), 2*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()

	select {
	case v := <-seenVerified:
		if !v.Found || !v.SameUser || v.LoginName != "owner@example.com" {
			t.Fatalf("expected verified same-user peer, got %+v", v)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("timed out waiting for inbound proxied connection")
	}
}

func TestPipeBidirectionalPreservesDelayedResponseAfterHalfClose(t *testing.T) {
	srvLn, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer srvLn.Close()

	go func() {
		conn, aErr := srvLn.Accept()
		if aErr != nil {
			return
		}
		defer conn.Close()
		buf := make([]byte, 64)
		_, _ = conn.Read(buf)
		// Simulate Hub computing attestation/TLS proof after client finishes writing request
		time.Sleep(80 * time.Millisecond)
		_, _ = conn.Write([]byte("ATTEST_OK"))
	}()

	proxyLn, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer proxyLn.Close()

	go func() {
		c, aErr := proxyLn.Accept()
		if aErr != nil {
			return
		}
		defer c.Close()
		up, dErr := net.Dial("tcp", srvLn.Addr().String())
		if dErr != nil {
			return
		}
		defer up.Close()
		pipeBidirectional(c, up)
	}()

	cli, err := net.Dial("tcp", proxyLn.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer cli.Close()
	_, _ = cli.Write([]byte("HELLO"))
	_ = cli.(*net.TCPConn).CloseWrite()

	resp := make([]byte, 32)
	n, err := cli.Read(resp)
	if err != nil || string(resp[:n]) != "ATTEST_OK" {
		t.Fatalf("expected ATTEST_OK after half-close, got %q (err=%v)", string(resp[:n]), err)
	}
}

func TestForwarderSecretBlocksUnauthenticatedLocalCallers(t *testing.T) {
	echoLn, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer echoLn.Close()
	go func() {
		for {
			c, aErr := echoLn.Accept()
			if aErr != nil {
				return
			}
			go func(conn net.Conn) {
				defer conn.Close()
				_, _ = io.Copy(conn, conn)
			}(c)
		}
	}()

	fb := &fakeBackend{
		dialAddr: echoLn.Addr().String(),
		status: &RawTailnetStatus{
			BackendState: "Running",
			SelfUserID:   42,
			Peers: []RawPeerInfo{
				{UserID: 42, HostName: "lework-hub-mac", Online: true, TailscaleIPs: []string{"100.64.0.1"}},
			},
		},
	}

	const secret = "f8e1c2a3b4d567890123456789abcdef"
	mgr, err := NewManager(Config{
		StateDir:        t.TempDir(),
		Hostname:        "lework-android",
		HubPort:         64450,
		ForwarderSecret: secret,
	}, func(Config) (EngineBackend, error) { return fb, nil })
	if err != nil {
		t.Fatal(err)
	}
	defer mgr.Close()

	snap := mgr.Status(context.Background())
	proxyAddr := strings.TrimPrefix(snap.Peers[0].LocalProxyURL, "https://")

	// 1. Rogue co-installed app sending raw TLS ClientHello without preamble MUST be dropped
	rogue, err := net.DialTimeout("tcp", proxyAddr, 2*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	_, _ = rogue.Write([]byte("\x16\x03\x01\x00\xf0ROGUE_CLIENT_HELLO_PADDING_BYTES_0123456789ABCDEF"))
	_ = rogue.SetReadDeadline(time.Now().Add(1 * time.Second))
	rogueBuf := make([]byte, 16)
	if n, rErr := rogue.Read(rogueBuf); rErr == nil && n > 0 {
		t.Fatalf("expected rogue caller without preamble to be closed, got %q", string(rogueBuf[:n]))
	}
	_ = rogue.Close()

	// 2. Legitimate caller sending exact preamble followed immediately by TLS bytes MUST pass intact
	legit, err := net.DialTimeout("tcp", proxyAddr, 2*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer legit.Close()
	_, _ = legit.Write([]byte("LEWORK-FWD " + secret + "\nTLS_CLIENT_HELLO"))
	out := make([]byte, 16)
	if _, err := io.ReadFull(legit, out); err != nil || string(out) != "TLS_CLIENT_HELLO" {
		t.Fatalf("expected exact TLS_CLIENT_HELLO stream after preamble, got %q (err=%v)", string(out), err)
	}
}
