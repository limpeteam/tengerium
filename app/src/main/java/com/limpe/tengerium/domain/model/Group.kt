package com.limpe.tengerium.domain.model

/**
 * Доменная модель группы контактов.
 */
data class Group(
    val guid: String,
    val name: String,
    val contactAccounts: List<String> = emptyList()
)
