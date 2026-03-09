package com.onlinemsg.client.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey val id: String,
    val role: String,
    val sender: String,
    val subtitle: String,
    val content: String,
    val channel: String,
    val timestampMillis: Long,
    val contentType: String,
    val audioBase64: String,
    val audioDurationMillis: Long
)
