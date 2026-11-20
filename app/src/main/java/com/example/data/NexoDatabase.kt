package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        TransferRecordEntity::class,
        VaultItemEntity::class,
        HiddenAppEntity::class,
        TrustedPeerEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class NexoDatabase : RoomDatabase() {
    abstract fun transferRecordDao(): TransferRecordDao
    abstract fun vaultItemDao(): VaultItemDao
    abstract fun hiddenAppDao(): HiddenAppDao
    abstract fun trustedPeerDao(): TrustedPeerDao

    companion object {
        @Volatile
        private var INSTANCE: NexoDatabase? = null

        fun getInstance(context: Context): NexoDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    NexoDatabase::class.java,
                    "nexodrop_db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
