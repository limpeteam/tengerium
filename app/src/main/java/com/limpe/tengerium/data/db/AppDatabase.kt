package com.limpe.tengerium.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Зашифрованная БД Tengerium.
 */
@Database(entities = [MessageEntity::class, AvatarEntity::class], version = 5, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun messageDao(): MessageDao
    abstract fun avatarDao(): AvatarDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context, passphrase: ByteArray): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val helperFactory = SafeHelperFactory(passphrase)
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "tengerium_secure.db"
                )
                .openHelperFactory(helperFactory)
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
