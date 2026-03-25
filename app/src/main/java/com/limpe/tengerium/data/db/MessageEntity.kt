package com.limpe.tengerium.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Сущность сообщения в БД.
 */
@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ownerAccount: String, // Текущий аккаунт, которому принадлежит это сообщение
    val senderAccount: String,
    val receiverAccount: String,
    val encryptedText: String,
    val timestamp: Long,
    val isIncoming: Boolean,
    val isSent: Boolean = true,
    val error: String? = null,
    val isRead: Boolean = false
)
