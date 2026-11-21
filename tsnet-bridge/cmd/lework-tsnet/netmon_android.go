//go:build android

package main

import "tailscale.com/net/netmon"

func notifyAndroidDefaultRouteInterface(ifName string) {
	if ifName != "" {
		netmon.UpdateLastKnownDefaultRouteInterface(ifName)
	}
}
