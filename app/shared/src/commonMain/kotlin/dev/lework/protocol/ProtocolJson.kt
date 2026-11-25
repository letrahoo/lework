package dev.lework.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

val ProtocolJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    classDiscriminator = "type"
}

sealed interface IncomingRpcFrame {
    data class Response(
        val id: Int,
        val result: JsonElement?,
        val error: RpcErrorObject?,
    ) : IncomingRpcFrame

    data class EventNotification(
        val event: AgentEvent,
    ) : IncomingRpcFrame

    data class SessionNotification(
        val session: SessionMeta,
    ) : IncomingRpcFrame

    data object Unknown : IncomingRpcFrame
}

fun encodeRpcRequest(id: Int, method: String, params: JsonElement? = null): String {
    val obj = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("method", method)
        if (params != null && params !is JsonNull) {
            put("params", params)
        } else {
            put("params", JsonObject(emptyMap()))
        }
    }
    return ProtocolJson.encodeToString(JsonObject.serializer(), obj)
}

fun decodeIncomingRpcFrame(raw: String): IncomingRpcFrame {
    val root = runCatching { ProtocolJson.parseToJsonElement(raw).jsonObject }.getOrNull()
        ?: return IncomingRpcFrame.Unknown

    val idPrim = root["id"] as? JsonPrimitive
    val idInt = idPrim?.intOrNull
    if (idInt != null && ("result" in root || "error" in root)) {
        val errElem = root["error"]
        val err = if (errElem != null && errElem !is JsonNull) {
            runCatching { ProtocolJson.decodeFromJsonElement<RpcErrorObject>(errElem) }.getOrNull()
        } else {
            null
        }
        return IncomingRpcFrame.Response(
            id = idInt,
            result = root["result"],
            error = err,
        )
    }

    val method = (root["method"] as? JsonPrimitive)?.content ?: return IncomingRpcFrame.Unknown
    val params = root["params"] ?: return IncomingRpcFrame.Unknown
    return when (method) {
        "stream.event" -> {
            val n = runCatching {
                ProtocolJson.decodeFromJsonElement<StreamEventNotification>(params)
            }.getOrNull()
            if (n != null) IncomingRpcFrame.EventNotification(n.event) else IncomingRpcFrame.Unknown
        }
        "session.updated" -> {
            val n = runCatching {
                ProtocolJson.decodeFromJsonElement<SessionUpdatedNotification>(params)
            }.getOrNull()
            if (n != null) IncomingRpcFrame.SessionNotification(n.session) else IncomingRpcFrame.Unknown
        }
        else -> IncomingRpcFrame.Unknown
    }
}

inline fun <reified T> encodeParams(value: T): JsonElement =
    ProtocolJson.encodeToJsonElement(value)

inline fun <reified T> decodeResult(element: JsonElement?): T {
    requireNotNull(element) { "Missing JSON-RPC result payload" }
    return ProtocolJson.decodeFromJsonElement(element)
}

@Serializable
data class HubDiscoverResult(
    val ok: Boolean,
    val hub: HubInfo,
    val oauthEnabled: Boolean = false,
    val oauthProvider: String? = null,
    val oauthDisplayName: String? = null,
    val tailscaleSameUser: Boolean = false,
    val tailscaleUser: String? = null,
    val tsnetAvailable: Boolean = false,
    val relayUrls: List<String> = emptyList(),
    val wsPath: String = "/v1/ws",
)

@Serializable
data class DirectTokenAuthResult(
    val ok: Boolean,
    val accessToken: String,
    val user: OAuthUser? = null,
    val fingerprint: String,
    val proof: String,
    val hub: HubInfo,
    val endpoints: List<String> = emptyList(),
)

@Serializable
data class QrPairingPayloadDto(
    val v: Int = 2,
    val hubId: String = "",
    val name: String = "",
    val role: String = "slave",
    val urls: List<String> = emptyList(),
    val url: String = "",
    val fp: String = "",
    val pairCode: String? = null,
    val token: String? = null,
)

@Serializable
data class OAuthStartResult(
    val sid: String,
    val userCode: String,
    val verifyUrl: String,
    val appLaunchUrl: String = "",
    val expiresIn: Int = 300,
    val interval: Int = 2,
)

@Serializable
data class OAuthUser(
    val subject: String = "",
    val name: String,
    val email: String = "",
)

@Serializable
data class OAuthPollResponse(
    val status: String,
    val user: OAuthUser? = null,
    val accessToken: String? = null,
    val name: String? = null,
    val detail: String? = null,
)
