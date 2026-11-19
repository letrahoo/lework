package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"

	"dev.lework/tsnetbridge/internal/mesh"
)

func main() {
	home, _ := os.UserHomeDir()
	defaultDir := filepath.Join(home, ".lework", "tsnet")
	host, _ := os.Hostname()

	stateDir := flag.String("state-dir", defaultDir, "tsnet state directory")
	hostname := flag.String("hostname", "lework-hub-"+host, "Tailscale node hostname")
	controlURL := flag.String("control-url", "", "Optional custom Headscale / coordination server URL")
	authKey := flag.String("auth-key", os.Getenv("TS_AUTHKEY"), "Optional Tailscale auth key")
	upstream := flag.String("upstream", "127.0.0.1:64450", "Local hubd TLS upstream address")
	hubPort := flag.Int("hub-port", 64450, "Virtual tsnet port to listen on")
	verbose := flag.Bool("verbose", false, "Enable verbose tsnet logging")
	oneShotLogin := flag.Bool("login", false, "Run interactive login in foreground and exit once Running")
	flag.Parse()

	mgr, err := mesh.NewManager(mesh.Config{
		StateDir:        *stateDir,
		Hostname:        *hostname,
		ControlURL:      *controlURL,
		AuthKey:         *authKey,
		HubUpstreamAddr: *upstream,
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
