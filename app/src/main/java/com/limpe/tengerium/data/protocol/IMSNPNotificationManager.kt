package com.limpe.tengerium.data.protocol

/**
 * Интерфейс для управления Notification Server независимо от версии протокола.
 */
interface IMSNPNotificationManager {
    fun connect(
        host: String, 
        port: Int, 
        account: String, 
        password: String, 
        nexusUrl: String = "https://nexus.passport.net/rdr/pprdr.asp",
        msnObject: String? = null
    )
    fun disconnect()
    fun changeStatus(status: String, msnObject: String? = null)
    fun updateNickname(nick: String)
    fun updatePersonalMessage(psm: String)
    fun updateAvatar(bytes: ByteArray) {}
    fun addContact(acc: String, mask: Int = MSNPProto.List.FL or MSNPProto.List.AL)
    fun removeContact(acc: String, mask: Int = MSNPProto.List.FL)
    fun blockContact(acc: String) {}
    fun unblockContact(acc: String) {}
    fun requestSwitchboard(target: String? = null): Int
    
    // Новые методы для MSNP11
    fun setContactDisplayName(account: String, newName: String) {}
    fun setPrivacyMode(allowOnlyFromList: Boolean) {}
    fun requestConfig(configUrl: String? = null) {}
    fun syncContacts() {}

    // Группы
    fun addContactToGroup(contactGuid: String, groupGuid: String) {}
    fun removeContactFromGroup(contactGuid: String, groupGuid: String) {}
    fun createGroup(name: String) {}
    fun deleteGroup(guid: String) {}
    fun renameGroup(guid: String, newName: String) {}
}
