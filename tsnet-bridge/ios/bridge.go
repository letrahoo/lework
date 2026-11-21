package main

/*
#include <stdlib.h>
*/
import "C"

import (
	"context"
	"encoding/json"
	"sync"
	"time"
	"unsafe"

	"dev.lework/tsnetbridge/internal/mesh"
)

var (
	bridgeMu  sync.Mutex
	bridgeMgr *mesh.Manager
)

func toCStringJson(v any) *C.char {
	b, err := json.Marshal(v)
	if err != nil {
		return C.CString(`{"backendState":"Error","tailscaleIps":[],"peers":[],"error":"marshal failed"}`)
	}
	return C.CString(string(b))
}

func statusWithStartupGrace(mgr *mesh.Manager) mesh.StatusSnapshot {
	for i := 0; i < 8; i++ {
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		st := mgr.Status(ctx)
		cancel()
		if st.BackendState != "Starting" && !(st.BackendState == "Running" && len(st.Peers) == 0 && i < 3) {
			return st
		}
		time.Sleep(250 * time.Millisecond)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	return mgr.Status(ctx)
}

//export LeworkTsnetStart
func LeworkTsnetStart(stateDir *C.char, hostname *C.char, controlURL *C.char) *C.char {
	bridgeMu.Lock()
	mgr := bridgeMgr
	if mgr == nil {
		created, err := mesh.NewManager(mesh.Config{
			StateDir:   C.GoString(stateDir),
			Hostname:   C.GoString(hostname),
			ControlURL: C.GoString(controlURL),
			HubPort:    64450,
		}, nil)
		if err != nil {
			bridgeMu.Unlock()
			return toCStringJson(mesh.StatusSnapshot{
				BackendState: "Error",
				TailscaleIPs: []string{},
				Peers:        []mesh.PeerCandidate{},
				Error:        err.Error(),
			})
		}
		bridgeMgr = created
		mgr = created
	}
	bridgeMu.Unlock()
	return toCStringJson(statusWithStartupGrace(mgr))
}

//export LeworkTsnetStatus
func LeworkTsnetStatus() *C.char {
	bridgeMu.Lock()
	mgr := bridgeMgr
	bridgeMu.Unlock()
	if mgr == nil {
		return toCStringJson(mesh.StatusSnapshot{
			BackendState: "Stopped",
			TailscaleIPs: []string{},
			Peers:        []mesh.PeerCandidate{},
		})
	}
	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
	defer cancel()
	return toCStringJson(mgr.Status(ctx))
}

//export LeworkTsnetLogin
func LeworkTsnetLogin() *C.char {
	bridgeMu.Lock()
	mgr := bridgeMgr
	bridgeMu.Unlock()
	if mgr == nil {
		return toCStringJson(mesh.StatusSnapshot{
			BackendState: "Stopped",
			TailscaleIPs: []string{},
			Peers:        []mesh.PeerCandidate{},
			Error:        "tsnet not started",
		})
	}
	ctx, cancel := context.WithTimeout(context.Background(), 12*time.Second)
	defer cancel()
	return toCStringJson(mgr.Login(ctx))
}

//export LeworkTsnetConfigure
func LeworkTsnetConfigure(controlURL *C.char, authKey *C.char) *C.char {
	bridgeMu.Lock()
	mgr := bridgeMgr
	bridgeMu.Unlock()
	if mgr == nil {
		return toCStringJson(mesh.StatusSnapshot{
			BackendState: "Stopped",
			TailscaleIPs: []string{},
			Peers:        []mesh.PeerCandidate{},
			Error:        "tsnet not started",
		})
	}
	if err := mgr.Reconfigure(C.GoString(controlURL), C.GoString(authKey)); err != nil {
		return toCStringJson(mesh.StatusSnapshot{
			BackendState: "Error",
			TailscaleIPs: []string{},
			Peers:        []mesh.PeerCandidate{},
			Error:        err.Error(),
		})
	}
	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
	defer cancel()
	return toCStringJson(mgr.Status(ctx))
}

//export LeworkTsnetLogout
func LeworkTsnetLogout() *C.char {
	bridgeMu.Lock()
	mgr := bridgeMgr
	bridgeMu.Unlock()
	if mgr == nil {
		return toCStringJson(mesh.StatusSnapshot{
			BackendState: "Stopped",
			TailscaleIPs: []string{},
			Peers:        []mesh.PeerCandidate{},
		})
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	return toCStringJson(mgr.Logout(ctx))
}

//export LeworkTsnetFreeString
func LeworkTsnetFreeString(ptr *C.char) {
	if ptr != nil {
		C.free(unsafe.Pointer(ptr))
	}
}

func main() {}
