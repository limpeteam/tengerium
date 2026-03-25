package com.limpe.tengerium.data.security

import android.util.Log

/**
 * Объект для прозрачного шифрования данных перед сохранением.
 * Применяет концепцию "Safe Storage" для защиты сообщений.
 */
object SafeStorage {

    /**
     * Шифрует текст для хранения в БД.
     */
    fun encryptText(plainText: String): String {
        return try {
            EncryptionManager.encrypt(plainText)
        } catch (e: Throwable) {
            Log.e("SafeStorage", "Encryption error", e)
            // В случае ошибки не возвращаем текст в открытом виде, чтобы избежать утечки
            "ENCRYPTION_ERROR"
        }
    }

    /**
     * Расшифровывает текст для отображения в UI.
     */
    fun decryptText(encryptedText: String): String {
        if (encryptedText.isBlank()) return ""
        return try {
            EncryptionManager.decrypt(encryptedText)
        } catch (e: Throwable) {
            Log.e("SafeStorage", "Decryption error for: $encryptedText", e)
            "DECRYPTION_ERROR"
        }
    }
}