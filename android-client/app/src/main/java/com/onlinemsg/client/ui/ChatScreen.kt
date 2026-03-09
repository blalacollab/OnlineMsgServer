package com.onlinemsg.client.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import android.util.Base64
import android.view.MotionEvent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.KeyboardVoice
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.isSystemInDarkTheme
import com.onlinemsg.client.ui.theme.OnlineMsgTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.io.File
import com.onlinemsg.client.ui.theme.themeOptions
import com.onlinemsg.client.util.AudioRecorder
import com.onlinemsg.client.util.LanguageManager
import kotlinx.coroutines.delay


/**
 * 主界面底部导航栏的选项卡枚举。
 */
private enum class MainTab(val labelKey: String) {
    CHAT("tab.chat"),
    SETTINGS("tab.settings")
}

private enum class ChatInputMode {
    TEXT,
    AUDIO
}

/**
 * 应用程序的根可组合函数。
 * 集成 ViewModel、主题、Scaffold 以及选项卡切换逻辑。
 * @param viewModel 由 [viewModel] 自动提供的 [ChatViewModel] 实例
 */
@Composable
fun OnlineMsgApp(viewModel: ChatViewModel = viewModel()) {

    val state by viewModel.uiState.collectAsStateWithLifecycle()
    OnlineMsgTheme(
        darkTheme = isSystemInDarkTheme(),  // 仍可跟随系统
        themeId = state.themeId,
        useDynamicColor = state.useDynamicColor
    )
    {
        val clipboard = LocalClipboardManager.current
        val snackbarHostState = remember { SnackbarHostState() }
        var tab by rememberSaveable { mutableStateOf(MainTab.CHAT) }

        // 定义翻译函数 t
        fun t(key: String) = LanguageManager.getString(key, state.language)

        // 监听 ViewModel 发送的 UI 事件（如 Snackbar 消息）
        LaunchedEffect(Unit) {
            viewModel.events.collect { event ->
                when (event) {
                    is UiEvent.ShowSnackbar -> snackbarHostState.showSnackbar(event.message)
                }
            }
        }

        Scaffold(
            topBar = {
                AppTopBar(
                    statusText = localizedConnectionStatusText(state.status, state.language),
                    statusColor = when (state.status) {
                        ConnectionStatus.READY -> MaterialTheme.colorScheme.primary
                        ConnectionStatus.ERROR -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.secondary
                    },
                    canConnect = state.canConnect,
                    canDisconnect = state.canDisconnect,
                    onConnect = viewModel::connect,
                    onDisconnect = viewModel::disconnect
                )
            },
            bottomBar = {
                NavigationBar(
                    modifier = Modifier
                        .navigationBarsPadding()
                        .height(64.dp),
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp
                ) {
                    NavigationBarItem(
                        selected = tab == MainTab.CHAT,
                        onClick = { tab = MainTab.CHAT },
                        label = { Text(t(MainTab.CHAT.labelKey), style = MaterialTheme.typography.labelSmall) },
                        icon = { 
                            Icon(
                                imageVector = Icons.Rounded.Forum, 
                                contentDescription = null,
                                modifier = Modifier.size(22.dp)
                            ) 
                        }
                    )
                    NavigationBarItem(
                        selected = tab == MainTab.SETTINGS,
                        onClick = { tab = MainTab.SETTINGS },
                        label = { Text(t(MainTab.SETTINGS.labelKey), style = MaterialTheme.typography.labelSmall) },
                        icon = { 
                            Icon(
                                imageVector = Icons.Rounded.Settings, 
                                contentDescription = null,
                                modifier = Modifier.size(22.dp)
                            ) 
                        }
                    )
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { innerPadding ->
            when (tab) {
                MainTab.CHAT -> {
                    ChatTab(
                        modifier = Modifier
                            .padding(innerPadding)
                            .consumeWindowInsets(innerPadding),
                        state = state,
                        onToggleDirectMode = viewModel::toggleDirectMode,
                        onTargetKeyChange = viewModel::updateTargetKey,
                        onDraftChange = viewModel::updateDraft,
                        onSend = viewModel::sendMessage,
                        onSendAudio = viewModel::sendAudioMessage,
                        onCopyMessage = { content ->
                            clipboard.setText(AnnotatedString(content))
                            viewModel.onMessageCopied()
                        }
                    )
                }

                MainTab.SETTINGS -> {
                    SettingsTab(
                        modifier = Modifier
                            .padding(innerPadding)
                            .consumeWindowInsets(innerPadding),
                        state = state,
                        onDisplayNameChange = viewModel::updateDisplayName,
                        onServerUrlChange = viewModel::updateServerUrl,
                        onSaveServer = viewModel::saveCurrentServerUrl,
                        onRemoveServer = viewModel::removeCurrentServerUrl,
                        onSelectServer = viewModel::updateServerUrl,
                        onToggleShowSystem = viewModel::toggleShowSystemMessages,
                        onRevealPublicKey = viewModel::revealMyPublicKey,
                        onCopyPublicKey = {
                            if (state.myPublicKey.isNotBlank()) {
                                clipboard.setText(AnnotatedString(state.myPublicKey))
                                viewModel.onMessageCopied()
                            }
                        },
                        onClearMessages = viewModel::clearMessages,
                        onThemeChange = viewModel::updateTheme,
                        onUseDynamicColorChange = viewModel::updateUseDynamicColor,
                        onLanguageChange = viewModel::updateLanguage
                    )
                }
            }
        }
    }
}

/**
 * 应用程序顶部栏，显示标题和当前连接状态徽章。
 * @param statusText 状态文本
 * @param statusColor 状态指示点的颜色
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppTopBar(
    statusText: String,
    statusColor: Color,
    canConnect: Boolean,
    canDisconnect: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    val enabled = canConnect || canDisconnect
    TopAppBar(
        title = {
            Text(
                text = "OnlineMsg",
                style = MaterialTheme.typography.titleMedium
            )
        },
        actions = {
            AssistChip(
                onClick = {
                    when {
                        canDisconnect -> onDisconnect()
                        canConnect -> onConnect()
                    }
                },
                enabled = enabled,
                label = { Text(statusText) },
                leadingIcon = {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(statusColor, RoundedCornerShape(999.dp))
                    )
                },
                modifier = Modifier.height(32.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
        },
        windowInsets = WindowInsets(top = 20.dp), // 顶部高度(状态栏)
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            scrolledContainerColor = MaterialTheme.colorScheme.surface
        )
    )
}

/**
 * 聊天选项卡的主界面。
 * 包含模式切换、消息列表和输入区域。
 */
@Composable
@OptIn(ExperimentalComposeUiApi::class)
private fun ChatTab(
    modifier: Modifier,
    state: ChatUiState,
    onToggleDirectMode: (Boolean) -> Unit,
    onTargetKeyChange: (String) -> Unit,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onSendAudio: (String, Long) -> Unit,
    onCopyMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val audioRecorder = remember(context) { AudioRecorder(context) }
    val audioPlayer = remember(context) { AudioMessagePlayer(context) }
    var inputMode by rememberSaveable { mutableStateOf(ChatInputMode.TEXT) }
    var isRecording by remember { mutableStateOf(false) }
    var cancelOnRelease by remember { mutableStateOf(false) }
    var pressDownRawY by remember { mutableStateOf(0f) }
    var audioHint by remember { mutableStateOf("") }
    var audioHintVersion by remember { mutableStateOf(0L) }
    var playingMessageId by remember { mutableStateOf<String?>(null) }
    var recordingStartedAtMillis by remember { mutableStateOf(0L) }
    var recordingElapsedMillis by remember { mutableStateOf(0L) }
    val recordingPulse = rememberInfiniteTransition(label = "recordingPulse")
    val recordingPulseScale by recordingPulse.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700),
            repeatMode = RepeatMode.Reverse
        ),
        label = "recordingPulseScale"
    )
    val recordingPulseAlpha by recordingPulse.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.65f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700),
            repeatMode = RepeatMode.Reverse
        ),
        label = "recordingPulseAlpha"
    )

    // 定义翻译函数 t
    fun t(key: String) = LanguageManager.getString(key, state.language)
    fun showAudioHint(message: String) {
        audioHint = message
        audioHintVersion += 1L
    }
    val canHoldToRecord = state.status == ConnectionStatus.READY &&
            !state.sending &&
            (!state.directMode || state.targetKey.trim().isNotBlank())

    fun hasRecordPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun startRecording() {
        if (isRecording) return
        if (!canHoldToRecord) return
        if (!hasRecordPermission()) {
            showAudioHint(t("chat.audio_permission_required"))
            return
        }
        val started = audioRecorder.start()
        if (!started) {
            showAudioHint(t("chat.audio_record_failed"))
            return
        }
        isRecording = true
        cancelOnRelease = false
        recordingStartedAtMillis = System.currentTimeMillis()
        recordingElapsedMillis = 0L
        audioHint = ""
    }

    fun finishRecording(send: Boolean) {
        if (!isRecording) return
        isRecording = false
        cancelOnRelease = false
        recordingStartedAtMillis = 0L
        recordingElapsedMillis = 0L
        val recorded = audioRecorder.stopAndEncode(send = send)
        when {
            !send -> {
                showAudioHint(t("chat.audio_canceled"))
            }
            recorded == null -> {
                showAudioHint(t("chat.audio_record_failed"))
            }
            recorded.durationMillis < MIN_AUDIO_DURATION_MS -> {
                showAudioHint(t("chat.audio_too_short"))
            }
            else -> {
                onSendAudio(recorded.base64, recorded.durationMillis)
                audioHint = ""
            }
        }
    }

    // 当消息列表新增消息时，自动滚动到底部
    LaunchedEffect(state.visibleMessages.size) {
        if (state.visibleMessages.isNotEmpty()) {
            listState.animateScrollToItem(state.visibleMessages.lastIndex)
        }
    }

    LaunchedEffect(isRecording, recordingStartedAtMillis) {
        if (!isRecording || recordingStartedAtMillis <= 0L) return@LaunchedEffect
        while (isRecording) {
            recordingElapsedMillis = (System.currentTimeMillis() - recordingStartedAtMillis)
                .coerceAtLeast(0L)
            delay(100L)
        }
    }

    LaunchedEffect(audioHintVersion) {
        val latest = audioHint
        val latestVersion = audioHintVersion
        if (latest.isBlank()) return@LaunchedEffect
        delay(2200L)
        if (audioHintVersion == latestVersion && audioHint == latest) {
            audioHint = ""
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            audioRecorder.release()
            audioPlayer.release()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .padding(start = 16.dp, end = 16.dp, bottom = 8.dp) // 移除了顶部多余 padding
    ) {
        // 广播/私聊模式切换按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = !state.directMode,
                onClick = { onToggleDirectMode(false) },
                label = { Text(t("chat.broadcast")) }
            )
            FilterChip(
                selected = state.directMode,
                onClick = { onToggleDirectMode(true) },
                label = { Text(t("chat.private")) }
            )

            // 在这一行腾出的空间可以放置其他快捷操作，或者保持简洁
        }

        Spacer(modifier = Modifier.height(8.dp))
        val statusHintText = if (audioHint.isNotBlank()) {
            audioHint
        } else {
            localizedStatusHintText(state.statusHint, state.language)
        }
        val statusHintColor = if (audioHint.isNotBlank()) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
        Text(
            text = statusHintText,
            style = MaterialTheme.typography.bodySmall,
            color = statusHintColor
        )

        if (state.directMode) {
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextField(
                value = state.targetKey,
                onValueChange = onTargetKeyChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(t("chat.target_key")) },
                placeholder = { Text(t("chat.target_key")) },
                maxLines = 3
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // 消息列表
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (state.visibleMessages.isEmpty()) {
                // 无消息时显示提示卡片
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Text(
                            text = t("chat.empty_hint"),
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            } else {
                items(state.visibleMessages, key = { it.id }) { message ->
                    MessageItem(
                        message = message,
                        onCopy = { onCopyMessage(message.content) },
                        onPlayAudio = {
                            val nextPlaying = audioPlayer.toggle(
                                messageId = message.id,
                                audioBase64 = message.audioBase64
                            ) { stoppedId ->
                                if (playingMessageId == stoppedId) {
                                    playingMessageId = null
                                }
                            }
                            playingMessageId = nextPlaying
                        },
                        isPlaying = playingMessageId == message.id,
                        currentLanguage = state.language
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (inputMode == ChatInputMode.TEXT) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = { inputMode = ChatInputMode.AUDIO },
                    modifier = Modifier
                        .size(56.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(14.dp)
                        )
                ) {
                    Icon(
                        imageVector = Icons.Rounded.KeyboardVoice,
                        contentDescription = t("chat.mode_audio"),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                OutlinedTextField(
                    value = state.draft,
                    onValueChange = onDraftChange,
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    placeholder = { Text(t("chat.input_placeholder")) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = { onSend() }
                    )
                )

                Button(
                    onClick = onSend,
                    enabled = state.canSend,
                    modifier = Modifier.size(56.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.Send,
                        contentDescription = t("chat.send")
                    )
                }
            }
        } else {
            val holdToTalkText = when {
                state.sending -> t("chat.sending")
                isRecording && cancelOnRelease -> t("chat.audio_release_cancel")
                isRecording -> t("chat.audio_release_send")
                else -> t("chat.audio_hold_to_talk")
            }
            val holdToTalkColor = when {
                !canHoldToRecord -> MaterialTheme.colorScheme.surfaceVariant
                isRecording && cancelOnRelease -> MaterialTheme.colorScheme.errorContainer
                isRecording -> MaterialTheme.colorScheme.tertiaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
            val holdToTalkTextColor = when {
                isRecording && cancelOnRelease -> MaterialTheme.colorScheme.onErrorContainer
                isRecording -> MaterialTheme.colorScheme.onTertiaryContainer
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = {
                        if (isRecording) {
                            finishRecording(send = false)
                        }
                        inputMode = ChatInputMode.TEXT
                    },
                    modifier = Modifier
                        .size(56.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(14.dp)
                        )
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Keyboard,
                        contentDescription = t("chat.mode_text"),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Card(
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp)
                        .pointerInteropFilter { event ->
                            if (!canHoldToRecord && event.actionMasked == MotionEvent.ACTION_DOWN) {
                                return@pointerInteropFilter false
                            }
                            when (event.actionMasked) {
                                MotionEvent.ACTION_DOWN -> {
                                    pressDownRawY = event.rawY
                                    startRecording()
                                    true
                                }

                                MotionEvent.ACTION_MOVE -> {
                                    if (isRecording) {
                                        cancelOnRelease = pressDownRawY - event.rawY > AUDIO_CANCEL_TRIGGER_PX
                                    }
                                    true
                                }

                                MotionEvent.ACTION_UP -> {
                                    finishRecording(send = !cancelOnRelease)
                                    true
                                }

                                MotionEvent.ACTION_CANCEL -> {
                                    finishRecording(send = false)
                                    true
                                }

                                else -> false
                            }
                        },
                    colors = CardDefaults.cardColors(containerColor = holdToTalkColor),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isRecording) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Box(
                                        modifier = Modifier
                                            .size(18.dp)
                                            .graphicsLayer {
                                                scaleX = recordingPulseScale
                                                scaleY = recordingPulseScale
                                            }
                                            .background(
                                                color = holdToTalkTextColor.copy(alpha = recordingPulseAlpha),
                                                shape = RoundedCornerShape(999.dp)
                                            )
                                    )
                                    Icon(
                                        imageVector = Icons.Rounded.KeyboardVoice,
                                        contentDescription = null,
                                        tint = holdToTalkTextColor,
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                                Column(
                                    horizontalAlignment = Alignment.Start,
                                    verticalArrangement = Arrangement.spacedBy(1.dp)
                                ) {
                                    Text(
                                        text = holdToTalkText,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = holdToTalkTextColor
                                    )
                                    Text(
                                        text = "${t("chat.audio_recording")} ${formatRecordingElapsed(recordingElapsedMillis)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = holdToTalkTextColor.copy(alpha = 0.9f)
                                    )
                                }
                            }
                        } else {
                            Text(
                                text = holdToTalkText,
                                style = MaterialTheme.typography.titleMedium,
                                color = holdToTalkTextColor
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 单个消息气泡组件。
 */
@Composable
private fun MessageItem(
    message: UiMessage,
    onCopy: () -> Unit,
    onPlayAudio: () -> Unit,
    isPlaying: Boolean,
    currentLanguage: String
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        val isSystem = message.role == MessageRole.SYSTEM

        if (isSystem) {
            // 系统消息居中显示，最大占比 90%
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .align(Alignment.Center),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = message.content,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            text = formatTime(message.timestampMillis),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        } else {
            val isOutgoing = message.role == MessageRole.OUTGOING
            val shouldShowSender = !isOutgoing
            val senderDisplayName = message.sender.ifBlank {
                LanguageManager.getString("session.sender.anonymous", currentLanguage)
            }
            val bubbleColor = if (isOutgoing) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            }
            val bubbleTextColor = if (isOutgoing) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            }
            val bubbleShape = if (isOutgoing) {
                RoundedCornerShape(
                    topStart = 18.dp,
                    topEnd = 6.dp,
                    bottomEnd = 18.dp,
                    bottomStart = 18.dp
                )
            } else {
                RoundedCornerShape(
                    topStart = 6.dp,
                    topEnd = 18.dp,
                    bottomEnd = 18.dp,
                    bottomStart = 18.dp
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = if (isOutgoing) Arrangement.End else Arrangement.Start
            ) {
                // 使用 Box(fillMaxWidth(0.82f)) 限制气泡最大宽度占比
                Box(
                    modifier = Modifier.fillMaxWidth(0.82f),
                    contentAlignment = if (isOutgoing) Alignment.CenterEnd else Alignment.CenterStart
                ) {
                    Card(
                        shape = bubbleShape,
                        colors = CardDefaults.cardColors(containerColor = bubbleColor)
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (shouldShowSender) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = senderDisplayName,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = if (isOutgoing) {
                                            bubbleTextColor.copy(alpha = 0.9f)
                                        } else {
                                            MaterialTheme.colorScheme.primary
                                        }
                                    )
                                    if (message.subtitle.isNotBlank()) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = message.subtitle,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = bubbleTextColor.copy(alpha = 0.75f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }

                            if (message.contentType == MessageContentType.AUDIO &&
                                message.audioBase64.isNotBlank()
                            ) {
                                AudioMessageBody(
                                    message = message,
                                    bubbleTextColor = bubbleTextColor,
                                    onPlayAudio = onPlayAudio,
                                    isPlaying = isPlaying,
                                    currentLanguage = currentLanguage
                                )
                            } else {
                                Text(
                                    text = message.content,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = bubbleTextColor
                                )
                            }

                            // 时间戳和复制按钮
                            Row(
                                modifier = if (message.contentType == MessageContentType.AUDIO) {
                                    Modifier.align(Alignment.End)
                                } else {
                                    Modifier.fillMaxWidth()
                                },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (message.contentType == MessageContentType.TEXT) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                                Text(
                                    text = formatTime(message.timestampMillis),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = bubbleTextColor.copy(alpha = 0.7f)
                                )
                                if (message.contentType == MessageContentType.TEXT) {
                                    IconButton(
                                        onClick = onCopy,
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.ContentCopy,
                                            contentDescription = LanguageManager.getString("common.copied", currentLanguage),
                                            tint = bubbleTextColor.copy(alpha = 0.7f),
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioMessageBody(
    message: UiMessage,
    bubbleTextColor: Color,
    onPlayAudio: () -> Unit,
    isPlaying: Boolean,
    currentLanguage: String
) {
    val actionText = if (isPlaying) {
        LanguageManager.getString("chat.audio_stop", currentLanguage)
    } else {
        LanguageManager.getString("chat.audio_play", currentLanguage)
    }
    val waveformPulse by rememberInfiniteTransition(label = "audioPlaybackWave").animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 480),
            repeatMode = RepeatMode.Reverse
        ),
        label = "audioPlaybackWavePulse"
    )
    val waveScales = if (isPlaying) {
        listOf(
            0.75f + waveformPulse * 0.22f,
            0.92f + waveformPulse * 0.2f,
            0.82f + waveformPulse * 0.28f,
            0.9f + waveformPulse * 0.18f,
            0.7f + waveformPulse * 0.24f
        )
    } else {
        listOf(0.75f, 0.95f, 0.82f, 0.9f, 0.72f)
    }
    val baseWaveHeights = listOf(8.dp, 14.dp, 10.dp, 13.dp, 9.dp)

    Row(
        modifier = Modifier
            .widthIn(min = 140.dp, max = 210.dp)
            .background(
                color = bubbleTextColor.copy(alpha = 0.12f),
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onPlayAudio)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(
                    color = bubbleTextColor.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(999.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                contentDescription = actionText,
                tint = bubbleTextColor,
                modifier = Modifier.size(18.dp)
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            baseWaveHeights.forEachIndexed { index, baseHeight ->
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(baseHeight)
                        .graphicsLayer {
                            scaleY = waveScales[index]
                        }
                        .background(
                            color = bubbleTextColor.copy(alpha = if (isPlaying) 0.95f else 0.72f),
                            shape = RoundedCornerShape(999.dp)
                        )
                )
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = formatAudioDuration(message.audioDurationMillis),
            style = MaterialTheme.typography.labelMedium,
            color = bubbleTextColor.copy(alpha = 0.8f)
        )
    }
}

/**
 * 设置选项卡界面，包含个人设置、服务器管理、身份安全、语言、主题和诊断信息。
 * @param modifier 修饰符
 * @param state 当前的 UI 状态
 * @param onDisplayNameChange 显示名称变更
 * @param onServerUrlChange 服务器地址变更
 * @param onSaveServer 保存当前服务器地址
 * @param onRemoveServer 删除当前服务器地址
 * @param onSelectServer 选择历史服务器地址
 * @param onToggleShowSystem 切换显示系统消息
 * @param onRevealPublicKey 显示/生成公钥
 * @param onCopyPublicKey 复制公钥
 * @param onClearMessages 清空消息
 * @param onThemeChange 切换主题
 * @param onUseDynamicColorChange 切换动态颜色
 * @param onLanguageChange 切换语言
 */
@Composable
private fun SettingsTab(
    modifier: Modifier,
    state: ChatUiState,
    onDisplayNameChange: (String) -> Unit,
    onServerUrlChange: (String) -> Unit,
    onSaveServer: () -> Unit,
    onRemoveServer: () -> Unit,
    onSelectServer: (String) -> Unit,
    onToggleShowSystem: (Boolean) -> Unit,
    onRevealPublicKey: () -> Unit,
    onCopyPublicKey: () -> Unit,
    onClearMessages: () -> Unit,
    onThemeChange: (String) -> Unit,
    onUseDynamicColorChange: (Boolean) -> Unit,
    onLanguageChange: (String) -> Unit
) {
    fun t(key: String) = LanguageManager.getString(key, state.language)

    val settingsCardModifier = Modifier.fillMaxWidth()
    val settingsCardContentModifier = Modifier
        .fillMaxWidth()
        .heightIn(min = 112.dp)
        .padding(horizontal = 14.dp, vertical = 12.dp)
    val settingsCardContentSpacing = Arrangement.spacedBy(10.dp)

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(modifier = settingsCardModifier) {
                Column(
                    modifier = settingsCardContentModifier,
                    verticalArrangement = settingsCardContentSpacing
                ) {
                    Text(t("settings.personal"), style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = state.displayName,
                        onValueChange = onDisplayNameChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(t("settings.display_name")) },
                        maxLines = 1
                    )
                }
            }
        }

        item {
            Card(modifier = settingsCardModifier) {
                Column(
                    modifier = settingsCardContentModifier,
                    verticalArrangement = settingsCardContentSpacing
                ) {
                    Text(t("settings.chat_data"), style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(onClick = onClearMessages) {
                        Text(t("settings.clear_msg"))
                    }
                }
            }
        }

        item {
            Card(modifier = settingsCardModifier) {
                Column(
                    modifier = settingsCardContentModifier,
                    verticalArrangement = settingsCardContentSpacing
                ) {
                    Text(t("settings.server"), style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = state.serverUrl,
                        onValueChange = onServerUrlChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(t("settings.server_url")) },
                        maxLines = 1
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onSaveServer) { Text(t("settings.save_server")) }
                        OutlinedButton(onClick = onRemoveServer) { Text(t("settings.remove_current")) }
                    }
                    if (state.serverUrls.isNotEmpty()) {
                        HorizontalDivider()
                        Text(t("settings.saved_servers"), style = MaterialTheme.typography.labelLarge)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(state.serverUrls) { url ->
                                AssistChip(
                                    onClick = { onSelectServer(url) },
                                    label = { Text(url, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            Card(modifier = settingsCardModifier) {
                Column(
                    modifier = settingsCardContentModifier,
                    verticalArrangement = settingsCardContentSpacing
                ) {
                    Text(t("settings.identity"), style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onRevealPublicKey, enabled = !state.loadingPublicKey) {
                            Text(if (state.loadingPublicKey) "..." else t("settings.reveal_key"))
                        }
                        OutlinedButton(
                            onClick = onCopyPublicKey,
                            enabled = state.myPublicKey.isNotBlank()
                        ) {
                            Text(t("settings.copy_key"))
                        }
                    }
                    OutlinedTextField(
                        value = state.myPublicKey,
                        onValueChange = {},
                        modifier = Modifier.fillMaxWidth(),
                        readOnly = true,
                        label = { Text(t("settings.my_key")) },
                        maxLines = 4
                    )
                }
            }
        }

        item {
            Card(modifier = settingsCardModifier) {
                Column(
                    modifier = settingsCardContentModifier,
                    verticalArrangement = settingsCardContentSpacing
                ) {
                    Text(t("settings.language"), style = MaterialTheme.typography.titleMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(LanguageManager.supportedLanguages) { lang ->
                            FilterChip(
                                selected = state.language == lang.code,
                                onClick = { onLanguageChange(lang.code) },
                                label = { Text(lang.name) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Rounded.Language,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }

        item {
            Card(modifier = settingsCardModifier) {
                Column(
                    modifier = settingsCardContentModifier,
                    verticalArrangement = settingsCardContentSpacing
                ) {
                    Text(t("settings.theme"), style = MaterialTheme.typography.titleMedium)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Switch(
                                checked = state.useDynamicColor,
                                onCheckedChange = onUseDynamicColorChange
                            )
                            Text(t("settings.dynamic_color"))
                        }
                    }
                    if (!state.useDynamicColor || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                        Text(t("settings.preset_themes"), style = MaterialTheme.typography.labelLarge)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(themeOptions) { option ->
                                val themeName = when (option.id) {
                                    "blue" -> t("theme.blue")
                                    "gray" -> t("theme.gray")
                                    "green" -> t("theme.green")
                                    "red" -> t("theme.red")
                                    else -> option.name
                                }
                                FilterChip(
                                    selected = state.themeId == option.id,
                                    onClick = { onThemeChange(option.id) },
                                    label = { Text(themeName) },
                                    leadingIcon = {
                                        Box(
                                            modifier = Modifier
                                                .size(16.dp)
                                                .background(option.primary, RoundedCornerShape(4.dp))
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            Card(modifier = settingsCardModifier) {
                Column(
                    modifier = settingsCardContentModifier,
                    verticalArrangement = settingsCardContentSpacing
                ) {
                    Text(t("settings.diagnostics"), style = MaterialTheme.typography.titleMedium)
                    Text("${t("settings.status_hint")}：${localizedStatusHintText(state.statusHint, state.language)}")
                    Text("${t("settings.current_status")}：${localizedConnectionStatusText(state.status, state.language)}")
                    Text("${t("settings.cert_fingerprint")}：${state.certFingerprint.ifBlank { "N/A" }}")
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Switch(checked = state.showSystemMessages, onCheckedChange = onToggleShowSystem)
                        Text(t("settings.show_system"))
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(12.dp)) }
    }
}

private class AudioMessagePlayer(private val context: Context) {
    private var mediaPlayer: MediaPlayer? = null
    private var currentMessageId: String? = null
    private var currentAudioFile: File? = null

    fun toggle(
        messageId: String,
        audioBase64: String,
        onStopped: (String) -> Unit
    ): String? {
        if (currentMessageId == messageId) {
            stopPlayback()?.let(onStopped)
            return null
        }

        stopPlayback()?.let(onStopped)

        val bytes = runCatching {
            Base64.decode(audioBase64, Base64.DEFAULT)
        }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null

        val audioFile = runCatching {
            File.createTempFile("oms_play_", ".m4a", context.cacheDir).apply {
                writeBytes(bytes)
            }
        }.getOrNull() ?: return null

        val player = MediaPlayer()
        val started = runCatching {
            player.setDataSource(audioFile.absolutePath)
            player.setOnCompletionListener {
                stopPlayback()?.let(onStopped)
            }
            player.setOnErrorListener { _, _, _ ->
                stopPlayback()?.let(onStopped)
                true
            }
            player.prepare()
            player.start()
            true
        }.getOrElse {
            runCatching { player.release() }
            audioFile.delete()
            false
        }
        if (!started) return null

        mediaPlayer = player
        currentMessageId = messageId
        currentAudioFile = audioFile
        return currentMessageId
    }

    fun release() {
        stopPlayback()
    }

    private fun stopPlayback(): String? {
        val stoppedId = currentMessageId
        runCatching { mediaPlayer?.stop() }
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
        currentMessageId = null
        currentAudioFile?.delete()
        currentAudioFile = null
        return stoppedId
    }
}

private fun formatAudioDuration(durationMillis: Long): String {
    val totalSeconds = (durationMillis / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return if (minutes > 0L) {
        String.format("%d:%02d", minutes, seconds)
    } else {
        "${seconds}s"
    }
}

private fun formatRecordingElapsed(durationMillis: Long): String {
    val clamped = durationMillis.coerceAtLeast(0L)
    val seconds = clamped / 1000L
    val tenths = (clamped % 1000L) / 100L
    return "${seconds}.${tenths}s"
}

private fun localizedConnectionStatusText(status: ConnectionStatus, language: String): String {
    val key = when (status) {
        ConnectionStatus.IDLE -> "status.idle"
        ConnectionStatus.CONNECTING,
        ConnectionStatus.HANDSHAKING,
        ConnectionStatus.AUTHENTICATING -> "status.connecting"
        ConnectionStatus.READY -> "status.ready"
        ConnectionStatus.ERROR -> "status.error"
    }
    return LanguageManager.getString(key, language)
}

private fun localizedStatusHintText(raw: String, language: String): String {
    val exact = when (raw) {
        "点击连接开始聊天" -> "hint.tap_to_connect"
        "正在连接服务器..." -> "hint.connecting_server"
        "已连接，可以开始聊天" -> "hint.ready_chat"
        "连接已关闭" -> "hint.closed"
        "连接已中断，正在重试" -> "hint.reconnecting"
        "重连失败：服务器地址无效" -> "hint.reconnect_invalid_server"
        "请先填写目标公钥，再发送私聊消息" -> "hint.fill_target_key"
        else -> null
    }
    if (exact != null) {
        return LanguageManager.getString(exact, language)
    }
    return when {
        raw.startsWith("服务器拒绝连接：") -> {
            val suffix = raw.removePrefix("服务器拒绝连接：")
            LanguageManager.getString("hint.server_rejected_prefix", language) + suffix
        }

        raw.startsWith("语音发送失败：") -> {
            val suffix = raw.removePrefix("语音发送失败：")
            LanguageManager.getString("hint.audio_send_failed_prefix", language) + suffix
        }

        else -> raw
    }
}

private const val AUDIO_CANCEL_TRIGGER_PX = 120f
private const val MIN_AUDIO_DURATION_MS = 350L

/**
 * 将时间戳格式化为本地时间的小时:分钟（如 "14:30"）。
 * @param tsMillis 毫秒时间戳
 * @return 格式化后的时间字符串
 */
private fun formatTime(tsMillis: Long): String {
    val formatter = DateTimeFormatter.ofPattern("HH:mm")
    return Instant.ofEpochMilli(tsMillis)
        .atZone(ZoneId.systemDefault())
        .toLocalTime()
        .format(formatter)
}
