package com.limpe.tengerium.data

import android.content.Context
import com.limpe.tengerium.data.db.AppDatabase
import com.limpe.tengerium.data.db.AvatarEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class AvatarLibraryManager(
    private val context: Context,
    private val database: AppDatabase,
    private val avatarManager: AvatarManager,
    private val scope: kotlinx.coroutines.CoroutineScope
) {
    private val avatarDao = database.avatarDao()

    val allAvatars: Flow<List<AvatarEntity>> = avatarDao.getAllAvatars()

    init {
        scope.launch {
            if (avatarDao.getAllAvatars().first().isEmpty()) {
                initStandardAvatars()
            }
        }
    }

    private suspend fun initStandardAvatars() {
        val standards = listOf(
            "car", "cat", "dog", "ball", "drip", "duck", "fish", "frog",
            "kick", "beach", "chess", "guitar", "horses", "skater",
            "liftoff", "airplane", "dirtbike", "palmtree", "astronaut",
            "butterfly123", "redflower", "snowflake", "pinkflower"
        )
        
        standards.forEach { name ->
            avatarDao.insertAvatar(AvatarEntity(
                sha1 = "std_$name",
                path = name,
                isStandard = true
            ))
        }
    }

    suspend fun addCustomAvatar(bytes: ByteArray): AvatarEntity {
        val sha1 = avatarManager.calculateSha1(bytes)
        val path = avatarManager.saveAvatar(bytes, sha1)
        val entity = AvatarEntity(
            sha1 = sha1,
            path = path,
            isStandard = false
        )
        avatarDao.insertAvatar(entity)
        return entity
    }
    
    suspend fun updateLastUsed(sha1: String) {
        avatarDao.updateLastUsed(sha1)
    }
}
