package dev.lework.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TsnetIdentityVaultTest {
    private val validStateJson =
        """{"_current-profile":"profile-1234","_machinekey":"privkey:abcd","profile-1234":"e30="}"""

    @Test
    fun authenticatedStateValidationRejectsCorruptEmptyAndUnauthenticatedPayloads() {
        assertFalse(isAuthenticatedTailscaledState(null))
        assertFalse(isAuthenticatedTailscaledState(""))
        assertFalse(isAuthenticatedTailscaledState("{truncated"))
        assertFalse(isAuthenticatedTailscaledState("""{"_machinekey":"privkey:abcd"}"""))
        assertFalse(isAuthenticatedTailscaledState("""{"_current-profile":"","_machinekey":"privkey:abcd"}"""))
        assertFalse(isAuthenticatedTailscaledState("""{"_current-profile":"   ","_machinekey":"privkey:abcd"}"""))
        assertFalse(isAuthenticatedTailscaledState("""{"_current-profile":123,"_machinekey":"privkey:abcd"}"""))
        assertFalse(isAuthenticatedTailscaledState("""{"_current-profile":"profile-1234"}"""))
        assertTrue(isAuthenticatedTailscaledState(validStateJson))
    }

    @Test
    fun encodeAndDecodeRoundTripPreservesStateAndNormalizesControlUrl() {
        assertNull(encodeTsnetIdentityVault("""{"_machinekey":"privkey:only"}"""))

        val encoded = encodeTsnetIdentityVault(
            tailscaledStateJson = "  $validStateJson  ",
            controlUrl = "  https://headscale.example.com  ",
            enabled = true,
        )
        assertNotNull(encoded)

        val decoded = decodeTsnetIdentityVault(encoded)
        assertNotNull(decoded)
        assertEquals(TSNET_IDENTITY_VAULT_VERSION, decoded.version)
        assertEquals(validStateJson, decoded.tailscaledStateJson)
        assertEquals("https://headscale.example.com", decoded.controlUrl)
        assertTrue(decoded.enabled)

        val noControl = decodeTsnetIdentityVault(
            encodeTsnetIdentityVault(validStateJson, controlUrl = "   "),
        )
        assertNotNull(noControl)
        assertNull(noControl.controlUrl)
    }

    @Test
    fun decodeRejectsUnsupportedVersionOrInvalidEmbeddedState() {
        assertNull(decodeTsnetIdentityVault(null))
        assertNull(decodeTsnetIdentityVault("not-json"))
        assertNull(
            decodeTsnetIdentityVault(
                """{"version":99,"tailscaledStateJson":"{\"_current-profile\":\"p\",\"k\":1}"}""",
            ),
        )
        assertNull(
            decodeTsnetIdentityVault(
                """{"version":1,"tailscaledStateJson":"{}"}""",
            ),
        )
    }

    @Test
    fun shouldRestoreRepairsMissingOrCorruptLocalStateWithoutClobberingHealthyLocalState() {
        val vault = decodeTsnetIdentityVault(encodeTsnetIdentityVault(validStateJson))
        assertNotNull(vault)

        // Fresh install after uninstall -> missing local file
        assertTrue(shouldRestoreTsnetState(null, vault))
        assertTrue(shouldRestoreTsnetState("", vault))
        // Crash-truncated local file
        assertTrue(shouldRestoreTsnetState("{broken", vault))
        // Unauthenticated fresh tsnet boot file
        assertTrue(shouldRestoreTsnetState("""{"_machinekey":"privkey:new"}""", vault))
        // Healthy existing local state must NEVER be overwritten
        assertFalse(shouldRestoreTsnetState(validStateJson, vault))
        assertFalse(shouldRestoreTsnetState(null, null))
    }

    @Test
    fun bridgeSettingsEncodingEscapesValidControlUrlAndSkipsBlank() {
        assertNull(encodeTsnetBridgeSettingsJson(null))
        assertNull(encodeTsnetBridgeSettingsJson("   "))
        assertEquals(
            """{"controlUrl":"https://hs.example.com:8443"}""",
            encodeTsnetBridgeSettingsJson("  https://hs.example.com:8443  "),
        )
    }
}
