package com.onlinemsg.client.data.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class EnvelopeDto(
    @SerialName("type") val type: String,
    @SerialName("key") val key: String? = null,
    @SerialName("data") val data: JsonElement? = JsonNull
)

@Serializable
data class HelloDataDto(
    @SerialName("publicKey") val publicKey: String,
    @SerialName("authChallenge") val authChallenge: String,
    @SerialName("authTtlSeconds") val authTtlSeconds: Int? = null,
    @SerialName("certFingerprintSha256") val certFingerprintSha256: String? = null
)

@Serializable
data class AuthPayloadDto(
    @SerialName("publicKey") val publicKey: String,
    @SerialName("challenge") val challenge: String,
    @SerialName("timestamp") val timestamp: Long,
    @SerialName("nonce") val nonce: String,
    @SerialName("signature") val signature: String
)

@Serializable
data class SignedPayloadDto(
    @SerialName("payload") val payload: String,
    @SerialName("timestamp") val timestamp: Long,
    @SerialName("nonce") val nonce: String,
    @SerialName("signature") val signature: String
)

fun JsonElement?.asPayloadText(): String {
    if (this == null || this is JsonNull) return ""
    return if (this is JsonPrimitive && this.isString) {
        this.content
    } else {
        this.toString()
    }
}

fun JsonElement?.asStringOrNull(): String? {
    if (this == null || this is JsonNull) return null
    return runCatching { this.jsonPrimitive.content }.getOrNull()
}
