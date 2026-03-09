package com.onlinemsg.client.ui

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.onlinemsg.client.MainActivity
import com.onlinemsg.client.R
import com.onlinemsg.client.data.crypto.RsaCryptoManager
import com.onlinemsg.client.data.local.ChatDatabase
import com.onlinemsg.client.data.local.ChatHistoryRepository
import com.onlinemsg.client.data.network.OnlineMsgSocketClient
import com.onlinemsg.client.data.preferences.ServerUrlFormatter
import com.onlinemsg.client.data.preferences.UserPreferencesRepository
import com.onlinemsg.client.data.protocol.AudioPayloadDto
import com.onlinemsg.client.data.protocol.AudioChunkPayloadDto
import com.onlinemsg.client.data.protocol.AuthPayloadDto
import com.onlinemsg.client.data.protocol.EnvelopeDto
import com.onlinemsg.client.data.protocol.HelloDataDto
import com.onlinemsg.client.data.protocol.SignedPayloadDto
import com.onlinemsg.client.data.protocol.asPayloadText
import com.onlinemsg.client.service.ChatForegroundService
import com.onlinemsg.client.util.LanguageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import java.util.UUID

/**
 * 单例管理类，负责整个聊天会话的生命周期、网络连接、消息收发、状态维护和持久化。
 * 所有公开方法均通过 ViewModel 代理调用，内部使用协程处理异步操作。
 */
object ChatSessionManager {

    private val json = Json {
        ignoreUnknownKeys = true
    }

    private fun t(key: String): String {
        return LanguageManager.getString(key, _uiState.value.language)
    }

    private fun tf(key: String, vararg args: Any): String {
        val pattern = t(key)
        return runCatching { String.format(pattern, *args) }.getOrElse { pattern }
    }

    private lateinit var app: Application
    private lateinit var preferencesRepository: UserPreferencesRepository
    private lateinit var cryptoManager: RsaCryptoManager
    private lateinit var historyRepository: ChatHistoryRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val socketClient = OnlineMsgSocketClient()
    private var initialized = false

    // 状态流，供 UI 层订阅
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState = _uiState.asStateFlow()

    // 事件流（如 Snackbar 消息）
    private val _events = MutableSharedFlow<UiEvent>()
    val events = _events.asSharedFlow()

    // 用于线程安全地访问本地身份
    private val identityMutex = Mutex()
    private var identity: RsaCryptoManager.Identity? = null

    // 连接相关内部状态
    private var manualClose = false          // 是否为手动断开
    private var fallbackTried = false        // 是否已尝试切换 ws/wss
    private var connectedUrl = ""            // 当前连接的服务器地址
    private var serverPublicKey = ""          // 服务端公钥（握手后获得）
    private var helloTimeoutJob: Job? = null  // 握手超时任务
    private var authTimeoutJob: Job? = null   // 认证超时任务
    private var reconnectJob: Job? = null     // 自动重连任务
    private var reconnectAttempt: Int = 0     // 当前重连尝试次数
    private val systemMessageExpiryJobs: MutableMap<String, Job> = mutableMapOf() // 系统消息自动过期任务
    private var autoReconnectTriggered = false
    @Volatile
    private var keepAliveRequested = false    // 是否应保活（前台服务标志）
    private var notificationIdSeed = 2000
    private val incomingAudioChunkBuffers = mutableMapOf<String, IncomingAudioChunkBuffer>()

    // WebSocket 事件监听器
    private val socketListener = object : OnlineMsgSocketClient.Listener {
        override fun onOpen() {
            scope.launch {
                _uiState.update {
                    it.copy(
                        status = ConnectionStatus.HANDSHAKING,
                        statusHint = t("session.hint.connected_preparing")
                    )
                }
                addSystemMessage(t("session.msg.connection_established"))
                startHelloTimeout()
            }
        }

        override fun onMessage(text: String) {
            scope.launch {
                runCatching {
                    handleIncomingMessage(text)
                }.onFailure { error ->
                    addSystemMessage(
                        tf(
                            "session.msg.text_frame_error",
                            error.message ?: t("common.unknown")
                        )
                    )
                }
            }
        }

        override fun onBinaryMessage(payload: ByteArray) {
            scope.launch {
                if (_uiState.value.status == ConnectionStatus.HANDSHAKING) {
                    _uiState.update { it.copy(statusHint = t("session.hint.binary_handshake_parsing")) }
                }

                val utf8 = runCatching { String(payload, StandardCharsets.UTF_8) }.getOrNull().orEmpty()
                if (utf8.isNotBlank()) {
                    runCatching {
                        handleIncomingMessage(utf8)
                    }.onFailure { error ->
                        addSystemMessage(
                            tf(
                                "session.msg.binary_frame_error",
                                error.message ?: t("common.unknown")
                            )
                        )
                    }
                } else if (_uiState.value.status == ConnectionStatus.HANDSHAKING) {
                    val hexPreview = payload.take(24).joinToString(" ") { byte ->
                        "%02x".format(byte)
                    }
                    addSystemMessage(
                        tf("session.msg.handshake_binary_unreadable", payload.size, hexPreview)
                    )
                }
            }
        }

        override fun onClosed(code: Int, reason: String) {
            scope.launch {
                handleSocketClosed(code, reason)
            }
        }

        override fun onFailure(throwable: Throwable) {
            scope.launch {
                if (manualClose) return@launch
                val message = throwable.message?.takeIf { it.isNotBlank() } ?: t("common.unknown")
                addSystemMessage(tf("session.msg.connection_error", message))
                _uiState.update {
                    it.copy(
                        status = ConnectionStatus.ERROR,
                        statusHint = t("session.hint.connection_error_retrying")
                    )
                }
                scheduleReconnect(t("session.reason.connection_error"))
            }
        }
    }

    /**
     * 初始化管理器，必须在应用启动时调用一次。
     * @param application Application 实例
     */
    @Synchronized
    fun initialize(application: Application) {
        if (initialized) return
        initialized = true
        app = application
        preferencesRepository = UserPreferencesRepository(application, json)
        cryptoManager = RsaCryptoManager(application)
        historyRepository = ChatHistoryRepository(ChatDatabase.getInstance(application).chatMessageDao())
        ensureMessageNotificationChannel()

        scope.launch {
            val pref = preferencesRepository.preferencesFlow.first()
            val historyMessages = withContext(Dispatchers.IO) {
                historyRepository.loadMessages(MAX_MESSAGES)
            }
            keepAliveRequested = pref.shouldAutoReconnect
            _uiState.update { current ->
                current.copy(
                    displayName = pref.displayName,
                    serverUrls = pref.serverUrls,
                    serverUrl = pref.currentServerUrl,
                    directMode = pref.directMode,
                    showSystemMessages = pref.showSystemMessages,
                    themeId = pref.themeId,
                    useDynamicColor = pref.useDynamicColor,
                    language = pref.language,
                    messages = historyMessages
                )
            }
            // 如果上次会话启用了自动重连，则自动恢复连接
            if (pref.shouldAutoReconnect && !autoReconnectTriggered) {
                autoReconnectTriggered = true
                ChatForegroundService.start(application)
                connectInternal(isAutoRestore = true)
            }
        }
    }

    /**
     * 更新主题
     * @param themeId 主题名
     */
    fun updateTheme(themeId: String) {
        _uiState.update { it.copy(themeId = themeId) }
        scope.launch {
            preferencesRepository.setThemeId(themeId)
        }
    }

    /**
     * 更新语言
     * @param language 语言代码
     */
    fun updateLanguage(language: String) {
        _uiState.update { it.copy(language = language) }
        scope.launch {
            preferencesRepository.setLanguage(language)
        }
    }

    /**
     * 更改使用动态颜色
     * @param enabled 主题名
     */
    fun updateUseDynamicColor(enabled: Boolean) {
        _uiState.update { it.copy(useDynamicColor = enabled) }
        scope.launch {
            preferencesRepository.setUseDynamicColor(enabled)
        }
    }

    /**
     * 更新显示名称并持久化。
     * @param value 新名称（自动截断至 64 字符）
     */
    fun updateDisplayName(value: String) {
        val displayName = value.take(64)
        _uiState.update { it.copy(displayName = displayName) }
        scope.launch {
            preferencesRepository.setDisplayName(displayName)
        }
    }

    /**
     * 更新当前输入的服务器地址（不持久化）。
     * @param value 新地址
     */
    fun updateServerUrl(value: String) {
        _uiState.update { it.copy(serverUrl = value) }
    }

    /**
     * 更新私聊目标公钥。
     * @param value 公钥字符串
     */
    fun updateTargetKey(value: String) {
        _uiState.update { it.copy(targetKey = value) }
    }

    /**
     * 更新消息草稿。
     * @param value 草稿内容
     */
    fun updateDraft(value: String) {
        _uiState.update { it.copy(draft = value) }
    }

    /**
     * 切换广播/私聊模式并持久化。
     * @param enabled true 为私聊模式
     */
    fun toggleDirectMode(enabled: Boolean) {
        _uiState.update { it.copy(directMode = enabled) }
        scope.launch {
            preferencesRepository.setDirectMode(enabled)
        }
    }

    /**
     * 切换是否显示系统消息并持久化。
     * @param show true 显示
     */
    fun toggleShowSystemMessages(show: Boolean) {
        _uiState.update { it.copy(showSystemMessages = show) }
        scope.launch {
            preferencesRepository.setShowSystemMessages(show)
        }
    }

    /**
     * 清空所有消息，并取消系统消息的过期任务。
     */
    fun clearMessages() {
        cancelSystemMessageExpiryJobs()
        _uiState.update { it.copy(messages = emptyList()) }
        scope.launch(Dispatchers.IO) {
            runCatching {
                historyRepository.clearAll()
            }
        }
    }

    /**
     * 保存当前服务器地址到历史列表并持久化。
     */
    fun saveCurrentServerUrl() {
        val normalized = ServerUrlFormatter.normalize(_uiState.value.serverUrl)
        if (normalized.isBlank()) {
            scope.launch {
                _events.emit(UiEvent.ShowSnackbar(t("session.snackbar.invalid_server")))
            }
            return
        }

        val nextUrls = ServerUrlFormatter.append(_uiState.value.serverUrls, normalized)
        _uiState.update {
            it.copy(
                serverUrl = normalized,
                serverUrls = nextUrls,
                statusHint = t("session.hint.server_saved")
            )
        }

        scope.launch {
            preferencesRepository.saveCurrentServerUrl(normalized)
            _events.emit(UiEvent.ShowSnackbar(t("session.snackbar.server_saved")))
        }
    }

    /**
     * 从历史列表中移除当前服务器地址。
     * 如果列表清空则恢复默认地址。
     */
    fun removeCurrentServerUrl() {
        val normalized = ServerUrlFormatter.normalize(_uiState.value.serverUrl)
        if (normalized.isBlank()) return

        val filtered = _uiState.value.serverUrls.filterNot { it == normalized }
        val nextUrls = if (filtered.isEmpty()) {
            listOf(ServerUrlFormatter.defaultServerUrl)
        } else {
            filtered
        }

        _uiState.update {
            it.copy(
                serverUrls = nextUrls,
                serverUrl = nextUrls.first(),
                statusHint = if (filtered.isEmpty()) {
                    t("session.hint.server_restored_default")
                } else {
                    t("session.hint.server_removed")
                }
            )
        }

        scope.launch {
            preferencesRepository.removeCurrentServerUrl(normalized)
            _events.emit(UiEvent.ShowSnackbar(t("session.snackbar.server_list_updated")))
        }
    }

    /**
     * 加载或生成本地身份密钥对，并将公钥显示到 UI。
     */
    fun revealMyPublicKey() {
        scope.launch {
            _uiState.update { it.copy(loadingPublicKey = true) }
            runCatching {
                ensureIdentity()
            }.onSuccess { id ->
                _uiState.update {
                    it.copy(
                        myPublicKey = id.publicKeyBase64,
                        loadingPublicKey = false
                    )
                }
            }.onFailure { error ->
                _uiState.update { it.copy(loadingPublicKey = false) }
                _events.emit(
                    UiEvent.ShowSnackbar(
                        tf(
                            "session.snackbar.public_key_read_failed",
                            error.message ?: t("common.unknown")
                        )
                    )
                )
            }
        }
    }

    /**
     * 主动连接服务器（由用户点击连接触发）。
     */
    fun connect() {
        connectInternal(isAutoRestore = false)
    }

    /**
     * 内部连接逻辑，区分自动恢复和手动连接。
     * @param isAutoRestore 是否为应用启动时的自动恢复连接
     */
    private fun connectInternal(isAutoRestore: Boolean) {
        if (!initialized) return
        val state = _uiState.value
        if (!state.canConnect) return

        val normalized = ServerUrlFormatter.normalize(state.serverUrl)
        if (normalized.isBlank()) {
            _uiState.update {
                it.copy(
                    status = ConnectionStatus.ERROR,
                    statusHint = t("session.hint.fill_valid_server")
                )
            }
            return
        }

        manualClose = false
        keepAliveRequested = true
        fallbackTried = false
        connectedUrl = normalized
        serverPublicKey = ""
        cancelReconnect()
        reconnectAttempt = 0
        cancelHelloTimeout()
        cancelAuthTimeout()

        _uiState.update {
            it.copy(
                status = ConnectionStatus.CONNECTING,
                statusHint = t("session.hint.connecting_server"),
                serverUrl = normalized,
                certFingerprint = ""
            )
        }

        scope.launch {
            preferencesRepository.setCurrentServerUrl(normalized)
            preferencesRepository.setShouldAutoReconnect(true)
        }

        ChatForegroundService.start(app)
        socketClient.connect(normalized, socketListener)

        if (isAutoRestore) {
            addSystemMessage(t("session.msg.auto_restore_connecting"))
        }
    }

    /**
     * 主动断开连接。
     * @param stopService 是否同时停止前台服务（默认 true）
     */
    fun disconnect(stopService: Boolean = true) {
        manualClose = true
        cancelReconnect()
        cancelHelloTimeout()
        cancelAuthTimeout()
        socketClient.close(1000, "manual_close")
        _uiState.update {
            it.copy(
                status = ConnectionStatus.IDLE,
                statusHint = t("session.hint.connection_closed")
            )
        }
        autoReconnectTriggered = false
        keepAliveRequested = false
        scope.launch {
            preferencesRepository.setShouldAutoReconnect(false)
        }
        if (stopService) {
            ChatForegroundService.stop(app)
        }
        addSystemMessage(t("session.msg.disconnected"))
    }

    /**
     * 发送消息（广播或私聊）。
     * 执行签名、加密并发送。
     */
    fun sendMessage() {
        val current = _uiState.value
        if (!current.canSend) return
        val text = current.draft.trim()
        if (text.isBlank()) return
        val route = resolveOutgoingRoute(current) ?: return

        scope.launch {
            _uiState.update { it.copy(sending = true) }

            runCatching {
                sendSignedPayload(route = route, payloadText = text)
            }.onSuccess {
                addOutgoingMessage(text, route.subtitle, route.channel)
                _uiState.update { it.copy(draft = "", sending = false) }
            }.onFailure { error ->
                _uiState.update { it.copy(sending = false) }
                addSystemMessage(
                    tf(
                        "session.msg.send_failed",
                        error.message ?: t("common.unknown")
                    )
                )
            }
        }
    }

    /**
     * 发送语音消息（Base64 音频负载）。
     */
    fun sendAudioMessage(audioBase64: String, durationMillis: Long) {
        val current = _uiState.value
        if (current.status != ConnectionStatus.READY || current.sending) return
        if (audioBase64.isBlank()) return
        val route = resolveOutgoingRoute(current) ?: return

        scope.launch {
            _uiState.update { it.copy(sending = true) }
            val safeDuration = durationMillis.coerceAtLeast(0L)
            val normalized = audioBase64.trim()
            val chunks = splitAudioBase64(normalized, AUDIO_CHUNK_BASE64_SIZE)
            if (chunks.size > MAX_AUDIO_CHUNK_COUNT) {
                _uiState.update {
                    it.copy(
                        sending = false,
                        statusHint = t("session.hint.audio_chunk_over_limit")
                    )
                }
                addSystemMessage(t("session.msg.audio_chunk_canceled"))
                return@launch
            }

            runCatching {
                if (chunks.size == 1) {
                    val taggedPayload = AUDIO_MESSAGE_PREFIX + json.encodeToString(
                        AudioPayloadDto(
                            durationMillis = safeDuration,
                            data = normalized
                        )
                    )
                    sendSignedPayload(route = route, payloadText = taggedPayload)
                } else {
                    val messageId = UUID.randomUUID().toString()
                    chunks.forEachIndexed { index, chunk ->
                        val taggedPayload = AUDIO_CHUNK_MESSAGE_PREFIX + json.encodeToString(
                            AudioChunkPayloadDto(
                                messageId = messageId,
                                index = index,
                                total = chunks.size,
                                durationMillis = safeDuration,
                                data = chunk
                            )
                        )
                        sendSignedPayload(route = route, payloadText = taggedPayload)
                    }
                }
            }.onSuccess {
                addOutgoingAudioMessage(
                    subtitle = route.subtitle,
                    channel = route.channel,
                    audioBase64 = normalized,
                    durationMillis = safeDuration
                )
                _uiState.update { it.copy(sending = false) }
            }.onFailure { error ->
                val message = error.message ?: t("common.unknown")
                _uiState.update {
                    it.copy(
                        sending = false,
                        statusHint = tf("session.hint.audio_send_failed", message)
                    )
                }
                addSystemMessage(tf("session.msg.audio_send_failed", message))
            }
        }
    }

    /**
     * 消息复制成功后的回调，显示“已复制”提示。
     */
    fun onMessageCopied() {
        scope.launch {
            _events.emit(UiEvent.ShowSnackbar(t("common.copied")))
        }
    }

    /**
     * 确保本地身份已加载 or 创建。
     * @return 本地身份对象
     */
    private suspend fun ensureIdentity(): RsaCryptoManager.Identity {
        return identityMutex.withLock {
            identity ?: withContext(Dispatchers.Default) {
                cryptoManager.getOrCreateIdentity()
            }.also { created ->
                identity = created
            }
        }
    }

    /**
     * 处理收到的原始文本消息（可能是握手包 or 加密消息）。
     * @param rawText 原始文本
     */
    private suspend fun handleIncomingMessage(rawText: String) {
        if (_uiState.value.status == ConnectionStatus.HANDSHAKING) {
            _uiState.update { it.copy(statusHint = t("session.hint.handshake_data_received")) }
        }

        val normalizedText = extractJsonCandidate(rawText)
        val rootObject = runCatching {
            json.decodeFromString<JsonElement>(normalizedText) as? JsonObject
        }.getOrNull()

        // 尝试直接解析为 HelloDataDto（某些服务器可能直接发送，不带外层）
        val directHello = rootObject?.let { obj ->
            val hasPublicKey = obj["publicKey"] != null
            val hasChallenge = obj["authChallenge"] != null
            if (hasPublicKey && hasChallenge) {
                runCatching { json.decodeFromJsonElement<HelloDataDto>(obj) }.getOrNull()
            } else {
                null
            }
        }
        if (directHello != null) {
            cancelHelloTimeout()
            handleServerHello(directHello)
            return
        }

        // 尝试解析为带外层的 EnvelopeDto
        val plain = runCatching { json.decodeFromString<EnvelopeDto>(normalizedText) }.getOrNull()
        if (plain?.type == "publickey") {
            cancelHelloTimeout()
            val hello = plain.data?.let {
                runCatching { json.decodeFromJsonElement<HelloDataDto>(it) }.getOrNull()
            }
            if (hello == null || hello.publicKey.isBlank() || hello.authChallenge.isBlank()) {
                _uiState.update {
                    it.copy(
                        status = ConnectionStatus.ERROR,
                        statusHint = t("session.hint.handshake_incomplete_response")
                    )
                }
                return
            }
            handleServerHello(hello)
            return
        }

        // 握手阶段收到非预期消息则报错
        if (_uiState.value.status == ConnectionStatus.HANDSHAKING && plain != null) {
            _uiState.update { it.copy(statusHint = t("session.hint.handshake_unexpected_message")) }
            addSystemMessage(tf("session.msg.handshake_unexpected_type", plain.type))
        } else if (_uiState.value.status == ConnectionStatus.HANDSHAKING && plain == null) {
            val preview = rawText
                .replace("\n", " ")
                .replace("\r", " ")
                .take(80)
            _uiState.update { it.copy(statusHint = t("session.hint.handshake_first_packet_parse_failed")) }
            addSystemMessage(tf("session.msg.handshake_parse_failed", preview))
        }

        // 尝试解密（若已握手完成，收到的应是加密消息）
        val id = ensureIdentity()
        val decrypted = runCatching {
            withContext(Dispatchers.Default) {
                cryptoManager.decryptChunked(id.privateKey, normalizedText)
            }
        }.getOrElse {
            addSystemMessage(t("session.msg.decryption_failed"))
            return
        }

        val secure = runCatching {
            json.decodeFromString<EnvelopeDto>(decrypted)
        }.getOrNull() ?: return

        handleSecureMessage(secure)
    }

    /**
     * 处理服务端发来的握手 Hello 数据。
     * @param hello 服务端公钥和挑战
     */
    private suspend fun handleServerHello(hello: HelloDataDto) {
        cancelHelloTimeout()
        serverPublicKey = hello.publicKey
        _uiState.update {
            it.copy(
                status = ConnectionStatus.AUTHENTICATING,
                statusHint = t("session.hint.authenticating"),
                certFingerprint = hello.certFingerprintSha256.orEmpty()
            )
        }

        cancelAuthTimeout()
        authTimeoutJob = scope.launch {
            delay(AUTH_TIMEOUT_MS)
            if (_uiState.value.status == ConnectionStatus.AUTHENTICATING) {
                _uiState.update {
                    it.copy(
                        status = ConnectionStatus.ERROR,
                        statusHint = t("session.hint.connection_timeout_retry")
                    )
                }
                addSystemMessage(t("session.msg.auth_timeout"))
                socketClient.close(1000, "auth_timeout")
            }
        }

        runCatching {
            sendAuth(hello.authChallenge)
        }.onSuccess {
            addSystemMessage(t("session.msg.auth_request_sent"))
        }.onFailure { error ->
            cancelAuthTimeout()
            _uiState.update {
                it.copy(
                    status = ConnectionStatus.ERROR,
                    statusHint = t("session.hint.auth_failed")
                )
            }
            addSystemMessage(
                tf(
                    "session.msg.auth_send_failed",
                    error.message ?: t("common.unknown")
                )
            )
            socketClient.close(1000, "auth_failed")
        }
    }

    /**
     * 发送认证消息（包含签名后的身份信息）。
     * @param challenge 服务端提供的挑战值
     */
    private suspend fun sendAuth(challenge: String) {
        val id = ensureIdentity()
        val displayName = _uiState.value.displayName.trim().ifBlank { createGuestName() }
        if (displayName != _uiState.value.displayName) {
            _uiState.update { it.copy(displayName = displayName) }
            preferencesRepository.setDisplayName(displayName)
        }

        val timestamp = cryptoManager.unixSecondsNow()
        val nonce = cryptoManager.createNonce()
        val signingInput = listOf(
            "publickey",
            displayName,
            id.publicKeyBase64,
            challenge,
            timestamp.toString(),
            nonce
        ).joinToString("\n")

        val signature = withContext(Dispatchers.Default) {
            cryptoManager.signText(id.privateKey, signingInput)
        }

        val payload = AuthPayloadDto(
            publicKey = id.publicKeyBase64,
            challenge = challenge,
            timestamp = timestamp,
            nonce = nonce,
            signature = signature
        )
        val envelope = EnvelopeDto(
            type = "publickey",
            key = displayName,
            data = json.encodeToJsonElement(payload)
        )

        val plain = json.encodeToString(envelope)
        val cipher = withContext(Dispatchers.Default) {
            cryptoManager.encryptChunked(serverPublicKey, plain)
        }
        val sizeBytes = cipher.toByteArray(StandardCharsets.UTF_8).size
        require(sizeBytes <= MAX_OUTBOUND_MESSAGE_BYTES) {
            tf("session.error.message_too_large", sizeBytes)
        }
        check(socketClient.send(cipher)) { t("session.error.connection_unavailable") }
    }

    /**
     * 处理安全通道建立后的业务消息（广播、私聊、认证结果等）。
     * @param message 解密后的 EnvelopeDto
     */
    private fun handleSecureMessage(message: EnvelopeDto) {
        when (message.type) {
            "auth_ok" -> {
                cancelAuthTimeout()
                cancelReconnect()
                reconnectAttempt = 0
                _uiState.update {
                    it.copy(
                        status = ConnectionStatus.READY,
                        statusHint = t("session.hint.ready_to_chat")
                    )
                }
                addSystemMessage(t("session.msg.ready"))
            }

            "broadcast" -> {
                val sender = message.key?.takeIf { it.isNotBlank() } ?: t("session.sender.anonymous")
                val payloadText = message.data.asPayloadText()
                val audioChunk = parseAudioChunkPayload(payloadText)
                if (audioChunk != null) {
                    ingestIncomingAudioChunk(
                        sender = sender,
                        subtitle = "",
                        channel = MessageChannel.BROADCAST,
                        chunk = audioChunk
                    )
                    return
                }
                val audio = parseAudioPayload(payloadText)
                if (audio != null) {
                    addIncomingAudioMessage(
                        sender = sender,
                        subtitle = "",
                        audioBase64 = audio.data,
                        durationMillis = audio.durationMillis,
                        channel = MessageChannel.BROADCAST
                    )
                } else {
                    addIncomingMessage(
                        sender = sender,
                        subtitle = "",
                        content = payloadText,
                        channel = MessageChannel.BROADCAST
                    )
                }
            }

            "forward" -> {
                val sourceKey = message.key.orEmpty()
                val payloadText = message.data.asPayloadText()
                val subtitle = sourceKey.takeIf { it.isNotBlank() }
                    ?.let { tf("session.subtitle.from_key", summarizeKey(it)) }
                    .orEmpty()
                val audioChunk = parseAudioChunkPayload(payloadText)
                if (audioChunk != null) {
                    ingestIncomingAudioChunk(
                        sender = t("session.sender.private_message"),
                        subtitle = subtitle,
                        channel = MessageChannel.PRIVATE,
                        chunk = audioChunk
                    )
                    return
                }
                val audio = parseAudioPayload(payloadText)
                if (audio != null) {
                    addIncomingAudioMessage(
                        sender = t("session.sender.private_message"),
                        subtitle = subtitle,
                        audioBase64 = audio.data,
                        durationMillis = audio.durationMillis,
                        channel = MessageChannel.PRIVATE
                    )
                } else {
                    addIncomingMessage(
                        sender = t("session.sender.private_message"),
                        subtitle = subtitle,
                        content = payloadText,
                        channel = MessageChannel.PRIVATE
                    )
                }
            }

            else -> addSystemMessage(tf("session.msg.unknown_message_type", message.type))
        }
    }

    /**
     * 处理 WebSocket 连接关闭事件。
     * @param code 关闭状态码
     * @param reason 关闭原因
     */
    private fun handleSocketClosed(code: Int, reason: String) {
        cancelHelloTimeout()
        cancelAuthTimeout()

        if (manualClose) {
            return
        }
        if (reason == "reconnect") {
            return
        }
        if (reconnectJob?.isActive == true) {
            return
        }

        val reasonLower = reason.lowercase()
        val isPolicyBlocked = code == 1008 ||
                reasonLower.contains("ip blocked") ||
                reasonLower.contains("message too large") ||
                reasonLower.contains("rate limited")
        if (isPolicyBlocked) {
            keepAliveRequested = false
            _uiState.update {
                it.copy(
                    status = ConnectionStatus.ERROR,
                    statusHint = tf(
                        "session.hint.server_rejected",
                        reason.ifBlank { t("session.text.policy_restriction") }
                    )
                )
            }
            addSystemMessage(
                tf(
                    "session.msg.server_rejected",
                    code,
                    reason.ifBlank { t("session.text.policy_restriction") }
                )
            )
            scope.launch {
                preferencesRepository.setShouldAutoReconnect(false)
            }
            ChatForegroundService.stop(app)
            return
        }

        val currentStatus = _uiState.value.status

        val allowFallback = !fallbackTried && currentStatus != ConnectionStatus.READY

        // 尝试切换 ws/wss 协议重试（仅限非就绪状态）
        if (allowFallback) {
            val fallbackUrl = ServerUrlFormatter.toggleWsProtocol(connectedUrl)
            if (fallbackUrl.isNotBlank()) {
                fallbackTried = true
                connectedUrl = fallbackUrl
                _uiState.update {
                    it.copy(
                        status = ConnectionStatus.CONNECTING,
                        statusHint = t("session.hint.auto_retry_connecting"),
                        serverUrl = fallbackUrl
                    )
                }
                addSystemMessage(t("session.msg.switching_connection_mode_retry"))
                socketClient.connect(fallbackUrl, socketListener)
                return
            }
        }

        _uiState.update {
            it.copy(
                status = ConnectionStatus.ERROR,
                statusHint = t("session.hint.connection_interrupted_retry")
            )
        }
        addSystemMessage(
            tf(
                "session.msg.connection_closed_with_code",
                code,
                reason.ifBlank { t("session.text.connection_interrupted") }
            )
        )
        scheduleReconnect(t("session.reason.connection_interrupted"))
    }

    /**
     * 添加一条系统消息（自动按 TTL 过期）。
     * @param content 消息内容
     */
    private fun addSystemMessage(content: String) {
        val message = UiMessage(
            role = MessageRole.SYSTEM,
            sender = t("session.sender.system"),
            subtitle = "",
            content = content,
            channel = MessageChannel.BROADCAST
        )
        appendMessage(message)
        scheduleSystemMessageExpiry(message.id)
    }

    /**
     * 添加一条接收到的用户消息。
     * @param sender 发送者名称
     * @param subtitle 附加说明（如私聊来源）
     * @param content 消息内容
     * @param channel 消息通道（广播/私聊）
     */
    private fun addIncomingMessage(
        sender: String,
        subtitle: String,
        content: String,
        channel: MessageChannel
    ) {
        showIncomingNotification(
            title = sender,
            body = content.ifBlank { t("session.notification.new_message") }
        )
        appendMessage(
            UiMessage(
                role = MessageRole.INCOMING,
                sender = sender,
                subtitle = subtitle,
                content = content,
                channel = channel
            )
        )
    }

    private fun addIncomingAudioMessage(
        sender: String,
        subtitle: String,
        audioBase64: String,
        durationMillis: Long,
        channel: MessageChannel
    ) {
        showIncomingNotification(
            title = sender,
            body = t("session.notification.new_voice_message")
        )
        appendMessage(
            UiMessage(
                role = MessageRole.INCOMING,
                sender = sender,
                subtitle = subtitle,
                content = t("session.message.voice"),
                channel = channel,
                contentType = MessageContentType.AUDIO,
                audioBase64 = audioBase64,
                audioDurationMillis = durationMillis.coerceAtLeast(0L)
            )
        )
    }

    /**
     * 添加一条发出的消息。
     * @param content 消息内容
     * @param subtitle 附加说明（如私聊目标）
     * @param channel 消息通道
     */
    private fun addOutgoingMessage(
        content: String,
        subtitle: String,
        channel: MessageChannel
    ) {
        appendMessage(
            UiMessage(
                role = MessageRole.OUTGOING,
                sender = t("session.sender.me"),
                subtitle = subtitle,
                content = content,
                channel = channel
            )
        )
    }

    private fun addOutgoingAudioMessage(
        subtitle: String,
        channel: MessageChannel,
        audioBase64: String,
        durationMillis: Long
    ) {
        appendMessage(
            UiMessage(
                role = MessageRole.OUTGOING,
                sender = t("session.sender.me"),
                subtitle = subtitle,
                content = t("session.message.voice"),
                channel = channel,
                contentType = MessageContentType.AUDIO,
                audioBase64 = audioBase64,
                audioDurationMillis = durationMillis.coerceAtLeast(0L)
            )
        )
    }

    private fun resolveOutgoingRoute(state: ChatUiState): OutgoingRoute? {
        val key = if (state.directMode) state.targetKey.trim() else ""
        if (state.directMode && key.isBlank()) {
            _uiState.update { it.copy(statusHint = t("session.hint.fill_target_key_before_private")) }
            return null
        }
        val type = if (key.isBlank()) "broadcast" else "forward"
        val channel = if (key.isBlank()) MessageChannel.BROADCAST else MessageChannel.PRIVATE
        val subtitle = if (key.isBlank()) "" else tf("session.subtitle.private_to_key", summarizeKey(key))
        return OutgoingRoute(type = type, key = key, channel = channel, subtitle = subtitle)
    }

    private suspend fun sendSignedPayload(route: OutgoingRoute, payloadText: String) {
        val id = ensureIdentity()
        val timestamp = cryptoManager.unixSecondsNow()
        val nonce = cryptoManager.createNonce()
        val signingInput = listOf(
            route.type,
            route.key,
            payloadText,
            timestamp.toString(),
            nonce
        ).joinToString("\n")
        val signature = withContext(Dispatchers.Default) {
            cryptoManager.signText(id.privateKey, signingInput)
        }

        val payload = SignedPayloadDto(
            payload = payloadText,
            timestamp = timestamp,
            nonce = nonce,
            signature = signature
        )
        val envelope = EnvelopeDto(
            type = route.type,
            key = route.key,
            data = json.encodeToJsonElement(payload)
        )

        val plain = json.encodeToString(envelope)
        val cipher = withContext(Dispatchers.Default) {
            cryptoManager.encryptChunked(serverPublicKey, plain)
        }
        check(socketClient.send(cipher)) { t("session.error.connection_unavailable") }
    }

    private fun parseAudioPayload(payloadText: String): AudioPayloadDto? {
        if (!payloadText.startsWith(AUDIO_MESSAGE_PREFIX)) return null
        val encoded = payloadText.removePrefix(AUDIO_MESSAGE_PREFIX).trim()
        if (encoded.isBlank()) return null
        return runCatching {
            json.decodeFromString<AudioPayloadDto>(encoded)
        }.getOrNull()?.takeIf { dto ->
            dto.encoding.equals("base64", ignoreCase = true) && dto.data.isNotBlank()
        }
    }

    private fun parseAudioChunkPayload(payloadText: String): AudioChunkPayloadDto? {
        if (!payloadText.startsWith(AUDIO_CHUNK_MESSAGE_PREFIX)) return null
        val encoded = payloadText.removePrefix(AUDIO_CHUNK_MESSAGE_PREFIX).trim()
        if (encoded.isBlank()) return null
        return runCatching {
            json.decodeFromString<AudioChunkPayloadDto>(encoded)
        }.getOrNull()?.takeIf { dto ->
            dto.encoding.equals("base64", ignoreCase = true) &&
                    dto.messageId.isNotBlank() &&
                    dto.total in 1..MAX_AUDIO_CHUNK_COUNT &&
                    dto.index in 0 until dto.total &&
                    dto.data.isNotBlank()
        }
    }

    private fun ingestIncomingAudioChunk(
        sender: String,
        subtitle: String,
        channel: MessageChannel,
        chunk: AudioChunkPayloadDto
    ) {
        val now = System.currentTimeMillis()
        purgeExpiredAudioChunkBuffers(now)
        val bufferKey = "${channel.name}:${sender}:${chunk.messageId}"
        val buffer = incomingAudioChunkBuffers[bufferKey]
        val active = if (buffer == null || buffer.total != chunk.total) {
            IncomingAudioChunkBuffer(
                sender = sender,
                subtitle = subtitle,
                channel = channel,
                total = chunk.total,
                durationMillis = chunk.durationMillis.coerceAtLeast(0L),
                createdAtMillis = now,
                chunks = MutableList(chunk.total) { null }
            ).also { created ->
                incomingAudioChunkBuffers[bufferKey] = created
            }
        } else {
            if (buffer.sender != sender || buffer.channel != channel) {
                return
            }
            buffer
        }

        active.chunks[chunk.index] = chunk.data
        val completed = active.chunks.all { !it.isNullOrBlank() }
        if (!completed) return

        incomingAudioChunkBuffers.remove(bufferKey)
        val merged = buildString {
            active.chunks.forEach { part ->
                append(part.orEmpty())
            }
        }
        if (merged.isBlank()) return

        addIncomingAudioMessage(
            sender = active.sender,
            subtitle = active.subtitle,
            audioBase64 = merged,
            durationMillis = active.durationMillis,
            channel = active.channel
        )
    }

    private fun purgeExpiredAudioChunkBuffers(nowMillis: Long) {
        if (incomingAudioChunkBuffers.isEmpty()) return
        val expiredKeys = incomingAudioChunkBuffers
            .filterValues { nowMillis - it.createdAtMillis >= AUDIO_CHUNK_BUFFER_TTL_MS }
            .keys
        expiredKeys.forEach { key ->
            incomingAudioChunkBuffers.remove(key)
        }
    }

    private fun splitAudioBase64(base64: String, chunkSize: Int): List<String> {
        if (base64.isEmpty() || chunkSize <= 0) return emptyList()
        if (base64.length <= chunkSize) return listOf(base64)
        val chunks = ArrayList<String>((base64.length + chunkSize - 1) / chunkSize)
        var start = 0
        while (start < base64.length) {
            val end = minOf(start + chunkSize, base64.length)
            chunks.add(base64.substring(start, end))
            start = end
        }
        return chunks
    }

    /**
     * 将消息追加到列表尾部，并清理超出数量限制的消息。
     * @param message 要追加的消息
     */
    private fun appendMessage(message: UiMessage) {
        _uiState.update { current ->
            val next = (current.messages + message).takeLast(MAX_MESSAGES)
            val aliveIds = next.asSequence().map { it.id }.toSet()
            val removedIds = systemMessageExpiryJobs.keys.filterNot { it in aliveIds }
            removedIds.forEach { id ->
                systemMessageExpiryJobs.remove(id)?.cancel()
            }
            current.copy(messages = next)
        }
        if (message.role == MessageRole.SYSTEM) return
        scope.launch(Dispatchers.IO) {
            runCatching {
                historyRepository.appendMessage(message, MAX_MESSAGES)
            }
        }
    }

    /**
     * 取消认证超时任务。
     */
    private fun cancelAuthTimeout() {
        authTimeoutJob?.cancel()
        authTimeoutJob = null
    }

    /**
     * 安排自动重连（指数退避）。
     * @param reason 触发重连的原因
     */
    private fun scheduleReconnect(reason: String) {
        if (manualClose) return
        if (reconnectJob?.isActive == true) return

        reconnectAttempt += 1
        val exponential = 1 shl minOf(reconnectAttempt - 1, 5)
        val delaySeconds = minOf(MAX_RECONNECT_DELAY_SECONDS, exponential)
        addSystemMessage(tf("session.msg.auto_reconnect_in", reason, delaySeconds, reconnectAttempt))
        _uiState.update {
            it.copy(
                status = ConnectionStatus.ERROR,
                statusHint = tf("session.hint.auto_reconnect_in", delaySeconds, reconnectAttempt)
            )
        }

        reconnectJob = scope.launch {
            delay(delaySeconds * 1000L)
            if (manualClose) return@launch

            val target = ServerUrlFormatter.normalize(connectedUrl).ifBlank {
                ServerUrlFormatter.normalize(_uiState.value.serverUrl)
            }
            if (target.isBlank()) {
                _uiState.update {
                    it.copy(
                        status = ConnectionStatus.ERROR,
                        statusHint = t("session.hint.reconnect_invalid_server")
                    )
                }
                return@launch
            }

            fallbackTried = false
            serverPublicKey = ""
            connectedUrl = target
            cancelHelloTimeout()
            cancelAuthTimeout()
            _uiState.update {
                it.copy(
                    status = ConnectionStatus.CONNECTING,
                    statusHint = t("session.hint.auto_reconnecting")
                )
            }
            socketClient.connect(target, socketListener)
        }
    }

    /**
     * 取消自动重连任务。
     */
    private fun cancelReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
    }

    /**
     * 为系统消息安排过期自动删除。
     * @param messageId 消息唯一 ID
     */
    private fun scheduleSystemMessageExpiry(messageId: String) {
        systemMessageExpiryJobs.remove(messageId)?.cancel()
        systemMessageExpiryJobs[messageId] = scope.launch {
            delay(SYSTEM_MESSAGE_TTL_MS)
            _uiState.update { current ->
                val filtered = current.messages.filterNot { it.id == messageId }
                current.copy(messages = filtered)
            }
            systemMessageExpiryJobs.remove(messageId)
        }
    }

    /**
     * 取消所有系统消息的过期任务。
     */
    private fun cancelSystemMessageExpiryJobs() {
        systemMessageExpiryJobs.values.forEach { it.cancel() }
        systemMessageExpiryJobs.clear()
    }

    /**
     * 启动握手超时计时器。
     */
    private fun startHelloTimeout() {
        cancelHelloTimeout()
        helloTimeoutJob = scope.launch {
            delay(HELLO_TIMEOUT_MS)
            if (_uiState.value.status == ConnectionStatus.HANDSHAKING) {
                val currentUrl = connectedUrl.ifBlank { "unknown" }
                _uiState.update {
                    it.copy(
                        status = ConnectionStatus.ERROR,
                        statusHint = t("session.hint.handshake_timeout")
                    )
                }
                addSystemMessage(tf("session.msg.handshake_timeout_with_url", currentUrl))
                socketClient.close(1000, "hello_timeout")
            }
        }
    }

    /**
     * 取消握手超时任务。
     */
    private fun cancelHelloTimeout() {
        helloTimeoutJob?.cancel()
        helloTimeoutJob = null
    }

    /**
     * 缩写显示公钥（取前后各8字符）。
     * @param key 完整公钥
     * @return 缩写字符串
     */
    private fun summarizeKey(key: String): String {
        if (key.length <= 16) return key
        return "${key.take(8)}...${key.takeLast(8)}"
    }

    /**
     * 生成访客名称（如 guest-123456）。
     * @return 随机名称
     */
    private fun createGuestName(): String {
        val rand = (100000..999999).random()
        return "guest-$rand"
    }

    /**
     * 从可能包含前缀的原始文本中提取 JSON 对象部分。
     * @param rawText 原始文本
     * @return 最外层的 JSON 字符串
     */
    private fun extractJsonCandidate(rawText: String): String {
        val trimmed = rawText.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed
        }

        val start = rawText.indexOf('{')
        val end = rawText.lastIndexOf('}')
        return if (start in 0 until end) {
            rawText.substring(start, end + 1)
        } else {
            rawText
        }
    }

    /**
     * 关闭所有资源（用于应用退出时）。
     */
    fun shutdownAll() {
        cancelSystemMessageExpiryJobs()
        cancelReconnect()
        cancelHelloTimeout()
        cancelAuthTimeout()
        socketClient.shutdown()
    }

    /**
     * 前台服务停止时的回调。
     */
    fun onForegroundServiceStopped() {
        keepAliveRequested = false
        if (_uiState.value.status != ConnectionStatus.IDLE) {
            disconnect(stopService = false)
        } else {
            scope.launch {
                preferencesRepository.setShouldAutoReconnect(false)
            }
        }
    }

    /**
     * 判断前台服务是否应该运行。
     * @return true 表示应保持服务运行
     */
    fun shouldForegroundServiceRun(): Boolean = keepAliveRequested

    /**
     * 创建消息通知渠道（Android O+）。
     */
    private fun ensureMessageNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            MESSAGE_CHANNEL_ID,
            t("session.notification.channel_name"),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = t("session.notification.channel_desc")
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * 显示新消息到达的通知。
     * @param title 通知标题
     * @param body 通知正文
     */
    private fun showIncomingNotification(title: String, body: String) {
        if (!initialized) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val launchIntent = Intent(app, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            app,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(app, MESSAGE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title.ifBlank { "OnlineMsg" })
            .setContentText(body.take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationManagerCompat.from(app).notify(nextMessageNotificationId(), notification)
    }

    /**
     * 生成下一个通知 ID（线程安全递增）。
     * @return 新的通知 ID
     */
    @Synchronized
    private fun nextMessageNotificationId(): Int {
        notificationIdSeed += 1
        return notificationIdSeed
    }

    private data class OutgoingRoute(
        val type: String,
        val key: String,
        val channel: MessageChannel,
        val subtitle: String
    )

    private data class IncomingAudioChunkBuffer(
        val sender: String,
        val subtitle: String,
        val channel: MessageChannel,
        val total: Int,
        val durationMillis: Long,
        val createdAtMillis: Long,
        val chunks: MutableList<String?>
    )

    // 常量定义
    private const val HELLO_TIMEOUT_MS = 12_000L
    private const val AUTH_TIMEOUT_MS = 20_000L
    private const val MAX_MESSAGES = 500
    private const val MAX_RECONNECT_DELAY_SECONDS = 30
    private const val SYSTEM_MESSAGE_TTL_MS = 1_000L
    private const val MESSAGE_CHANNEL_ID = "onlinemsg_messages"
    private const val AUDIO_MESSAGE_PREFIX = "[[OMS_AUDIO_V1]]"
    private const val AUDIO_CHUNK_MESSAGE_PREFIX = "[[OMS_AUDIO_CHUNK_V1]]"
    private const val AUDIO_CHUNK_BASE64_SIZE = 20_000
    private const val MAX_AUDIO_CHUNK_COUNT = 30
    private const val AUDIO_CHUNK_BUFFER_TTL_MS = 180_000L
    private const val MAX_OUTBOUND_MESSAGE_BYTES = 60 * 1024
}
