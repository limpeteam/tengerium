package com.limpe.tengerium.data

import com.limpe.tengerium.data.protocol.MSNPProto
import com.limpe.tengerium.domain.model.Contact
import com.limpe.tengerium.domain.model.Group
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.*

// Менеджер контактов. Знает, кто в сети, кто печатает, а кто прислал запрос в друзья.
class ContactManager {
    // Весь ростер контактов. Главный источник правды для списков в UI.
    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())
    val contacts: StateFlow<List<Contact>> = _contacts.asStateFlow()

    // Группы контактов из SDK.
    private val _groups = MutableStateFlow<List<Group>>(emptyList())
    val groups: StateFlow<List<Group>> = _groups.asStateFlow()

    // Входящие заявки. Висят тут, пока юзер не решит их судьбу.
    private val _pendingRequests = MutableStateFlow<List<Contact>>(emptyList())
    val pendingRequests: StateFlow<List<Contact>> = _pendingRequests.asStateFlow()

    // Наш текущий статус. По умолчанию — шифруемся (Offline).
    private val _myStatus = MutableStateFlow(MSNPProto.Status.OFFLINE)
    val myStatus: StateFlow<String> = _myStatus.asStateFlow()

    // Наша "цитата" в профиле. PSM — Personal Status Message.
    private val _myPersonalMessage = MutableStateFlow("")
    val myPersonalMessage: StateFlow<String> = _myPersonalMessage.asStateFlow()

    private val _contactAddedFlow = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val contactAddedFlow: SharedFlow<String> = _contactAddedFlow.asSharedFlow()

    /**
     * Обновляет статус контакта.
     * @return true, если контакт перешел из оффлайна в любой онлайн статус.
     */
    fun updateContactStatus(account: String, status: String, nickname: String, avatarUrl: String? = null, psm: String? = null): Boolean {
        val normalized = account.lowercase(Locale.ROOT).trim()
        var cameOnline = false
        
        _contacts.update { list ->
            val existing = list.find { it.account.lowercase(Locale.ROOT).trim() == normalized }
            if (existing != null) {
                val oldStatus = existing.status
                val wasOffline = oldStatus == MSNPProto.Status.OFFLINE || oldStatus == "FLN"
                val isNowOnline = status.isNotEmpty() && status != MSNPProto.Status.OFFLINE && status != "FLN"
                
                if (wasOffline && isNowOnline) {
                    cameOnline = true
                }

                // Проверяем, изменилось ли хоть что-то
                val statusChanged = status.isNotEmpty() && existing.status != status
                val nicknameChanged = nickname.isNotEmpty() && existing.nickname != nickname
                val avatarChanged = avatarUrl != null && existing.avatarUrl != avatarUrl
                val psmChanged = psm != null && existing.personalMessage != psm

                if (!statusChanged && !nicknameChanged && !avatarChanged && !psmChanged) {
                    return@update list
                }

                list.map { 
                    if (it.account.lowercase(Locale.ROOT).trim() == normalized) {
                        val updatedNick = if (nickname.isNotEmpty() && (nickname != account || it.nickname.isEmpty() || it.nickname == it.account)) {
                            nickname
                        } else {
                            it.nickname
                        }
                        
                        it.copy(
                            status = if (status.isNotEmpty()) status else it.status, 
                            nickname = updatedNick,
                            avatarUrl = avatarUrl ?: it.avatarUrl,
                            personalMessage = psm ?: it.personalMessage
                        ) 
                    } else it 
                }
            } else {
                val isNowOnline = status.isNotEmpty() && status != MSNPProto.Status.OFFLINE && status != "FLN"
                if (isNowOnline) cameOnline = true
                
                list + Contact(account, nickname, status, avatarUrl = avatarUrl, personalMessage = psm ?: "")
            }
        }
        return cameOnline
    }

    fun setTyping(account: String, isTyping: Boolean) {
        val normalized = account.lowercase(Locale.ROOT).trim()
        _contacts.update { list ->
            list.map { if (it.account.lowercase(Locale.ROOT).trim() == normalized) it.copy(isTyping = isTyping) else it }
        }
    }

    fun addOrUpdateContact(account: String, nickname: String, listType: String, emitEvent: Boolean = true, groupGuids: List<String> = emptyList()) {
        val normalized = account.lowercase(Locale.ROOT).trim()
        var isActuallyNew = false
        _contacts.update { list ->
            val existing = list.find { it.account.lowercase(Locale.ROOT).trim() == normalized }
            if (existing == null) {
                isActuallyNew = true
                list + Contact(account, nickname, listType = listType, guid = null, groupGuids = groupGuids)
            } else {
                list.map { 
                    if (it.account.lowercase(Locale.ROOT).trim() == normalized) {
                        val updatedNick = if (nickname.isNotEmpty() && nickname != account) nickname else it.nickname
                        it.copy(listType = listType, nickname = updatedNick, groupGuids = groupGuids)
                    } else it 
                }
            }
        }
        
        if (groupGuids.isNotEmpty()) {
            _groups.update { groups ->
                groups.map { group ->
                    if (groupGuids.contains(group.guid)) {
                        if (!group.contactAccounts.contains(normalized)) {
                            group.copy(contactAccounts = group.contactAccounts + normalized)
                        } else group
                    } else {
                        group.copy(contactAccounts = group.contactAccounts - normalized)
                    }
                }
            }
        }

        if (isActuallyNew && emitEvent) {
            _contactAddedFlow.tryEmit(normalized)
        }
    }

    fun updateContactFullInfo(account: String, nickname: String, guid: String, listType: String, groupGuids: List<String>) {
        val normalized = account.lowercase(Locale.ROOT).trim()
        _contacts.update { list ->
            val existing = list.find { it.account.lowercase(Locale.ROOT).trim() == normalized }
            if (existing == null) {
                list + Contact(account, nickname, listType = listType, guid = guid, groupGuids = groupGuids)
            } else {
                list.map { 
                    if (it.account.lowercase(Locale.ROOT).trim() == normalized) {
                        it.copy(nickname = nickname, listType = listType, guid = guid, groupGuids = groupGuids)
                    } else it 
                }
            }
        }

        _groups.update { groups ->
            groups.map { group ->
                if (groupGuids.contains(group.guid)) {
                    if (!group.contactAccounts.contains(normalized)) {
                        group.copy(contactAccounts = group.contactAccounts + normalized)
                    } else group
                } else {
                    group.copy(contactAccounts = group.contactAccounts - normalized)
                }
            }
        }
    }

    fun addContactToGroupLocally(account: String, groupGuid: String) {
        val normalized = account.lowercase(Locale.ROOT).trim()
        _contacts.update { list ->
            list.map { 
                if (it.account.lowercase(Locale.ROOT).trim() == normalized) {
                    if (!it.groupGuids.contains(groupGuid)) {
                        it.copy(groupGuids = it.groupGuids + groupGuid)
                    } else it
                } else it
            }
        }
        _groups.update { groups ->
            groups.map { group ->
                if (group.guid == groupGuid && !group.contactAccounts.contains(normalized)) {
                    group.copy(contactAccounts = group.contactAccounts + normalized)
                } else group
            }
        }
    }

    fun removeContactFromGroupLocally(account: String, groupGuid: String) {
        val normalized = account.lowercase(Locale.ROOT).trim()
        _contacts.update { list ->
            list.map { 
                if (it.account.lowercase(Locale.ROOT).trim() == normalized) {
                    it.copy(groupGuids = it.groupGuids - groupGuid)
                } else it
            }
        }
        _groups.update { groups ->
            groups.map { group ->
                if (group.guid == groupGuid) {
                    group.copy(contactAccounts = group.contactAccounts - normalized)
                } else group
            }
        }
    }

    fun addGroup(name: String, guid: String) {
        val accountsInGroup = _contacts.value
            .filter { it.groupGuids.contains(guid) }
            .map { it.account.lowercase(Locale.ROOT).trim() }

        _groups.update { list ->
            if (list.none { it.guid == guid }) {
                list + Group(guid, name, accountsInGroup)
            } else {
                list.map { if (it.guid == guid) it.copy(name = name, contactAccounts = accountsInGroup) else it }
            }
        }
    }

    fun removeGroup(guid: String) {
        _groups.update { it.filter { g -> g.guid != guid } }
    }

    fun removeContact(account: String) {
        val normalized = account.lowercase(Locale.ROOT).trim()
        _contacts.update { it.filter { c -> c.account.lowercase(Locale.ROOT).trim() != normalized } }
        _groups.update { groups ->
            groups.map { it.copy(contactAccounts = it.contactAccounts - normalized) }
        }
    }

    fun setAllOffline() {
        _contacts.update { list -> list.map { it.copy(status = "FLN", isTyping = false) } }
        _myStatus.value = "FLN"
    }

    fun updateMyStatus(status: String) { _myStatus.value = status }
    fun updateMyPersonalMessage(psm: String) { _myPersonalMessage.value = psm }
    
    fun addPendingRequest(account: String, nickname: String) {
        val normalized = account.lowercase(Locale.ROOT).trim()
        _pendingRequests.update { list ->
            if (list.none { it.account.lowercase(Locale.ROOT).trim() == normalized })
                list + Contact(account, nickname)
            else list
        }
    }

    fun clearPendingRequest(account: String) {
        val normalized = account.lowercase(Locale.ROOT).trim()
        _pendingRequests.update { list -> list.filter { it.account.lowercase(Locale.ROOT).trim() != normalized } }
    }
    
    fun clear() {
        _contacts.value = emptyList()
        _groups.value = emptyList()
        _pendingRequests.value = emptyList()
        _myStatus.value = MSNPProto.Status.OFFLINE
        _myPersonalMessage.value = ""
    }
}
