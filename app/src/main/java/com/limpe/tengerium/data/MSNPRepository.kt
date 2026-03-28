package com.limpe.tengerium.data

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import com.limpe.tengerium.data.db.AppDatabase
import com.limpe.tengerium.data.protocol.*
import com.limpe.tengerium.data.protocol.v11.MSNP11NotificationManager
import com.limpe.tengerium.data.security.SafeStorage
import com.limpe.tengerium.data.security.SecurePrefs
import com.limpe.tengerium.util.UiConstants
import com.limpe.tengerium.ui.AvatarUtils
import com.limpe.tengerium.util.SoundUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import uniffi.msnp11_sdk.Config
import java.io.File
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MSNPRepository @Inject constructor(val context: Context) : MSNPProtocolListener {

    companion object {
        private const val TAG = "MSNPRepository"
    }

    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Internal Managers
    private val securePrefs = SecurePrefs(context)
    private val database = AppDatabase.getInstance(context, securePrefs.getDatabasePassphrase()?.toByteArray() ?: ByteArray(0))
    private val contactManager = ContactManager()
    private val nsManager: IMSNPNotificationManager = MSNP11NotificationManager(this, scope)
    private val sessionManager = SessionManager(nsManager, scope, this, context)
    private val messageManager = MessageManager(context, database, scope, securePrefs)
    private val avatarManager = AvatarManager(context, scope, sessionManager)
    
    val avatarLibraryManager = AvatarLibraryManager(context, database, avatarManager, scope)

    private val _loginState = MutableStateFlow<MSNPLoginState>(MSNPLoginState.Idle)
    val loginState: StateFlow<MSNPLoginState> = _loginState.asStateFlow()

    // Exposed properties and flows for UI
    val contacts = contactManager.contacts
    val pendingRequests = contactManager.pendingRequests
    val groups = contactManager.groups
    val incomingMessageFlow = messageManager.incomingMessageFlow
    val contactAddedFlow = contactManager.contactAddedFlow
    
    val myStatus = contactManager.myStatus
    val myPersonalMessage = contactManager.myPersonalMessage
    val currentStatus: String get() = contactManager.myStatus.value

    private var currentAccount: String? = null
    private var userInitiatedLogout = false
    private val isReconnecting = AtomicBoolean(false)
    private val reconnectAttempt = AtomicInteger(0)
    private var reconnectJob: Job? = null
    
    private val _config = MutableStateFlow<Config?>(null)
    val config: StateFlow<Config?> = _config.asStateFlow()

    private val _nudgeCooldowns = MutableStateFlow<Map<String, Int>>(emptyMap())
    val nudgeCooldowns: StateFlow<Map<String, Int>> = _nudgeCooldowns.asStateFlow()

    private val lastNudgeSent = ConcurrentHashMap<String, Long>()
    private val lastTypingSent = ConcurrentHashMap<String, Long>()
    
    var activeChatAccount: String? = null
    private var lastKnownNetworkState = true
    private var isCurrentSessionRemembered = false

    fun updateNetworkState(isConnected: Boolean) {
        lastKnownNetworkState = isConnected
        if (!isConnected) {
            _loginState.value = MSNPLoginState.NoInternet
            nsManager.disconnect()
        }
    }

    fun login(account: String, pass: String, remember: Boolean) {
        isCurrentSessionRemembered = remember
        if (remember) {
            securePrefs.saveCredentials(account, pass)
        }
        userInitiatedLogout = false
        performLoginDude(account, pass)
    }

    fun autoLogin() {
        val acc = securePrefs.getSavedAccount()
        val pwd = securePrefs.getSavedPassword()
        if (acc != null && pwd != null) {
            login(acc, pwd, true)
        }
    }

    private fun performLoginDude(account: String, pass: String) {
        if (!lastKnownNetworkState) {
            _loginState.value = MSNPLoginState.NoInternet
            return
        }
        if (!isReconnecting.get()) _loginState.value = MSNPLoginState.Loading
        nsManager.connect(securePrefs.serverAddress, securePrefs.serverPort, account, pass, MSNPProto.getNexusUrl(securePrefs))
    }

    fun logout() {
        userInitiatedLogout = true
        isReconnecting.set(false)
        reconnectAttempt.set(0)
        reconnectJob?.cancel()
        nsManager.disconnect()
        sessionManager.disconnectAll()
        _loginState.value = MSNPLoginState.Idle
        contactManager.setAllOffline()
    }

    fun setStatus(status: String) {
        contactManager.updateMyStatus(status)
        nsManager.changeStatus(status)
    }

    fun changeStatus(status: String) = setStatus(status)

    fun sendMessage(target: String, text: String) {
        scope.launch {
            val account = getCurrentAccount() ?: ""
            val id = messageManager.saveAndEmitMessage(account, target, text, false)
            performSendMessageInternal(id, target, text)
        }
    }

    fun resendMessage(messageId: Long) {
        scope.launch {
            val msg = database.messageDao().getMessageById(messageId) ?: return@launch
            val text = SafeStorage.decryptText(msg.encryptedText)
            messageManager.updateMessageStatus(messageId, false, null)
            performSendMessageInternal(messageId, msg.receiverAccount, text)
        }
    }

    private fun performSendMessageInternal(id: Long, target: String, text: String) {
        scope.launch {
            try {
                val normalizedTarget = target.lowercase(Locale.ROOT).trim()
                val contact = contacts.value.find { it.account.lowercase(Locale.ROOT).trim() == normalizedTarget }
                if (contact == null || contact.status == MSNPProto.Status.OFFLINE) {
                    if (securePrefs.disableMessageQueue) messageManager.updateMessageStatus(id, false, "Offline")
                    return@launch
                }
                val session = sessionManager.getOrOpenSession(normalizedTarget)
                if (session != null) session.sendMessage(text, id)
                else if (securePrefs.disableMessageQueue) messageManager.updateMessageStatus(id, false, "SB Failed")
            } catch (e: Exception) {
                if (securePrefs.disableMessageQueue) messageManager.updateMessageStatus(id, false, e.message)
            }
        }
    }

    fun sendNudge(target: String) {
        val normalizedTarget = target.lowercase(Locale.ROOT).trim()
        val now = System.currentTimeMillis()
        if (now - (lastNudgeSent[normalizedTarget] ?: 0L) < UiConstants.NUDGE_THROTTLE_TIMEOUT) return
        lastNudgeSent[normalizedTarget] = now
        scope.launch {
            val totalSeconds = (UiConstants.NUDGE_THROTTLE_TIMEOUT / 1000).toInt()
            for (i in totalSeconds downTo 0) {
                _nudgeCooldowns.update { it + (normalizedTarget to i) }
                if (i > 0) delay(1000)
            }
            _nudgeCooldowns.update { it - normalizedTarget }
        }
        scope.launch {
            val account = getCurrentAccount() ?: ""
            val id = messageManager.saveAndEmitMessage(account, normalizedTarget, "[NUDGE]", false)
            sessionManager.getOrOpenSession(normalizedTarget)?.sendNudge(id) ?: messageManager.updateMessageStatus(id, false, "SB Failed")
        }
    }

    fun sendTyping(target: String) {
        val normalizedTarget = target.lowercase(Locale.ROOT).trim()
        val now = System.currentTimeMillis()
        if (now - (lastTypingSent[normalizedTarget] ?: 0L) < 5000) return 
        lastTypingSent[normalizedTarget] = now
        scope.launch { sessionManager.getOrOpenSession(normalizedTarget)?.sendTyping() }
    }

    fun getMessages(contact: String) = database.messageDao().getMessagesForAccount(getCurrentAccount() ?: "", contact.lowercase(Locale.ROOT).trim())
    fun getRecentChats() = database.messageDao().getRecentChats(getCurrentAccount() ?: "")
    fun getUnreadChatsCount() = database.messageDao().getUnreadChatsCount(getCurrentAccount() ?: "")
    fun getUnreadCountForAccount(contact: String) = database.messageDao().getUnreadCountForAccount(getCurrentAccount() ?: "", contact.lowercase(Locale.ROOT).trim())
    fun markAsRead(contact: String) = messageManager.markAsRead(getCurrentAccount() ?: "", contact)

    fun clearAllMessages() {
        scope.launch { getCurrentAccount()?.let { database.messageDao().clearAllMessages(it) } }
    }

    fun setPrivacyMode(allowOnlyFromList: Boolean) = nsManager.setPrivacyMode(allowOnlyFromList)
    
    fun updateNickname(newName: String) {
        nsManager.updateNickname(newName)
        val acc = getCurrentAccount() ?: return
        if (isCurrentSessionRemembered) securePrefs.setNickname(acc.lowercase(Locale.ROOT).trim(), newName)
        _loginState.update { if (it is MSNPLoginState.Success) it.copy(nickname = newName) else it }
    }

    fun updatePersonalMessage(psm: String) {
        nsManager.updatePersonalMessage(psm)
        contactManager.updateMyPersonalMessage(psm)
    }
    
    fun addContact(account: String) = nsManager.addContact(account)
    fun removeContact(account: String, mask: Int = MSNPProto.List.FL) = nsManager.removeContact(account, mask)
    fun blockContact(account: String) = nsManager.blockContact(account)
    fun unblockContact(account: String) = nsManager.unblockContact(account)

    fun acceptRequest(account: String) { addContact(account); contactManager.clearPendingRequest(account) }
    fun declineRequest(account: String) { contactManager.clearPendingRequest(account) }

    fun getCurrentAccount(): String? = currentAccount ?: securePrefs.getSavedAccount()
    fun getSavedAccount(): String? = securePrefs.getSavedAccount()
    fun getSavedPassword(): String? = securePrefs.getSavedPassword()
    
    fun updateAvatar(filePath: String) {
        scope.launch {
            val bytes = when {
                filePath.startsWith("std_") -> AvatarUtils.prepareAvatarFromRes(context, context.resources.getIdentifier(filePath.removePrefix("std_"), "drawable", context.packageName))
                filePath.startsWith("/") -> AvatarUtils.prepareAvatarFromFile(File(filePath))
                else -> AvatarUtils.prepareAvatar(context, filePath.toUri())
            } ?: return@launch
            performUpdateAvatarInternal(getCurrentAccount() ?: "", bytes)
        }
    }

    fun updateAvatar(bytes: ByteArray) {
        scope.launch { performUpdateAvatarInternal(getCurrentAccount() ?: "", bytes) }
    }

    private suspend fun performUpdateAvatarInternal(acc: String, bytes: ByteArray) {
        val normalized = acc.lowercase(Locale.ROOT).trim()
        nsManager.updateAvatar(bytes)
        val path = avatarManager.saveOwnAvatar(bytes)
        securePrefs.setAvatarPath(normalized, path)
        _loginState.update { if (it is MSNPLoginState.Success) it.copy(avatarUrl = path) else it }
    }

    // Groups management
    fun createGroup(name: String) = nsManager.createGroup(name)
    fun deleteGroup(guid: String) = nsManager.deleteGroup(guid)
    
    fun addContactToGroup(account: String, contactGuid: String, groupGuid: String) {
        nsManager.addContactToGroup(contactGuid, groupGuid)
        contactManager.addContactToGroupLocally(account, groupGuid)
    }

    fun removeContactFromGroup(account: String, contactGuid: String, groupGuid: String) {
        nsManager.removeContactFromGroup(contactGuid, groupGuid)
        contactManager.removeContactFromGroupLocally(account, groupGuid)
    }

    fun inviteContactToChat(chatAccount: String, contactToInvite: String) {
        sessionManager.inviteToChat(chatAccount, contactToInvite)
    }

    override fun onConnected() { reconnectAttempt.set(0) }

    override fun onAuthSuccess(account: String, nickname: String) {
        val normalized = account.lowercase(Locale.ROOT).trim()
        currentAccount = normalized
        reconnectAttempt.set(0)
        val bestNickname = if (nickname.isEmpty() || nickname == account) securePrefs.getNickname(normalized) ?: account else nickname
        
        // 1. Ищем сохраненный аватар
        val savedAvatarPath = securePrefs.getAvatarPath(normalized)
        val avatarFile = savedAvatarPath?.let { File(it) }?.takeIf { it.exists() && it.length() > 0 } ?: avatarManager.getAvatarFile(securePrefs.getMsnObject(normalized))
        
        _loginState.value = MSNPLoginState.Success(account, bestNickname, avatarUrl = avatarFile?.absolutePath)
        
        // 2. ВАЖНО: Если у нас есть файл аватара, нужно отправить его байты в SDK при входе
        avatarFile?.let { file ->
            scope.launch {
                val bytes = file.readBytes()
                nsManager.updateAvatar(bytes)
            }
        }
        
        // 3. Обновляем статус
        setStatus(MSNPProto.Status.ONLINE)
    }

    override fun onContactStatusChanged(account: String, status: String, nickname: String, isInitial: Boolean, msnObject: String?) {
        val avatarFile = avatarManager.getAvatarFile(msnObject)
        contactManager.updateContactStatus(account, status, nickname, avatarFile?.absolutePath)
        if (msnObject != null && avatarFile == null && status != MSNPProto.Status.OFFLINE) {
            avatarManager.requestAvatar(account, msnObject)
        }
    }

    override fun onContactAdded(account: String, nickname: String, listType: String, isInitial: Boolean) {
        contactManager.addOrUpdateContact(account, nickname, listType)
    }

    override fun onContactRemoved(account: String, listType: String) {
        contactManager.removeContact(account)
    }

    override fun onContactRenamed(account: String, newNickname: String) {
        contactManager.updateContactStatus(account, "", newNickname)
    }

    override fun onPrivacySettingChanged(type: String, value: String) {}

    override fun onUrlReceived(urlType: String, url: String) {}

    override fun onMessageReceived(target: String, senderAccount: String, nickname: String, message: String) {
        scope.launch {
            messageManager.saveAndEmitMessage(getCurrentAccount() ?: "", senderAccount, message, true, nickname)
        }
    }

    override fun onSwitchboardRequest(host: String, port: Int, key: String, sessionId: String?, callerAccount: String?, callerNickname: String?, transactionId: Int?) {
        sessionManager.handleSwitchboardRequest(host, port, key, sessionId, callerAccount, transactionId, getCurrentAccount() ?: "")
    }

    override fun onSwitchboardIdentified(sessionId: String, account: String) {
        sessionManager.identifySession(sessionId, account)
    }

    override fun onSwitchboardDisconnected(account: String) {
        sessionManager.removeSession(account)
    }

    override fun onError(message: String) {
        Log.e(TAG, "Protocol Error: $message")
    }

    override fun onDisconnected() {
        if (!userInitiatedLogout) {
            _loginState.value = MSNPLoginState.Idle
        }
        contactManager.setAllOffline()
    }

    override fun onTypingReceived(account: String) {
        contactManager.setTyping(account, true)
        scope.launch { delay(5000); contactManager.setTyping(account, false) }
    }

    override fun onMessageSent(messageId: Long) {
        messageManager.updateMessageStatus(messageId, true)
    }

    override fun onMessageFailed(messageId: Long, error: String?) {
        messageManager.updateMessageStatus(messageId, false, error)
    }

    override fun onRawLog(direction: String, content: String) {}

    override fun onConfigReceived(config: Config) {
        _config.value = config
    }

    override fun onContactFullInfo(account: String, nickname: String, guid: String, listTypes: String, groupGuids: List<String>) {
        contactManager.updateContactFullInfo(account, nickname, guid, listTypes, groupGuids)
    }

    override fun onContactPersonalMessageChanged(account: String, psm: String) {
        contactManager.updateContactStatus(account, "", "", psm = psm)
    }

    override fun onGroupAdded(name: String, guid: String) {
        contactManager.addGroup(name, guid)
    }

    override fun onGroupRemoved(guid: String) {
        contactManager.removeGroup(guid)
    }
}
