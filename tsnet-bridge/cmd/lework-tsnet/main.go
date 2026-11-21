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
	"tailscale.com/net/netmon"
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
	// On Android (without `/etc/resolv.conf`), Go's pure-Go resolver defaults to `127.0.0.1:53`
	// unless custom DNS servers are supplied. Fall back to reliable public resolvers.
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

func probeOutboundLocalIP() net.IP {
	for _, target := range []string{"223.5.5.5:53", "1.1.1.1:53", "8.8.8.8:53"} {
		conn, err := net.DialTimeout("udp4", target, 500*time.Millisecond)
		if err != nil {
			continue
		}
		addr, ok := conn.LocalAddr().(*net.UDPAddr)
		_ = conn.Close()
		if ok && addr != nil && len(addr.IP) > 0 && !addr.IP.IsLoopback() && !addr.IP.IsUnspecified() {
			return addr.IP
		}
	}
	return nil
}

// installSafeInterfaceGetterIfNeeded avoids Android 11+ SELinux `NETLINK_ROUTE` denials when
// `net.Interfaces()` is blocked inside an untrusted_app sandbox.
func installSafeInterfaceGetterIfNeeded(rawSpec string) {
	_, stdErr := net.Interfaces()
	trimmed := strings.TrimSpace(rawSpec)
	if stdErr == nil && trimmed == "" {
		return
	}

	type parsedIf struct {
		name  string
		addrs []net.Addr
	}
	var staticList []parsedIf
	var primaryName string
	for _, entry := range strings.Split(trimmed, ",") {
		entry = strings.TrimSpace(entry)
		if entry == "" {
			continue
		}
		ifName := "wlan0"
		cidr := entry
		if colon := strings.IndexByte(entry, '='); colon > 0 {
			ifName = strings.TrimSpace(entry[:colon])
			cidr = strings.TrimSpace(entry[colon+1:])
		}
		_, ipNet, err := net.ParseCIDR(cidr)
		if err != nil {
			ip := net.ParseIP(cidr)
			if ip == nil {
				continue
			}
			bits := 128
			mask := 64
			if v4 := ip.To4(); v4 != nil {
				ip = v4
				bits = 32
				mask = 24
			}
			ipNet = &net.IPNet{IP: ip, Mask: net.CIDRMask(mask, bits)}
		} else if ip := net.ParseIP(strings.Split(cidr, "/")[0]); ip != nil {
			if v4 := ip.To4(); v4 != nil {
				ipNet.IP = v4
			} else {
				ipNet.IP = ip
			}
		}
		if primaryName == "" {
			primaryName = ifName
		}
		merged := false
		for i := range staticList {
			if staticList[i].name == ifName {
				staticList[i].addrs = append(staticList[i].addrs, ipNet)
				merged = true
				break
			}
		}
		if !merged {
			staticList = append(staticList, parsedIf{name: ifName, addrs: []net.Addr{ipNet}})
		}
	}
	if primaryName == "" {
		primaryName = "wlan0"
	}
	notifyAndroidDefaultRouteInterface(primaryName)

	netmon.RegisterInterfaceGetter(func() ([]netmon.Interface, error) {
		out := []netmon.Interface{
			{
				Interface: &net.Interface{
					Index: 1,
					MTU:   65536,
					Name:  "lo",
					Flags: net.FlagUp | net.FlagLoopback,
				},
				AltAddrs: []net.Addr{
					&net.IPNet{IP: net.IPv4(127, 0, 0, 1), Mask: net.CIDRMask(8, 32)},
				},
			},
		}

		// Always probe live outbound UDP route IP first so Wi-Fi <-> 5G transitions update dynamically
		if liveIP := probeOutboundLocalIP(); liveIP != nil {
			v4 := liveIP.To4()
			if v4 != nil {
				out = append(out, netmon.Interface{
					Interface: &net.Interface{
						Index: 2,
						MTU:   1500,
						Name:  primaryName,
						Flags: net.FlagUp | net.FlagBroadcast | net.FlagMulticast,
					},
					AltAddrs: []net.Addr{
						&net.IPNet{IP: v4, Mask: net.CIDRMask(24, 32)},
					},
				})
				return out, nil
			}
		}

		for idx, item := range staticList {
			out = append(out, netmon.Interface{
				Interface: &net.Interface{
					Index: idx + 2,
					MTU:   1500,
					Name:  item.name,
					Flags: net.FlagUp | net.FlagBroadcast | net.FlagMulticast,
				},
				AltAddrs: item.addrs,
			})
		}
		if len(out) == 1 {
			// Offline fallback placeholder so netmon.New() never aborts daemon startup
			out = append(out, netmon.Interface{
				Interface: &net.Interface{
					Index: 2,
					MTU:   1500,
					Name:  primaryName,
					Flags: net.FlagUp,
				},
				AltAddrs: []net.Addr{},
			})
		}
		return out, nil
	})
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
	interfacesSpec := flag.String("interfaces", "", "Comma-separated ifname=cidr entries (used on Android 11+ where netlink is restricted)")
	hubPort := flag.Int("hub-port", 64450, "Virtual tsnet port to listen/connect on")
	verbose := flag.Bool("verbose", false, "Enable verbose tsnet logging")
	oneShotLogin := flag.Bool("login", false, "Run interactive login in foreground and exit once Running")
	flag.Parse()

	installCustomDNSResolverIfNeeded(*dnsServers)
	installSafeInterfaceGetterIfNeeded(*interfacesSpec)

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
		ForwarderSecret: strings.TrimSpace(os.Getenv("LEWORK_FORWARDER_SECRET")),
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
