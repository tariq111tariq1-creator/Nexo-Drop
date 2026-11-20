package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TransferRecordDao {
    @Query("SELECT * FROM transfer_records ORDER BY timestamp DESC")
    fun getAllRecords(): Flow<List<TransferRecordEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: TransferRecordEntity): Long

    @Query("DELETE FROM transfer_records")
    suspend fun clearAll()

    @Query("DELETE FROM transfer_records WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface VaultItemDao {
    @Query("SELECT * FROM vault_items ORDER BY addedAt DESC")
    fun getAllVaultItems(): Flow<List<VaultItemEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: VaultItemEntity): Long

    @Query("DELETE FROM vault_items WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface HiddenAppDao {
    @Query("SELECT * FROM hidden_apps ORDER BY hiddenAt DESC")
    fun getAllHiddenApps(): Flow<List<HiddenAppEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(app: HiddenAppEntity)

    @Query("DELETE FROM hidden_apps WHERE packageName = :packageName")
    suspend fun deleteByPackage(packageName: String)
}

@Dao
interface TrustedPeerDao {
    @Query("SELECT * FROM trusted_peers ORDER BY lastConnectedAt DESC")
    fun getAllPeers(): Flow<List<TrustedPeerEntity>>

    @Query("SELECT isBanned FROM trusted_peers WHERE deviceId = :deviceId LIMIT 1")
    suspend fun isPeerBanned(deviceId: String): Boolean?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(peer: TrustedPeerEntity)

    @Query("UPDATE trusted_peers SET isBanned = :banned WHERE deviceId = :deviceId")
    suspend fun setBanned(deviceId: String, banned: Boolean)
}
