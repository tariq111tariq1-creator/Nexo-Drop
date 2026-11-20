package com.example.data

import kotlinx.coroutines.flow.Flow

class NexoRepository(private val db: NexoDatabase) {

    val transferRecords: Flow<List<TransferRecordEntity>> = db.transferRecordDao().getAllRecords()
    val vaultItems: Flow<List<VaultItemEntity>> = db.vaultItemDao().getAllVaultItems()
    val hiddenApps: Flow<List<HiddenAppEntity>> = db.hiddenAppDao().getAllHiddenApps()
    val trustedPeers: Flow<List<TrustedPeerEntity>> = db.trustedPeerDao().getAllPeers()

    suspend fun recordTransfer(record: TransferRecordEntity) {
        db.transferRecordDao().insert(record)
    }

    suspend fun clearTransferHistory() {
        db.transferRecordDao().clearAll()
    }

    suspend fun addVaultItem(item: VaultItemEntity): Long {
        return db.vaultItemDao().insert(item)
    }

    suspend fun removeVaultItem(id: Long) {
        db.vaultItemDao().deleteById(id)
    }

    suspend fun hideApp(packageName: String, label: String) {
        db.hiddenAppDao().insert(HiddenAppEntity(packageName = packageName, appLabel = label))
    }

    suspend fun unhideApp(packageName: String) {
        db.hiddenAppDao().deleteByPackage(packageName)
    }

    suspend fun upsertTrustedPeer(peer: TrustedPeerEntity) {
        db.trustedPeerDao().upsert(peer)
    }

    suspend fun setPeerBanned(deviceId: String, banned: Boolean) {
        db.trustedPeerDao().setBanned(deviceId, banned)
    }

    suspend fun isPeerBanned(deviceId: String): Boolean {
        return db.trustedPeerDao().isPeerBanned(deviceId) == true
    }
}
