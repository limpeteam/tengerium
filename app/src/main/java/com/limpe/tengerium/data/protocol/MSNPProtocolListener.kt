package com.limpe.tengerium.data.protocol

import uniffi.msnp11_sdk.Config

/**
 * Интерфейс для прослушивания событий протокола MSNP.
 */
interface MSNPProtocolListener {
    fun onConnected()
    fun onAuthSuccess(account: String, nickname: String)
    fun onContactStatusChanged(account: String, status: String, nickname: String, isInitial: Boolean = false, msnObject: String? = null)
    fun onContactAdded(account: String, nickname: String, listType: String, isInitial: Boolean = false)
    
    // Расширенная информация о контакте для MSNP11
    fun onContactFullInfo(account: String, nickname: String, guid: String, listTypes: String, groupGuids: List<String>) {}

    fun onContactRemoved(account: String, listType: String)
    fun onContactRemovedFromList(account: String, list: String) {}
    fun onContactRenamed(account: String, newNickname: String)
    fun onPrivacySettingChanged(type: String, value: String)
    fun onUrlReceived(urlType: String, url: String)
    
    /**
     * @param target Либо почта собеседника (ЛС), либо sessionId (Групповой чат)
     * @param senderAccount Почта отправителя
     */
    fun onMessageReceived(target: String, senderAccount: String, nickname: String, message: String)
    
    fun onContactPersonalMessageChanged(account: String, psm: String) {}

    fun onSwitchboardRequest(
        host: String, 
        port: Int, 
        key: String, 
        sessionId: String? = null,
        callerAccount: String? = null,
        callerNickname: String? = null,
        transactionId: Int? = null 
    )

    fun onSwitchboardIdentified(sessionId: String, account: String) {}
    
    fun onError(message: String)
    fun onDisconnected()
    
    fun onSwitchboardDisconnected(account: String) {}
    
    fun onMessageSent(messageId: Long) {}
    fun onMessageFailed(messageId: Long, error: String?) {}

    fun onTypingReceived(account: String) {}

    fun onGroupStateChanged(sessionId: String, participants: Set<String>) {}
    
    fun onOIMNotificationReceived(token: String) {}

    fun onRawLog(direction: String, content: String)

    // Новые методы для обработки доп. событий
    fun onGroupAdded(name: String, guid: String) {}
    fun onGroupRemoved(guid: String) {}
    fun onConfigReceived(config: Config) {}
}
