package com.limpe.tengerium.data

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import com.limpe.tengerium.data.db.AppDatabase
import com.limpe.tengerium.data.db.ContactEntity
import com.limpe.tengerium.data.db.GroupEntity
import com.limpe.tengerium.data.protocol.*
import com.limpe.tengerium.data.protocol.v11.MSNP11NotificationManager
import com.limpe.tengerium.data.security.SafeStorage
import com.limpe.tengerium.data.security.SecurePrefs
import com.limpe.tengerium.domain.model.Contact
import com.limpe.tengerium.domain.model.Group
import com.limpe.tengerium.util.UiConstants
import com.limpe.tengerium.ui.AvatarUtils
import com.limpe.tengerium.util.NetworkObserver
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

    init {
        // Наблюдаем за состоянием сети глобально
        scope.launch {
            NetworkObserver(context.applicationContext).isConnected.collectLatest { isConnected ->
                updateNetworkState(isConnected)
            }
        }

        // Load initial data from DB if available
        scope.launch {
            val owner = getCurrentAccount() ?: return@launch
            
            // Collect groups first
            val initialGroups = database.groupDao().getGroupsSync(owner).map { 
                Group(it.guid, it.name, emptyList()) 
            }
            contactManager.setGroups(initialGroups)

            // Collect contacts
            val initialContacts = database.contactDao().getContactsSync(owner).map { entity ->
                Contact(
                    account = entity.account,
                    nickname = entity.nickname,
                    status = "FLN", // Always offline from DB
                    listType = entity.listType,
                    avatarUrl = entity.avatarUrl,
                    personalMessage = entity.personalMessage,
                    guid = entity.guid,
                    groupGuids = entity.groupGuids.split(",").filter { it.isNotEmpty() }
                )
            }
            contactManager.setContacts(initialContacts)
            
            // Re-sync groups with contact accounts
            val updatedGroups = initialGroups.map { g ->
                g.copy(contactAccounts = initialContacts.filter { it.groupGuids.contains(g.guid) }.map { it.account })
            }
            contactManager.setGroups(updatedGroups)
        }
    }

    fun updateNetworkState(isConnected: Boolean) {
        val wasConnected = lastKnownNetworkState
        lastKnownNetworkState = isConnected
        
        Log.d(TAG, "Network state updated: $isConnected (was $wasConnected), state=${_loginState.value}")
        
        if (!isConnected) {
            _loginState.value = MSNPLoginState.NoInternet
            nsManager.disconnect()
            reconnectJob?.cancel()
        } else if (!wasConnected) {
            // Network restored
            if (_loginState.value == MSNPLoginState.NoInternet) {
                _loginState.value = MSNPLoginState.Idle
                if (!userInitiatedLogout && getSavedAccount() != null) {
                    Log.d(TAG, "Network restored, auto-logging in...")
                    autoLogin()
                }
            }
        }
    }

    fun login(account: String, pass: String, remember: Boolean) {
        Log.d(TAG, "Manual login requested for $account")
        isCurrentSessionRemembered = remember
        if (remember) {
            securePrefs.saveCredentials(account, pass)
        }
        userInitiatedLogout = false
        
        // Сбрасываем очередь переподключения при ручном входе
        reconnectJob?.cancel()
        isReconnecting.set(false)
        reconnectAttempt.set(0)

        performLoginDude(account, pass)
    }

    fun autoLogin() {
        val acc = securePrefs.getSavedAccount()
        val pwd = securePrefs.getSavedPassword()
        if (acc != null && pwd != null) {
            Log.d(TAG, "Auto-login for $acc")
            performLoginDude(acc, pwd)
        } else {
            Log.d(TAG, "Auto-login skipped: no credentials")
        }
    }

    private fun performLoginDude(account: String, pass: String) {
        if (!lastKnownNetworkState) {
            Log.w(TAG, "Cannot login: no internet")
            _loginState.value = MSNPLoginState.NoInternet
            return
        }
        if (!isReconnecting.get()) {
            _loginState.value = MSNPLoginState.Loading
        }
        nsManager.connect(securePrefs.serverAddress, securePrefs.serverPort, account, pass, MSNPProto.getNexusUrl(securePrefs))
    }

    private fun startReconnectionLoop() {
        if (userInitiatedLogout || !lastKnownNetworkState || getSavedAccount() == null) {
            Log.d(TAG, "Reconnection loop skipped: userLogout=$userInitiatedLogout, hasNet=$lastKnownNetworkState, hasAcc=${getSavedAccount() != null}")
            return
        }

        if (isReconnecting.get()) {
            Log.d(TAG, "Reconnection loop already running")
            return
        }

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            isReconnecting.set(true)
            try {
                val attempt = reconnectAttempt.get()
                val delaySeconds = when (attempt) {
                    0 -> 3
                    1 -> 10
                    2 -> 30
                    3 -> 60
                    else -> 120
                }

                Log.d(TAG, "Reconnection attempt $attempt starting in $delaySeconds seconds")

                for (i in delaySeconds downTo 1) {
                    if (userInitiatedLogout || !lastKnownNetworkState) break
                    _loginState.value = MSNPLoginState.Reconnecting(i)
                    delay(1000)
                }

                if (!userInitiatedLogout && lastKnownNetworkState) {
                    reconnectAttempt.incrementAndGet()
                    autoLogin()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Reconnection loop error", e)
            } finally {
                isReconnecting.set(false)
            }
        }
    }

    fun logout() {
        Log.d(TAG, "Logout requested")
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
    
    fun markAsRead(contact: String) {
        messageManager.markAsRead(getCurrentAccount() ?: "", contact)
        MSNPService.cancelMessageNotification(context, contact)
    }

    suspend fun clearAllMessages() = withContext(Dispatchers.IO) {
        getCurrentAccount()?.let { owner ->
            database.messageDao().clearAllMessages(owner)
            try {
                database.openHelper.writableDatabase.execSQL("VACUUM")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to vacuum", e)
            }
        }
    }

    suspend fun getMessageCount(): Long = withContext(Dispatchers.IO) {
        getCurrentAccount()?.let { database.messageDao().getMessageCount(it) } ?: 0L
    }

    fun clearChatMessages(contact: String) {
        scope.launch {
            val owner = getCurrentAccount() ?: return@launch
            database.messageDao().clearChatMessages(owner, contact.lowercase(Locale.ROOT).trim())
        }
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
        saveContactsAndGroupsToDb()
    }

    fun removeContactFromGroup(account: String, contactGuid: String, groupGuid: String) {
        nsManager.removeContactFromGroup(contactGuid, groupGuid)
        contactManager.removeContactFromGroupLocally(account, groupGuid)
        saveContactsAndGroupsToDb()
    }

    fun inviteContactToChat(chatAccount: String, contactToInvite: String) {
        sessionManager.inviteToChat(chatAccount, contactToInvite)
    }

    private fun saveContactsAndGroupsToDb() {
        scope.launch {
            val owner = getCurrentAccount() ?: return@launch
            
            val entities = contacts.value.map { contact ->
                ContactEntity(
                    account = contact.account.lowercase(Locale.ROOT).trim(),
                    ownerAccount = owner,
                    nickname = contact.nickname,
                    listType = contact.listType,
                    avatarUrl = contact.avatarUrl,
                    personalMessage = contact.personalMessage,
                    guid = contact.guid,
                    groupGuids = contact.groupGuids.joinToString(",")
                )
            }
            database.contactDao().syncContacts(owner, entities)

            val groupEntities = groups.value.map { group ->
                GroupEntity(
                    guid = group.guid,
                    ownerAccount = owner,
                    name = group.name
                )
            }
            database.groupDao().syncGroups(owner, groupEntities)
        }
    }

    override fun onConnected() { 
        Log.d(TAG, "onConnected")
        reconnectAttempt.set(0) 
    }

    override fun onAuthSuccess(account: String, nickname: String) {
        Log.d(TAG, "onAuthSuccess for $account")
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
        val normalized = account.lowercase(Locale.ROOT).trim()
        val avatarFile = avatarManager.getAvatarFile(msnObject)
        val avatarPath = avatarFile?.absolutePath
        
        val cameOnline = contactManager.updateContactStatus(account, status, nickname, avatarPath)
        
        // Если контакт появился в сети, проверяем очередь сообщений
        if (cameOnline && !securePrefs.disableMessageQueue) {
            processPendingMessages(normalized)
        }
        
        // Обновляем наш собственный аватар и ник в стейте логина, если пришло обновление по нам
        if (normalized == currentAccount) {
            _loginState.update { state ->
                if (state is MSNPLoginState.Success) {
                    state.copy(
                        nickname = if (nickname.isNotEmpty()) nickname else state.nickname,
                        avatarUrl = avatarPath ?: state.avatarUrl
                    )
                } else state
            }
        }

        if (msnObject != null && avatarFile == null && status != MSNPProto.Status.OFFLINE) {
            avatarManager.requestAvatar(account, msnObject)
        }
    }

    private fun processPendingMessages(targetAccount: String) {
        scope.launch {
            val owner = getCurrentAccount() ?: return@launch
            val pending = database.messageDao().getUnsentMessagesForContact(owner, targetAccount)
            if (pending.isNotEmpty()) {
                Log.d(TAG, "Sending ${pending.size} pending messages to $targetAccount")
                pending.forEach { msg ->
                    val text = SafeStorage.decryptText(msg.encryptedText)
                    performSendMessageInternal(msg.id, targetAccount, text)
                }
            }
        }
    }

    override fun onContactAdded(account: String, nickname: String, listType: String, isInitial: Boolean) {
        contactManager.addOrUpdateContact(account, nickname, listType)
        saveContactsAndGroupsToDb()
    }

    override fun onContactRemoved(account: String, listType: String) {
        contactManager.removeContact(account)
        saveContactsAndGroupsToDb()
    }

    override fun onContactRenamed(account: String, newNickname: String) {
        contactManager.updateContactStatus(account, "", newNickname)
        saveContactsAndGroupsToDb()
    }

    override fun onPrivacySettingChanged(type: String, value: String) {}

    override fun onUrlReceived(urlType: String, url: String) {}

    override fun onMessageReceived(target: String, senderAccount: String, nickname: String, message: String) {
        scope.launch {
            messageManager.saveAndEmitMessage(getCurrentAccount() ?: "", senderAccount, message, true, nickname)
            MSNPService.showMessageNotification(context, nickname, message, senderAccount)
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
        scope.launch {
            if (message == "ERROR_LOGGED_IN_ANOTHER_DEVICE") {
                _loginState.value = MSNPLoginState.LoggedInElsewhere(message)
            } else {
                if (message == "AUTH_INVALID_PASSWORD" || message == "AUTH_INVALID_ACCOUNT" || message == "ERROR_AUTH_FAILED") {
                    _loginState.value = MSNPLoginState.Error(message)
                    return@launch
                }
                
                // Если мы пытались подключиться и получили ошибку - показываем её и пробуем снова (если не юзер вышел)
                _loginState.value = MSNPLoginState.Error(message)
                if (!userInitiatedLogout) {
                    startReconnectionLoop()
                }
            }
        }
    }

    override fun onDisconnected() {
        Log.d(TAG, "onDisconnected. userInitiatedLogout=$userInitiatedLogout, state=${_loginState.value}, acc=${getSavedAccount()}")
        if (!userInitiatedLogout) {
            val currentState = _loginState.value
            // Если мы были онлайн или в процессе подключения и дисконнектнулись - это ошибка соединения
            if (currentState is MSNPLoginState.Success || currentState is MSNPLoginState.Loading || currentState is MSNPLoginState.Reconnecting) {
                _loginState.value = MSNPLoginState.Error("CONNECTION_LOST")
                startReconnectionLoop()
            } else if (currentState is MSNPLoginState.Idle && getSavedAccount() != null) {
                // Если мы внезапно Idle, но есть аккаунт - пробуем переподключиться
                _loginState.value = MSNPLoginState.Error("DISCONNECTED_UNEXPECTEDLY")
                startReconnectionLoop()
            }
        } else {
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
        saveContactsAndGroupsToDb()
    }

    override fun onContactPersonalMessageChanged(account: String, psm: String) {
        contactManager.updateContactStatus(account, "", "", psm = psm)
        saveContactsAndGroupsToDb()
    }

    override fun onGroupAdded(name: String, guid: String) {
        contactManager.addGroup(name, guid)
        saveContactsAndGroupsToDb()
    }

    override fun onGroupRemoved(guid: String) {
        contactManager.removeGroup(guid)
        saveContactsAndGroupsToDb()
    }
}
