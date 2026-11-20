package com.example

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.DeviceFileItem
import com.example.data.FileCategory
import com.example.data.HiddenAppEntity
import com.example.data.InstalledAppItem
import com.example.data.SaveLocationMode
import com.example.data.StorageRootInfo
import com.example.data.TransferRecordEntity
import com.example.data.UserSettings
import com.example.data.VaultItemEntity
import com.example.p2p.GroupWorkspaceState
import com.example.p2p.LocalSourceDescriptor
import com.example.p2p.P2pEvent
import com.example.p2p.PeerPermission
import com.example.p2p.ScannedQrResult
import com.example.p2p.SharedGroupItem
import com.example.p2p.TransferForegroundService
import com.example.security.CryptoEngine
import com.example.ui.theme.ThemeMode
import com.example.util.Formatters
import com.example.util.PermissionSnapshot
import com.example.util.PermissionsHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as NexoDropApplication
    private val preferences = app.preferences
    private val repository = app.repository
    private val storageRepository = app.storageRepository
    private val vaultManager = app.vaultManager
    val p2pManager = app.p2pManager

    val settings: StateFlow<UserSettings> = preferences.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, UserSettings())

    val groupState: StateFlow<GroupWorkspaceState> = p2pManager.state

    val transferRecords: StateFlow<List<TransferRecordEntity>> = repository.transferRecords
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val vaultItems: StateFlow<List<VaultItemEntity>> = repository.vaultItems
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val hiddenApps: StateFlow<List<HiddenAppEntity>> = repository.hiddenApps
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _installedApps = MutableStateFlow<List<InstalledAppItem>>(emptyList())
    val installedApps: StateFlow<List<InstalledAppItem>> = _installedApps.asStateFlow()

    private val _deviceFiles = MutableStateFlow<List<DeviceFileItem>>(emptyList())
    val deviceFiles: StateFlow<List<DeviceFileItem>> = _deviceFiles.asStateFlow()

    private val _storageRoots = MutableStateFlow<List<StorageRootInfo>>(emptyList())
    val storageRoots: StateFlow<List<StorageRootInfo>> = _storageRoots.asStateFlow()

    private val _currentFolderPath = MutableStateFlow("")
    val currentFolderPath: StateFlow<String> = _currentFolderPath.asStateFlow()

    private val _currentFolderFiles = MutableStateFlow<List<DeviceFileItem>>(emptyList())
    val currentFolderFiles: StateFlow<List<DeviceFileItem>> = _currentFolderFiles.asStateFlow()

    private val _vaultUnlocked = MutableStateFlow(false)
    val vaultUnlocked: StateFlow<Boolean> = _vaultUnlocked.asStateFlow()

    private val _permissionSnapshot = MutableStateFlow(PermissionsHelper.getSnapshot(app))
    val permissionSnapshot: StateFlow<PermissionSnapshot> = _permissionSnapshot.asStateFlow()

    private val _uiMessages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val uiMessages: SharedFlow<String> = _uiMessages.asSharedFlow()

    private val _languageChangeRequested = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val languageChangeRequested: SharedFlow<String> = _languageChangeRequested.asSharedFlow()

    init {
        viewModelScope.launch {
            preferences.ensureDeviceId()
        }
        refreshStorageAndApps(includeSystemApps = false)
        observeP2pEvents()
    }

    private fun observeP2pEvents() {
        viewModelScope.launch {
            p2pManager.events.collect { event ->
                val curSettings = settings.value
                when (event) {
                    is P2pEvent.JoinRequestArrived -> {
                        if (!curSettings.notifyCompleteOnly) {
                            TransferForegroundService.notifyAlert(
                                app,
                                app.getString(R.string.notif_join_request_title, event.request.deviceName),
                                app.getString(R.string.notif_join_request_body),
                                curSettings.silentNotifications
                            )
                        }
                    }
                    is P2pEvent.FileReceived -> {
                        TransferForegroundService.notifyAlert(
                            app,
                            app.getString(R.string.app_name),
                            app.getString(R.string.notif_file_received, event.fileName, Formatters.formatBytes(event.sizeBytes)),
                            curSettings.silentNotifications
                        )
                    }
                    is P2pEvent.GroupNotification -> {
                        _uiMessages.tryEmit(event.message)
                    }
                }
            }
        }
    }

    fun refreshPermissions() {
        _permissionSnapshot.value = PermissionsHelper.getSnapshot(app)
        refreshStorageAndApps(includeSystemApps = false)
    }

    fun refreshStorageAndApps(includeSystemApps: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            _installedApps.value = storageRepository.getInstalledApps(includeSystemApps)
            _deviceFiles.value = storageRepository.queryMediaStoreFiles()
            val roots = storageRepository.getStorageRoots()
            _storageRoots.value = roots
            if (_currentFolderPath.value.isBlank() && roots.isNotEmpty()) {
                val rootPath = roots.first().path
                _currentFolderPath.value = rootPath
                _currentFolderFiles.value = storageRepository.listRawDirectory(rootPath)
            } else if (_currentFolderPath.value.isNotBlank()) {
                _currentFolderFiles.value = storageRepository.listRawDirectory(_currentFolderPath.value)
            }
        }
    }

    fun openDirectory(path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _currentFolderPath.value = path
            _currentFolderFiles.value = storageRepository.listRawDirectory(path)
        }
    }

    fun navigateUpDirectory(): Boolean {
        val current = _currentFolderPath.value
        val roots = _storageRoots.value.map { it.path }
        if (current.isBlank() || roots.contains(current)) return false
        val parent = File(current).parent ?: return false
        openDirectory(parent)
        return true
    }

    fun createNoMediaInCurrentFolder() {
        viewModelScope.launch(Dispatchers.IO) {
            val path = _currentFolderPath.value
            if (path.isNotBlank() && storageRepository.createNoMediaFile(path)) {
                _uiMessages.tryEmit(app.getString(R.string.msg_nomedia_created))
                _currentFolderFiles.value = storageRepository.listRawDirectory(path)
            }
        }
    }

    // --- Group Actions ---

    fun startDiscovery() = p2pManager.startScanning()
    fun stopDiscovery() = p2pManager.stopScanning()
    fun createWifiDirectGroup() = p2pManager.startWifiDirectGroup()
    fun discoverWifiDirect() = p2pManager.discoverWifiDirect()
    fun startFallbackHotspot() = p2pManager.startFallbackHotspot()
    fun toggleLocalNetworkMode(enabled: Boolean) = p2pManager.toggleLocalNetworkMode(enabled)

    fun connectToDirectPeer(peer: com.example.p2p.WifiDirectPeer, pin: String = "") {
        p2pManager.connectToDirectPeer(peer, pin)
    }

    fun joinViaDirectCredentials(
        ssid: String,
        passphrase: String,
        hostIp: String,
        hostPort: Int,
        groupName: String,
        pin: String
    ) {
        p2pManager.joinViaDirectQrOrCredentials(ssid, passphrase, hostIp, hostPort, groupName, pin)
    }

    fun parseScannedQr(raw: String): ScannedQrResult? {
        val text = raw.trim()
        if (text.startsWith("{")) {
            return runCatching {
                val j = org.json.JSONObject(text)
                if (j.optString("t") == "nx_direct" || j.has("ssid") || j.has("ip")) {
                    ScannedQrResult(
                        ssid = j.optString("ssid", ""),
                        passphrase = j.optString("pass", ""),
                        hostIp = j.optString("ip", "192.168.49.1"),
                        hostPort = j.optInt("port", com.example.p2p.P2pGroupManager.PRIMARY_P2P_PORT),
                        groupName = j.optString("name", "Direct Group"),
                        hasPin = j.optBoolean("pin", false)
                    )
                } else null
            }.getOrNull()
        }
        if (text.startsWith("WIFI:", ignoreCase = true)) {
            val ssid = Regex("S:([^;]+)").find(text)?.groupValues?.getOrNull(1) ?: ""
            val pass = Regex("P:([^;]+)").find(text)?.groupValues?.getOrNull(1) ?: ""
            if (ssid.isNotBlank()) {
                return ScannedQrResult(
                    ssid = ssid,
                    passphrase = pass,
                    hostIp = "192.168.43.1",
                    hostPort = com.example.p2p.P2pGroupManager.PRIMARY_P2P_PORT,
                    groupName = ssid,
                    hasPin = false
                )
            }
        }
        if (text.startsWith("nexodrop://", ignoreCase = true)) {
            return runCatching {
                val uri = Uri.parse(text)
                val ip = uri.host ?: return null
                val port = if (uri.port > 0) uri.port else com.example.p2p.P2pGroupManager.PRIMARY_P2P_PORT
                val name = uri.getQueryParameter("name") ?: ip
                ScannedQrResult(
                    ssid = "",
                    passphrase = "",
                    hostIp = ip,
                    hostPort = port,
                    groupName = name,
                    hasPin = false
                )
            }.getOrNull()
        }
        return null
    }

    fun createGroup(name: String, pin: String, autoApprove: Boolean, webShare: Boolean) {
        p2pManager.createGroup(name, pin, autoApprove, webShare)
    }

    fun joinGroup(ip: String, port: Int, groupName: String, pin: String) {
        p2pManager.joinGroup(ip, port, groupName, pin)
    }

    fun closeOrLeaveGroup() = p2pManager.closeOrLeaveGroup()
    fun respondJoinRequest(requestId: String, approve: Boolean) = p2pManager.respondToJoinRequest(requestId, approve)
    fun updatePeerPermission(deviceId: String, perm: PeerPermission) = p2pManager.updatePeerPermission(deviceId, perm)
    fun kickOrBanPeer(deviceId: String, ban: Boolean) = p2pManager.kickOrBanPeer(deviceId, ban)
    fun sendChat(text: String) = p2pManager.sendChatMessage(text)
    fun downloadGroupItem(item: SharedGroupItem, toVault: Boolean) = p2pManager.downloadGroupItem(item, toVault)
    fun cancelTransfer(transferId: String) = p2pManager.cancelTransfer(transferId)

    fun shareSelectedToGroup(
        selectedApps: List<InstalledAppItem>,
        selectedFiles: List<DeviceFileItem>,
        extraUris: List<Uri> = emptyList()
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val descriptors = mutableListOf<LocalSourceDescriptor>()
            selectedApps.forEach { appItem ->
                val packaged = storageRepository.prepareAppForTransfer(appItem)
                descriptors += LocalSourceDescriptor(
                    itemId = UUID.randomUUID().toString().take(10),
                    fileName = packaged.name,
                    fileSize = packaged.length(),
                    mimeType = storageRepository.guessMimeType(packaged.name),
                    uri = null,
                    filePath = packaged.absolutePath,
                    isAppPackage = true
                )
            }
            selectedFiles.filter { !it.isDirectory }.forEach { f ->
                descriptors += LocalSourceDescriptor(
                    itemId = UUID.randomUUID().toString().take(10),
                    fileName = f.name,
                    fileSize = f.size,
                    mimeType = f.mimeType,
                    uri = f.uri,
                    filePath = f.filePath,
                    isAppPackage = f.category == FileCategory.APP
                )
            }
            extraUris.forEach { uri ->
                storageRepository.resolveUriMetadata(uri)?.let { meta ->
                    descriptors += LocalSourceDescriptor(
                        itemId = UUID.randomUUID().toString().take(10),
                        fileName = meta.name,
                        fileSize = meta.size,
                        mimeType = meta.mimeType,
                        uri = meta.uri,
                        filePath = meta.filePath,
                        isAppPackage = meta.category == FileCategory.APP
                    )
                }
            }
            if (descriptors.isNotEmpty()) {
                p2pManager.addFilesToSharedGroup(descriptors)
                _uiMessages.tryEmit(app.getString(R.string.msg_added_to_group, descriptors.size))
            }
        }
    }

    // --- Vault & Hidden Apps ---

    fun setupVaultPin(pin: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val salt = CryptoEngine.generateSalt()
            val hash = CryptoEngine.hashPin(pin, salt)
            preferences.setVaultPin(hash, salt)
            _vaultUnlocked.value = true
        }
    }

    fun unlockVaultWithPin(pin: String): Boolean {
        val s = settings.value
        val valid = CryptoEngine.verifyPin(pin, s.vaultPinSalt, s.vaultPinHash)
        if (valid) _vaultUnlocked.value = true
        return valid
    }

    fun unlockVaultWithBiometrics() {
        _vaultUnlocked.value = true
    }

    fun lockVault() {
        _vaultUnlocked.value = false
        vaultManager.clearPreviewCache()
    }

    fun hideFilesInVault(files: List<DeviceFileItem>, uris: List<Uri> = emptyList()) {
        viewModelScope.launch(Dispatchers.IO) {
            var count = 0
            files.filter { !it.isDirectory }.forEach { f ->
                runCatching {
                    storageRepository.openInputStream(f.uri, f.filePath).use { input ->
                        val res = vaultManager.encryptIntoVault(input)
                        repository.addVaultItem(
                            VaultItemEntity(
                                originalName = f.name,
                                encryptedFileName = res.encryptedFileName,
                                fileSize = res.plainSize,
                                mimeType = f.mimeType,
                                category = f.category.name,
                                sha256 = res.sha256,
                                originalUriOrPath = f.filePath ?: f.uri?.toString() ?: ""
                            )
                        )
                        // If raw file path is writable, remove public copy so it's truly hidden
                        f.filePath?.let { File(it).takeIf { file -> file.canWrite() }?.delete() }
                        count++
                    }
                }
            }
            uris.forEach { uri ->
                storageRepository.resolveUriMetadata(uri)?.let { meta ->
                    runCatching {
                        storageRepository.openInputStream(meta.uri, meta.filePath).use { input ->
                            val res = vaultManager.encryptIntoVault(input)
                            repository.addVaultItem(
                                VaultItemEntity(
                                    originalName = meta.name,
                                    encryptedFileName = res.encryptedFileName,
                                    fileSize = res.plainSize,
                                    mimeType = meta.mimeType,
                                    category = meta.category.name,
                                    sha256 = res.sha256,
                                    originalUriOrPath = uri.toString()
                                )
                            )
                            count++
                        }
                    }
                }
            }
            if (count > 0) {
                _uiMessages.tryEmit(app.getString(R.string.msg_saved_to_vault))
                refreshStorageAndApps(false)
            }
        }
    }

    fun hideInstalledApps(apps: List<InstalledAppItem>) {
        viewModelScope.launch(Dispatchers.IO) {
            apps.forEach { a -> repository.hideApp(a.packageName, a.label) }
        }
    }

    fun unhideApp(packageName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.unhideApp(packageName)
        }
    }

    fun openVaultItem(item: VaultItemEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val previewFile = vaultManager.decryptToTempPreviewFile(item.encryptedFileName, item.originalName)
            withContext(Dispatchers.Main) {
                if (item.originalName.lowercase().endsWith(".apk") || item.originalName.lowercase().endsWith(".apks")) {
                    storageRepository.installApkOrApks(previewFile)
                } else {
                    storageRepository.openFileWithSystem(previewFile, item.mimeType)
                }
            }
        }
    }

    fun exportVaultItemToStorage(item: VaultItemEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val temp = vaultManager.decryptToTempPreviewFile(item.encryptedFileName, item.originalName)
            val curSettings = settings.value.copy(
                saveMode = if (settings.value.saveMode == SaveLocationMode.PRIVATE_VAULT) {
                    SaveLocationMode.APP_DOWNLOADS
                } else settings.value.saveMode
            )
            storageRepository.saveReceivedTempFile(temp, item.originalName, item.mimeType, curSettings, forceVault = false)
            temp.delete()
            vaultManager.deleteEncryptedFile(item.encryptedFileName)
            repository.removeVaultItem(item.id)
        }
    }

    fun deleteVaultItem(item: VaultItemEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            vaultManager.deleteEncryptedFile(item.encryptedFileName)
            repository.removeVaultItem(item.id)
        }
    }

    // --- Reports ---

    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) { repository.clearTransferHistory() }
    }

    fun exportCsvReport(records: List<TransferRecordEntity>) {
        viewModelScope.launch {
            val file = storageRepository.exportTransferReportCsv(records)
            _uiMessages.tryEmit(app.getString(R.string.reports_exported_toast))
            storageRepository.shareExportedReport(file, "text/csv")
        }
    }

    fun exportPdfReport(records: List<TransferRecordEntity>) {
        viewModelScope.launch {
            val file = storageRepository.exportTransferReportPdf(records)
            _uiMessages.tryEmit(app.getString(R.string.reports_exported_toast))
            storageRepository.shareExportedReport(file, "application/pdf")
        }
    }

    // --- Settings ---

    fun setOnboardingCompleted() {
        viewModelScope.launch { preferences.setOnboardingCompleted(true) }
    }

    fun setDeviceName(name: String) {
        viewModelScope.launch { preferences.setDeviceName(name) }
    }

    fun setLanguage(code: String) {
        viewModelScope.launch {
            preferences.setLanguageCode(code)
            _languageChangeRequested.tryEmit(code)
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { preferences.setThemeMode(mode) }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { preferences.setDynamicColor(enabled) }
    }

    fun setSaveMode(mode: SaveLocationMode) {
        viewModelScope.launch { preferences.setSaveMode(mode) }
    }

    fun setCustomSaveTree(uri: Uri) {
        viewModelScope.launch {
            val label = storageRepository.getTreeDisplayName(uri)
            preferences.setCustomSaveFolder(uri.toString(), label)
        }
    }

    fun setBiometricEnabled(enabled: Boolean) {
        viewModelScope.launch { preferences.setBiometricEnabled(enabled) }
    }

    fun setStripExif(enabled: Boolean) {
        viewModelScope.launch { preferences.setStripExifOnSend(enabled) }
    }

    fun setSilentNotifications(enabled: Boolean) {
        viewModelScope.launch { preferences.setSilentNotifications(enabled) }
    }

    fun setNotifyCompleteOnly(enabled: Boolean) {
        viewModelScope.launch { preferences.setNotifyCompleteOnly(enabled) }
    }

    override fun onCleared() {
        super.onCleared()
        vaultManager.clearPreviewCache()
    }
}
