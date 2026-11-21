package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"net"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"sync/atomic"
	"syscall"
	"time"

	"dev.lework/tsnetbridge/internal/mesh"
)

func installCustomDNSResolverIfNeeded(rawServers string) {
	var servers []string
	for _, item := range strings.Split(rawServers, ",") {
		clean := strings.TrimSpace(item)
		if clean == "" {
			continue
		}
		if _, _, err := net.SplitHostPort(clean); err != nil {
			clean = net.JoinHostPort(clean, "53")
		}
		servers = append(servers, clean)
	}
	// On Android (`GOOS=linux` without `/etc/resolv.conf`), Go's pure-Go resolver defaults to
	// `127.0.0.1:53` unless custom DNS servers are supplied. Fall back to reliable public resolvers.
	if len(servers) == 0 {
		if _, err := os.Stat("/etc/resolv.conf"); err != nil {
			servers = []string{"223.5.5.5:53", "1.1.1.1:53", "8.8.8.8:53"}
		}
	}
	if len(servers) == 0 {
		return
	}

	var rr atomic.Uint32
	dialer := &net.Dialer{Timeout: 3 * time.Second}
	net.DefaultResolver = &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, _ string) (net.Conn, error) {
			idx := int(rr.Add(1)-1) % len(servers)
			var lastErr error
			for i := 0; i < len(servers); i++ {
				target := servers[(idx+i)%len(servers)]
				conn, err := dialer.DialContext(ctx, network, target)
				if err == nil {
					return conn, nil
				}
				lastErr = err
			}
			return nil, lastErr
		},
	}
}

func main() {
	home, _ := os.UserHomeDir()
	defaultDir := filepath.Join(home, ".lework", "tsnet")
	host, _ := os.Hostname()

	stateDir := flag.String("state-dir", defaultDir, "tsnet state directory")
	hostname := flag.String("hostname", "lework-hub-"+host, "Tailscale node hostname")
	controlURL := flag.String("control-url", "", "Optional custom Headscale / coordination server URL")
	authKey := flag.String("auth-key", os.Getenv("TS_AUTHKEY"), "Optional Tailscale auth key")
	upstream := flag.String("upstream", "127.0.0.1:64450", "Local hubd TLS upstream address (empty for mobile client mode)")
	clientOnly := flag.Bool("client-only", false, "Run in mobile client forwarder mode without inbound Hub proxy")
	dnsServers := flag.String("dns", "", "Comma-separated DNS servers (used on Android where /etc/resolv.conf is absent)")
	hubPort := flag.Int("hub-port", 64450, "Virtual tsnet port to listen/connect on")
	verbose := flag.Bool("verbose", false, "Enable verbose tsnet logging")
	oneShotLogin := flag.Bool("login", false, "Run interactive login in foreground and exit once Running")
	flag.Parse()

	installCustomDNSResolverIfNeeded(*dnsServers)

	resolvedUpstream := *upstream
	if *clientOnly {
		resolvedUpstream = ""
	}

	mgr, err := mesh.NewManager(mesh.Config{
		StateDir:        *stateDir,
		Hostname:        *hostname,
		ControlURL:      *controlURL,
		AuthKey:         *authKey,
		HubUpstreamAddr: resolvedUpstream,
		HubPort:         *hubPort,
		VerboseLog:      *verbose,
	}, nil)
	if err != nil {
		fmt.Fprintf(os.Stderr, "failed to start tsnet manager: %v\n", err)
		os.Exit(1)
	}
	defer mgr.Close()

	if *oneShotLogin {
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Minute)
		defer cancel()
		snap := mgr.Login(ctx)
		if snap.BackendState == "Running" {
			out, _ := json.Marshal(snap)
			fmt.Println(string(out))
			return
		}
		if snap.AuthURL != "" {
			fmt.Printf("AUTH_URL=%s\n", snap.AuthURL)
		}
		for {
			select {
			case <-ctx.Done():
				fmt.Fprintln(os.Stderr, "timed out waiting for Tailscale login confirmation")
				os.Exit(1)
			case <-time.After(1500 * time.Millisecond):
				cur := mgr.Status(ctx)
				if cur.AuthURL != "" && snap.AuthURL == "" {
					snap.AuthURL = cur.AuthURL
					fmt.Printf("AUTH_URL=%s\n", cur.AuthURL)
				}
				if cur.BackendState == "Running" {
					out, _ := json.Marshal(cur)
					fmt.Println(string(out))
					return
				}
			}
		}
	}

	secret := os.Getenv("LEWORK_TSNET_SECRET")
	if secret == "" {
		fmt.Fprintln(os.Stderr, "LEWORK_TSNET_SECRET env is required in daemon mode")
		os.Exit(1)
	}
	ctlPort, srv, err := mgr.ServeControlHTTP(secret)
	if err != nil {
		fmt.Fprintf(os.Stderr, "failed to listen control HTTP: %v\n", err)
		os.Exit(1)
	}

	readyLine, _ := json.Marshal(map[string]any{
		"ready":       true,
		"controlPort": ctlPort,
	})
	fmt.Println(string(readyLine))

	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)
	<-sigCh
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	_ = srv.Shutdown(ctx)
}
