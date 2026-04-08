package com.limpe.tengerium.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Сущность контакта для локального хранения.
 */
@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val account: String, // lowercase
    val ownerAccount: String,
    val nickname: String,
    val listType: String = "FL",
    val avatarUrl: String? = null,
    val personalMessage: String? = null,
    val guid: String? = null,
    val groupGuids: String = "" // Сохраненные через запятую GUID групп
)
