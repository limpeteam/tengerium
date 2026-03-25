package com.limpe.tengerium.domain.model

/**
 * Доменная модель контакта.
 */
data class Contact(
    val account: String,
    val nickname: String,
    val status: String = "FLN",
    val listType: String = "FL",
    val avatarUrl: String? = null,
    val personalMessage: String? = null,
    val isTyping: Boolean = false,
    val msnObject: String? = null, // Храним сырой MSNObject для получения аватара
    val guid: String? = null, // GUID для операций в MSNP11
    val groupGuids: List<String> = emptyList()
)
