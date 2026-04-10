package com.limpe.tengerium.util

/**
 * Глобальные константы интерфейса.
 */
object UiConstants {
    // Анимации
    const val ANIM_DURATION_SHORT = 200L
    const val ANIM_DURATION_MEDIUM = 300L
    const val ANIM_DURATION_LONG = 400L
    
    // Вибрация (Nudge)
    val NUDGE_VIBRATION_PATTERN = longArrayOf(0, 300, 50, 300, 50, 300, 50, 300)
    
    // Тайминги
    const val TYPING_NOTIFICATION_INTERVAL = 3000L
    const val NOTIFICATION_DISPLAY_DELAY = 2500L
    const val NUDGE_THROTTLE_TIMEOUT = 16000L
    
    // Цвета статусов (Hex)
    const val COLOR_STATUS_ONLINE = "#7fba00"
    const val COLOR_STATUS_AWAY = "#ffd004"
    const val COLOR_STATUS_BUSY = "#e81123"
    const val COLOR_STATUS_OFFLINE = "#93999d"
}
