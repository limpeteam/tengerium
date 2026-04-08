package com.limpe.tengerium.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "contact_groups")
data class GroupEntity(
    @PrimaryKey val guid: String,
    val ownerAccount: String,
    val name: String
)
