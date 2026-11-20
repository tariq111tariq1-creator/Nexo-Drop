package com.example

import android.app.Application
import com.example.data.AppPreferences
import com.example.data.BackupManager
import com.example.data.NexoDatabase
import com.example.data.NexoRepository
import com.example.data.StorageRepository
import com.example.p2p.P2pGroupManager
import com.example.security.VaultManager

class NexoDropApplication : Application() {

    lateinit var preferences: AppPreferences
        private set
    lateinit var database: NexoDatabase
        private set
    lateinit var repository: NexoRepository
        private set
    lateinit var vaultManager: VaultManager
        private set
    lateinit var storageRepository: StorageRepository
        private set
    lateinit var backupManager: BackupManager
        private set
    lateinit var p2pManager: P2pGroupManager
        private set

    override fun onCreate() {
        super.onCreate()
        preferences = AppPreferences(this)
        database = NexoDatabase.getInstance(this)
        repository = NexoRepository(database)
        vaultManager = VaultManager(this)
        storageRepository = StorageRepository(this, vaultManager)
        backupManager = BackupManager(this, repository, storageRepository, vaultManager, preferences)
        p2pManager = P2pGroupManager(this, preferences, repository, storageRepository)
    }
}
