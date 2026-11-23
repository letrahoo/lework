package mesh

import (
	"context"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"tailscale.com/ipn"
	"tailscale.com/tsnet"
)

// Config configures either a Host tsnet node (when HubUpstreamAddr != "")
// or a Mobile/Client tsnet node (when HubUpstreamAddr == "").
type Config struct {
	StateDir        string `json:"stateDir"`
	Hostname        string `json:"hostname"`
	ControlURL      string `json:"controlUrl,omitempty"`
	AuthKey         string `json:"authKey,omitempty"`
	HubUpstreamAddr string `json:"hubUpstreamAddr,omitempty"`
	HubPort         int    `json:"hubPort,omitempty"`
	ForwarderSecret string                         `json:"-"`
	OnNetworkChange func(dns, interfaces string)   `json:"-"`
	VerboseLog      bool                           `json:"verboseLog,omitempty"`
}

type PeerCandidate struct {
	HostName      string `json:"hostName"`
	DNSName       string `json:"dnsName"`
	TailscaleIP   string `json:"tailscaleIp"`
	OS            string `json:"os,omitempty"`
	Online        bool   `json:"online"`
	IsLeworkNode  bool   `json:"isLeworkNode"`
	LocalProxyURL string `json:"localProxyUrl,omitempty"`
}

type StatusSnapshot struct {
	BackendState string          `json:"backendState"`
	AuthURL      string          `json:"authUrl,omitempty"`
	ControlURL   string          `json:"controlUrl,omitempty"`
	SelfHostName string          `json:"selfHostName,omitempty"`
	SelfDNSName  string          `json:"selfDnsName,omitempty"`
	TailscaleIPs []string        `json:"tailscaleIps"`
	LoginName    string          `json:"loginName,omitempty"`
	DisplayName  string          `json:"displayName,omitempty"`
	Peers        []PeerCandidate `json:"peers"`
	Error        string          `json:"error,omitempty"`
}

type VerifiedInboundPeer struct {
	Found       bool   `json:"found"`
	SameUser    bool   `json:"sameUser"`
	IsTagged    bool   `json:"isTagged"`
	UserID      int64  `json:"userId,omitempty"`
	LoginName   string `json:"loginName,omitempty"`
	DisplayName string `json:"displayName,omitempty"`
	NodeName    string `json:"nodeName,omitempty"`
	TailscaleIP string `json:"tailscaleIp,omitempty"`
}

type persistedSettings struct {
	ControlURL string `json:"controlUrl,omitempty"`
}

// EngineBackend abstracts tsnet.Server + LocalClient so core handshake/proxy logic is 100% unit-testable.
type EngineBackend interface {
	Start() error
	Close() error
	Listen(network, addr string) (net.Listener, error)
	Dial(ctx context.Context, network, addr string) (net.Conn, error)
	QueryStatus(ctx context.Context) (*RawTailnetStatus, error)
	StartInteractiveLogin(ctx context.Context) (string, error)
	Logout(ctx context.Context) error
	WhoIs(ctx context.Context, remoteAddr string) (*VerifiedInboundPeer, error)
	NotifyLinkChange(ctx context.Context) error
}

type RawPeerInfo struct {
	UserID       int64
	IsTagged     bool
	HostName     string
	DNSName      string
	OS           string
	Online       bool
	TailscaleIPs []string
}

type RawTailnetStatus struct {
	BackendState string
	AuthURL      string
	SelfUserID   int64
	SelfHostName string
	SelfDNSName  string
	SelfIPs      []string
	LoginName    string
	DisplayName  string
	Peers        []RawPeerInfo
}

type realTsnetBackend struct {
	srv     *tsnet.Server
	mu      sync.Mutex
	authURL string
}

func prepareSandboxEnv(stateDir string) {
	dirs := map[string]string{
		"TS_LOGS_DIR":     filepath.Join(stateDir, "logs"),
		"TMPDIR":          filepath.Join(stateDir, "tmp"),
		"XDG_CACHE_HOME":  filepath.Join(stateDir, "cache"),
		"XDG_CONFIG_HOME": filepath.Join(stateDir, "config"),
	}
	for envKey, dir := range dirs {
		if err := os.MkdirAll(dir, 0o700); err == nil {
			if envKey == "TS_LOGS_DIR" || os.Getenv(envKey) == "" {
				_ = os.Setenv(envKey, dir)
			}
		}
	}
	if os.Getenv("HOME") == "" {
		homeDir := filepath.Join(stateDir, "home")
		if os.MkdirAll(homeDir, 0o700) == nil {
			_ = os.Setenv("HOME", homeDir)
		}
	}
	_ = os.Setenv("TS_NO_LOGS_NO_SUPPORT", "true")
}

func newRealTsnetBackend(cfg Config) (*realTsnetBackend, error) {
	if err := os.MkdirAll(cfg.StateDir, 0o700); err != nil {
		return nil, err
	}
	prepareSandboxEnv(cfg.StateDir)
	logf := func(string, ...any) {}
	if cfg.VerboseLog {
		logf = func(format string, args ...any) {
			fmt.Fprintf(os.Stderr, "[tsnet] "+format+"\n", args...)
		}
	}
	srv := &tsnet.Server{
		Dir:        cfg.StateDir,
		Hostname:   sanitizeHostname(cfg.Hostname),
		ControlURL: strings.TrimSpace(cfg.ControlURL),
		AuthKey:    strings.TrimSpace(cfg.AuthKey),
		Ephemeral:  false,
		Logf:       logf,
	}
	b := &realTsnetBackend{srv: srv}
	return b, nil
}

func (b *realTsnetBackend) Start() error {
	if err := b.srv.Start(); err != nil {
		return err
	}
	go b.watchAuthURL()
	return nil
}

func (b *realTsnetBackend) watchAuthURL() {
	lc, err := b.srv.LocalClient()
	if err != nil {
		return
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	watcher, err := lc.WatchIPNBus(ctx, ipn.NotifyInitialState)
	if err != nil {
		return
	}
	defer watcher.Close()
	for {
		n, err := watcher.Next()
		if err != nil {
			return
		}
		if n.BrowseToURL != nil && *n.BrowseToURL != "" {
			b.mu.Lock()
			b.authURL = *n.BrowseToURL
			b.mu.Unlock()
		}
		if n.State != nil && *n.State == ipn.Running {
			b.mu.Lock()
			b.authURL = ""
			b.mu.Unlock()
		}
	}
}

func (b *realTsnetBackend) Close() error {
	return b.srv.Close()
}

func (b *realTsnetBackend) Listen(network, addr string) (net.Listener, error) {
	return b.srv.Listen(network, addr)
}

func (b *realTsnetBackend) Dial(ctx context.Context, network, addr string) (net.Conn, error) {
	return b.srv.Dial(ctx, network, addr)
}

func (b *realTsnetBackend) QueryStatus(ctx context.Context) (*RawTailnetStatus, error) {
	lc, err := b.srv.LocalClient()
	if err != nil {
		return nil, err
	}
	st, err := lc.Status(ctx)
	if err != nil {
		return nil, err
	}
	b.mu.Lock()
	cachedAuthURL := b.authURL
	b.mu.Unlock()

	authURL := st.AuthURL
	if authURL == "" {
		authURL = cachedAuthURL
	}

	out := &RawTailnetStatus{
		BackendState: st.BackendState,
		AuthURL:      authURL,
	}
	if st.Self != nil {
		out.SelfUserID = int64(st.Self.UserID)
		out.SelfHostName = st.Self.HostName
		out.SelfDNSName = strings.TrimSuffix(st.Self.DNSName, ".")
		for _, ip := range st.Self.TailscaleIPs {
			out.SelfIPs = append(out.SelfIPs, ip.String())
		}
		if prof, ok := st.User[st.Self.UserID]; ok {
			out.LoginName = prof.LoginName
			out.DisplayName = prof.DisplayName
		}
	}
	for _, p := range st.Peer {
		if p == nil {
			continue
		}
		isTagged := p.Tags != nil && p.Tags.Len() > 0
		var ips []string
		// Prefer IPv4 first, then IPv6
		for _, ip := range p.TailscaleIPs {
			if ip.Is4() {
				ips = append(ips, ip.String())
			}
		}
		for _, ip := range p.TailscaleIPs {
			if ip.Is6() {
				ips = append(ips, ip.String())
			}
		}
		out.Peers = append(out.Peers, RawPeerInfo{
			UserID:       int64(p.UserID),
			IsTagged:     isTagged,
			HostName:     p.HostName,
			DNSName:      strings.TrimSuffix(p.DNSName, "."),
			OS:           p.OS,
			Online:       p.Online,
			TailscaleIPs: ips,
		})
	}
	return out, nil
}

func (b *realTsnetBackend) StartInteractiveLogin(ctx context.Context) (string, error) {
	lc, err := b.srv.LocalClient()
	if err != nil {
		return "", err
	}
	// Watch IPN bus concurrently so we catch BrowseToURL immediately
	watchCtx, cancel := context.WithTimeout(ctx, 12*time.Second)
	defer cancel()

	urlCh := make(chan string, 1)
	go func() {
		watcher, wErr := lc.WatchIPNBus(watchCtx, ipn.NotifyInitialState)
		if wErr != nil {
			return
		}
		defer watcher.Close()
		for {
			n, nErr := watcher.Next()
			if nErr != nil {
				return
			}
			if n.BrowseToURL != nil && *n.BrowseToURL != "" {
				b.mu.Lock()
				b.authURL = *n.BrowseToURL
				b.mu.Unlock()
				select {
				case urlCh <- *n.BrowseToURL:
				default:
				}
				return
			}
		}
	}()

	_ = lc.StartLoginInteractive(watchCtx)

	// Check if already running or URL already populated
	if st, sErr := lc.StatusWithoutPeers(watchCtx); sErr == nil {
		if st.BackendState == "Running" {
			return "", nil
		}
		if st.AuthURL != "" {
			return st.AuthURL, nil
		}
	}
	b.mu.Lock()
	existing := b.authURL
	b.mu.Unlock()
	if existing != "" {
		return existing, nil
	}

	select {
	case u := <-urlCh:
		return u, nil
	case <-watchCtx.Done():
		b.mu.Lock()
		u := b.authURL
		b.mu.Unlock()
		return u, nil
	}
}

func (b *realTsnetBackend) Logout(ctx context.Context) error {
	lc, err := b.srv.LocalClient()
	if err != nil {
		return err
	}
	b.mu.Lock()
	b.authURL = ""
	b.mu.Unlock()
	return lc.Logout(ctx)
}

func (b *realTsnetBackend) NotifyLinkChange(ctx context.Context) error {
	if nm, ok := b.srv.Sys().NetMon.GetOK(); ok {
		nm.InjectEvent()
	}
	lc, err := b.srv.LocalClient()
	if err != nil {
		return err
	}
	_ = lc.DebugAction(ctx, "rebind")
	_ = lc.DebugAction(ctx, "restun")
	return nil
}

func (b *realTsnetBackend) WhoIs(ctx context.Context, remoteAddr string) (*VerifiedInboundPeer, error) {
	lc, err := b.srv.LocalClient()
	if err != nil {
		return nil, err
	}
	st, err := lc.StatusWithoutPeers(ctx)
	if err != nil {
		return nil, err
	}
	w, err := lc.WhoIs(ctx, remoteAddr)
	if err != nil {
		return nil, err
	}
	selfUID := int64(0)
	if st.Self != nil {
		selfUID = int64(st.Self.UserID)
	}
	peerUID := int64(0)
	loginName := ""
	displayName := ""
	if w.UserProfile != nil {
		peerUID = int64(w.UserProfile.ID)
		loginName = w.UserProfile.LoginName
		displayName = w.UserProfile.DisplayName
	}
	isTagged := false
	nodeName := ""
	if w.Node != nil {
		isTagged = w.Node.IsTagged()
		nodeName = w.Node.ComputedName
		if nodeName == "" {
			nodeName = strings.TrimSuffix(w.Node.Name, ".")
		}
	}
	remoteHost, _, _ := net.SplitHostPort(remoteAddr)
	sameUser := selfUID != 0 && peerUID == selfUID && !isTagged
	return &VerifiedInboundPeer{
		Found:       true,
		SameUser:    sameUser,
		IsTagged:    isTagged,
		UserID:      peerUID,
		LoginName:   loginName,
		DisplayName: displayName,
		NodeName:    nodeName,
		TailscaleIP: remoteHost,
	}, nil
}

// Manager coordinates the tsnet backend, Host inbound proxy + WhoIs table, and Mobile outbound forwarders.
type Manager struct {
	mu             sync.Mutex
	cfg            Config
	backendFactory func(Config) (EngineBackend, error)
	backend        EngineBackend
	stopHostLoop   context.CancelFunc

	inboundMu    sync.RWMutex
	inboundBySrc map[int]VerifiedInboundPeer
	pendingDials atomic.Int32

	forwardMu  sync.Mutex
	forwarders map[string]*localForwarder
}

type localForwarder struct {
	peerIP string
	port   int
	ln     net.Listener
}

func NewManager(cfg Config, factory func(Config) (EngineBackend, error)) (*Manager, error) {
	if cfg.HubPort <= 0 {
		cfg.HubPort = 64450
	}
	if cfg.StateDir == "" {
		return nil, errors.New("stateDir is required")
	}
	if err := os.MkdirAll(cfg.StateDir, 0o700); err != nil {
		return nil, err
	}
	// Load persisted custom controlUrl if not overridden
	if cfg.ControlURL == "" {
		if saved, err := os.ReadFile(filepath.Join(cfg.StateDir, "bridge-settings.json")); err == nil {
			var ps persistedSettings
			if json.Unmarshal(saved, &ps) == nil && ps.ControlURL != "" {
				cfg.ControlURL = ps.ControlURL
			}
		}
	}
	if factory == nil {
		factory = func(c Config) (EngineBackend, error) {
			return newRealTsnetBackend(c)
		}
	}
	m := &Manager{
		cfg:            cfg,
		backendFactory: factory,
		inboundBySrc:   make(map[int]VerifiedInboundPeer),
		forwarders:     make(map[string]*localForwarder),
	}
	if err := m.startLocked(); err != nil {
		return nil, err
	}
	return m, nil
}

func (m *Manager) startLocked() error {
	b, err := m.backendFactory(m.cfg)
	if err != nil {
		return err
	}
	if err := b.Start(); err != nil {
		return err
	}
	m.backend = b
	if m.cfg.HubUpstreamAddr != "" {
		ctx, cancel := context.WithCancel(context.Background())
		m.stopHostLoop = cancel
		go m.runHostInboundProxy(ctx, b)
	}
	return nil
}

func (m *Manager) Close() error {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.closeLocked()
}

func (m *Manager) closeLocked() error {
	if m.stopHostLoop != nil {
		m.stopHostLoop()
		m.stopHostLoop = nil
	}
	m.forwardMu.Lock()
	for k, f := range m.forwarders {
		_ = f.ln.Close()
		delete(m.forwarders, k)
	}
	m.forwardMu.Unlock()
	if m.backend != nil {
		err := m.backend.Close()
		m.backend = nil
		return err
	}
	return nil
}

func (m *Manager) Reconfigure(controlURL, authKey string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	_ = m.closeLocked()
	m.cfg.ControlURL = strings.TrimSpace(controlURL)
	m.cfg.AuthKey = strings.TrimSpace(authKey)
	settingsBytes, _ := json.Marshal(persistedSettings{ControlURL: m.cfg.ControlURL})
	_ = os.WriteFile(filepath.Join(m.cfg.StateDir, "bridge-settings.json"), settingsBytes, 0o600)
	return m.startLocked()
}

func (m *Manager) Status(ctx context.Context) StatusSnapshot {
	m.mu.Lock()
	b := m.backend
	ctrlURL := m.cfg.ControlURL
	isClientMode := m.cfg.HubUpstreamAddr == ""
	m.mu.Unlock()

	if b == nil {
		return StatusSnapshot{BackendState: "Stopped", ControlURL: ctrlURL, TailscaleIPs: []string{}, Peers: []PeerCandidate{}}
	}
	raw, err := b.QueryStatus(ctx)
	if err != nil {
		return StatusSnapshot{
			BackendState: "Error",
			ControlURL:   ctrlURL,
			TailscaleIPs: []string{},
			Peers:        []PeerCandidate{},
			Error:        err.Error(),
		}
	}

	var candidates []PeerCandidate
	for _, p := range raw.Peers {
		// SECURITY: strictly restrict discovery to untagged nodes belonging to the EXACT SAME Tailscale user!
		if raw.SelfUserID == 0 || p.UserID != raw.SelfUserID || p.IsTagged {
			continue
		}
		if len(p.TailscaleIPs) == 0 {
			continue
		}
		isLework := isLeworkHubHost(p.HostName, p.OS)
		primaryIP := p.TailscaleIPs[0]
		candidates = append(candidates, PeerCandidate{
			HostName:     p.HostName,
			DNSName:      p.DNSName,
			TailscaleIP:  primaryIP,
			OS:           p.OS,
			Online:       p.Online,
			IsLeworkNode: isLework,
		})
	}

	sort.Slice(candidates, func(i, j int) bool {
		iMobile := isMobileClientPeer(candidates[i].HostName, candidates[i].OS)
		jMobile := isMobileClientPeer(candidates[j].HostName, candidates[j].OS)
		if iMobile != jMobile {
			// Desktop / server hosts (macOS, Linux, Windows) always rank ahead of iOS/Android mobile client nodes
			return !iMobile
		}
		if candidates[i].Online != candidates[j].Online {
			return candidates[i].Online
		}
		if candidates[i].IsLeworkNode != candidates[j].IsLeworkNode {
			return candidates[i].IsLeworkNode
		}
		return candidates[i].HostName < candidates[j].HostName
	})

	if len(candidates) > 16 {
		candidates = candidates[:16]
	}
	if isClientMode && raw.BackendState == "Running" {
		for i := range candidates {
			if port, fErr := m.ensureForwarder(candidates[i].TailscaleIP); fErr == nil && port > 0 {
				candidates[i].LocalProxyURL = fmt.Sprintf("https://127.0.0.1:%d", port)
			}
		}
	}

	selfIPs := raw.SelfIPs
	if selfIPs == nil {
		selfIPs = []string{}
	}
	if candidates == nil {
		candidates = []PeerCandidate{}
	}

	return StatusSnapshot{
		BackendState: raw.BackendState,
		AuthURL:      raw.AuthURL,
		ControlURL:   ctrlURL,
		SelfHostName: raw.SelfHostName,
		SelfDNSName:  raw.SelfDNSName,
		TailscaleIPs: selfIPs,
		LoginName:    raw.LoginName,
		DisplayName:  raw.DisplayName,
		Peers:        candidates,
	}
}

func (m *Manager) Login(ctx context.Context) StatusSnapshot {
	m.mu.Lock()
	b := m.backend
	m.mu.Unlock()
	if b == nil {
		return StatusSnapshot{BackendState: "Stopped", TailscaleIPs: []string{}, Peers: []PeerCandidate{}}
	}
	authURL, err := b.StartInteractiveLogin(ctx)
	snap := m.Status(ctx)
	if authURL != "" && snap.AuthURL == "" {
		snap.AuthURL = authURL
	}
	if err != nil && snap.Error == "" {
		snap.Error = err.Error()
	}
	return snap
}

func (m *Manager) Logout(ctx context.Context) StatusSnapshot {
	m.mu.Lock()
	b := m.backend
	m.mu.Unlock()
	if b != nil {
		_ = b.Logout(ctx)
	}
	return m.Status(ctx)
}

func (m *Manager) NotifyNetworkChange(ctx context.Context, dns, interfaces string) StatusSnapshot {
	m.mu.Lock()
	hook := m.cfg.OnNetworkChange
	b := m.backend
	m.mu.Unlock()
	if hook != nil {
		hook(dns, interfaces)
	}
	if b != nil {
		_ = b.NotifyLinkChange(ctx)
	}
	return m.Status(ctx)
}

func (m *Manager) LookupInboundByLocalPort(localPort int) VerifiedInboundPeer {
	for attempt := 0; attempt < 100; attempt++ {
		m.inboundMu.RLock()
		p, ok := m.inboundBySrc[localPort]
		m.inboundMu.RUnlock()
		if ok {
			return p
		}
		if m.pendingDials.Load() <= 0 {
			break
		}
		time.Sleep(time.Millisecond)
	}
	return VerifiedInboundPeer{Found: false}
}

func (m *Manager) ensureForwarder(peerIP string) (int, error) {
	m.forwardMu.Lock()
	defer m.forwardMu.Unlock()
	if existing, ok := m.forwarders[peerIP]; ok {
		return existing.port, nil
	}
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return 0, err
	}
	port := ln.Addr().(*net.TCPAddr).Port
	f := &localForwarder{peerIP: peerIP, port: port, ln: ln}
	m.forwarders[peerIP] = f
	go m.serveForwarder(f)
	return port, nil
}

func (m *Manager) serveForwarder(f *localForwarder) {
	for {
		clientConn, err := f.ln.Accept()
		if err != nil {
			return
		}
		go func(c net.Conn) {
			defer c.Close()
			m.mu.Lock()
			b := m.backend
			hubPort := m.cfg.HubPort
			fwdSecret := m.cfg.ForwarderSecret
			m.mu.Unlock()
			if b == nil {
				return
			}
			// SECURITY: When ForwarderSecret is set (e.g., on Android where 127.0.0.1 is shared across
			// all apps with INTERNET permission), require an exact-length pre-TLS secret preamble before
			// opening a Tailscale tunnel so co-installed apps cannot piggyback on `/v1/auth/tailscale`.
			if fwdSecret != "" {
				expected := []byte("LEWORK-FWD " + fwdSecret + "\n")
				buf := make([]byte, len(expected))
				_ = c.SetReadDeadline(time.Now().Add(2 * time.Second))
				_, rErr := io.ReadFull(c, buf)
				_ = c.SetReadDeadline(time.Time{})
				if rErr != nil || subtle.ConstantTimeCompare(buf, expected) != 1 {
					return
				}
			}
			dialCtx, cancel := context.WithTimeout(context.Background(), 12*time.Second)
			target := net.JoinHostPort(f.peerIP, strconv.Itoa(hubPort))
			tsConn, err := b.Dial(dialCtx, "tcp", target)
			cancel()
			if err != nil {
				return
			}
			defer tsConn.Close()
			pipeBidirectional(c, tsConn)
		}(clientConn)
	}
}

func (m *Manager) runHostInboundProxy(ctx context.Context, b EngineBackend) {
	listenAddr := fmt.Sprintf(":%d", m.cfg.HubPort)
	for {
		if ctx.Err() != nil {
			return
		}
		stCtx, stCancel := context.WithTimeout(ctx, 3*time.Second)
		st, err := b.QueryStatus(stCtx)
		stCancel()
		if err != nil || st.BackendState != "Running" {
			select {
			case <-ctx.Done():
				return
			case <-time.After(1200 * time.Millisecond):
				continue
			}
		}

		ln, err := b.Listen("tcp", listenAddr)
		if err != nil {
			select {
			case <-ctx.Done():
				return
			case <-time.After(2 * time.Second):
				continue
			}
		}

		go func() {
			<-ctx.Done()
			_ = ln.Close()
		}()

		for {
			tsConn, aErr := ln.Accept()
			if aErr != nil {
				_ = ln.Close()
				break
			}
			go m.handleInboundTsConn(ctx, b, tsConn)
		}
	}
}

func (m *Manager) handleInboundTsConn(ctx context.Context, b EngineBackend, tsConn net.Conn) {
	defer tsConn.Close()

	whoCtx, cancel := context.WithTimeout(ctx, 4*time.Second)
	verified, err := b.WhoIs(whoCtx, tsConn.RemoteAddr().String())
	cancel()
	if err != nil || verified == nil || !verified.SameUser {
		// Reject connections from different Tailscale accounts or ACL-tagged shared machines right at TCP layer!
		return
	}

	m.pendingDials.Add(1)
	localConn, err := net.DialTimeout("tcp", m.cfg.HubUpstreamAddr, 5*time.Second)
	if err != nil {
		m.pendingDials.Add(-1)
		return
	}
	defer localConn.Close()

	localTCP, ok := localConn.LocalAddr().(*net.TCPAddr)
	if !ok {
		m.pendingDials.Add(-1)
		return
	}
	srcPort := localTCP.Port

	m.inboundMu.Lock()
	m.inboundBySrc[srcPort] = *verified
	m.inboundMu.Unlock()
	m.pendingDials.Add(-1)

	defer func() {
		m.inboundMu.Lock()
		delete(m.inboundBySrc, srcPort)
		m.inboundMu.Unlock()
	}()

	pipeBidirectional(tsConn, localConn)
}

func pipeBidirectional(a, b net.Conn) {
	closeWrite := func(c net.Conn) {
		if cw, ok := c.(interface{ CloseWrite() error }); ok {
			_ = cw.CloseWrite()
		}
	}
	done := make(chan struct{}, 2)
	go func() {
		_, _ = io.Copy(a, b)
		closeWrite(a)
		done <- struct{}{}
	}()
	go func() {
		_, _ = io.Copy(b, a)
		closeWrite(b)
		done <- struct{}{}
	}()
	<-done
	_ = a.SetDeadline(time.Now().Add(10 * time.Second))
	_ = b.SetDeadline(time.Now().Add(10 * time.Second))
	<-done
}

// ServeControlHTTP serves the authenticated localhost control API used by hubd.
func (m *Manager) ServeControlHTTP(secret string) (int, *http.Server, error) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return 0, nil, err
	}
	mux := http.NewServeMux()
	auth := func(next http.HandlerFunc) http.HandlerFunc {
		return func(w http.ResponseWriter, r *http.Request) {
			got := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
			if secret == "" || subtle.ConstantTimeCompare([]byte(got), []byte(secret)) != 1 {
				w.WriteHeader(http.StatusUnauthorized)
				_, _ = w.Write([]byte(`{"error":"unauthorized"}`))
				return
			}
			w.Header().Set("Content-Type", "application/json; charset=utf-8")
			next(w, r)
		}
	}

	mux.HandleFunc("/v1/status", auth(func(w http.ResponseWriter, r *http.Request) {
		ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
		defer cancel()
		_ = json.NewEncoder(w).Encode(m.Status(ctx))
	}))

	mux.HandleFunc("/v1/login", auth(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			w.WriteHeader(http.StatusMethodNotAllowed)
			return
		}
		ctx, cancel := context.WithTimeout(r.Context(), 15*time.Second)
		defer cancel()
		_ = json.NewEncoder(w).Encode(m.Login(ctx))
	}))

	mux.HandleFunc("/v1/logout", auth(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			w.WriteHeader(http.StatusMethodNotAllowed)
			return
		}
		ctx, cancel := context.WithTimeout(r.Context(), 6*time.Second)
		defer cancel()
		_ = json.NewEncoder(w).Encode(m.Logout(ctx))
	}))

	mux.HandleFunc("/v1/configure", auth(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			w.WriteHeader(http.StatusMethodNotAllowed)
			return
		}
		var body struct {
			ControlURL string `json:"controlUrl"`
			AuthKey    string `json:"authKey"`
		}
		_ = json.NewDecoder(r.Body).Decode(&body)
		if err := m.Reconfigure(body.ControlURL, body.AuthKey); err != nil {
			w.WriteHeader(http.StatusInternalServerError)
			_ = json.NewEncoder(w).Encode(map[string]string{"error": err.Error()})
			return
		}
		ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
		defer cancel()
		_ = json.NewEncoder(w).Encode(m.Status(ctx))
	}))

	mux.HandleFunc("/v1/whois", auth(func(w http.ResponseWriter, r *http.Request) {
		port, _ := strconv.Atoi(r.URL.Query().Get("localPort"))
		_ = json.NewEncoder(w).Encode(m.LookupInboundByLocalPort(port))
	}))

	mux.HandleFunc("/v1/netchange", auth(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			w.WriteHeader(http.StatusMethodNotAllowed)
			return
		}
		var body struct {
			DNS        string `json:"dns"`
			Interfaces string `json:"interfaces"`
		}
		_ = json.NewDecoder(r.Body).Decode(&body)
		ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
		defer cancel()
		_ = json.NewEncoder(w).Encode(m.NotifyNetworkChange(ctx, body.DNS, body.Interfaces))
	}))

	srv := &http.Server{Handler: mux}
	go func() { _ = srv.Serve(ln) }()
	return ln.Addr().(*net.TCPAddr).Port, srv, nil
}

func isMobileClientPeer(hostName, osName string) bool {
	lowerOS := strings.ToLower(strings.TrimSpace(osName))
	if lowerOS == "ios" || lowerOS == "android" {
		return true
	}
	lowerHost := strings.ToLower(strings.TrimSpace(hostName))
	return strings.HasPrefix(lowerHost, "lework-ios") ||
		strings.HasPrefix(lowerHost, "lework-android") ||
		strings.HasPrefix(lowerHost, "levi-remote-")
}

func isLeworkHubHost(hostName, osName string) bool {
	if isMobileClientPeer(hostName, osName) {
		return false
	}
	lower := strings.ToLower(strings.TrimSpace(hostName))
	return strings.HasPrefix(lower, "lework-hub") || strings.HasPrefix(lower, "lework")
}

func sanitizeHostname(raw string) string {
	s := strings.ToLower(strings.TrimSpace(raw))
	var b strings.Builder
	lastDash := false
	for _, r := range s {
		if (r >= 'a' && r <= 'z') || (r >= '0' && r <= '9') {
			b.WriteRune(r)
			lastDash = false
		} else if !lastDash && b.Len() > 0 {
			b.WriteByte('-')
			lastDash = true
		}
	}
	out := strings.Trim(b.String(), "-")
	if out == "" {
		return "lework-node"
	}
	if len(out) > 48 {
		return strings.Trim(out[:48], "-")
	}
	return out
}
