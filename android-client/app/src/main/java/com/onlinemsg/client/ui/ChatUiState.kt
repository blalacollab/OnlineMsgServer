package com.onlinemsg.client.ui

import java.util.UUID

enum class ConnectionStatus {
    IDLE,
    CONNECTING,
    HANDSHAKING,
    AUTHENTICATING,
    READY,
    ERROR
}

enum class MessageRole {
    SYSTEM,
    INCOMING,
    OUTGOING
}

enum class MessageChannel {
    BROADCAST,
    PRIVATE
}

data class UiMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: MessageRole,
    val sender: String,
    val subtitle: String = "",
    val content: String,
    val channel: MessageChannel,
    val timestampMillis: Long = System.currentTimeMillis()
)

data class ChatUiState(
    val status: ConnectionStatus = ConnectionStatus.IDLE,
    val statusHint: String = "点击连接开始聊天",
    val displayName: String = "",
    val serverUrls: List<String> = emptyList(),
    val serverUrl: String = "",
    val directMode: Boolean = false,
    val targetKey: String = "",
    val draft: String = "",
    val messages: List<UiMessage> = emptyList(),
    val showSystemMessages: Boolean = false,
    val certFingerprint: String = "",
    val myPublicKey: String = "",
    val sending: Boolean = false,
    val loadingPublicKey: Boolean = false
) {
    val canConnect: Boolean
        get() = status == ConnectionStatus.IDLE || status == ConnectionStatus.ERROR

    val canDisconnect: Boolean
        get() = status == ConnectionStatus.CONNECTING ||
            status == ConnectionStatus.HANDSHAKING ||
            status == ConnectionStatus.AUTHENTICATING ||
            status == ConnectionStatus.READY

    val canSend: Boolean
        get() = status == ConnectionStatus.READY && draft.trim().isNotEmpty() && !sending

    val statusText: String
        get() = when (status) {
            ConnectionStatus.IDLE -> "未连接"
            ConnectionStatus.CONNECTING,
            ConnectionStatus.HANDSHAKING,
            ConnectionStatus.AUTHENTICATING -> "连接中"
            ConnectionStatus.READY -> "已连接"
            ConnectionStatus.ERROR -> "异常断开"
        }

    val visibleMessages: List<UiMessage>
        get() = messages.filter { item ->
            if (item.role == MessageRole.SYSTEM) {
                showSystemMessages
            } else {
                val selected = if (directMode) MessageChannel.PRIVATE else MessageChannel.BROADCAST
                item.channel == selected
            }
        }
}

sealed interface UiEvent {
    data class ShowSnackbar(val message: String) : UiEvent
}
