package com.limpe.tengerium.data

import android.content.Context
import android.util.Log
import com.limpe.tengerium.data.db.AppDatabase
import com.limpe.tengerium.data.db.MessageEntity
import com.limpe.tengerium.data.protocol.IMSNPNotificationManager
import com.limpe.tengerium.data.protocol.MSNPProto
import com.limpe.tengerium.data.protocol.MSNPProtocolListener
import com.limpe.tengerium.data.protocol.MSNPService
import com.limpe.tengerium.data.protocol.v11.MSNP11NotificationManager
import com.limpe.tengerium.data.security.SafeStorage
import com.limpe.tengerium.data.security.SecurePrefs
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import uniffi.msnp11_sdk.Config
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class MSNPRepository(
    val context: Context
) : MSNPProtocolListener {

    private val securePrefs = SecurePrefs(context)
    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val database: AppDatabase = run {
        val passphrase = securePrefs.getDatabasePassphrase() ?: UUID.randomUUID().toString().also { 
            securePrefs.saveDatabasePassphrase(it) 
        }
        AppDatabase.getInstance(context, passphrase.toByteArray())
    }
    
    private val nsManager: IMSNPNotificationManager = MSNP11NotificationManager(this, scope)

    private val contactManager = ContactManager()
    private val messageManager = MessageManager(context, database, scope, securePrefs)
    private val sessionManager = SessionManager(nsManager, scope, this, context)
    private val avatarManager = AvatarManager(context, scope, sessionManager)

    private val isReconnecting = AtomicBoolean(false)
    private var userInitiatedLogout = false
    private var reconnectJob: Job? = null
    private var currentAccount: String? = null
    
    private val lastTypingSent = ConcurrentHashMap<String, Long>()
    private val typingTimers = ConcurrentHashMap<String, Job>()

    var activeChatAccount: String? = null

    val contacts = contactManager.contacts
    val groups = contactManager.groups
    val pendingRequests = contactManager.pendingRequests
    val myStatus = contactManager.myStatus
    val myPersonalMessage = contactManager.myPersonalMessage
    val contactAddedFlow = contactManager.contactAddedFlow

    private val _loginState = MutableStateFlow<MSNPLoginState>(MSNPLoginState.Idle)
    val loginState: StateFlow<MSNPLoginState> = _loginState.asStateFlow()

    private val _config = MutableStateFlow<Config?>(null)
    val config: StateFlow<Config?> = _config.asStateFlow()

    private val _eventFlow = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val eventFlow = _eventFlow.asSharedFlow()

    val incomingMessageFlow: SharedFlow<MessageManager.IncomingMessage> by lazy { messageManager.incomingMessageFlow }

    val currentStatus: String get() = myStatus.value

    init {
        // Наблюдатель за контактами для автоматической досылки сообщений
        scope.launch {
            contacts.collectLatest { list ->
                val onlineAccounts = list.filter { it.status != MSNPProto.Status.OFFLINE }
                    .map { it.account.lowercase(Locale.ROOT).trim() }
                
                if (onlineAccounts.isNotEmpty()) {
                    syncPendingMessagesForOnlineUsers(onlineAccounts)
                }
            }
        }
    }

    private suspend fun syncPendingMessagesForOnlineUsers(onlineAccounts: List<String>) {
        val myAcc = getCurrentAccount() ?: return
        val unsent = database.messageDao().getUnsentMessages(myAcc)
        
        unsent.groupBy { it.receiverAccount.lowercase(Locale.ROOT).trim() }
            .forEach { (target, messages) ->
                if (onlineAccounts.contains(target)) {
                    Log.d("MSNP-Repo", "Found ${messages.size} pending messages for $target who is now online")
                    messages.forEach { msg ->
                        val text = SafeStorage.decryptText(msg.encryptedText)
                        performSendMessageInternal(msg.id, target, text)
                        delay(200) 
                    }
                }
            }
    }

    fun login(account: String, pass: String, rememberMe: Boolean = true) {
        val normalized = account.lowercase(Locale.ROOT).trim()
        currentAccount = normalized
        userInitiatedLogout = false
        if (rememberMe) securePrefs.saveCredentials(normalized, pass)
        else securePrefs.clearCredentials()
        contactManager.clear()
        _config.value = null
        isReconnecting.set(false)
        reconnectJob?.cancel()
        performLoginDude(normalized, pass)
    }

    fun autoLogin() {
        val acc = securePrefs.getSavedAccount()
        val pwd = securePrefs.getSavedPassword()
        if (acc != null && pwd != null) login(acc, pwd, true)
    }

    private fun performLoginDude(account: String, pass: String) {
        _loginState.value = if (isReconnecting.get()) MSNPLoginState.Reconnecting else MSNPLoginState.Loading
        nsManager.connect(
            securePrefs.serverAddress, 
            securePrefs.serverPort, 
            account, 
            pass, 
            MSNPProto.getNexusUrl(securePrefs)
        )
    }

    fun logout() {
        userInitiatedLogout = true
        isReconnecting.set(false)
        reconnectJob?.cancel()
        nsManager.disconnect()
        sessionManager.disconnectAll()
        _loginState.value = MSNPLoginState.Idle
        _config.value = null
        contactManager.setAllOffline()
    }

    fun setStatus(status: String) {
        contactManager.updateMyStatus(status)
        nsManager.changeStatus(status, securePrefs.getMsnObject(getCurrentAccount() ?: ""))
    }

    fun changeStatus(status: String) = setStatus(status)

    fun sendMessage(target: String, text: String) {
        scope.launch {
            val account = getCurrentAccount() ?: ""
            val id = messageManager.saveAndEmitMessage(account, target, text, false)
            performSendMessageInternal(id, target, text)
        }
    }

    private fun performSendMessageInternal(id: Long, target: String, text: String) {
        scope.launch {
            try {
                val normalizedTarget = target.lowercase(Locale.ROOT).trim()
                
                // Проверяем статус контакта перед попыткой. Если оффлайн - даже не дергаем сессию.
                val contact = contacts.value.find { it.account.lowercase(Locale.ROOT).trim() == normalizedTarget }
                if (contact == null || contact.status == MSNPProto.Status.OFFLINE) {
                    Log.d("MSNP-Repo", "Target $target is offline, message $id stays pending")
                    return@launch
                }

                val session = sessionManager.getOrOpenSession(normalizedTarget)
                if (session != null) {
                    session.sendMessage(text, id)
                } else {
                    Log.d("MSNP-Repo", "Session for $target is not ready yet, message $id stays pending")
                }
            } catch (e: Exception) {
                // В случае критической ошибки (не таймаут открытия сессии) - можно пометить ошибкой.
                // Но для "pending" лучше просто оставить как есть.
                Log.e("MSNP-Repo", "Error sending message $id: ${e.message}")
            }
        }
    }

    private fun syncUnsentMessages() {
        val account = getCurrentAccount() ?: return
        scope.launch {
            val unsent = database.messageDao().getUnsentMessages(account)
            if (unsent.isNotEmpty()) {
                Log.d("MSNP-Repo", "Syncing ${unsent.size} unsent messages")
                unsent.forEach { msg ->
                    val target = msg.receiverAccount
                    val text = SafeStorage.decryptText(msg.encryptedText)
                    performSendMessageInternal(msg.id, target, text)
                }
            }
        }
    }

    fun sendNudge(target: String) {
        scope.launch {
            val account = getCurrentAccount() ?: ""
            val normalizedTarget = target.lowercase(Locale.ROOT).trim()
            val id = messageManager.saveAndEmitMessage(account, normalizedTarget, "[NUDGE]", false)
            try {
                val session = sessionManager.getOrOpenSession(normalizedTarget)
                if (session != null) {
                    session.sendNudge(id)
                } else {
                    messageManager.updateMessageStatus(id, false, "Session failed")
                }
            } catch (e: Exception) {
                messageManager.updateMessageStatus(id, false, e.message)
            }
        }
    }

    fun sendTyping(target: String) {
        val normalizedTarget = target.lowercase(Locale.ROOT).trim()
        if (normalizedTarget.isEmpty()) return

        val now = System.currentTimeMillis()
        val lastSent = lastTypingSent[normalizedTarget] ?: 0L
        
        if (now - lastSent < 5000) return 
        lastTypingSent[normalizedTarget] = now
        
        scope.launch {
            val session = sessionManager.getOrOpenSession(normalizedTarget)
            session?.sendTyping()
        }
    }

    fun getMessages(contact: String): Flow<List<MessageEntity>> {
        return database.messageDao().getMessagesForAccount(getCurrentAccount() ?: "", contact.lowercase(Locale.ROOT).trim())
    }

    fun getRecentChats(): Flow<List<MessageEntity>> {
        return database.messageDao().getRecentChats(getCurrentAccount() ?: "")
    }

    fun getUnreadChatsCount(): Flow<Int> {
        return database.messageDao().getUnreadChatsCount(getCurrentAccount() ?: "")
    }

    fun getUnreadCountForAccount(contact: String): Flow<Int> {
        return database.messageDao().getUnreadCountForAccount(getCurrentAccount() ?: "", contact.lowercase(Locale.ROOT).trim())
    }

    fun markAsRead(contact: String) {
        messageManager.markAsRead(getCurrentAccount() ?: "", contact)
    }

    fun clearAllMessages() {
        scope.launch {
            val account = getCurrentAccount()
            if (account != null) {
                database.messageDao().clearAllMessages(account)
            }
        }
    }

    fun setPrivacyMode(allowOnlyFromList: Boolean) = nsManager.setPrivacyMode(allowOnlyFromList)
    
    fun updateNickname(newName: String) {
        nsManager.updateNickname(newName)
        val acc = getCurrentAccount() ?: return
        val normalized = acc.lowercase(Locale.ROOT).trim()
        securePrefs.setNickname(normalized, newName)
        _loginState.update { state ->
            if (state is MSNPLoginState.Success && state.account.lowercase(Locale.ROOT).trim() == normalized) {
                state.copy(nickname = newName)
            } else state
        }
    }

    fun updatePersonalMessage(psm: String) {
        nsManager.updatePersonalMessage(psm)
        contactManager.updateMyPersonalMessage(psm)
    }
    
    fun addContact(account: String) = nsManager.addContact(account, MSNPProto.List.FL or MSNPProto.List.AL)
    fun removeContact(account: String, mask: Int = MSNPProto.List.FL) = nsManager.removeContact(account, mask)
    fun blockContact(account: String) = nsManager.blockContact(account)
    fun unblockContact(account: String) = nsManager.unblockContact(account)

    fun acceptRequest(account: String) {
        addContact(account)
        contactManager.clearPendingRequest(account)
    }

    fun declineRequest(account: String) {
        contactManager.clearPendingRequest(account)
    }

    fun getSavedAccount(): String? = securePrefs.getSavedAccount()
    fun getSavedPassword(): String? = securePrefs.getSavedPassword()
    fun getCurrentAccount(): String? = currentAccount ?: getSavedAccount()
    
    fun inviteContactToChat(chatAccount: String, inviteAccount: String) {
        sessionManager.inviteToChat(chatAccount, inviteAccount)
    }

    fun updateAvatar(filePath: String) {
        scope.launch {
            try {
                val uri = android.net.Uri.parse(filePath)
                val inputStream = context.contentResolver.openInputStream(uri)
                val bytes = inputStream?.readBytes()
                if (bytes != null) {
                    nsManager.updateAvatar(bytes)
                    val path = avatarManager.saveOwnAvatar(bytes)
                    val sha1 = avatarManager.calculateSha1(bytes)
                    val msnObj = "MSNObject SHA1D='$sha1'" // Упрощенно для обновления UI
                    _loginState.update { state ->
                        if (state is MSNPLoginState.Success) state.copy(avatarUrl = path) else state
                    }
                }
            } catch (e: Exception) {
                Log.e("MSNP-Repo", "Failed to update avatar: ${e.message}")
            }
        }
    }

    override fun onConnected() { Log.d("MSNP-Repo", "Connected to notification server") }

    override fun onAuthSuccess(account: String, nickname: String) {
        val normalized = account.lowercase(Locale.ROOT).trim()
        currentAccount = normalized
        
        val bestNickname = if (nickname == account || nickname.isEmpty()) {
            securePrefs.getNickname(normalized) ?: account
        } else {
            nickname
        }
        
        _loginState.value = MSNPLoginState.Success(account, bestNickname)
        contactManager.updateMyStatus(MSNPProto.Status.ONLINE)
        isReconnecting.set(false)

        // Синхронизируем недоставленные сообщения при успешном входе
        syncUnsentMessages()
    }

    override fun onContactStatusChanged(account: String, status: String, nickname: String, isInitial: Boolean, msnObject: String?) {
        val normalized = account.lowercase(Locale.ROOT).trim()
        val myAcc = getCurrentAccount()?.lowercase(Locale.ROOT)?.trim()
        
        val sha1 = msnObject?.let { avatarManager.extractAttr(it, "SHA1D") }
        val cachedAvatarPath = sha1?.let { avatarManager.getAvatarBySha1(it) }

        if (normalized == myAcc) {
            contactManager.updateMyStatus(status)
            if (nickname.isNotEmpty() && nickname != account) {
                securePrefs.setNickname(normalized, nickname)
                _loginState.update { state ->
                    if (state is MSNPLoginState.Success && state.account.lowercase(Locale.ROOT).trim() == normalized) {
                        state.copy(nickname = nickname, avatarUrl = cachedAvatarPath ?: state.avatarUrl)
                    } else state
                }
            } else if (cachedAvatarPath != null) {
                _loginState.update { state ->
                    if (state is MSNPLoginState.Success && state.account.lowercase(Locale.ROOT).trim() == normalized) {
                        state.copy(avatarUrl = cachedAvatarPath)
                    } else state
                }
            }
            return
        }

        contactManager.updateContactStatus(account, status, nickname, cachedAvatarPath)
        
        if (msnObject != null && cachedAvatarPath == null && status != MSNPProto.Status.OFFLINE) {
            avatarManager.requestAvatar(account, msnObject)
        }

        // Если контакт зашел в онлайн — пробуем отправить ему накопившиеся сообщения
        if (status != MSNPProto.Status.OFFLINE && !isInitial) {
            syncUnsentMessagesForContact(normalized)
        }
    }

    private fun syncUnsentMessagesForContact(contact: String) {
        val account = getCurrentAccount() ?: return
        scope.launch {
            val unsent = database.messageDao().getUnsentMessages(account).filter { 
                it.receiverAccount.lowercase(Locale.ROOT).trim() == contact 
            }
            if (unsent.isNotEmpty()) {
                Log.d("MSNP-Repo", "Syncing ${unsent.size} messages for $contact")
                unsent.forEach { msg ->
                    val text = SafeStorage.decryptText(msg.encryptedText)
                    performSendMessageInternal(msg.id, contact, text)
                }
            }
        }
    }

    override fun onMessageReceived(target: String, senderAccount: String, nickname: String, message: String) {
        val normalizedTarget = target.lowercase(Locale.ROOT).trim()
        val normalizedSender = senderAccount.lowercase(Locale.ROOT).trim()
        val myAcc = getCurrentAccount()?.lowercase(Locale.ROOT)?.trim() ?: ""
        val isMe = normalizedSender == myAcc
        
        scope.launch {
            messageManager.saveAndEmitMessage(myAcc, target, message, true, nickname)
            
            // Если чат открыт — сразу помечаем как прочитанное
            if (normalizedTarget == activeChatAccount?.lowercase(Locale.ROOT)?.trim()) {
                markAsRead(normalizedTarget)
            } else if (!isMe) {
                MSNPService.showMessageNotification(context, nickname, message, senderAccount)
            }
        }
    }

    override fun onSwitchboardRequest(host: String, port: Int, key: String, sessionId: String?, callerAccount: String?, callerNickname: String?, transactionId: Int?) {
        sessionManager.handleSwitchboardRequest(host, port, key, sessionId, callerAccount, transactionId, getCurrentAccount() ?: "")
    }

    override fun onSwitchboardIdentified(sessionId: String, account: String) = sessionManager.identifySession(sessionId, account)
    override fun onSwitchboardDisconnected(account: String) = sessionManager.removeSession(account)

    override fun onError(message: String) {
        if (message == "ERROR_LOGGED_IN_ANOTHER_DEVICE") {
            handleLoggedInElsewhereDude()
            return
        }
        if (message.contains("TIMEOUT") || message.contains("broken pipe")) handleConnectionErrorDude()
        scope.launch { _eventFlow.emit("Error: $message") }
    }

    override fun onDisconnected() {
        if (!userInitiatedLogout && !isReconnecting.get()) handleConnectionErrorDude()
        contactManager.setAllOffline()
    }

    private fun handleConnectionErrorDude() {
        if (isReconnecting.compareAndSet(false, true)) {
            _loginState.value = MSNPLoginState.Reconnecting
            reconnectJob = scope.launch {
                delay(5000)
                val acc = securePrefs.getSavedAccount()
                val pwd = securePrefs.getSavedPassword()
                if (acc != null && pwd != null && !userInitiatedLogout) performLoginDude(acc, pwd)
            }
        }
    }

    private fun handleLoggedInElsewhereDude() {
        isReconnecting.set(false)
        reconnectJob?.cancel()
        nsManager.disconnect()
        sessionManager.disconnectAll()
        _loginState.value = MSNPLoginState.LoggedInElsewhere("ERROR_LOGGED_IN_ANOTHER_DEVICE")
        contactManager.setAllOffline()
    }

    override fun onContactAdded(account: String, nickname: String, listType: String, isInitial: Boolean) {
        val normalized = account.lowercase(Locale.ROOT).trim()
        val myAcc = getCurrentAccount()?.lowercase(Locale.ROOT)?.trim()
        if (normalized == myAcc && nickname.isNotEmpty() && nickname != account) {
            securePrefs.setNickname(normalized, nickname)
            _loginState.update { state ->
                if (state is MSNPLoginState.Success && state.account.lowercase(Locale.ROOT).trim() == normalized) {
                    state.copy(nickname = nickname)
                } else state
            }
        }
        
        if (listType.contains("PL")) contactManager.addPendingRequest(account, nickname)
        contactManager.addOrUpdateContact(account, nickname, listType, emitEvent = !isInitial)
    }

    override fun onContactFullInfo(account: String, nickname: String, guid: String, listTypes: String, groupGuids: List<String>) {
        val normalized = account.lowercase(Locale.ROOT).trim()
        val myAcc = getCurrentAccount()?.lowercase(Locale.ROOT)?.trim()
        if (normalized == myAcc && nickname.isNotEmpty() && nickname != account) {
            securePrefs.setNickname(normalized, nickname)
            _loginState.update { state ->
                if (state is MSNPLoginState.Success && state.account.lowercase(Locale.ROOT).trim() == normalized) {
                    state.copy(nickname = nickname)
                } else state
            }
        }
        contactManager.updateContactFullInfo(account, nickname, guid, listTypes, groupGuids)
    }

    override fun onContactRemoved(account: String, listType: String) = contactManager.removeContact(account)
    
    override fun onContactRenamed(account: String, newNickname: String) {
        val normalized = account.lowercase(Locale.ROOT).trim()
        val myAcc = getCurrentAccount()?.lowercase(Locale.ROOT)?.trim()
        if (normalized == myAcc) {
            if (newNickname.isNotEmpty() && newNickname != account) {
                securePrefs.setNickname(normalized, newNickname)
            }
            _loginState.update { state ->
                if (state is MSNPLoginState.Success && state.account.lowercase(Locale.ROOT).trim() == normalized) {
                    state.copy(nickname = newNickname)
                } else state
            }
        }
        contactManager.updateContactStatus(account, "", newNickname)
    }
    
    override fun onContactPersonalMessageChanged(account: String, psm: String) {
        val normalized = account.lowercase(Locale.ROOT).trim()
        val myAcc = getCurrentAccount()?.lowercase(Locale.ROOT)?.trim()
        if (normalized == myAcc) {
            contactManager.updateMyPersonalMessage(psm)
            return
        }
        contactManager.updateContactStatus(account, "", "", psm = psm)
    }

    override fun onTypingReceived(account: String) {
        val normalized = account.lowercase(Locale.ROOT).trim()
        contactManager.setTyping(normalized, true)
        
        typingTimers[normalized]?.cancel()
        typingTimers[normalized] = scope.launch {
            delay(6000)
            contactManager.setTyping(normalized, false)
            typingTimers.remove(normalized)
        }
    }
    
    override fun onMessageSent(messageId: Long) = messageManager.updateMessageStatus(messageId, true)
    override fun onUrlReceived(urlType: String, url: String) {
        if (urlType == "AVATAR_BYTES") {
            val parts = url.split("|")
            if (parts.size >= 2) {
                val account = parts[0]
                val base64 = parts[1]
                try {
                    val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                    val sha1 = avatarManager.calculateSha1(bytes)
                    val path = avatarManager.saveAvatar(bytes, sha1)
                    
                    val myAcc = getCurrentAccount()?.lowercase(Locale.ROOT)?.trim()
                    if (account.lowercase(Locale.ROOT).trim() == myAcc) {
                        _loginState.update { state ->
                            if (state is MSNPLoginState.Success) state.copy(avatarUrl = path) else state
                        }
                    }

                    contactManager.updateContactStatus(account, "", "", avatarUrl = path)
                } catch (e: Exception) { Log.e("MSNP-Repo", "Failed to process avatar", e) }
            }
        }
    }
    override fun onPrivacySettingChanged(type: String, value: String) {}
    override fun onRawLog(direction: String, content: String) {}
    
    override fun onGroupAdded(name: String, guid: String) {
        contactManager.addGroup(name, guid)
    }

    override fun onConfigReceived(config: Config) {
        _config.value = config
    }

    fun addContactToGroup(account: String, contactGuid: String, groupGuid: String) {
        nsManager.addContactToGroup(contactGuid, groupGuid)
        contactManager.addContactToGroupLocally(account, groupGuid)
    }

    fun removeContactFromGroup(account: String, contactGuid: String, groupGuid: String) {
        nsManager.removeContactFromGroup(contactGuid, groupGuid)
        contactManager.removeContactFromGroupLocally(account, groupGuid)
    }

    fun createGroup(name: String) {
        nsManager.createGroup(name)
        // Если мы в сети, добавляем группу локально для немедленного отображения.
        // Guid пока неизвестен (он придет от сервера), но мы можем использовать временный или ждать события.
        // Обычно сервер пришлет Event.Group, на который мы отреагируем в onGroupAdded.
    }

    fun deleteGroup(guid: String) {
        nsManager.deleteGroup(guid)
        contactManager.removeGroup(guid)
    }

    fun renameGroup(guid: String, newName: String) = nsManager.renameGroup(guid, newName)
}
