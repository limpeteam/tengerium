package com.limpe.tengerium.data.protocol.v11

import android.content.Context
import android.util.Base64
import android.util.Log
import com.limpe.tengerium.data.protocol.*
import uniffi.msnp11_sdk.*
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import com.limpe.tengerium.R

/**
 * Имплементация Switchboard сессии через Rust SDK (UniFFI).
 */
class MSNP11SwitchboardSession(
    private val listener: MSNPProtocolListener,
    var initialTarget: String? = null,
    private val context: Context? = null
) : IMSNPSwitchboardSession {
    
    companion object {
        private const val TAG = "MSNP11-SB"
        private const val INACTIVITY_TIMEOUT = 10000L // Сокращено до 10 секунд для экономии батареи
    }

    private var rustWrapper: SwitchboardWrapper? = null
    private var rawSession: Switchboard? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isConnected = false
    private var isIncomingSession = false
    private var myAccount: String = ""
    private var currentSessionId: String? = null
    private var isGroup = false
    
    private var inactivityJob: Job? = null
    private val participants = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    override fun connect(host: String, port: Int, account: String, key: String, isIncoming: Boolean, sessionId: String?, target: String?) {
        this.myAccount = MSNPUtils.normalizeEmail(account)
        this.isIncomingSession = isIncoming
        this.currentSessionId = sessionId
        if (target != null && target != "PENDING_INCOMING") {
            this.initialTarget = MSNPUtils.normalizeEmail(target)
        }
        
        participants.clear()
        participants.add(myAccount)
        
        scope.safeLaunch(TAG) {
            val sb = sessionId?.let { MSNP11NotificationManager.activeRustSessions.remove(it) }
            
            if (sb != null) {
                rawSession = sb
                rustWrapper = SwitchboardWrapper(sb)
                
                setupHandler()
                
                // Если сессия исходящая и у нас есть таргет — считаем её сразу готовой,
                // так как SDK возвращает SB только после успешного XFR/соединения.
                if (!isIncoming && initialTarget != null) {
                    isConnected = true
                }
                
                resetInactivityTimer()
                Log.d(TAG, "Session connected. SID: $sessionId, Target: $initialTarget, isConnected: $isConnected")
            } else {
                Log.e(TAG, "Could not find active session for SID: $sessionId")
                handleClose()
            }
        }
    }

    private fun resetInactivityTimer() {
        inactivityJob?.cancel()
        if (isGroup || participants.size > 2) return 
        
        inactivityJob = scope.launch {
            delay(INACTIVITY_TIMEOUT)
            Log.d(TAG, "Closing session due to inactivity with $initialTarget")
            handleClose()
        }
    }

    private fun getTarget(sender: String): String {
        return if (isGroup) (currentSessionId ?: sender) else (initialTarget ?: sender)
    }

    private fun getString(resId: Int, vararg args: Any): String {
        return context?.getString(resId, *args) ?: ""
    }

    private fun setupHandler() {
        rustWrapper?.addEventHandler(object : EventHandler {
            override suspend fun handle(event: Event) {
                resetInactivityTimer()
                
                try {
                    when (event) {
                        is Event.TextMessage -> {
                            val sender = MSNPUtils.normalizeEmail(event.email)
                            val originalText = event.message.text
                            val target = getTarget(sender)
                            Log.d(TAG, "MSG from $sender: ${originalText.take(20)}...")
                            
                            val text = if (isGroup || participants.size > 2) {
                                "$sender:\n$originalText"
                            } else {
                                originalText
                            }
                            
                            scope.safeLaunch(TAG) {
                                if (sender != myAccount) {
                                    listener.onMessageReceived(target, sender, sender, text)
                                }
                            }
                        }
                        is Event.Nudge -> {
                            val sender = MSNPUtils.normalizeEmail(event.email)
                            val target = getTarget(sender)
                            scope.safeLaunch(TAG) {
                                if (sender != myAccount) {
                                    listener.onMessageReceived(target, sender, sender, "[NUDGE]")
                                }
                            }
                        }
                        is Event.TypingNotification -> {
                            val sender = MSNPUtils.normalizeEmail(event.email)
                            scope.safeLaunch(TAG) {
                                listener.onTypingReceived(sender)
                            }
                        }
                        is Event.DisplayPicture -> {
                            val email = event.email
                            val data = event.data
                            Log.d(TAG, "Received DisplayPicture event for $email (${data.size} bytes)")
                            scope.safeLaunch(TAG) {
                                val encoded = Base64.encodeToString(data, Base64.NO_WRAP)
                                listener.onUrlReceived("AVATAR_BYTES", "$email|$encoded")
                                
                                // Если это была сессия только для аватара (не группа), закрываем её быстрее
                                if (!isGroup && participants.size <= 2) {
                                    Log.d(TAG, "Closing session after avatar received for $email")
                                    handleClose()
                                }
                            }
                        }
                        is Event.ParticipantInSwitchboard -> {
                            val email = MSNPUtils.normalizeEmail(event.email)
                            val isNew = !participants.contains(email)
                            participants.add(email)
                            Log.d(TAG, "Participant joined: $email")
                            
                            if (participants.size > 2) {
                                isGroup = true
                            }

                            currentSessionId?.let { sid ->
                                listener.onGroupStateChanged(sid, participants.toSet())
                            }

                            if (isIncomingSession && (initialTarget == null || initialTarget == "pending_incoming") && email != myAccount) {
                                initialTarget = email
                                currentSessionId?.let { sid ->
                                    scope.safeLaunch(TAG) { listener.onSwitchboardIdentified(sid, email) }
                                }
                            }

                            isConnected = true
                            
                            val target = getTarget(email)
                            if (isNew && email != myAccount && (isGroup || participants.size >= 3)) {
                                scope.safeLaunch(TAG) {
                                    val msg = getString(R.string.user_joined_chat, email) + " (${participants.size})"
                                    listener.onMessageReceived(target, "SYSTEM", "SYSTEM", msg)
                                }
                            }
                        }
                        is Event.ParticipantLeftSwitchboard -> {
                            val email = MSNPUtils.normalizeEmail(event.email)
                            val wasPresent = participants.remove(email)
                            Log.d(TAG, "Participant left: $email")
                            
                            if (wasPresent) {
                                currentSessionId?.let { sid ->
                                    listener.onGroupStateChanged(sid, participants.toSet())
                                }
                                
                                val target = getTarget(email)
                                if (isGroup || participants.size >= 2) {
                                    scope.safeLaunch(TAG) {
                                        val msg = getString(R.string.user_left_chat, email) + " (${participants.size})"
                                        listener.onMessageReceived(target, "SYSTEM", "SYSTEM", msg)
                                    }
                                }
                            }
                            
                            if (participants.count { it != myAccount } == 0) {
                                scope.safeLaunch(TAG) { handleClose() }
                            }
                        }
                        is Event.Disconnected -> {
                            Log.d(TAG, "Session disconnected event received")
                            scope.safeLaunch(TAG) { handleClose() }
                        }
                        else -> {}
                    }
                } finally {
                    event.destroySafe()
                }
            }
        })
    }
    
    private fun handleClose() {
        inactivityJob?.cancel()
        if (isConnected || rustWrapper != null) {
            isConnected = false
            val target = initialTarget ?: currentSessionId ?: "pending_incoming"
            Log.d(TAG, "Closing SB session for $target")
            participants.clear()
            isGroup = false
            
            val oldWrapper = rustWrapper
            val oldRaw = rawSession
            rustWrapper = null
            rawSession = null
            
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    oldWrapper?.disconnect()
                } catch (e: Exception) {
                    Log.w(TAG, "SDK disconnect error: ${e.message}")
                } finally {
                    oldWrapper.destroySafe()
                    oldRaw.destroySafe()
                }
            }
            listener.onSwitchboardDisconnected(target)
        }
    }

    override fun sendMessage(text: String, messageId: Long) {
        resetInactivityTimer()
        scope.safeLaunch(TAG) {
            if (waitForConnection() && rustWrapper != null) {
                rustWrapper?.sendTextMessage(PlainText(
                    bold = false, italic = false, underline = false, strikethrough = false,
                    color = "000000", text = text
                ))
                listener.onMessageSent(messageId)
            } else {
                Log.w(TAG, "Failed to send message: SB not connected")
                listener.onMessageFailed(messageId, "Session closed or handshake failed")
            }
        }
    }

    override fun requestAvatar(account: String, msnObject: String) {
        resetInactivityTimer()
        scope.safeLaunch(TAG) { 
            Log.d(TAG, "Waiting for connection to request avatar for $account...")
            if (waitForConnection(timeout = 15000)) {
                Log.d(TAG, "Connection ready, sending requestContactDisplayPicture for $account")
                try {
                    rustWrapper?.requestContactDisplayPicture(account, msnObject)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to request avatar via SDK: ${e.message}")
                }
            } else {
                Log.w(TAG, "Timed out waiting for connection to request avatar for $account")
            }
        }
    }

    private suspend fun waitForConnection(timeout: Long = 10000): Boolean {
        if (isConnected) return true
        return withTimeoutOrNull(timeout) {
            while (isActive && !isConnected && rustWrapper != null) {
                delay(100)
            }
            isConnected
        } ?: false
    }

    override fun disconnect() {
        handleClose()
        scope.cancel()
    }
    
    override fun sendTyping() { 
        resetInactivityTimer()
        scope.safeLaunch(TAG) { 
            if (waitForConnection()) {
                rustWrapper?.sendTypingUser(myAccount) 
            }
        }
    }
    
    override fun sendNudge(messageId: Long) {
        resetInactivityTimer()
        scope.safeLaunch(TAG) {
            if (waitForConnection()) {
                try {
                    rustWrapper?.sendNudge()
                    listener.onMessageSent(messageId)
                } catch (e: Exception) {
                    listener.onMessageFailed(messageId, e.message)
                }
            }
        }
    }
    
    override fun sendFile(account: String, file: File) {}
    override fun inviteParticipant(account: String) { 
        resetInactivityTimer()
        val normalized = MSNPUtils.normalizeEmail(account)
        if (!normalized.contains("@")) return
        scope.safeLaunch(TAG) { 
            rustWrapper?.invite(normalized)
        }
    }

    override fun isClosed() = rustWrapper == null
    override fun isJoined() = isConnected
    override fun isConnecting() = !isConnected && rustWrapper != null
}
