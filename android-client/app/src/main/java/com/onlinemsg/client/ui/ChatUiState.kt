package com.onlinemsg.client.ui

import java.util.UUID

/**
 * 连接状态枚举。
 */
enum class ConnectionStatus {
    IDLE,               // 未连接
    CONNECTING,         // 连接中
    HANDSHAKING,        // 握手阶段
    AUTHENTICATING,     // 认证阶段
    READY,              // 已就绪
    ERROR               // 错误
}

/**
 * 消息角色。
 */
enum class MessageRole {
    SYSTEM,     // 系统消息
    INCOMING,   // 接收到的消息
    OUTGOING    // 发出的消息
}

/**
 * 消息通道（广播/私聊）。
 */
enum class MessageChannel {
    BROADCAST,
    PRIVATE
}

/**
 * 消息内容类型（文本/音频）。
 */
enum class MessageContentType {
    TEXT,
    AUDIO
}

/**
 * 单条消息的数据类。
 * @property id 唯一标识（默认随机 UUID）
 * @property role 消息角色
 * @property sender 发送者显示名称
 * @property subtitle 附加说明（如私聊来源/目标缩写）
 * @property content 消息内容
 * @property channel 消息通道
 * @property timestampMillis 消息时间戳（毫秒）
 */
data class UiMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: MessageRole,
    val sender: String,
    val subtitle: String = "",
    val content: String,
    val channel: MessageChannel,
    val timestampMillis: Long = System.currentTimeMillis(),
    val contentType: MessageContentType = MessageContentType.TEXT,
    val audioBase64: String = "",
    val audioDurationMillis: Long = 0L
)

/**
 * 整个聊天界面的状态数据类。
 * @property status 连接状态
 * @property statusHint 详细状态提示文本
 * @property displayName 用户显示名称
 * @property serverUrls 已保存的服务器地址列表
 * @property serverUrl 当前输入的服务器地址
 * @property directMode 是否为私聊模式
 * @property targetKey 私聊目标公钥
 * @property draft 输入框草稿
 * @property messages 所有消息列表
 * @property showSystemMessages 是否显示系统消息
 * @property certFingerprint 服务器证书指纹
 * @property myPublicKey 本地公钥
 * @property sending 是否正在发送消息（用于禁用按钮）
 * @property loadingPublicKey 是否正在加载公钥
 * @property language 当前选择的语言代码 (如 "zh", "en", "ja")
 */
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
    val loadingPublicKey: Boolean = false,
    val themeId: String = "blue",
    val useDynamicColor: Boolean = true,
    val language: String = "zh"
) {
    /**
     * 是否允许连接。
     */
    val canConnect: Boolean
        get() = status == ConnectionStatus.IDLE || status == ConnectionStatus.ERROR

    /**
     * 是否允许断开连接。
     */
    val canDisconnect: Boolean
        get() = status == ConnectionStatus.CONNECTING ||
                status == ConnectionStatus.HANDSHAKING ||
                status == ConnectionStatus.AUTHENTICATING ||
                status == ConnectionStatus.READY

    /**
     * 是否允许发送消息（就绪且草稿非空且不在发送中）。
     */
    val canSend: Boolean
        get() = status == ConnectionStatus.READY && draft.trim().isNotEmpty() && !sending

    /**
     * 连接状态的简短文本描述。
     */
    val statusText: String
        get() = when (status) {
            ConnectionStatus.IDLE -> "未连接"
            ConnectionStatus.CONNECTING,
            ConnectionStatus.HANDSHAKING,
            ConnectionStatus.AUTHENTICATING -> "连接中"
            ConnectionStatus.READY -> "已连接"
            ConnectionStatus.ERROR -> "异常断开"
        }

    /**
     * 根据当前模式（广播/私聊）和是否显示系统消息，过滤出要显示的消息列表。
     */
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

/**
 * UI 事件接口，用于向界面发送一次性通知。
 */
sealed interface UiEvent {
    data class ShowSnackbar(val message: String) : UiEvent
}
