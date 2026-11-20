package com.example.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.example.security.VaultManager
import com.example.ui.theme.ThemeMode
import com.example.util.Formatters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class BackupProgress(
    val current: Int,
    val total: Int,
    val currentLabel: String
)

data class BackupResult(
    val success: Boolean,
    val outputUri: Uri? = null,
    val outputPath: String? = null,
    val fileCount: Int = 0,
    val skippedCount: Int = 0,
    val totalBytes: Long = 0L,
    val summaryLabel: String = "",
    val error: String? = null
)

data class RestoreResult(
    val success: Boolean,
    val vaultItemsRestored: Int = 0,
    val hiddenAppsRestored: Int = 0,
    val historyRestored: Int = 0,
    val settingsRestored: Boolean = false,
    val error: String? = null
)

class BackupManager(
    private val context: Context,
    private val repository: NexoRepository,
    private val storageRepository: StorageRepository,
    private val vaultManager: VaultManager,
    private val preferences: AppPreferences
) {
    companion object {
        private const val NXBK_MAGIC = 0x4E58424B // "NXBK"
        private const val NXBK_VERSION = 1
        private const val PBKDF2_ITERATIONS = 120_000
        private const val CHUNK_SIZE = 256 * 1024
    }

    /**
     * 1. Installed Apps Backup (.apk / .apks + apps_manifest.json)
     * If compressAsZip == true, writes a single portable NexoDrop_Apps_<stamp>.zip (ideal for P2P transfer).
     * Otherwise writes individual APKs + manifest into the chosen folder or Downloads/NexoDrop/Backups/Apps_<stamp>.
     */
    suspend fun backupInstalledApps(
        apps: List<InstalledAppItem>,
        compressAsZip: Boolean,
        targetTreeUri: Uri?,
        onProgress: (BackupProgress) -> Unit = {}
    ): BackupResult = withContext(Dispatchers.IO) {
        if (apps.isEmpty()) {
            return@withContext BackupResult(success = false, error = "No apps selected")
        }
        val stamp = Formatters.formatBackupStamp(System.currentTimeMillis())
        val manifestArr = JSONArray()
        val total = apps.size
        var totalBytes = 0L

        return@withContext runCatching {
            if (compressAsZip) {
                val tempZip = File(context.cacheDir, "NexoDrop_Apps_$stamp.zip")
                try {
                    ZipOutputStream(BufferedOutputStream(FileOutputStream(tempZip))).use { zos ->
                        apps.forEachIndexed { idx, app ->
                            onProgress(BackupProgress(idx + 1, total, app.label))
                            val packaged = storageRepository.prepareAppForTransfer(app)
                            totalBytes += packaged.length()
                            manifestArr.put(JSONObject().apply {
                                put("packageName", app.packageName)
                                put("label", app.label)
                                put("versionName", app.versionName)
                                put("versionCode", app.versionCode)
                                put("isSplitApk", app.isSplitApk)
                                put("fileName", packaged.name)
                                put("sizeBytes", packaged.length())
                                put("backedUpAt", System.currentTimeMillis())
                            })
                            zos.putNextEntry(ZipEntry(packaged.name))
                            BufferedInputStream(FileInputStream(packaged)).use { bis ->
                                bis.copyTo(zos, 64 * 1024)
                            }
                            zos.closeEntry()
                        }
                        val manifestBytes = JSONObject().apply {
                            put("createdAt", System.currentTimeMillis())
                            put("count", apps.size)
                            put("apps", manifestArr)
                        }.toString(2).toByteArray(Charsets.UTF_8)
                        zos.putNextEntry(ZipEntry("apps_manifest.json"))
                        zos.write(manifestBytes)
                        zos.closeEntry()
                    }
                    val saved = persistBackupOutputFile(
                        tempFile = tempZip,
                        fileName = tempZip.name,
                        mimeType = "application/zip",
                        targetTreeUri = targetTreeUri,
                        subfolderName = "Backups"
                    )
                    BackupResult(
                        success = true,
                        outputUri = saved.first,
                        outputPath = saved.second,
                        fileCount = apps.size,
                        totalBytes = tempZip.length(),
                        summaryLabel = tempZip.name
                    )
                } finally {
                    tempZip.delete()
                }
            } else {
                var lastUri: Uri? = null
                var lastLocation = ""
                val folderName = "Apps_$stamp"
                val destTreeDir = targetTreeUri?.let { createOrGetTreeSubdir(it, folderName) }

                apps.forEachIndexed { idx, app ->
                    onProgress(BackupProgress(idx + 1, total, app.label))
                    val packaged = storageRepository.prepareAppForTransfer(app)
                    totalBytes += packaged.length()
                    manifestArr.put(JSONObject().apply {
                        put("packageName", app.packageName)
                        put("label", app.label)
                        put("versionName", app.versionName)
                        put("versionCode", app.versionCode)
                        put("isSplitApk", app.isSplitApk)
                        put("fileName", packaged.name)
                        put("sizeBytes", packaged.length())
                        put("backedUpAt", System.currentTimeMillis())
                    })
                    val saved = persistBackupOutputFile(
                        tempFile = packaged,
                        fileName = packaged.name,
                        mimeType = storageRepository.guessMimeType(packaged.name),
                        targetTreeUri = destTreeDir?.uri ?: targetTreeUri,
                        subfolderName = "Backups/$folderName"
                    )
                    lastUri = saved.first
                    lastLocation = saved.second ?: "Downloads/NexoDrop/Backups/$folderName"
                }

                val manifestTemp = File(context.cacheDir, "apps_manifest_$stamp.json")
                manifestTemp.writeText(
                    JSONObject().apply {
                        put("createdAt", System.currentTimeMillis())
                        put("count", apps.size)
                        put("apps", manifestArr)
                    }.toString(2)
                )
                persistBackupOutputFile(
                    tempFile = manifestTemp,
                    fileName = "apps_manifest.json",
                    mimeType = "application/json",
                    targetTreeUri = destTreeDir?.uri ?: targetTreeUri,
                    subfolderName = "Backups/$folderName"
                )
                manifestTemp.delete()

                BackupResult(
                    success = true,
                    outputUri = lastUri,
                    outputPath = lastLocation,
                    fileCount = apps.size,
                    totalBytes = totalBytes,
                    summaryLabel = lastLocation
                )
            }
        }.getOrElse { err ->
            BackupResult(success = false, error = err.localizedMessage ?: "App backup failed")
        }
    }

    /**
     * 2. Files & Folders Backup:
     * - Either ZIP compression (NexoDrop_Files_<stamp>.zip)
     * - Or Incremental folder mirror (skips already-backed-up files with matching name & size).
     */
    suspend fun backupFilesAndFolders(
        sourceFolderPath: String?,
        selectedFiles: List<DeviceFileItem>,
        compressZip: Boolean,
        incrementalOnly: Boolean,
        targetTreeUri: Uri?,
        onProgress: (BackupProgress) -> Unit = {}
    ): BackupResult = withContext(Dispatchers.IO) {
        val stamp = Formatters.formatBackupStamp(System.currentTimeMillis())
        return@withContext runCatching {
            val fileSources = mutableListOf<Pair<String, DeviceFileItem>>()
            if (!sourceFolderPath.isNullOrBlank()) {
                val rootDir = File(sourceFolderPath)
                if (rootDir.exists() && rootDir.isDirectory) {
                    collectDirectoryFiles(rootDir, rootDir, fileSources)
                }
            }
            selectedFiles.filter { !it.isDirectory }.forEach { f ->
                fileSources += f.name to f
            }
            if (fileSources.isEmpty()) {
                return@withContext BackupResult(success = false, error = "No files found to back up")
            }

            val total = fileSources.size
            if (compressZip) {
                val tempZip = File(context.cacheDir, "NexoDrop_Files_$stamp.zip")
                var totalBytes = 0L
                val usedNames = HashSet<String>()
                try {
                    ZipOutputStream(BufferedOutputStream(FileOutputStream(tempZip))).use { zos ->
                        fileSources.forEachIndexed { idx, (relPath, item) ->
                            onProgress(BackupProgress(idx + 1, total, item.name))
                            val safeEntry = uniqueEntryName(relPath, usedNames)
                            zos.putNextEntry(ZipEntry(safeEntry))
                            storageRepository.openInputStream(item.uri, item.filePath).use { input ->
                                totalBytes += input.copyTo(zos, 64 * 1024)
                            }
                            zos.closeEntry()
                        }
                    }
                    val saved = persistBackupOutputFile(
                        tempFile = tempZip,
                        fileName = tempZip.name,
                        mimeType = "application/zip",
                        targetTreeUri = targetTreeUri,
                        subfolderName = "Backups"
                    )
                    BackupResult(
                        success = true,
                        outputUri = saved.first,
                        outputPath = saved.second,
                        fileCount = fileSources.size,
                        totalBytes = tempZip.length(),
                        summaryLabel = tempZip.name
                    )
                } finally {
                    tempZip.delete()
                }
            } else {
                val folderName = "Files_Mirror"
                var copied = 0
                var skipped = 0
                var copiedBytes = 0L
                var lastUri: Uri? = null

                if (targetTreeUri != null) {
                    val destRoot = createOrGetTreeSubdir(targetTreeUri, folderName)
                        ?: DocumentFile.fromTreeUri(context, targetTreeUri)
                        ?: throw IllegalStateException("Destination folder unavailable")

                    fileSources.forEachIndexed { idx, (_, item) ->
                        onProgress(BackupProgress(idx + 1, total, item.name))
                        val existing = destRoot.findFile(item.name)
                        if (incrementalOnly && existing != null && existing.length() == item.size && item.size > 0L) {
                            skipped++
                        } else {
                            existing?.delete()
                            val created = destRoot.createFile(item.mimeType.ifBlank { storageRepository.guessMimeType(item.name) }, item.name)
                            if (created != null) {
                                storageRepository.openInputStream(item.uri, item.filePath).use { input ->
                                    context.contentResolver.openOutputStream(created.uri)?.use { out ->
                                        copiedBytes += input.copyTo(out, 64 * 1024)
                                    }
                                }
                                copied++
                                lastUri = created.uri
                            }
                        }
                    }
                    BackupResult(
                        success = true,
                        outputUri = lastUri,
                        outputPath = destRoot.name ?: folderName,
                        fileCount = copied,
                        skippedCount = skipped,
                        totalBytes = copiedBytes,
                        summaryLabel = destRoot.name ?: folderName
                    )
                } else {
                    val mirrorDir = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        "NexoDrop/Backups/$folderName"
                    ).apply { if (!exists()) mkdirs() }

                    fileSources.forEachIndexed { idx, (relPath, item) ->
                        onProgress(BackupProgress(idx + 1, total, item.name))
                        val dst = File(mirrorDir, relPath)
                        dst.parentFile?.mkdirs()
                        if (incrementalOnly && dst.exists() && dst.length() == item.size && item.size > 0L) {
                            skipped++
                        } else {
                            storageRepository.openInputStream(item.uri, item.filePath).use { input ->
                                dst.outputStream().buffered().use { out ->
                                    copiedBytes += input.copyTo(out, 64 * 1024)
                                }
                            }
                            copied++
                            lastUri = runCatching {
                                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", dst)
                            }.getOrNull()
                        }
                    }
                    BackupResult(
                        success = true,
                        outputUri = lastUri,
                        outputPath = mirrorDir.absolutePath,
                        fileCount = copied,
                        skippedCount = skipped,
                        totalBytes = copiedBytes,
                        summaryLabel = "Downloads/NexoDrop/Backups/$folderName"
                    )
                }
            }
        }.getOrElse { err ->
            BackupResult(success = false, error = err.localizedMessage ?: "Files backup failed")
        }
    }

    /**
     * 3. Encrypted Full App & Vault Backup (.nxbackup using PBKDF2WithHmacSHA256 + AES-256-GCM).
     * Includes: Private Vault decrypted contents (re-encrypted with portable password),
     * Hidden Apps list, Transfer History, and User Settings.
     */
    suspend fun exportEncryptedAppBackup(
        password: String,
        targetTreeUri: Uri?,
        settings: UserSettings,
        onProgress: (BackupProgress) -> Unit = {}
    ): BackupResult = withContext(Dispatchers.IO) {
        if (password.length < 4) {
            return@withContext BackupResult(success = false, error = "Password must be at least 4 characters")
        }
        val stamp = Formatters.formatBackupStamp(System.currentTimeMillis())
        val innerZip = File(context.cacheDir, "nxbk_inner_$stamp.zip")
        val encTemp = File(context.cacheDir, "NexoDrop_Vault_$stamp.nxbackup")

        return@withContext runCatching {
            val vaultList = repository.vaultItems.first()
            val hiddenList = repository.hiddenApps.first()
            val recordsList = repository.transferRecords.first().take(500)
            val totalSteps = vaultList.size + 2

            onProgress(BackupProgress(1, totalSteps, "Metadata"))

            val vaultMetaArr = JSONArray()
            ZipOutputStream(BufferedOutputStream(FileOutputStream(innerZip))).use { zos ->
                vaultList.forEachIndexed { idx, v ->
                    onProgress(BackupProgress(idx + 2, totalSteps, v.originalName))
                    val safeName = v.originalName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                    val entryPath = "vault/${idx}_$safeName"
                    val decTemp = File(context.cacheDir, "dec_tmp_${v.id}")
                    try {
                        decTemp.outputStream().buffered().use { out ->
                            vaultManager.decryptFromVault(v.encryptedFileName, out)
                        }
                        zos.putNextEntry(ZipEntry(entryPath))
                        decTemp.inputStream().buffered().use { input ->
                            input.copyTo(zos, 64 * 1024)
                        }
                        zos.closeEntry()
                        vaultMetaArr.put(JSONObject().apply {
                            put("entryPath", entryPath)
                            put("originalName", v.originalName)
                            put("fileSize", v.fileSize)
                            put("mimeType", v.mimeType)
                            put("category", v.category)
                            put("sha256", v.sha256)
                            put("addedAt", v.addedAt)
                        })
                    } finally {
                        decTemp.delete()
                    }
                }

                val hiddenArr = JSONArray().apply {
                    hiddenList.forEach { h ->
                        put(JSONObject().apply {
                            put("packageName", h.packageName)
                            put("appLabel", h.appLabel)
                            put("hiddenAt", h.hiddenAt)
                        })
                    }
                }

                val recordsArr = JSONArray().apply {
                    recordsList.forEach { r ->
                        put(JSONObject().apply {
                            put("transferId", r.transferId)
                            put("fileName", r.fileName)
                            put("fileSize", r.fileSize)
                            put("mimeType", r.mimeType)
                            put("category", r.category)
                            put("direction", r.direction)
                            put("peerDeviceName", r.peerDeviceName)
                            put("peerDeviceId", r.peerDeviceId)
                            put("groupName", r.groupName)
                            put("speedBytesPerSec", r.speedBytesPerSec)
                            put("durationMs", r.durationMs)
                            put("status", r.status)
                            put("sha256", r.sha256)
                            put("savedLocation", r.savedLocation)
                            put("isInVault", r.isInVault)
                            put("timestamp", r.timestamp)
                        })
                    }
                }

                val metaJson = JSONObject().apply {
                    put("formatVersion", NXBK_VERSION)
                    put("createdAt", System.currentTimeMillis())
                    put("settings", JSONObject().apply {
                        put("deviceName", settings.deviceName)
                        put("saveMode", settings.saveMode.name)
                        put("themeMode", settings.themeMode.name)
                        put("stripExifOnSend", settings.stripExifOnSend)
                        put("biometricEnabled", settings.biometricEnabled)
                        put("silentNotifications", settings.silentNotifications)
                        put("notifyCompleteOnly", settings.notifyCompleteOnly)
                    })
                    put("vaultItems", vaultMetaArr)
                    put("hiddenApps", hiddenArr)
                    put("transferRecords", recordsArr)
                }.toString(2).toByteArray(Charsets.UTF_8)

                zos.putNextEntry(ZipEntry("backup_meta.json"))
                zos.write(metaJson)
                zos.closeEntry()
            }

            onProgress(BackupProgress(totalSteps, totalSteps, encTemp.name))

            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val key = deriveBackupKey(password, salt, PBKDF2_ITERATIONS)
            val random = SecureRandom()

            DataOutputStream(BufferedOutputStream(FileOutputStream(encTemp))).use { dos ->
                dos.writeInt(NXBK_MAGIC)
                dos.writeInt(NXBK_VERSION)
                dos.writeInt(PBKDF2_ITERATIONS)
                dos.writeInt(salt.size)
                dos.write(salt)

                val buf = ByteArray(CHUNK_SIZE)
                var chunkIndex = 0
                BufferedInputStream(FileInputStream(innerZip)).use { bis ->
                    while (true) {
                        val read = bis.read(buf)
                        if (read <= 0) break
                        val plain = if (read == buf.size) buf else buf.copyOf(read)
                        val iv = ByteArray(12).also { random.nextBytes(it) }
                        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
                        cipher.updateAAD(ByteBuffer.allocate(4).putInt(chunkIndex).array())
                        val enc = cipher.doFinal(plain)
                        dos.writeInt(iv.size)
                        dos.write(iv)
                        dos.writeInt(enc.size)
                        dos.write(enc)
                        chunkIndex++
                    }
                }
                dos.writeInt(-1) // EOF sentinel
                dos.flush()
            }

            val saved = persistBackupOutputFile(
                tempFile = encTemp,
                fileName = encTemp.name,
                mimeType = "application/octet-stream",
                targetTreeUri = targetTreeUri,
                subfolderName = "Backups"
            )
            BackupResult(
                success = true,
                outputUri = saved.first,
                outputPath = saved.second,
                fileCount = vaultList.size + hiddenList.size,
                totalBytes = encTemp.length(),
                summaryLabel = encTemp.name
            )
        }.getOrElse { err ->
            BackupResult(success = false, error = err.localizedMessage ?: "Encrypted backup failed")
        }.also {
            innerZip.delete()
            encTemp.delete()
        }
    }

    /**
     * 4. Restore Encrypted Full App & Vault Backup (.nxbackup).
     */
    suspend fun restoreEncryptedAppBackup(
        backupUri: Uri,
        password: String,
        onProgress: (BackupProgress) -> Unit = {}
    ): RestoreResult = withContext(Dispatchers.IO) {
        if (password.isBlank()) {
            return@withContext RestoreResult(success = false, error = "Enter backup password")
        }
        val decZip = File(context.cacheDir, "nxbk_restore_${System.currentTimeMillis()}.zip")
        return@withContext runCatching {
            val inputStream = storageRepository.openInputStream(backupUri, if (backupUri.scheme == "file") backupUri.path else null)
            DataInputStream(BufferedInputStream(inputStream)).use { dis ->
                val magic = dis.readInt()
                require(magic == NXBK_MAGIC) { "Not a valid NexoDrop (.nxbackup) file" }
                val version = dis.readInt()
                require(version == NXBK_VERSION) { "Unsupported backup version ($version)" }
                val iterations = dis.readInt()
                val saltLen = dis.readInt()
                require(saltLen in 8..64) { "Invalid backup header" }
                val salt = ByteArray(saltLen)
                dis.readFully(salt)

                val key = deriveBackupKey(password, salt, iterations)
                var chunkIndex = 0
                BufferedOutputStream(FileOutputStream(decZip)).use { out ->
                    while (true) {
                        val ivLen = dis.readInt()
                        if (ivLen == -1) break
                        require(ivLen in 8..32) { "Corrupted backup stream" }
                        val iv = ByteArray(ivLen)
                        dis.readFully(iv)
                        val encLen = dis.readInt()
                        require(encLen > 0) { "Corrupted chunk" }
                        val enc = ByteArray(encLen)
                        dis.readFully(enc)

                        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
                        cipher.updateAAD(ByteBuffer.allocate(4).putInt(chunkIndex).array())
                        val plain = cipher.doFinal(enc)
                        out.write(plain)
                        chunkIndex++
                    }
                    out.flush()
                }
            }

            // Parse backup_meta.json and extract vault entries
            val tempExtractDir = File(context.cacheDir, "nxbk_extract_${System.currentTimeMillis()}").apply { mkdirs() }
            try {
                var metaJson: JSONObject? = null
                ZipInputStream(BufferedInputStream(FileInputStream(decZip))).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            if (entry.name == "backup_meta.json") {
                                metaJson = JSONObject(zis.readBytes().toString(Charsets.UTF_8))
                            } else if (entry.name.startsWith("vault/") && !entry.name.contains("..")) {
                                val outFile = File(tempExtractDir, entry.name)
                                outFile.parentFile?.mkdirs()
                                outFile.outputStream().buffered().use { out ->
                                    zis.copyTo(out, 64 * 1024)
                                }
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }

                val meta = metaJson ?: throw IllegalStateException("Missing backup_meta.json")
                val vaultArr = meta.optJSONArray("vaultItems") ?: JSONArray()
                val hiddenArr = meta.optJSONArray("hiddenApps") ?: JSONArray()
                val recordsArr = meta.optJSONArray("transferRecords") ?: JSONArray()
                val totalSteps = vaultArr.length() + 1

                var restoredVault = 0
                for (i in 0 until vaultArr.length()) {
                    val vObj = vaultArr.getJSONObject(i)
                    val entryPath = vObj.getString("entryPath")
                    val origName = vObj.getString("originalName")
                    onProgress(BackupProgress(i + 1, totalSteps, origName))
                    val plainFile = File(tempExtractDir, entryPath)
                    if (plainFile.exists()) {
                        val encRes = plainFile.inputStream().buffered().use { vaultManager.encryptIntoVault(it) }
                        repository.addVaultItem(
                            VaultItemEntity(
                                originalName = origName,
                                encryptedFileName = encRes.encryptedFileName,
                                fileSize = encRes.plainSize,
                                mimeType = vObj.optString("mimeType", "application/octet-stream"),
                                category = vObj.optString("category", FileCategory.OTHER.name),
                                sha256 = encRes.sha256,
                                originalUriOrPath = "vault://${encRes.encryptedFileName}",
                                addedAt = vObj.optLong("addedAt", System.currentTimeMillis())
                            )
                        )
                        restoredVault++
                    }
                }

                var restoredHidden = 0
                for (i in 0 until hiddenArr.length()) {
                    val hObj = hiddenArr.getJSONObject(i)
                    repository.hideApp(
                        packageName = hObj.getString("packageName"),
                        label = hObj.optString("appLabel", hObj.getString("packageName"))
                    )
                    restoredHidden++
                }

                var restoredRecords = 0
                for (i in 0 until recordsArr.length()) {
                    val rObj = recordsArr.getJSONObject(i)
                    repository.recordTransfer(
                        TransferRecordEntity(
                            transferId = rObj.optString("transferId", "restored_$i"),
                            fileName = rObj.optString("fileName", "file"),
                            fileSize = rObj.optLong("fileSize", 0L),
                            mimeType = rObj.optString("mimeType", "application/octet-stream"),
                            category = rObj.optString("category", FileCategory.OTHER.name),
                            direction = rObj.optString("direction", TransferDirection.RECEIVED.name),
                            peerDeviceName = rObj.optString("peerDeviceName", "Backup"),
                            peerDeviceId = rObj.optString("peerDeviceId", ""),
                            groupName = rObj.optString("groupName", "Restored"),
                            speedBytesPerSec = rObj.optLong("speedBytesPerSec", 0L),
                            durationMs = rObj.optLong("durationMs", 0L),
                            status = rObj.optString("status", TransferStatus.COMPLETED.name),
                            sha256 = rObj.optString("sha256", ""),
                            savedLocation = rObj.optString("savedLocation", ""),
                            isInVault = rObj.optBoolean("isInVault", false),
                            timestamp = rObj.optLong("timestamp", System.currentTimeMillis())
                        )
                    )
                    restoredRecords++
                }

                var restoredSettings = false
                meta.optJSONObject("settings")?.let { sObj ->
                    sObj.optString("deviceName").takeIf { it.isNotBlank() }?.let { preferences.setDeviceName(it) }
                    runCatching { SaveLocationMode.valueOf(sObj.optString("saveMode")) }.getOrNull()?.let {
                        preferences.setSaveMode(it)
                    }
                    runCatching { ThemeMode.valueOf(sObj.optString("themeMode")) }.getOrNull()?.let {
                        preferences.setThemeMode(it)
                    }
                    preferences.setStripExifOnSend(sObj.optBoolean("stripExifOnSend", false))
                    preferences.setBiometricEnabled(sObj.optBoolean("biometricEnabled", true))
                    preferences.setSilentNotifications(sObj.optBoolean("silentNotifications", false))
                    preferences.setNotifyCompleteOnly(sObj.optBoolean("notifyCompleteOnly", false))
                    restoredSettings = true
                }

                onProgress(BackupProgress(totalSteps, totalSteps, "Done"))
                RestoreResult(
                    success = true,
                    vaultItemsRestored = restoredVault,
                    hiddenAppsRestored = restoredHidden,
                    historyRestored = restoredRecords,
                    settingsRestored = restoredSettings
                )
            } finally {
                tempExtractDir.deleteRecursively()
            }
        }.getOrElse { err ->
            val msg = if (err is javax.crypto.AEADBadTagException) {
                "Incorrect password or tampered backup file"
            } else {
                err.localizedMessage ?: "Restore failed"
            }
            RestoreResult(success = false, error = msg)
        }.also {
            decZip.delete()
        }
    }

    private fun deriveBackupKey(password: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        val raw = factory.generateSecret(spec).encoded
        return SecretKeySpec(raw, "AES")
    }

    private fun createOrGetTreeSubdir(treeUri: Uri, folderName: String): DocumentFile? {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        if (!root.canWrite()) return null
        return root.findFile(folderName)?.takeIf { it.isDirectory } ?: root.createDirectory(folderName)
    }

    private fun persistBackupOutputFile(
        tempFile: File,
        fileName: String,
        mimeType: String,
        targetTreeUri: Uri?,
        subfolderName: String
    ): Pair<Uri?, String?> {
        if (targetTreeUri != null) {
            val treeDoc = DocumentFile.fromTreeUri(context, targetTreeUri)
            if (treeDoc != null && treeDoc.canWrite()) {
                treeDoc.findFile(fileName)?.delete()
                val created = treeDoc.createFile(mimeType, fileName)
                if (created != null) {
                    context.contentResolver.openOutputStream(created.uri)?.use { out ->
                        tempFile.inputStream().buffered().use { input -> input.copyTo(out, 64 * 1024) }
                    }
                    return created.uri to (treeDoc.name ?: fileName)
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val relPath = "${Environment.DIRECTORY_DOWNLOADS}/NexoDrop/$subfolderName"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val itemUri = runCatching {
                context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            }.getOrNull()
            if (itemUri != null) {
                context.contentResolver.openOutputStream(itemUri)?.use { out ->
                    tempFile.inputStream().buffered().use { input -> input.copyTo(out, 64 * 1024) }
                }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                context.contentResolver.update(itemUri, values, null, null)
                return itemUri to "$relPath/$fileName"
            }
        }

        val backupsDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "NexoDrop/$subfolderName"
        ).apply { if (!exists()) mkdirs() }
        val dst = File(backupsDir, fileName)
        tempFile.inputStream().buffered().use { input ->
            dst.outputStream().buffered().use { out -> input.copyTo(out, 64 * 1024) }
        }
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", dst)
        }.getOrNull() ?: Uri.fromFile(dst)
        return uri to dst.absolutePath
    }

    private fun collectDirectoryFiles(
        rootDir: File,
        currentDir: File,
        out: MutableList<Pair<String, DeviceFileItem>>
    ) {
        val children = currentDir.listFiles() ?: return
        for (f in children) {
            if (f.isDirectory) {
                collectDirectoryFiles(rootDir, f, out)
            } else if (f.canRead()) {
                val rel = runCatching { f.relativeTo(rootDir).path }.getOrDefault(f.name)
                val mime = storageRepository.guessMimeType(f.name)
                out += rel to DeviceFileItem(
                    id = f.absolutePath,
                    name = f.name,
                    size = f.length(),
                    mimeType = mime,
                    category = FileCategory.fromFileNameAndMime(f.name, mime),
                    modifiedAt = f.lastModified(),
                    uri = Uri.fromFile(f),
                    filePath = f.absolutePath,
                    isDirectory = false
                )
            }
        }
    }

    private fun uniqueEntryName(raw: String, used: MutableSet<String>): String {
        if (used.add(raw)) return raw
        val dot = raw.lastIndexOf('.')
        val base = if (dot > 0) raw.substring(0, dot) else raw
        val ext = if (dot > 0) raw.substring(dot) else ""
        var idx = 1
        while (true) {
            val candidate = "${base}_($idx)$ext"
            if (used.add(candidate)) return candidate
            idx++
        }
    }
}
