package com.onlinemsg.client.data.local

import com.onlinemsg.client.ui.MessageChannel
import com.onlinemsg.client.ui.MessageContentType
import com.onlinemsg.client.ui.MessageRole
import com.onlinemsg.client.ui.UiMessage

class ChatHistoryRepository(private val messageDao: ChatMessageDao) {
    suspend fun loadMessages(limit: Int): List<UiMessage> {
        return messageDao.listAll()
            .asSequence()
            .mapNotNull { entity -> entity.toUiMessageOrNull() }
            .toList()
            .takeLast(limit)
    }

    suspend fun appendMessage(message: UiMessage, limit: Int) {
        messageDao.upsert(message.toEntity())
        messageDao.trimToLatest(limit)
    }

    suspend fun clearAll() {
        messageDao.clearAll()
    }
}

private fun UiMessage.toEntity(): ChatMessageEntity {
    return ChatMessageEntity(
        id = id,
        role = role.name,
        sender = sender,
        subtitle = subtitle,
        content = content,
        channel = channel.name,
        timestampMillis = timestampMillis,
        contentType = contentType.name,
        audioBase64 = audioBase64,
        audioDurationMillis = audioDurationMillis
    )
}

private fun ChatMessageEntity.toUiMessageOrNull(): UiMessage? {
    val parsedRole = runCatching { MessageRole.valueOf(role) }.getOrNull() ?: return null
    val parsedChannel = runCatching { MessageChannel.valueOf(channel) }.getOrNull() ?: return null
    val parsedContentType = runCatching { MessageContentType.valueOf(contentType) }.getOrNull()
        ?: MessageContentType.TEXT
    return UiMessage(
        id = id,
        role = parsedRole,
        sender = sender,
        subtitle = subtitle,
        content = content,
        channel = parsedChannel,
        timestampMillis = timestampMillis,
        contentType = parsedContentType,
        audioBase64 = audioBase64,
        audioDurationMillis = audioDurationMillis
    )
}
