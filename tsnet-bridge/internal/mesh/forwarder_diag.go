package mesh

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"net/netip"
	"syscall"
	"time"

	"tailscale.com/ipn/ipnstate"
	"tailscale.com/net/netmon"
)

func emitForwarderEvent(w io.Writer, port int, event string, elapsed time.Duration) {
	fmt.Fprintf(w, "LEWORK_FWD_DIAG port=%d event=%s ms=%d\n", port, event, elapsed.Milliseconds())
}

// Classify returned errors only. In particular a deadline does not identify which
// part of tsnet.Dial (awaiting Running, path setup, TCP connect, etc.) timed out.
func forwarderDialEvent(err error) string {
	if err == nil {
		return "dial.ok"
	}
	var ne net.Error
	if errors.Is(err, context.DeadlineExceeded) || (errors.As(err, &ne) && ne.Timeout()) {
		return "dial.deadline"
	}
	if errors.Is(err, syscall.ECONNREFUSED) {
		return "dial.refused"
	}
	// gVisor gonet also returns untyped tcpip errors inside net.OpError.
	for cause := err; cause != nil; cause = errors.Unwrap(cause) {
		switch cause.Error() {
		case "connection was refused":
			return "dial.refused"
		case "operation timed out":
			return "dial.deadline"
		}
	}
	return "dial.other"
}

// Immutable projection of the most recent *ordinary* LocalClient.Status response.
// IPs are lookup keys only. Do not retain the original status, endpoints or user data.
type forwarderStatus struct {
	observed time.Time
	state    string
	peers    map[netip.Addr]forwarderPeer
}

type forwarderPeer struct {
	handshake time.Time
	pathHint  string
}

func projectForwarderStatus(st *ipnstate.Status, now time.Time) *forwarderStatus {
	s := &forwarderStatus{observed: now, state: "unknown", peers: make(map[netip.Addr]forwarderPeer)}
	switch st.BackendState {
	case "NoState", "InUseOtherUser", "NeedsLogin", "NeedsMachineAuth", "Stopped", "Starting", "Running":
		s.state = st.BackendState
	}
	for _, p := range st.Peer {
		if p == nil {
			continue
		}
		// Relay can be a peer's home DERP; neither field proves this dial's data path.
		hint := "none"
		if p.CurAddr != "" {
			hint = "direct_addr"
		} else if p.Relay != "" {
			hint = "relay_region"
		}
		for _, addr := range p.TailscaleIPs {
			s.peers[addr] = forwarderPeer{p.LastHandshake, hint}
		}
	}
	return s
}

func emitDialResult(w io.Writer, backend EngineBackend, f *localForwarder, err error, elapsed time.Duration) {
	event := forwarderDialEvent(err)
	emitForwarderEvent(w, f.port, event, elapsed)
	b, ok := backend.(*realTsnetBackend)
	if !ok || event != "dial.deadline" {
		return
	}
	var interfaces *netmon.State
	if sys := b.srv.Sys(); sys != nil {
		if mon, ok := sys.NetMon.GetOK(); ok {
			interfaces = mon.InterfaceState() // Last NetMon view, never enumerate interfaces or start a probe.
		}
	}
	addr, _ := netip.ParseAddr(f.peerIP)
	writeDeadlineSnapshot(w, f.port, addr, b.diagStatus.Load(), interfaces, time.Now())
}

func writeDeadlineSnapshot(w io.Writer, port int, target netip.Addr, s *forwarderStatus, interfaces *netmon.State, now time.Time) {
	age, state, peer, handshake, hint := int64(-1), "unknown", "unknown", "unknown", "unknown"
	if s != nil {
		age, state, peer = max(0, now.Sub(s.observed).Milliseconds()), s.state, "missing"
		if p, ok := s.peers[target]; ok {
			peer, hint = "found", p.pathHint
			switch d := now.Sub(p.handshake); {
			case p.handshake.IsZero():
				handshake = "not_reported"
			case d < 0:
				handshake = "unknown"
			case d <= 2*time.Minute:
				handshake = "recent"
			default:
				handshake = "older"
			}
		}
	}
	v4, v6 := "unknown", "unknown"
	if interfaces != nil {
		v4, v6 = fmt.Sprint(interfaces.HaveV4), fmt.Sprint(interfaces.HaveV6)
	}
	fmt.Fprintf(w, "LEWORK_FWD_DIAG port=%d event=dial.snapshot status_age_ms=%d backend=%s peer=%s handshake=%s path_hint=%s have_v4=%s have_v6=%s\n", port, age, state, peer, handshake, hint, v4, v6)
}
