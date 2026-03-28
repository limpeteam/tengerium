package com.limpe.tengerium.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface AvatarDao {
    @Query("SELECT * FROM avatars ORDER BY lastUsed DESC")
    fun getAllAvatars(): Flow<List<AvatarEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAvatar(avatar: AvatarEntity)

    @Delete
    suspend fun deleteAvatar(avatar: AvatarEntity)

    @Query("SELECT * FROM avatars WHERE sha1 = :sha1 LIMIT 1")
    suspend fun getAvatarBySha1(sha1: String): AvatarEntity?

    @Query("UPDATE avatars SET lastUsed = :timestamp WHERE sha1 = :sha1")
    suspend fun updateLastUsed(sha1: String, timestamp: Long = System.currentTimeMillis())
}
