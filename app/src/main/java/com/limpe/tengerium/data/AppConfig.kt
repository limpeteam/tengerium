package com.limpe.tengerium.data

import android.content.Context
import android.content.pm.PackageManager

/**
 * Глобальная конфигурация функций приложения.
 */
object AppConfig {

    // Показывать ли Toast с сырыми логами протокола и отладочной информацией
    var showDebugToasts: Boolean = false


    // Включить отображение аватарок (отключите, если есть проблемы с отображением или производительностью)
    var enableAvatars: Boolean = true

    // Специальный режим LIMPE_EXP
    const val LIMPE_EXP = false

    // --- OOBE Configuration ---
    // Включение/выключение этапов первоначальной настройки
    var oobeWelcomeEnabled: Boolean = true
    var oobeNicknameEnabled: Boolean = false
    var oobeNotificationsEnabled: Boolean = true
    var oobePhoneStatusEnabled: Boolean = true

    /**
     * Возвращает кодовое имя клиента из метаданных манифеста.
     */
    fun getCodeName(context: Context): String {
        return try {
            val ai = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getApplicationInfo(context.packageName, PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
            }
            ai.metaData.getString("client_codename") ?: "Unknown"
        } catch (e: Exception) {
            "Unknown"
        }
    }

    /**
     * Возвращает ветку разработки из метаданных манифеста.
     */
    fun getBranch(context: Context): String {
        return try {
            val ai = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getApplicationInfo(context.packageName, PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
            }
            ai.metaData.getString("client_branch") ?: "Release"
        } catch (e: Exception) {
            "Release"
        }
    }
}
