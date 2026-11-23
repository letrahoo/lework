package dev.lework

import dev.lework.platform.MobileTsnetPeer
import dev.lework.platform.PairedHost
import dev.lework.platform.isMobileClientNode
import dev.lework.platform.prioritizeTsnetPeers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TsnetHandshakePriorityTest {
    @Test
    fun `prioritizeTsnetPeers ranks previously paired Mac mini ahead of ASCII peers and mobile clients`() {
        val rawPeers = listOf(
            MobileTsnetPeer(
                hostName = "lework-ios-iphone",
                dnsName = "lework-ios-iphone.tail.ts.net",
                tailscaleIp = "100.79.10.1",
                os = "iOS",
                online = true,
                isLeworkNode = true,
                localProxyUrl = "https://127.0.0.1:51001",
            ),
            MobileTsnetPeer(
                hostName = "lework-android-phone",
                dnsName = "lework-android-phone.tail.ts.net",
                tailscaleIp = "100.110.10.2",
                os = "android",
                online = true,
                isLeworkNode = true,
                localProxyUrl = "https://127.0.0.1:51002",
            ),
            MobileTsnetPeer(
                hostName = "MacBook Air",
                dnsName = "macbook-air.tail.ts.net",
                tailscaleIp = "100.84.10.3",
                os = "macOS",
                online = true,
                localProxyUrl = "https://127.0.0.1:51003",
            ),
            MobileTsnetPeer(
                hostName = "cloud-gateway",
                dnsName = "cloud-gateway.tail.ts.net",
                tailscaleIp = "100.80.10.4",
                os = "linux",
                online = true,
                localProxyUrl = "https://127.0.0.1:51004",
            ),
            MobileTsnetPeer(
                hostName = "工作室 Mac mini",
                dnsName = "mac-mini.tail.ts.net",
                tailscaleIp = "100.64.0.99",
                os = "macOS",
                online = true,
                localProxyUrl = "https://127.0.0.1:51009",
            ),
        )

        val paired = listOf(
            PairedHost(
                hubId = "hub_home_mini",
                name = "home-mini",
                endpoints = listOf("https://192.168.1.200:64450", "https://100.64.0.99:64450"),
            ),
        )

        val sorted = prioritizeTsnetPeers(rawPeers, pairedHosts = paired, activeHostId = "hub_home_mini")
        assertEquals("工作室 Mac mini", sorted.first().hostName)
        assertFalse(sorted.first().isMobileClientNode())
        assertTrue(sorted.takeLast(2).all { it.isMobileClientNode() })
    }
}
