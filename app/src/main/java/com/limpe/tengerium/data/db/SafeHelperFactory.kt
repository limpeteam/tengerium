package com.limpe.tengerium.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory

/**
 * Фабрика для создания экземпляра SupportSQLiteOpenHelper с поддержкой SQLCipher.
 * Использует пароль для шифрования базы данных.
 */
class SafeHelperFactory(private val passphrase: ByteArray) : SupportSQLiteOpenHelper.Factory {

    private val factory = SupportFactory(passphrase)

    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        return factory.create(configuration)
    }

    fun clearPassphrase() {
        passphrase.fill(0)
    }
}