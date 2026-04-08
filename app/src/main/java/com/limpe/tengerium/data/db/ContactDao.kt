package com.limpe.tengerium.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts WHERE ownerAccount = :owner")
    fun getContacts(owner: String): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE ownerAccount = :owner")
    suspend fun getContactsSync(owner: String): List<ContactEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertContacts(contacts: List<ContactEntity>)

    @Query("DELETE FROM contacts WHERE ownerAccount = :owner AND account = :account")
    suspend fun deleteContact(owner: String, account: String)

    @Query("DELETE FROM contacts WHERE ownerAccount = :owner")
    suspend fun clearAll(owner: String)

    @Transaction
    suspend fun syncContacts(owner: String, serverContacts: List<ContactEntity>) {
        val localContacts = getContactsSync(owner)
        val serverAccounts = serverContacts.map { it.account }.toSet()
        
        // 1. Remove those not on server
        localContacts.forEach { local ->
            if (!serverAccounts.contains(local.account)) {
                deleteContact(owner, local.account)
            }
        }
        
        // 2. Insert/Update from server
        insertContacts(serverContacts)
    }
}
