package com.onlinemsg.client.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel

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
    fun onMessageCopied() = ChatSessionManager.onMessageCopied()
}
