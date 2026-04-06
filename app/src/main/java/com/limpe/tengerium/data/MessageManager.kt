package com.limpe.tengerium.data

import android.content.Context
import com.limpe.tengerium.R
import com.limpe.tengerium.data.db.AppDatabase
import com.limpe.tengerium.data.db.MessageEntity
import com.limpe.tengerium.data.security.SafeStorage
import com.limpe.tengerium.data.security.SecurePrefs
import com.limpe.tengerium.util.SoundUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.*
import java.util.concurrent.ConcurrentHashMap

// Главный по тарелочкам... то есть по сообщениям. Следит, чтобы всё шифровалось и в базу падало вовремя.
class MessageManager(
    private val context: Context,
    private val database: AppDatabase,
    private val scope: CoroutineScope,
    private val securePrefs: SecurePrefs
) {
    // Поток входящих сообщений. UI подписывается и радуется новым мессаджам.
    private val _incomingMessageFlow = MutableSharedFlow<IncomingMessage>(extraBufferCapacity = 10)
    val incomingMessageFlow = _incomingMessageFlow.asSharedFlow()

    // Счетчик косяков при отправке. Даем сообщению пару шансов, прежде чем ставить крест.
    private val messageRetryCounts = ConcurrentHashMap<Long, Int>()

    // Сохраняем мессадж и пуляем его в эфир.
    suspend fun saveAndEmitMessage(
        owner: String,
        target: String,
        text: String,
        isIncoming: Boolean,
        nickname: String = ""
    ): Long {
        // Прячем текст от любопытных глаз. Шифрование на базе SafeStorage.
        val encrypted = SafeStorage.encryptText(text)
        val entity = MessageEntity(
            ownerAccount = owner.lowercase(Locale.ROOT).trim(),
            senderAccount = if (isIncoming) target.lowercase(Locale.ROOT).trim() else "me",
            receiverAccount = if (isIncoming) "me" else target.lowercase(Locale.ROOT).trim(),
            encryptedText = encrypted,
            timestamp = System.currentTimeMillis(),
            isIncoming = isIncoming,
            isSent = isIncoming // Если пришло — значит уже успешно доставлено нам.
        )
        
        val id = database.messageDao().insertMessage(entity)
        
        // Если это нам — пуляем в поток. Звуки теперь в MSNPService для контроля фокуса.
        if (isIncoming) {
            _incomingMessageFlow.emit(IncomingMessage(target, nickname, text))
        }
        
        return id
    }

    // Обновляем статус сообщения в базе. Улетело оно или нет.
    fun updateMessageStatus(id: Long, isSent: Boolean, error: String? = null) {
        scope.launch {
            database.messageDao().updateMessageStatus(id, isSent, error)
        }
    }

    // Снимаем грех непрочитанности.
    fun markAsRead(owner: String, contact: String) {
        val normalizedOwner = owner.lowercase(Locale.ROOT).trim()
        val normalizedContact = contact.lowercase(Locale.ROOT).trim()
        scope.launch {
            database.messageDao().markAsRead(normalizedOwner, normalizedContact)
        }
    }

    // Всякие хелперы для счетчиков попыток.
    fun getRetryCount(messageId: Long): Int = messageRetryCounts[messageId] ?: 0
    fun incrementRetry(messageId: Long) { messageRetryCounts[messageId] = getRetryCount(messageId) + 1 }
    fun clearRetries(messageId: Long) { messageRetryCounts.remove(messageId) }

    data class IncomingMessage(val senderAccount: String, val nickname: String, val message: String)
}
