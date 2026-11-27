package dev.lework.platform

import dev.lework.protocol.ProtocolJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal const val TSNET_IDENTITY_VAULT_VERSION: Int = 1
private const val MIN_AUTHENTICATED_STATE_KEYS = 2

/**
 * Serialized snapshot of an authenticated embedded `tsnet` node identity (`tailscaled.state`
 * plus optional custom Headscale `controlUrl`). Persisted in platform secure storage that
 * survives app reinstalls (e.g. iOS Keychain) so the device keeps its exact Tailscale
 * MachineKey/NodeKey instead of registering duplicate nodes.
 */
@Serializable
internal data class TsnetIdentityVaultSnapshot(
    val version: Int = TSNET_IDENTITY_VAULT_VERSION,
    val tailscaledStateJson: String,
    val controlUrl: String? = null,
    val enabled: Boolean = true,
)

@Serializable
private data class TsnetBridgeSettingsPayload(
    val controlUrl: String = "",
)

/**
 * Returns `true` only when [rawJson] is a valid JSON object containing a non-blank
 * `_current-profile` string alongside profile/key data. Prevents overwriting a good
 * identity backup with an empty, unauthenticated, or crash-truncated file.
 */
internal fun isAuthenticatedTailscaledState(rawJson: String?): Boolean {
    val clean = rawJson?.trim().orEmpty()
    if (clean.isEmpty()) return false
    val parsed = runCatching { ProtocolJson.parseToJsonElement(clean) as? JsonObject }.getOrNull()
        ?: return false
    if (parsed.size < MIN_AUTHENTICATED_STATE_KEYS) return false
    val currentProfile = (parsed["_current-profile"] as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.contentOrNull
        ?.trim()
        .orEmpty()
    return currentProfile.isNotEmpty()
}

internal fun encodeTsnetIdentityVault(
    tailscaledStateJson: String,
    controlUrl: String? = null,
    enabled: Boolean = true,
): String? {
    val cleanState = tailscaledStateJson.trim()
    if (!isAuthenticatedTailscaledState(cleanState)) return null
    val snapshot = TsnetIdentityVaultSnapshot(
        version = TSNET_IDENTITY_VAULT_VERSION,
        tailscaledStateJson = cleanState,
        controlUrl = controlUrl?.trim()?.takeIf { it.isNotEmpty() },
        enabled = enabled,
    )
    return ProtocolJson.encodeToString(TsnetIdentityVaultSnapshot.serializer(), snapshot)
}

internal fun decodeTsnetIdentityVault(raw: String?): TsnetIdentityVaultSnapshot? {
    val clean = raw?.trim().orEmpty()
    if (clean.isEmpty()) return null
    val parsed = runCatching {
        ProtocolJson.decodeFromString(TsnetIdentityVaultSnapshot.serializer(), clean)
    }.getOrNull() ?: return null
    if (parsed.version != TSNET_IDENTITY_VAULT_VERSION) return null
    if (!isAuthenticatedTailscaledState(parsed.tailscaledStateJson)) return null
    return parsed.copy(controlUrl = parsed.controlUrl?.trim()?.takeIf { it.isNotEmpty() })
}

/**
 * Restores a persisted vault snapshot only when the local `tailscaled.state` is missing,
 * unauthenticated, or corrupted — never clobbering a healthy local authenticated identity.
 */
internal fun shouldRestoreTsnetState(
    existingLocalStateJson: String?,
    vaultSnapshot: TsnetIdentityVaultSnapshot?,
): Boolean {
    if (vaultSnapshot == null) return false
    if (!isAuthenticatedTailscaledState(vaultSnapshot.tailscaledStateJson)) return false
    return !isAuthenticatedTailscaledState(existingLocalStateJson)
}

internal fun encodeTsnetBridgeSettingsJson(controlUrl: String?): String? {
    val clean = controlUrl?.trim().orEmpty()
    if (clean.isEmpty()) return null
    return ProtocolJson.encodeToString(
        TsnetBridgeSettingsPayload.serializer(),
        TsnetBridgeSettingsPayload(controlUrl = clean),
    )
}
