package com.limpe.tengerium.data.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

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

    private val _prefsChangedFlow = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val prefsChangedFlow = _prefsChangedFlow.asSharedFlow()

    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null) {
            _prefsChangedFlow.tryEmit(key)
        }
    }

    init {
        plainPrefs.registerOnSharedPreferenceChangeListener(preferenceListener)
    }

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

    fun getPinnedChats(): Set<String> = plainPrefs.getStringSet("pinned_chats", emptySet()) ?: emptySet()
    
    fun setPinnedChats(chats: Set<String>) {
        plainPrefs.edit().putStringSet("pinned_chats", chats).apply()
        _prefsChangedFlow.tryEmit("pinned_chats")
    }

    fun isChatPinned(account: String): Boolean = getPinnedChats().contains(account.lowercase())

    fun togglePinChat(account: String) {
        val pinned = getPinnedChats().toMutableSet()
        val normalized = account.lowercase()
        if (pinned.contains(normalized)) pinned.remove(normalized) else pinned.add(normalized)
        setPinnedChats(pinned)
    }

    fun getMutedChats(): Set<String> = plainPrefs.getStringSet("muted_chats", emptySet()) ?: emptySet()
    
    fun setMutedChats(chats: Set<String>) {
        plainPrefs.edit().putStringSet("muted_chats", chats).apply()
        _prefsChangedFlow.tryEmit("muted_chats")
    }

    fun isChatMuted(account: String): Boolean = getMutedChats().contains(account.lowercase())

    fun toggleMuteChat(account: String) {
        val muted = getMutedChats().toMutableSet()
        val normalized = account.lowercase()
        if (muted.contains(normalized)) muted.remove(normalized) else muted.add(normalized)
        setMutedChats(muted)
    }

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

    fun getAvatarPath(account: String): String? = plainPrefs.getString("avatar_path_$account", null)

    fun setAvatarPath(account: String, path: String?) {
        plainPrefs.edit().putString("avatar_path_$account", path).apply()
    }

    fun getCollapsedGroups(): Set<String> = plainPrefs.getStringSet("collapsed_groups", emptySet()) ?: emptySet()
    
    fun setCollapsedGroups(groups: Set<String>) {
        plainPrefs.edit().putStringSet("collapsed_groups", groups).apply()
    }

    // Позволяет отслеживать версию, на которой пользователь был в последний раз
    var lastUsedVersionCode: Int
        get() = plainPrefs.getInt("last_version_code", 0)
        set(value) = plainPrefs.edit().putInt("last_version_code", value).apply()

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
        get() = plainPrefs.getBoolean("show_online_first", true)
        set(value) = plainPrefs.edit().putBoolean("show_online_first", value).apply()

    var privacyAllowOnlyFromList: Boolean
        get() = plainPrefs.getBoolean("privacy_only_from_list", false)
        set(value) = plainPrefs.edit().putBoolean("privacy_only_from_list", value).apply()

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

    var rememberMe: Boolean
        get() = plainPrefs.getBoolean("remember_me", false)
        set(value) = plainPrefs.edit().putBoolean("remember_me", value).apply()

    var disableMessageQueue: Boolean
        get() = plainPrefs.getBoolean("disable_message_queue", false)
        set(value) = plainPrefs.edit().putBoolean("disable_message_queue", value).apply()

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

    var chatSoundsEnabled: Boolean
        get() = plainPrefs.getBoolean("chat_sounds_enabled", true)
        set(value) = plainPrefs.edit().putBoolean("chat_sounds_enabled", value).apply()
}
