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
import com.onlinemsg.client.data.network.OnlineMsgSocketClient
import com.onlinemsg.client.data.preferences.ServerUrlFormatter
import com.onlinemsg.client.data.preferences.UserPreferencesRepository
import com.onlinemsg.client.data.protocol.AuthPayloadDto
import com.onlinemsg.client.data.protocol.EnvelopeDto
import com.onlinemsg.client.data.protocol.HelloDataDto
import com.onlinemsg.client.data.protocol.SignedPayloadDto
import com.onlinemsg.client.data.protocol.asPayloadText
import com.onlinemsg.client.service.ChatForegroundService
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

object ChatSessionManager {

    private val json = Json {
        ignoreUnknownKeys = true
    }

    private lateinit var app: Application
    private lateinit var preferencesRepository: UserPreferencesRepository
    private lateinit var cryptoManager: RsaCryptoManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val socketClient = OnlineMsgSocketClient()
    private var initialized = false

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>()
    val events = _events.asSharedFlow()

    private val identityMutex = Mutex()
    private var identity: RsaCryptoManager.Identity? = null

    private var manualClose = false
    private var fallbackTried = false
    private var connectedUrl = ""
    private var serverPublicKey = ""
    private var helloTimeoutJob: Job? = null
    private var authTimeoutJob: Job? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempt: Int = 0
    private val systemMessageExpiryJobs: MutableMap<String, Job> = mutableMapOf()
    private var autoReconnectTriggered = false
    @Volatile
    private var keepAliveRequested = false
    private var notificationIdSeed = 2000

    private val socketListener = object : OnlineMsgSocketClient.Listener {
        override fun onOpen() {
            scope.launch {
                _uiState.update {
                    it.copy(
                        status = ConnectionStatus.HANDSHAKING,
                        statusHint = "已连接，正在准备聊天..."
                    )
                }
                addSystemMessage("连接已建立")
                startHelloTimeout()
            }
        }

        override fun onMessage(text: String) {
            scope.launch {
                runCatching {
                    handleIncomingMessage(text)
                }.onFailure { error ->
                    addSystemMessage("文本帧处理异常：${error.message ?: "unknown"}")
                }
            }
        }

        override fun onBinaryMessage(payload: ByteArray) {
            scope.launch {
                if (_uiState.value.status == ConnectionStatus.HANDSHAKING) {
                    _uiState.update { it.copy(statusHint = "收到二进制握手帧，正在尝试解析...") }
                }

                val utf8 = runCatching { String(payload, StandardCharsets.UTF_8) }.getOrNull().orEmpty()
                if (utf8.isNotBlank()) {
                    runCatching {
                        handleIncomingMessage(utf8)
                    }.onFailure { error ->
                        addSystemMessage("二进制帧处理异常：${error.message ?: "unknown"}")
                    }
                } else if (_uiState.value.status == ConnectionStatus.HANDSHAKING) {
                    val hexPreview = payload.take(24).joinToString(" ") { byte ->
                        "%02x".format(byte)
                    }
                    addSystemMessage("握手二进制帧无法转为文本，len=${payload.size} hex=$hexPreview")
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
                val message = throwable.message?.takeIf { it.isNotBlank() } ?: "unknown"
                addSystemMessage("连接异常：$message")
                _uiState.update {
                    it.copy(
                        status = ConnectionStatus.ERROR,
                        statusHint = "连接异常，正在重试"
                    )
                }
                scheduleReconnect("连接异常")
            }
        }
    }

    @Synchronized
    fun initialize(application: Application) {
        if (initialized) return
        initialized = true
        app = application
        preferencesRepository = UserPreferencesRepository(application, json)
        cryptoManager = RsaCryptoManager(application)
        ensureMessageNotificationChannel()

        scope.launch {
            val pref = preferencesRepository.preferencesFlow.first()
            keepAliveRequested = pref.shouldAutoReconnect
            _uiState.update { current ->
                current.copy(
                    displayName = pref.displayName,
                    serverUrls = pref.serverUrls,
                    serverUrl = pref.currentServerUrl,
                    directMode = pref.directMode,
                    showSystemMessages = pref.showSystemMessages
                )
            }
            if (pref.shouldAutoReconnect && !autoReconnectTriggered) {
                autoReconnectTriggered = true
                ChatForegroundService.start(application)
                connectInternal(isAutoRestore = true)
            }
        }
    }

    fun updateDisplayName(value: String) {
        val displayName = value.take(64)
        _uiState.update { it.copy(displayName = displayName) }
        scope.launch {
            preferencesRepository.setDisplayName(displayName)
        }
    }

    fun updateServerUrl(value: String) {
        _uiState.update { it.copy(serverUrl = value) }
    }

    fun updateTargetKey(value: String) {
        _uiState.update { it.copy(targetKey = value) }
    }

    fun updateDraft(value: String) {
        _uiState.update { it.copy(draft = value) }
    }

    fun toggleDirectMode(enabled: Boolean) {
        _uiState.update { it.copy(directMode = enabled) }
        scope.launch {
            preferencesRepository.setDirectMode(enabled)
        }
    }

    fun toggleShowSystemMessages(show: Boolean) {
        _uiState.update { it.copy(showSystemMessages = show) }
        scope.launch {
            preferencesRepository.setShowSystemMessages(show)
        }
    }

    fun clearMessages() {
        cancelSystemMessageExpiryJobs()
        _uiState.update { it.copy(messages = emptyList()) }
    }

    fun saveCurrentServerUrl() {
        val normalized = ServerUrlFormatter.normalize(_uiState.value.serverUrl)
        if (normalized.isBlank()) {
            scope.launch {
                _events.emit(UiEvent.ShowSnackbar("请输入有效的服务器地址"))
            }
            return
        }

        val nextUrls = ServerUrlFormatter.append(_uiState.value.serverUrls, normalized)
        _uiState.update {
            it.copy(
                serverUrl = normalized,
                serverUrls = nextUrls,
                statusHint = "服务器地址已保存"
            )
        }

        scope.launch {
            preferencesRepository.saveCurrentServerUrl(normalized)
            _events.emit(UiEvent.ShowSnackbar("服务器地址已保存"))
        }
    }

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
                statusHint = if (filtered.isEmpty()) "已恢复默认服务器地址" else "已移除当前服务器地址"
            )
        }

        scope.launch {
            preferencesRepository.removeCurrentServerUrl(normalized)
            _events.emit(UiEvent.ShowSnackbar("已更新服务器地址列表"))
        }
    }

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
                _events.emit(UiEvent.ShowSnackbar("公钥读取失败：${error.message ?: "unknown"}"))
            }
        }
    }

    fun connect() {
        connectInternal(isAutoRestore = false)
    }

    private fun connectInternal(isAutoRestore: Boolean) {
        if (!initialized) return
        val state = _uiState.value
        if (!state.canConnect) return

        val normalized = ServerUrlFormatter.normalize(state.serverUrl)
        if (normalized.isBlank()) {
            _uiState.update {
                it.copy(
                    status = ConnectionStatus.ERROR,
                    statusHint = "请填写有效服务器地址"
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
                statusHint = "正在连接服务器...",
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
            addSystemMessage("已恢复上次会话，正在自动连接")
        }
    }

    fun disconnect(stopService: Boolean = true) {
        manualClose = true
        cancelReconnect()
        cancelHelloTimeout()
        cancelAuthTimeout()
        socketClient.close(1000, "manual_close")
        _uiState.update {
            it.copy(
                status = ConnectionStatus.IDLE,
                statusHint = "连接已关闭"
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
        addSystemMessage("已断开连接")
    }

    fun sendMessage() {
        val current = _uiState.value
        if (!current.canSend) return

        scope.launch {
            val text = _uiState.value.draft.trim()
            if (text.isBlank()) return@launch

            val key = if (_uiState.value.directMode) _uiState.value.targetKey.trim() else ""
            if (_uiState.value.directMode && key.isBlank()) {
                _uiState.update { it.copy(statusHint = "请先填写目标公钥，再发送私聊消息") }
                return@launch
            }

            val type = if (key.isBlank()) "broadcast" else "forward"
            val channel = if (key.isBlank()) MessageChannel.BROADCAST else MessageChannel.PRIVATE
            val subtitle = if (key.isBlank()) "" else "私聊 ${summarizeKey(key)}"

            _uiState.update { it.copy(sending = true) }

            runCatching {
                val id = ensureIdentity()
                val timestamp = cryptoManager.unixSecondsNow()
                val nonce = cryptoManager.createNonce()
                val signingInput = listOf(type, key, text, timestamp.toString(), nonce).joinToString("\n")
                val signature = withContext(Dispatchers.Default) {
                    cryptoManager.signText(id.privateKey, signingInput)
                }

                val payload = SignedPayloadDto(
                    payload = text,
                    timestamp = timestamp,
                    nonce = nonce,
                    signature = signature
                )
                val envelope = EnvelopeDto(
                    type = type,
                    key = key,
                    data = json.encodeToJsonElement(payload)
                )

                val plain = json.encodeToString(envelope)
                val cipher = withContext(Dispatchers.Default) {
                    cryptoManager.encryptChunked(serverPublicKey, plain)
                }

                check(socketClient.send(cipher)) { "连接不可用" }
            }.onSuccess {
                addOutgoingMessage(text, subtitle, channel)
                _uiState.update { it.copy(draft = "", sending = false) }
            }.onFailure { error ->
                _uiState.update { it.copy(sending = false) }
                addSystemMessage("发送失败：${error.message ?: "unknown"}")
            }
        }
    }

    fun onMessageCopied() {
        scope.launch {
            _events.emit(UiEvent.ShowSnackbar("已复制"))
        }
    }

    private suspend fun ensureIdentity(): RsaCryptoManager.Identity {
        return identityMutex.withLock {
            identity ?: withContext(Dispatchers.Default) {
                cryptoManager.getOrCreateIdentity()
            }.also { created ->
                identity = created
            }
        }
    }

    private suspend fun handleIncomingMessage(rawText: String) {
        if (_uiState.value.status == ConnectionStatus.HANDSHAKING) {
            _uiState.update { it.copy(statusHint = "已收到握手数据，正在解析...") }
        }

        val normalizedText = extractJsonCandidate(rawText)
        val rootObject = runCatching {
            json.decodeFromString<JsonElement>(normalizedText) as? JsonObject
        }.getOrNull()

        // 兼容某些代理/中间层直接转发 hello data 对象（没有 envelope 外层）
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
                        statusHint = "握手失败：服务端响应不完整"
                    )
                }
                return
            }
            handleServerHello(hello)
            return
        }

        if (_uiState.value.status == ConnectionStatus.HANDSHAKING && plain != null) {
            _uiState.update { it.copy(statusHint = "握手失败：收到非预期消息") }
            addSystemMessage("握手阶段收到非预期消息类型：${plain.type}")
        } else if (_uiState.value.status == ConnectionStatus.HANDSHAKING && plain == null) {
            val preview = rawText
                .replace("\n", " ")
                .replace("\r", " ")
                .take(80)
            _uiState.update { it.copy(statusHint = "握手失败：首包解析失败") }
            addSystemMessage("握手包解析失败：$preview")
        }

        val id = ensureIdentity()
        val decrypted = runCatching {
            withContext(Dispatchers.Default) {
                cryptoManager.decryptChunked(id.privateKey, normalizedText)
            }
        }.getOrElse {
            addSystemMessage("收到无法解密的消息")
            return
        }

        val secure = runCatching {
            json.decodeFromString<EnvelopeDto>(decrypted)
        }.getOrNull() ?: return

        handleSecureMessage(secure)
    }

    private suspend fun handleServerHello(hello: HelloDataDto) {
        cancelHelloTimeout()
        serverPublicKey = hello.publicKey
        _uiState.update {
            it.copy(
                status = ConnectionStatus.AUTHENTICATING,
                statusHint = "正在完成身份验证...",
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
                        statusHint = "连接超时，请重试"
                    )
                }
                addSystemMessage("认证超时，请检查网络后重试")
                socketClient.close(1000, "auth_timeout")
            }
        }

        runCatching {
            sendAuth(hello.authChallenge)
        }.onSuccess {
            addSystemMessage("已发送认证请求")
        }.onFailure { error ->
            cancelAuthTimeout()
            _uiState.update {
                it.copy(
                    status = ConnectionStatus.ERROR,
                    statusHint = "认证失败"
                )
            }
            addSystemMessage("认证发送失败：${error.message ?: "unknown"}")
            socketClient.close(1000, "auth_failed")
        }
    }

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
        check(socketClient.send(cipher)) { "连接不可用" }
    }

    private fun handleSecureMessage(message: EnvelopeDto) {
        when (message.type) {
            "auth_ok" -> {
                cancelAuthTimeout()
                cancelReconnect()
                reconnectAttempt = 0
                _uiState.update {
                    it.copy(
                        status = ConnectionStatus.READY,
                        statusHint = "已连接，可以开始聊天"
                    )
                }
                addSystemMessage("连接准备完成")
            }

            "broadcast" -> {
                val sender = message.key?.takeIf { it.isNotBlank() } ?: "匿名用户"
                addIncomingMessage(
                    sender = sender,
                    subtitle = "",
                    content = message.data.asPayloadText(),
                    channel = MessageChannel.BROADCAST
                )
            }

            "forward" -> {
                val sourceKey = message.key.orEmpty()
                addIncomingMessage(
                    sender = "私聊消息",
                    subtitle = sourceKey.takeIf { it.isNotBlank() }?.let { "来自 ${summarizeKey(it)}" }.orEmpty(),
                    content = message.data.asPayloadText(),
                    channel = MessageChannel.PRIVATE
                )
            }

            else -> addSystemMessage("收到未识别消息类型：${message.type}")
        }
    }

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

        val currentStatus = _uiState.value.status

        val allowFallback = !fallbackTried && currentStatus != ConnectionStatus.READY

        if (allowFallback) {
            val fallbackUrl = ServerUrlFormatter.toggleWsProtocol(connectedUrl)
            if (fallbackUrl.isNotBlank()) {
                fallbackTried = true
                connectedUrl = fallbackUrl
                _uiState.update {
                    it.copy(
                        status = ConnectionStatus.CONNECTING,
                        statusHint = "正在自动重试连接...",
                        serverUrl = fallbackUrl
                    )
                }
                addSystemMessage("连接方式切换中，正在重试")
                socketClient.connect(fallbackUrl, socketListener)
                return
            }
        }

        _uiState.update {
            it.copy(
                status = ConnectionStatus.ERROR,
                statusHint = "连接已中断，正在重试"
            )
        }
        addSystemMessage("连接关闭 ($code)：${reason.ifBlank { "连接中断" }}")
        scheduleReconnect("连接已中断")
    }

    private fun addSystemMessage(content: String) {
        val message = UiMessage(
            role = MessageRole.SYSTEM,
            sender = "系统",
            subtitle = "",
            content = content,
            channel = MessageChannel.BROADCAST
        )
        appendMessage(message)
        scheduleSystemMessageExpiry(message.id)
    }

    private fun addIncomingMessage(
        sender: String,
        subtitle: String,
        content: String,
        channel: MessageChannel
    ) {
        showIncomingNotification(
            title = sender,
            body = content.ifBlank { "收到一条新消息" }
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

    private fun addOutgoingMessage(
        content: String,
        subtitle: String,
        channel: MessageChannel
    ) {
        appendMessage(
            UiMessage(
                role = MessageRole.OUTGOING,
                sender = "我",
                subtitle = subtitle,
                content = content,
                channel = channel
            )
        )
    }

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
    }

    private fun cancelAuthTimeout() {
        authTimeoutJob?.cancel()
        authTimeoutJob = null
    }

    private fun scheduleReconnect(reason: String) {
        if (manualClose) return
        if (reconnectJob?.isActive == true) return

        reconnectAttempt += 1
        val exponential = 1 shl minOf(reconnectAttempt - 1, 5)
        val delaySeconds = minOf(MAX_RECONNECT_DELAY_SECONDS, exponential)
        addSystemMessage("$reason，${delaySeconds}s 后自动重连（第 $reconnectAttempt 次）")
        _uiState.update {
            it.copy(
                status = ConnectionStatus.ERROR,
                statusHint = "${delaySeconds}s 后自动重连（第 $reconnectAttempt 次）"
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
                        statusHint = "重连失败：服务器地址无效"
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
                    statusHint = "正在自动重连..."
                )
            }
            socketClient.connect(target, socketListener)
        }
    }

    private fun cancelReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
    }

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

    private fun cancelSystemMessageExpiryJobs() {
        systemMessageExpiryJobs.values.forEach { it.cancel() }
        systemMessageExpiryJobs.clear()
    }

    private fun startHelloTimeout() {
        cancelHelloTimeout()
        helloTimeoutJob = scope.launch {
            delay(HELLO_TIMEOUT_MS)
            if (_uiState.value.status == ConnectionStatus.HANDSHAKING) {
                val currentUrl = connectedUrl.ifBlank { "unknown" }
                _uiState.update {
                    it.copy(
                        status = ConnectionStatus.ERROR,
                        statusHint = "握手超时，请检查地址路径与反向代理"
                    )
                }
                addSystemMessage("握手超时：未收到服务端 publickey 首包（当前地址：$currentUrl）")
                socketClient.close(1000, "hello_timeout")
            }
        }
    }

    private fun cancelHelloTimeout() {
        helloTimeoutJob?.cancel()
        helloTimeoutJob = null
    }

    private fun summarizeKey(key: String): String {
        if (key.length <= 16) return key
        return "${key.take(8)}...${key.takeLast(8)}"
    }

    private fun createGuestName(): String {
        val rand = (100000..999999).random()
        return "guest-$rand"
    }

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

    fun shutdownAll() {
        cancelSystemMessageExpiryJobs()
        cancelReconnect()
        cancelHelloTimeout()
        cancelAuthTimeout()
        socketClient.shutdown()
    }

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

    fun shouldForegroundServiceRun(): Boolean = keepAliveRequested

    private fun ensureMessageNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            MESSAGE_CHANNEL_ID,
            "OnlineMsg 消息提醒",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "收到服务器新消息时提醒"
        }
        manager.createNotificationChannel(channel)
    }

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

    @Synchronized
    private fun nextMessageNotificationId(): Int {
        notificationIdSeed += 1
        return notificationIdSeed
    }

    private const val HELLO_TIMEOUT_MS = 12_000L
    private const val AUTH_TIMEOUT_MS = 20_000L
    private const val MAX_MESSAGES = 500
    private const val MAX_RECONNECT_DELAY_SECONDS = 30
    private const val SYSTEM_MESSAGE_TTL_MS = 1_000L
    private const val MESSAGE_CHANNEL_ID = "onlinemsg_messages"
}
