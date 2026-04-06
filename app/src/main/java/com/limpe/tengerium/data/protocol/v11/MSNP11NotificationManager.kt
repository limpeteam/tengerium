package com.limpe.tengerium.data.protocol.v11

import android.util.Base64
import android.util.Log
import com.limpe.tengerium.data.MSNPRepository
import com.limpe.tengerium.data.protocol.*
import com.limpe.tengerium.data.security.SecurePrefs
import kotlinx.coroutines.*
import uniffi.msnp11_sdk.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class MSNP11NotificationManager(
    private val listener: MSNPProtocolListener,
    private val scope: CoroutineScope
) : IMSNPNotificationManager {

    companion object {
        private const val TAG = "MSNP11NotificationManager"
        val activeRustSessions = ConcurrentHashMap<String, Switchboard>()
    }

    private var rustClient: Client? = null
    private var connectJob: Job? = null
    private val transactionId = AtomicInteger(1)
    
    private val contactStatuses = ConcurrentHashMap<String, String>()
    
    private var currentAccount = ""
    private var currentPassword = ""
    private var currentStatus = "NLN"
    private var currentMsnObject = ""
    private var currentHost = ""
    private var currentNexusUrl = ""

    override fun connect(host: String, port: Int, account: String, password: String, nexusUrl: String, msnObject: String?) {
        currentAccount = account
        currentPassword = password
        currentHost = host
        currentNexusUrl = nexusUrl
        msnObject?.let { currentMsnObject = it }
        
        connectJob?.cancel()
        connectJob = scope.safeLaunch(TAG) {
            try {
                val cleanHost = host.trim()
                Log.d(TAG, "Connecting to $cleanHost:$port as $account")
                rustClient?.destroySafe()
                
                val client = Client(cleanHost, port.toUShort())
                rustClient = client
                
                client.addEventHandler(object : EventHandler {
                    override suspend fun handle(event: Event) {
                        handleEvent(event)
                    }
                })

                val loginEvent = client.login(account, password, currentNexusUrl, "Tengerium", "0.0.4")
                handleEvent(loginEvent)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val errorMsg = if (e.message.isNullOrBlank() || e.message == ".") e.javaClass.simpleName else e.message
                Log.e(TAG, "Connection failed: $errorMsg", e)
                listener.onError(errorMsg ?: "CONNECTION_FAILED")
            }
        }
    }

    private fun handleEvent(event: Event) {
        try {
            when (event) {
                is Event.Authenticated -> {
                    scope.safeLaunch(TAG) {
                        listener.onConnected()
                        requestConfig()
                        listener.onAuthSuccess(currentAccount, "")
                    }
                }
                
                is Event.RedirectedTo -> {
                    Log.d(TAG, "Redirecting to ${event.server}:${event.port}")
                    connect(event.server, event.port.toInt(), currentAccount, currentPassword, currentNexusUrl, currentMsnObject)
                }

                is Event.Gtc -> {
                    scope.safeLaunch(TAG) { listener.onPrivacySettingChanged("GTC", event.v1) }
                }

                is Event.Blp -> {
                    scope.safeLaunch(TAG) { listener.onPrivacySettingChanged("BLP", event.v1) }
                }

                is Event.DisplayName -> {
                    scope.safeLaunch(TAG) { listener.onContactRenamed(currentAccount, event.v1) }
                }

                is Event.Group -> {
                    scope.safeLaunch(TAG) { listener.onGroupAdded(event.name, event.guid) }
                }

                is Event.Contact -> {
                    scope.safeLaunch(TAG) {
                        listener.onContactAdded(event.email, event.displayName, MSNP11Mapper.mapLists(event.lists), isInitial = true)
                    }
                }

                is Event.ContactInForwardList -> {
                    scope.safeLaunch(TAG) {
                        listener.onContactFullInfo(
                            event.email, 
                            event.displayName, 
                            event.guid, 
                            MSNP11Mapper.mapLists(event.lists), 
                            event.groups
                        )
                    }
                }
                
                is Event.InitialPresenceUpdate -> handlePresenceUpdate(event.email, event.presence, event.displayName, isInitial = true)

                is Event.PresenceUpdate -> handlePresenceUpdate(event.email, event.presence, event.displayName, isInitial = false)

                is Event.ContactOffline -> {
                    val email = event.email
                    contactStatuses[MSNPUtils.normalizeEmail(email)] = "FLN"
                    scope.safeLaunch(TAG) { listener.onContactStatusChanged(email, "FLN", email, false, null) }
                }

                is Event.SessionAnswered -> handleIncomingSession(event.v1)

                is Event.TextMessage -> {
                    scope.safeLaunch(TAG) { listener.onMessageReceived(event.email, event.email, event.email, event.message.text) }
                }

                is Event.Nudge -> {
                    scope.safeLaunch(TAG) {
                        listener.onUrlReceived("NUDGE_NOTIFY", event.email)
                        listener.onMessageReceived(event.email, event.email, event.email, "[NUDGE]")
                    }
                }

                is Event.TypingNotification -> {
                    scope.safeLaunch(TAG) { listener.onTypingReceived(event.email) }
                }

                is Event.DisplayPicture -> {
                    val email = event.email
                    val data = event.data
                    scope.safeLaunch(TAG) {
                        val encoded = Base64.encodeToString(data, Base64.NO_WRAP)
                        listener.onUrlReceived("AVATAR_BYTES", "$email|$encoded")
                    }
                }

                is Event.PersonalMessageUpdate -> {
                    scope.safeLaunch(TAG) { listener.onContactPersonalMessageChanged(event.email, event.personalMessage.psm) }
                }

                is Event.AddedBy -> {
                    scope.safeLaunch(TAG) {
                        listener.onContactAdded(event.email, event.displayName, "PL")
                    }
                }

                is Event.RemovedBy -> {
                    scope.safeLaunch(TAG) {
                        listener.onContactRemoved(event.v1, "RL")
                    }
                }

                is Event.Disconnected -> {
                    contactStatuses.clear()
                    scope.safeLaunch(TAG) { listener.onDisconnected() }
                }

                is Event.LoggedInAnotherDevice -> {
                    scope.safeLaunch(TAG) { listener.onError("ERROR_LOGGED_IN_ANOTHER_DEVICE") }
                }

                else -> Log.d(TAG, "Unhandled event: ${event.javaClass.simpleName}")
            }
        } finally {
            event.destroySafe()
        }
    }

    private fun handlePresenceUpdate(email: String, presence: Presence, displayName: String, isInitial: Boolean) {
        val status = MSNP11Mapper.mapStatus(presence.status)
        contactStatuses[MSNPUtils.normalizeEmail(email)] = status
        scope.safeLaunch(TAG) {
            val msnObj = MSNPUtils.decodeMsnObject(presence.msnObjectString)
            listener.onContactStatusChanged(email, status, displayName, isInitial, msnObj)
        }
    }

    private fun handleIncomingSession(sb: Switchboard) {
        val sbHandle = try { sb.uniffiCloneHandle() } catch (e: Exception) { 0L }
        if (sbHandle == 0L) return

        val sbClone = Switchboard(UniffiWithHandle, sbHandle)
        scope.safeLaunch(TAG) {
            try {
                val sid = SwitchboardWrapper(sbClone).use { it.getSessionId() }
                Log.d(TAG, "Incoming Session SID: $sid")
                activeRustSessions[sid] = sbClone
                listener.onSwitchboardRequest("RUST_SDK", 0, "RUST_KEY", sid, callerAccount = "PENDING_INCOMING")
            } catch (e: Exception) {
                Log.e(TAG, "Incoming session failed: ${e.message}")
                sbClone.destroySafe()
            }
        }
    }

    override fun disconnect() {
        connectJob?.cancel()
        scope.safeLaunch(TAG) {
            try {
                rustClient?.disconnect()
            } finally {
                rustClient?.destroySafe()
                rustClient = null
                activeRustSessions.values.forEach { it.destroySafe() }
                activeRustSessions.clear()
                contactStatuses.clear()
            }
        }
    }

    override fun changeStatus(status: String, msnObject: String?) {
        currentStatus = status
        if (!msnObject.isNullOrEmpty()) {
            currentMsnObject = msnObject
        }
        
        scope.safeLaunch(TAG) {
            try {
                val rustStatus = MSNP11Mapper.mapToMsnpStatus(status)
                rustClient?.setPresence(rustStatus)
                Log.d(TAG, "Status changed to $status. Current MSNObject in manager: $currentMsnObject")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to change status: ${e.message}")
            }
        }
    }

    override fun updateAvatar(bytes: ByteArray) {
        scope.safeLaunch(TAG) {
            try {
                Log.d(TAG, "Updating avatar in SDK with ${bytes.size} bytes")
                // 1. Устанавливаем картинку в SDK
                val newMsnObject = rustClient?.setDisplayPicture(bytes)
                
                if (!newMsnObject.isNullOrEmpty()) {
                    Log.d(TAG, "Avatar updated in SDK, new msnObject: $newMsnObject")
                    currentMsnObject = newMsnObject
                    
                    // 2. ВАЖНО: Принудительно вызываем setPresence, чтобы отправить CHG с новым MsnObject
                    // Без этого собеседники не узнают о смене картинки до следующей смены статуса.
                    val rustStatus = MSNP11Mapper.mapToMsnpStatus(currentStatus)
                    rustClient?.setPresence(rustStatus)
                    
                    listener.onContactStatusChanged(currentAccount, currentStatus, "", false, newMsnObject)
                } else {
                    Log.w(TAG, "SDK returned empty MSNObject after setDisplayPicture")
                    listener.onContactStatusChanged(currentAccount, currentStatus, "", false, null)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update avatar in SDK: ${e.message}", e)
            }
        }
    }

    override fun updateNickname(nick: String) { 
        scope.safeLaunch(TAG) { 
            try {
                rustClient?.setDisplayName(nick) 
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update nickname: ${e.message}")
            }
        } 
    }
    
    override fun updatePersonalMessage(psm: String) { 
        scope.safeLaunch(TAG) { 
            try {
                rustClient?.setPersonalMessage(PersonalMessage(psm, "")) 
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update PSM: ${e.message}")
            }
        } 
    }
    
    override fun requestSwitchboard(target: String?): Int {
        val tid = transactionId.getAndIncrement()
        if (target == null) return tid
        
        val normalizedTarget = MSNPUtils.normalizeEmail(target)
        if (!normalizedTarget.contains("@")) return tid

        scope.safeLaunch(TAG) {
            Log.d(TAG, "Requesting SB for $normalizedTarget (TID: $tid)")
            try {
                val sb = rustClient?.createSession(normalizedTarget)
                if (sb != null) {
                    val sid = SwitchboardWrapper(sb).use { it.getSessionId() }
                    activeRustSessions[sid] = sb
                    listener.onSwitchboardRequest("RUST_SDK", 0, "RUST_KEY", sid, transactionId = tid, callerAccount = normalizedTarget)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create session for $normalizedTarget: ${e.message}")
                listener.onUrlReceived("XFR_ERROR", normalizedTarget)
            }
        }
        return tid
    }

    override fun addContact(acc: String, mask: Int) {
        scope.safeLaunch(TAG) {
            try {
                Log.d(TAG, "Adding contact $acc with mask $mask")
                
                // Раньше мы просто вызывали метод и ждали событий через EventHandler.
                // Теперь мы можем обрабатывать результат прямо здесь для ускорения обновления UI.
                
                if ((mask and MSNPProto.List.FL) != 0) {
                    val event = rustClient?.addContact(acc, acc, MsnpList.FORWARD_LIST)
                    event?.let { handleEvent(it) }
                } else if ((mask and MSNPProto.List.AL) != 0) {
                    val event = rustClient?.addContact(acc, acc, MsnpList.ALLOW_LIST)
                    event?.let { handleEvent(it) }
                } else if ((mask and MSNPProto.List.BL) != 0) {
                    val event = rustClient?.addContact(acc, acc, MsnpList.BLOCK_LIST)
                    event?.let { handleEvent(it) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add contact: ${e.message}")
            }
        }
    }

    override fun removeContact(acc: String, mask: Int) {
        scope.safeLaunch(TAG) {
            try {
                Log.d(TAG, "Removing contact $acc with mask $mask")
                if ((mask and MSNPProto.List.FL) != 0) rustClient?.removeContact(acc, MsnpList.FORWARD_LIST)
                if ((mask and MSNPProto.List.AL) != 0) rustClient?.removeContact(acc, MsnpList.ALLOW_LIST)
                if ((mask and MSNPProto.List.BL) != 0) rustClient?.removeContact(acc, MsnpList.BLOCK_LIST)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove contact: ${e.message}")
            }
        }
    }

    override fun setPrivacyMode(allowOnlyFromList: Boolean) {
        scope.safeLaunch(TAG) { 
            try {
                rustClient?.setBlp(if (allowOnlyFromList) "AL" else "BL") 
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set privacy mode: ${e.message}")
            }
        }
    }

    override fun requestConfig(configUrl: String?) {
        val urlInput = configUrl ?: listener.let { (it as? MSNPRepository)?.context?.let { ctx -> SecurePrefs(ctx).configUrl } }
        if (urlInput.isNullOrEmpty()) {
            Log.w(TAG, "Could not determine config URL")
            return
        }

        val finalUrl = if (!urlInput.startsWith("http")) {
            "http://$urlInput/Config/MsgrConfig.asmx?op=GetClientConfig&Country=00&CLCID=0419&PLCID=0419&GeoID=203&ver=14.0.8117.416"
        } else {
            urlInput
        }

        scope.safeLaunch(TAG) { 
            try {
                Log.d(TAG, "DEBUG: Requesting config from URL: $finalUrl")
                val config = rustClient?.getConfig(finalUrl)
                if (config != null) {
                    listener.onUrlReceived("CONFIG_XML", config.msnTodayUrl)
                    listener.onConfigReceived(config)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get config: ${e.message}")
            }
        }
    }

    override fun addContactToGroup(contactGuid: String, groupGuid: String) {
        scope.safeLaunch(TAG) {
            try {
                rustClient?.addContactToGroup(contactGuid, groupGuid)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add contact to group: ${e.message}")
            }
        }
    }

    override fun removeContactFromGroup(contactGuid: String, groupGuid: String) {
        scope.safeLaunch(TAG) {
            try {
                rustClient?.removeContactFromGroup(contactGuid, groupGuid)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove contact from group: ${e.message}")
            }
        }
    }

    override fun createGroup(name: String) {
        scope.safeLaunch(TAG) {
            try {
                rustClient?.createGroup(name)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create group: ${e.message}")
            }
        }
    }

    override fun deleteGroup(guid: String) {
        scope.safeLaunch(TAG) {
            try {
                rustClient?.deleteGroup(guid)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete group: ${e.message}")
            }
        }
    }

    override fun renameGroup(guid: String, newName: String) {
        scope.safeLaunch(TAG) {
            try {
                rustClient?.renameGroup(guid, newName)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to rename group: ${e.message}")
            }
        }
    }

    override fun blockContact(account: String) {
        scope.safeLaunch(TAG) {
            try {
                val event = rustClient?.addContact(account, account, MsnpList.BLOCK_LIST)
                event?.let { handleEvent(it) }
                rustClient?.removeContact(account, MsnpList.ALLOW_LIST)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to block contact: ${e.message}")
            }
        }
    }

    override fun unblockContact(account: String) {
        scope.safeLaunch(TAG) {
            try {
                rustClient?.removeContact(account, MsnpList.BLOCK_LIST)
                val event = rustClient?.addContact(account, account, MsnpList.ALLOW_LIST)
                event?.let { handleEvent(it) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unblock contact: ${e.message}")
            }
        }
    }
}
