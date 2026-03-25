package com.limpe.tengerium.data

import android.content.Context
import android.util.Log
import com.limpe.tengerium.data.protocol.IMSNPNotificationManager
import com.limpe.tengerium.data.protocol.IMSNPSwitchboardSession
import com.limpe.tengerium.data.protocol.v11.MSNP11SwitchboardSession
import kotlinx.coroutines.*
import java.util.*
import java.util.concurrent.ConcurrentHashMap

// Менеджер сессий. Его работа — созваниваться с контактами и следить, чтобы трубка не висела просто так.
class SessionManager(
    private val nsManager: IMSNPNotificationManager,
    private val scope: CoroutineScope,
    private val listener: com.limpe.tengerium.data.protocol.MSNPProtocolListener,
    private val context: Context? = null
) {
    private val TAG = "SessionManager"
    // Активные чаты. Ключ — почта собеседника (или sessionId для групп).
    private val sbSessions = ConcurrentHashMap<String, IMSNPSwitchboardSession>()
    // Сессии, которые ломятся к нам, но мы еще не поняли, кто это.
    private val pendingIncomingSessions = ConcurrentHashMap<String, IMSNPSwitchboardSession>()
    // Запросы на переключение (XFR). Ждем, пока сервер даст добро.
    private val pendingXfrRequests = ConcurrentHashMap<Int, String>()
    // Чтобы не открывать один и тот же чат дважды, если юзер нетерпеливый.
    private val openingSessions = ConcurrentHashMap<String, Job>()

    // Достаем существующий чат или открываем новый. 
    suspend fun getOrOpenSession(target: String, forceNew: Boolean = false): IMSNPSwitchboardSession? {
        val normalized = target.lowercase(Locale.ROOT).trim()
        
        // Если старая сессия протухла — выкидываем.
        val existing = sbSessions[normalized]
        if (existing != null && (existing.isClosed() || forceNew)) {
            sbSessions.remove(normalized)?.disconnect()
        }

        var session = sbSessions[normalized]
        if (session == null || session.isClosed() || (!session.isJoined() && !session.isConnecting())) {
            // Если уже пытаемся открыть — просто ждем результат.
            if (openingSessions.containsKey(normalized)) {
                openingSessions[normalized]?.join()
            } else {
                val job = scope.launch {
                    try {
                        val tid = nsManager.requestSwitchboard(normalized)
                        if (tid != -1) pendingXfrRequests[tid] = normalized
                        // Даем серверу 10 секунд, чтобы проснуться.
                        withTimeoutOrNull(10000) {
                            while (isActive && sbSessions[normalized] == null) { delay(50) }
                        }
                    } finally {
                        openingSessions.remove(normalized)
                    }
                }
                openingSessions[normalized] = job
                job.join()
            }
        }
        
        // Ждем, пока произойдет финальное рукопожатие.
        session = sbSessions[normalized]
        if (session != null && !session.isJoined() && !session.isClosed()) {
            withTimeoutOrNull(10000) { 
                while (isActive && sbSessions[normalized]?.isJoined() != true && sbSessions[normalized]?.isClosed() != true) {
                    delay(50)
                }
            }
        }
        
        return sbSessions[normalized]?.takeIf { it.isJoined() && !it.isClosed() }
    }

    // Сервер говорит: "Эй, к тебе стучатся!". Поднимаем трубку.
    fun handleSwitchboardRequest(host: String, port: Int, key: String, sessionId: String?, callerAccount: String?, transactionId: Int?, currentAccount: String) {
        val pendingTarget = transactionId?.let { pendingXfrRequests.remove(it) }
        val target = pendingTarget ?: callerAccount?.lowercase(Locale.ROOT)?.trim() ?: return
        
        val isPendingIncoming = target == "pending_incoming"

        // Если чат уже есть и работает — не плодим сущности.
        if (!isPendingIncoming) {
            val existing = sbSessions[target]
            if (existing != null && !existing.isClosed() && (existing.isJoined() || existing.isConnecting())) return 
        }

        val session = MSNP11SwitchboardSession(listener, context = context)
        if (isPendingIncoming) {
            sessionId?.let { pendingIncomingSessions[it] = session }
        } else {
            sbSessions[target] = session
        }
        session.connect(host, port, currentAccount, key, transactionId == null || callerAccount != null, sessionId, target)
    }

    // Когда анонимный входящий наконец представился.
    fun identifySession(sessionId: String, account: String) {
        val normalized = account.lowercase(Locale.ROOT).trim()
        val session = pendingIncomingSessions.remove(sessionId)
        if (session != null) {
            val existing = sbSessions[normalized]
            if (existing != null && !existing.isClosed()) {
                session.disconnect() // Сорян, у нас уже есть связь.
            } else {
                sbSessions[normalized] = session
            }
        }
    }

    /**
     * Приглашает участника в существующую сессию.
     */
    fun inviteToChat(targetChat: String, contactToInvite: String) {
        val normalizedChat = targetChat.lowercase(Locale.ROOT).trim()
        val session = sbSessions[normalizedChat]
        if (session != null && !session.isClosed()) {
            session.inviteParticipant(contactToInvite)
        }
    }

    // Убираем сессию из списков. Всё, разговор окончен.
    fun removeSession(account: String) {
        val normalized = account.lowercase(Locale.ROOT).trim()
        if (normalized == "pending_incoming") {
            pendingIncomingSessions.values.removeAll { it.isClosed() }
        } else {
            sbSessions.remove(normalized)
        }
    }

    // Гасим всё. Обычно при выходе из аккаунта.
    fun disconnectAll() {
        sbSessions.values.forEach { it.disconnect() }
        sbSessions.clear()
        pendingIncomingSessions.values.forEach { it.disconnect() }
        pendingIncomingSessions.clear()
        pendingXfrRequests.clear()
    }
}
