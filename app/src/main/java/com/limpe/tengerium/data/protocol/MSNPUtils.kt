package com.limpe.tengerium.data.protocol

import java.net.URLDecoder
import java.util.Locale

object MSNPUtils {
    /**
     * Нормализует email (нижний регистр + удаление пробелов).
     */
    fun normalizeEmail(email: String): String {
        return email.lowercase(Locale.ROOT).trim()
    }

    /**
     * Декодирует строку MSNObject из URL-encoded или возвращает как есть, если это XML.
     * Поддерживает множественное декодирование (иногда MSNP кодирует дважды).
     */
    fun decodeMsnObject(msnObject: String?): String? {
        if (msnObject.isNullOrEmpty()) return null
        
        var current = msnObject.trim()
        
        // Пытаемся декодировать до 3-х раз, пока не получим XML или пока строка не перестанет меняться
        for (i in 1..3) {
            if (current.startsWith("<msnobj", ignoreCase = true)) return current
            
            val decoded = try {
                URLDecoder.decode(current, "UTF-8")
            } catch (e: Exception) {
                // Если стандартный декодер упал, пробуем ручную замену базовых символов
                current.replace("%3C", "<")
                    .replace("%3E", ">")
                    .replace("%22", "\"")
                    .replace("%20", " ")
                    .replace("%2F", "/")
                    .replace("%3D", "=")
            }
            
            if (decoded == current) break
            current = decoded
        }
        
        return if (current.startsWith("<msnobj", ignoreCase = true)) current else null
    }
}
