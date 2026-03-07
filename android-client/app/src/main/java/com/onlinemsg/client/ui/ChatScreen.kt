package com.onlinemsg.client.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.onlinemsg.client.ui.theme.OnlineMsgTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class MainTab(val label: String) {
    CHAT("聊天"),
    SETTINGS("设置")
}

@Composable
fun OnlineMsgApp(
    viewModel: ChatViewModel = viewModel()
) {
    OnlineMsgTheme {
        val state by viewModel.uiState.collectAsStateWithLifecycle()
        val clipboard = LocalClipboardManager.current
        val snackbarHostState = remember { SnackbarHostState() }
        var tab by rememberSaveable { mutableStateOf(MainTab.CHAT) }

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
                    statusText = state.statusText,
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
                NavigationBar(modifier = Modifier.navigationBarsPadding()) {
                    NavigationBarItem(
                        selected = tab == MainTab.CHAT,
                        onClick = { tab = MainTab.CHAT },
                        label = { Text(MainTab.CHAT.label) },
                        icon = {}
                    )
                    NavigationBarItem(
                        selected = tab == MainTab.SETTINGS,
                        onClick = { tab = MainTab.SETTINGS },
                        label = { Text(MainTab.SETTINGS.label) },
                        icon = {}
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
                        onClearMessages = viewModel::clearMessages
                    )
                }
            }
        }
    }
}

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
                text = "OnlineMsg Chat",
                style = MaterialTheme.typography.titleLarge
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
                            .width(10.dp)
                            .height(10.dp)
                            .background(statusColor, RoundedCornerShape(999.dp))
                    )
                }
            )
            Spacer(modifier = Modifier.width(12.dp))
        }
    )
}

@Composable
private fun ChatTab(
    modifier: Modifier,
    state: ChatUiState,
    onToggleDirectMode: (Boolean) -> Unit,
    onTargetKeyChange: (String) -> Unit,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onCopyMessage: (String) -> Unit
) {
    val listState = rememberLazyListState()

    LaunchedEffect(state.visibleMessages.size) {
        if (state.visibleMessages.isNotEmpty()) {
            listState.animateScrollToItem(state.visibleMessages.lastIndex)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilterChip(
                selected = !state.directMode,
                onClick = { onToggleDirectMode(false) },
                label = { Text("广播") }
            )
            FilterChip(
                selected = state.directMode,
                onClick = { onToggleDirectMode(true) },
                label = { Text("私聊") }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = state.statusHint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (state.directMode) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = state.targetKey,
                onValueChange = onTargetKeyChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("目标公钥") },
                placeholder = { Text("私聊模式：粘贴目标公钥") },
                maxLines = 3
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (state.visibleMessages.isEmpty()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Text(
                            text = "连接后即可聊天。默认广播，切换到私聊后可填写目标公钥。",
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            } else {
                items(state.visibleMessages, key = { it.id }) { message ->
                    MessageItem(
                        message = message,
                        onCopy = { onCopyMessage(message.content) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = state.draft,
                onValueChange = onDraftChange,
                modifier = Modifier.weight(1f),
                label = { Text("输入消息") },
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = { onSend() }
                )
            )

            Button(
                onClick = onSend,
                enabled = state.canSend,
                modifier = Modifier.height(56.dp)
            ) {
                Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "发送")
                Spacer(Modifier.width(6.dp))
                Text(if (state.sending) "发送中" else "发送")
            }
        }
    }
}

@Composable
private fun MessageItem(
    message: UiMessage,
    onCopy: () -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val maxBubbleWidth = if (message.role == MessageRole.SYSTEM) {
            maxWidth * 0.9f
        } else {
            maxWidth * 0.82f
        }

        if (message.role == MessageRole.SYSTEM) {
            Card(
                modifier = Modifier
                    .widthIn(max = maxBubbleWidth)
                    .align(Alignment.Center),
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
            return@BoxWithConstraints
        }

        val isOutgoing = message.role == MessageRole.OUTGOING
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
            Card(
                modifier = Modifier.widthIn(max = maxBubbleWidth),
                shape = bubbleShape,
                colors = CardDefaults.cardColors(containerColor = bubbleColor)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (!isOutgoing) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = message.sender,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
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

                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodyMedium,
                        color = bubbleTextColor
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = formatTime(message.timestampMillis),
                            style = MaterialTheme.typography.labelSmall,
                            color = bubbleTextColor.copy(alpha = 0.7f)
                        )
                        IconButton(
                            onClick = onCopy,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ContentCopy,
                                contentDescription = "复制",
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
    onClearMessages: () -> Unit
) {
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
                    Text("个人设置", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = state.displayName,
                        onValueChange = onDisplayNameChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("显示名称") },
                        supportingText = { Text("最长 64 字符") },
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
                    Text("聊天数据", style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(onClick = onClearMessages) {
                        Text("清空聊天记录")
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
                    Text("服务器", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = state.serverUrl,
                        onValueChange = onServerUrlChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("服务器地址") },
                        placeholder = { Text("ws://10.0.2.2:13173/") },
                        maxLines = 1
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onSaveServer) {
                            Text("保存地址")
                        }
                        OutlinedButton(onClick = onRemoveServer) {
                            Text("删除当前")
                        }
                    }
                    if (state.serverUrls.isNotEmpty()) {
                        HorizontalDivider()
                        Text("已保存地址", style = MaterialTheme.typography.labelLarge)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(state.serverUrls) { url ->
                                AssistChip(
                                    onClick = { onSelectServer(url) },
                                    label = {
                                        Text(
                                            text = url,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
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
                    Text("身份与安全", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onRevealPublicKey,
                            enabled = !state.loadingPublicKey
                        ) {
                            Text(if (state.loadingPublicKey) "读取中" else "查看/生成公钥")
                        }
                        OutlinedButton(
                            onClick = onCopyPublicKey,
                            enabled = state.myPublicKey.isNotBlank()
                        ) {
                            Text("复制公钥")
                        }
                    }
                    OutlinedTextField(
                        value = state.myPublicKey,
                        onValueChange = {},
                        modifier = Modifier.fillMaxWidth(),
                        readOnly = true,
                        label = { Text("我的公钥") },
                        placeholder = { Text("点击“查看/生成公钥”") },
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
                    Text("诊断", style = MaterialTheme.typography.titleMedium)
                    Text("连接提示：${state.statusHint}")
                    Text("当前状态：${state.statusText}")
                    Text("证书指纹：${state.certFingerprint.ifBlank { "未获取" }}")
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Switch(
                            checked = state.showSystemMessages,
                            onCheckedChange = onToggleShowSystem
                        )
                        Text("显示系统消息")
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

private fun formatTime(tsMillis: Long): String {
    val formatter = DateTimeFormatter.ofPattern("HH:mm")
    return Instant.ofEpochMilli(tsMillis)
        .atZone(ZoneId.systemDefault())
        .toLocalTime()
        .format(formatter)
}
