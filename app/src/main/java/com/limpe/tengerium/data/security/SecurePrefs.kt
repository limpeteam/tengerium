package com.limpe.tengerium.data.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Хранилище настроек. Чувствительные данные шифруются (AES-256), 
 * остальные хранятся в обычном SharedPreferences для быстрого доступа.
 */
class SecurePrefs(private val context: Context) {

    private val masterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val encryptedPrefs: SharedPreferences by lazy {
        EncryptedSharedPreferences.create(
            context,
            "secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private val plainPrefs: SharedPreferences = 
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    // --- Методы для работы с данными ---

    fun saveDatabasePassphrase(passphrase: String) {
        encryptedPrefs.edit().putString("db_passphrase", passphrase).apply()
    }

    fun getDatabasePassphrase(): String? = encryptedPrefs.getString("db_passphrase", null)

    fun saveCredentials(account: String, pass: String) {
        encryptedPrefs.edit()
            .putString("saved_account", account)
            .putString("saved_pass", pass)
            .apply()
    }

    fun getSavedAccount(): String? = encryptedPrefs.getString("saved_account", null)
    fun getSavedPassword(): String? = encryptedPrefs.getString("saved_pass", null)

    fun clearCredentials() {
        encryptedPrefs.edit()
            .remove("saved_account")
            .remove("saved_pass")
            .apply()
    }

    // --- OOBE & Nickname & MSNObject (Account specific) ---

    fun isOobeDone(account: String): Boolean = plainPrefs.getBoolean("oobe_done_$account", false)
    
    fun setOobeDone(account: String, done: Boolean) {
        plainPrefs.edit().putBoolean("oobe_done_$account", done).apply()
    }

    fun getNickname(account: String): String? = plainPrefs.getString("nickname_$account", null)
    
    fun setNickname(account: String, nickname: String) {
        plainPrefs.edit().putString("nickname_$account", nickname).apply()
    }

    fun getMsnObject(account: String): String? = plainPrefs.getString("msn_object_$account", null)

    fun setMsnObject(account: String, msnObject: String?) {
        plainPrefs.edit().putString("msn_object_$account", msnObject).apply()
    }

    // --- Collapsed Groups ---

    fun getCollapsedGroups(): Set<String> = plainPrefs.getStringSet("collapsed_groups", emptySet()) ?: emptySet()
    
    fun setCollapsedGroups(groups: Set<String>) {
        plainPrefs.edit().putStringSet("collapsed_groups", groups).apply()
    }

    // --- Обычные настройки (Plain) ---
    
    var serverAddress: String
        get() = plainPrefs.getString("server_address", "") ?: ""
        set(value) = plainPrefs.edit().putString("server_address", value).apply()

    var serverPort: Int
        get() = plainPrefs.getInt("server_port", 1863)
        set(value) = plainPrefs.edit().putInt("server_port", value).apply()

    var nexusDomain: String
        get() = plainPrefs.getString("nexus_domain", "") ?: ""
        set(value) = plainPrefs.edit().putString("nexus_domain", value).apply()

    var configUrl: String
        get() = plainPrefs.getString("config_url", "") ?: ""
        set(value) = plainPrefs.edit().putString("config_url", value).apply()

    var debugEnabled: Boolean
        get() = plainPrefs.getBoolean("debug_enabled", false)
        set(value) = plainPrefs.edit().putBoolean("debug_enabled", value).apply()

    var themePreset: Int
        get() = plainPrefs.getInt("theme_preset", 0)
        set(value) = plainPrefs.edit().putInt("theme_preset", value).apply()

    var chatBackgroundPath: String?
        get() = plainPrefs.getString("chat_bg_path", null)
        set(value) = plainPrefs.edit().putString("chat_bg_path", value).apply()

    var avatarPath: String?
        get() = plainPrefs.getString("avatar_path", null)
        set(value) = plainPrefs.edit().putString("avatar_path", value).apply()

    var useMaterialYou: Boolean
        get() = plainPrefs.getBoolean("use_material_you", true)
        set(value) = plainPrefs.edit().putBoolean("use_material_you", value).apply()

    var language: String
        get() = plainPrefs.getString("app_language", "system") ?: "system"
        set(value) = plainPrefs.edit().putString("app_language", value).apply()

    var showOnlineFirst: Boolean
        get() = plainPrefs.getBoolean("show_online_first", false)
        set(value) = plainPrefs.edit().putBoolean("show_online_first", value).apply()

    var privacyAllowOnlyFromList: Boolean
        get() = plainPrefs.getBoolean("privacy_only_from_list", false)
        set(value) = plainPrefs.edit().putBoolean("privacy_only_from_list", value).apply()

    // --- Новые настройки ---

    var showLinkPreview: Boolean
        get() = plainPrefs.getBoolean("show_link_preview", true)
        set(value) = plainPrefs.edit().putBoolean("show_link_preview", value).apply()

    var sendByEnter: Boolean
        get() = plainPrefs.getBoolean("send_by_enter", true)
        set(value) = plainPrefs.edit().putBoolean("send_by_enter", value).apply()

    var openChatsByDefault: Boolean
        get() = plainPrefs.getBoolean("open_chats_by_default", false)
        set(value) = plainPrefs.edit().putBoolean("open_chats_by_default", value).apply()

    var followSystemTheme: Boolean
        get() = plainPrefs.getBoolean("follow_system_theme", true)
        set(value) = plainPrefs.edit().putBoolean("follow_system_theme", value).apply()

    var darkTheme: Boolean
        get() = plainPrefs.getBoolean("dark_theme", false)
        set(value) = plainPrefs.edit().putBoolean("dark_theme", value).apply()

    var phoneStatusEnabled: Boolean
        get() = plainPrefs.getBoolean("phone_status_enabled", false)
        set(value) = plainPrefs.edit().putBoolean("phone_status_enabled", value).apply()

    // --- Уведомления ---

    var notifyMessages: Boolean
        get() = plainPrefs.getBoolean("notify_messages", true)
        set(value) = plainPrefs.edit().putBoolean("notify_messages", value).apply()

    var notifyLogin: Boolean
        get() = plainPrefs.getBoolean("notify_login", true)
        set(value) = plainPrefs.edit().putBoolean("notify_login", value).apply()

    var notifyNudge: Boolean
        get() = plainPrefs.getBoolean("notify_nudge", true)
        set(value) = plainPrefs.edit().putBoolean("notify_nudge", value).apply()

    var notifyAddedBy: Boolean
        get() = plainPrefs.getBoolean("notify_added_by", true)
        set(value) = plainPrefs.edit().putBoolean("notify_added_by", value).apply()

    var vibrationEnabled: Boolean
        get() = plainPrefs.getBoolean("vibration_enabled", true)
        set(value) = plainPrefs.edit().putBoolean("vibration_enabled", value).apply()
}
