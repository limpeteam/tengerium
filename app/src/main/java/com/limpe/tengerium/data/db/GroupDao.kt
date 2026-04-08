package com.limpe.tengerium.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface GroupDao {
    @Query("SELECT * FROM contact_groups WHERE ownerAccount = :owner")
    fun getGroups(owner: String): Flow<List<GroupEntity>>

    @Query("SELECT * FROM contact_groups WHERE ownerAccount = :owner")
    suspend fun getGroupsSync(owner: String): List<GroupEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGroups(groups: List<GroupEntity>)

    @Query("DELETE FROM contact_groups WHERE guid = :guid")
    suspend fun deleteGroup(guid: String)

    @Query("DELETE FROM contact_groups WHERE ownerAccount = :owner")
    suspend fun clearAll(owner: String)

    @Transaction
    suspend fun syncGroups(owner: String, serverGroups: List<GroupEntity>) {
        val localGroups = getGroupsSync(owner)
        val serverGuids = serverGroups.map { it.guid }.toSet()
        
        localGroups.forEach { local ->
            if (!serverGuids.contains(local.guid)) {
                deleteGroup(local.guid)
            }
        }
        insertGroups(serverGroups)
    }
}
