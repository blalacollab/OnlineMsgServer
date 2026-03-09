package com.onlinemsg.client.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ChatMessageDao {
    @Query("SELECT * FROM chat_messages ORDER BY timestampMillis ASC")
    suspend fun listAll(): List<ChatMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: ChatMessageEntity)

    @Query(
        """
        DELETE FROM chat_messages
        WHERE id NOT IN (
            SELECT id
            FROM chat_messages
            ORDER BY timestampMillis DESC
            LIMIT :limit
        )
        """
    )
    suspend fun trimToLatest(limit: Int)

    @Query("DELETE FROM chat_messages")
    suspend fun clearAll()
}
