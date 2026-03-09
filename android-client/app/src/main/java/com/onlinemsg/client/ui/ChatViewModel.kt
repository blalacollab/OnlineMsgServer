package com.onlinemsg.client.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel

/**
 * ViewModel 层，作为 UI 与 [ChatSessionManager] 的桥梁。
 * 初始化会话管理器并暴露其状态 and 事件流，同时提供所有用户操作的代理方法。
 * @param application Application 实例
 */
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    init {
        ChatSessionManager.initialize(application)
    }

    val uiState = ChatSessionManager.uiState
    val events = ChatSessionManager.events

    fun updateDisplayName(value: String) = ChatSessionManager.updateDisplayName(value)
    fun updateServerUrl(value: String) = ChatSessionManager.updateServerUrl(value)
    fun updateTargetKey(value: String) = ChatSessionManager.updateTargetKey(value)
    fun updateDraft(value: String) = ChatSessionManager.updateDraft(value)
    fun toggleDirectMode(enabled: Boolean) = ChatSessionManager.toggleDirectMode(enabled)
    fun toggleShowSystemMessages(show: Boolean) = ChatSessionManager.toggleShowSystemMessages(show)
    fun clearMessages() = ChatSessionManager.clearMessages()
    fun saveCurrentServerUrl() = ChatSessionManager.saveCurrentServerUrl()
    fun removeCurrentServerUrl() = ChatSessionManager.removeCurrentServerUrl()
    fun revealMyPublicKey() = ChatSessionManager.revealMyPublicKey()
    fun connect() = ChatSessionManager.connect()
    fun disconnect() = ChatSessionManager.disconnect()
    fun sendMessage() = ChatSessionManager.sendMessage()
    fun sendAudioMessage(audioBase64: String, durationMillis: Long) =
        ChatSessionManager.sendAudioMessage(audioBase64, durationMillis)
    fun onMessageCopied() = ChatSessionManager.onMessageCopied()

    fun updateTheme(themeId: String) = ChatSessionManager.updateTheme(themeId)
    fun updateUseDynamicColor(enabled: Boolean) = ChatSessionManager.updateUseDynamicColor(enabled)
    fun updateLanguage(language: String) = ChatSessionManager.updateLanguage(language)
}
