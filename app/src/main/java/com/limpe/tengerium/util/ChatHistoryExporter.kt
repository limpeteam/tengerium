package com.limpe.tengerium.util

import android.content.Context
import android.net.Uri
import com.limpe.tengerium.data.db.MessageEntity
import com.limpe.tengerium.data.security.SafeStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.*

/**
 * Утилита для экспорта истории чата в формате JSON.
 * Использует стандартный org.json, встроенный в Android.
 */
object ChatHistoryExporter {

    suspend fun exportChat(
        context: Context,
        uri: Uri,
        contactName: String?,
        contactEmail: String,
        myNickname: String?,
        myEmail: String,
        messages: List<MessageEntity>
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            
            val root = JSONObject()
            root.put("name", contactName ?: "")
            root.put("type", "personal_chat")
            root.put("hotmail", contactEmail)

            val messagesArray = JSONArray()
            messages.forEach { msg ->
                val msgObj = JSONObject()
                msgObj.put("id", msg.id)
                msgObj.put("type", "message")
                
                val date = Date(msg.timestamp)
                msgObj.put("date", dateFormat.format(date))
                msgObj.put("date_unixtime", msg.timestamp / 1000)
                
                // 'from' - имя отправителя, 'from_hotmail' - почта
                if (msg.isIncoming) {
                    msgObj.put("from", contactName ?: "")
                    msgObj.put("from_hotmail", contactEmail)
                } else {
                    msgObj.put("from", myNickname ?: "")
                    msgObj.put("from_hotmail", myEmail)
                }

                val decryptedText = try {
                    SafeStorage.decryptText(msg.encryptedText)
                } catch (_: Exception) {
                    "[Error decrypting]"
                }

                if (decryptedText == "[NUDGE]") {
                    msgObj.put("event", "NUDGE")
                } else {
                    msgObj.put("text", decryptedText)
                    val entities = JSONArray()
                    val entity = JSONObject()
                    entity.put("type", "plain")
                    entity.put("text", decryptedText)
                    entities.put(entity)
                    msgObj.put("text_entities", entities)
                }
                
                messagesArray.put(msgObj)
            }
            root.add("messages", messagesArray) // Исправлено: в JSONObject используется put, но в некоторых версиях/библиотеках может быть путаница. Для org.json это put.

            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                OutputStreamWriter(outputStream).use { writer ->
                    writer.write(root.toString(2))
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    // Вспомогательный метод для добавления массива, так как root.add может быть неочевиден
    private fun JSONObject.add(key: String, value: Any) {
        this.put(key, value)
    }
}
