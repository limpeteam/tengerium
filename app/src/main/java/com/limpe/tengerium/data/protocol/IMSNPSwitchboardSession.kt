package com.limpe.tengerium.data.protocol

import java.io.File

/**
 * Интерфейс для Switchboard сессии независимо от версии протокола.
 */
interface IMSNPSwitchboardSession {
    fun connect(host: String, port: Int, account: String, key: String, isIncoming: Boolean, sessionId: String? = null, target: String? = null)
    fun disconnect()
    fun isClosed(): Boolean
    fun isJoined(): Boolean
    fun isConnecting(): Boolean
    fun sendMessage(text: String, messageId: Long = -1L)
    fun sendTyping()
    fun sendNudge(messageId: Long = -1L)
    fun requestAvatar(account: String, msnObject: String)
    fun sendFile(account: String, file: File)
    
    // Групповые чаты
    fun inviteParticipant(account: String) {}
}