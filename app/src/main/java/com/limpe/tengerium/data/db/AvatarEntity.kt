package com.limpe.tengerium.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "avatars")
data class AvatarEntity(
    @PrimaryKey val sha1: String,
    val path: String?, // Path for custom avatars or drawable name for standard ones
    val isStandard: Boolean = false,
    val lastUsed: Long = System.currentTimeMillis()
)
