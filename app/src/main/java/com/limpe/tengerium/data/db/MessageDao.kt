package com.limpe.tengerium.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Insert
    suspend fun insertMessage(message: MessageEntity): Long

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun getMessageById(id: Long): MessageEntity?

    @Query("SELECT * FROM messages WHERE ownerAccount = :owner AND (senderAccount = :contact OR receiverAccount = :contact) ORDER BY timestamp ASC")
    fun getMessagesForAccount(owner: String, contact: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE id IN (SELECT MAX(id) FROM messages WHERE ownerAccount = :owner GROUP BY CASE WHEN senderAccount = 'me' THEN receiverAccount ELSE senderAccount END) ORDER BY timestamp DESC")
    fun getRecentChats(owner: String): Flow<List<MessageEntity>>

    @Query("SELECT COUNT(*) FROM messages WHERE ownerAccount = :owner AND isIncoming = 1 AND isRead = 0")
    fun getTotalUnreadCount(owner: String): Flow<Int>

    @Query("SELECT COUNT(DISTINCT senderAccount) FROM messages WHERE ownerAccount = :owner AND isIncoming = 1 AND isRead = 0")
    fun getUnreadChatsCount(owner: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM messages WHERE ownerAccount = :owner AND senderAccount = :contact AND isRead = 0 AND isIncoming = 1")
    fun getUnreadCountForAccount(owner: String, contact: String): Flow<Int>

    @Query("UPDATE messages SET isRead = 1 WHERE ownerAccount = :owner AND senderAccount = :contact AND isRead = 0")
    suspend fun markAsRead(owner: String, contact: String)

    @Query("UPDATE messages SET isSent = :isSent, error = :error WHERE id = :id")
    suspend fun updateMessageStatus(id: Long, isSent: Boolean, error: String?)

    @Query("SELECT * FROM messages WHERE ownerAccount = :owner AND isIncoming = 0 AND isSent = 0 AND error IS NULL")
    suspend fun getUnsentMessages(owner: String): List<MessageEntity>

    @Query("DELETE FROM messages WHERE ownerAccount = :owner")
    suspend fun clearAllMessages(owner: String)
}
