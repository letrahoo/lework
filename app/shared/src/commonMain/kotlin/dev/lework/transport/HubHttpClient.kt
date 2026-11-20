package dev.lework.transport

import io.ktor.client.HttpClient

/**
 * Creates the TLS HTTP/WebSocket client used to talk to a Hub.
 *
 * @param pinnedOnly `true`  → handshake succeeds only if the leaf certificate fingerprint is in
 *                              [dev.lework.security.HubTrust.pinned] (used for anything that
 *                              carries the access token).
 *                   `false` → accept any certificate but record its fingerprint per host:port
 *                              (discovery / attestation probes only).
 * Hostname verification is replaced by fingerprint pinning (Hubs use self-signed certificates and
 * are reached via changing IPs / relay hosts).
 */
expect fun createHubHttpClient(pinnedOnly: Boolean): HttpClient
