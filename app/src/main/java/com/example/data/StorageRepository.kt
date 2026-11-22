package com.example.data

import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.example.security.VaultManager
import com.example.util.Formatters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class InstalledAppItem(
    val packageName: String,
    val label: String,
    val versionName: String,
    val versionCode: Long,
    val totalSize: Long,
    val isSystemApp: Boolean,
    val isSplitApk: Boolean,
    val sourcePaths: List<String>,
    val installedAt: Long
)

data class DeviceFileItem(
    val id: String,
    val name: String,
    val size: Long,
    val mimeType: String,
    val category: FileCategory,
    val modifiedAt: Long,
    val uri: Uri?,
    val filePath: String?,
    val isDirectory: Boolean = false
)

data class StorageRootInfo(
    val label: String,
    val path: String,
    val isRemovable: Boolean
)

data class StorageSpaceInfo(
    val freeBytes: Long,
    val totalBytes: Long
)

class StorageRepository(
    private val context: Context,
    private val vaultManager: VaultManager
) {

    fun getInstalledApps(includeSystem: Boolean): List<InstalledAppItem> {
        val pm = context.packageManager
        val flags = PackageManager.GET_META_DATA
        val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(flags)
        }

        return packages.mapNotNull { pkgInfo ->
            val appInfo = pkgInfo.applicationInfo ?: return@mapNotNull null
            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (!includeSystem && isSystem) return@mapNotNull null

            val paths = mutableListOf<String>()
            appInfo.sourceDir?.let { paths += it }
            appInfo.splitSourceDirs?.forEach { paths += it }
            if (paths.isEmpty()) return@mapNotNull null

            val totalBytes = paths.sumOf { p -> File(p).takeIf { it.exists() }?.length() ?: 0L }
            val label = pm.getApplicationLabel(appInfo).toString()
            val vCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pkgInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pkgInfo.versionCode.toLong()
            }

            InstalledAppItem(
                packageName = pkgInfo.packageName,
                label = label,
                versionName = pkgInfo.versionName ?: "1.0",
                versionCode = vCode,
                totalSize = totalBytes,
                isSystemApp = isSystem,
                isSplitApk = (appInfo.splitSourceDirs?.isNotEmpty() == true),
                sourcePaths = paths,
                installedAt = pkgInfo.lastUpdateTime
            )
        }.sortedBy { it.label.lowercase() }
    }

    /**
     * Prepares an installed app for sharing:
     * - Single APK -> returns direct File
     * - Split APKs -> packages base.apk + split_*.apk into a clean .apks archive in cache
     */
    suspend fun prepareAppForTransfer(app: InstalledAppItem): File = withContext(Dispatchers.IO) {
        val safeLabel = app.label.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val exportDir = File(context.cacheDir, "apk_export").apply { if (!exists()) mkdirs() }
        if (!app.isSplitApk && app.sourcePaths.size == 1) {
            val src = File(app.sourcePaths.first())
            val dest = File(exportDir, "${safeLabel}_v${app.versionName}.apk")
            src.inputStream().use { input ->
                dest.outputStream().use { out -> input.copyTo(out, 64 * 1024) }
            }
            dest
        } else {
            val dest = File(exportDir, "${safeLabel}_v${app.versionName}.apks")
            ZipOutputStream(BufferedOutputStream(FileOutputStream(dest))).use { zos ->
                app.sourcePaths.forEach { path ->
                    val f = File(path)
                    if (f.exists()) {
                        zos.putNextEntry(ZipEntry(f.name))
                        BufferedInputStream(FileInputStream(f)).use { bis ->
                            bis.copyTo(zos, 64 * 1024)
                        }
                        zos.closeEntry()
                    }
                }
            }
            dest
        }
    }

    fun getStorageRoots(): List<StorageRootInfo> {
        val roots = mutableListOf<StorageRootInfo>()
        val sm = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            sm.storageVolumes.forEach { vol ->
                val dir = vol.directory
                if (dir != null && dir.exists()) {
                    roots += StorageRootInfo(
                        label = vol.getDescription(context),
                        path = dir.absolutePath,
                        isRemovable = vol.isRemovable
                    )
                }
            }
        }
        if (roots.isEmpty()) {
            val primary = Environment.getExternalStorageDirectory()
            if (primary != null && primary.exists()) {
                roots += StorageRootInfo("Internal Storage", primary.absolutePath, false)
            }
        }
        return roots
    }

    suspend fun listRawDirectory(dirPath: String): List<DeviceFileItem> = withContext(Dispatchers.IO) {
        val dir = File(dirPath)
        if (!dir.exists() || !dir.isDirectory) return@withContext emptyList()
        val children = dir.listFiles() ?: return@withContext emptyList()
        children.map { f ->
            val mime = if (f.isDirectory) "inode/directory" else guessMimeType(f.name)
            DeviceFileItem(
                id = f.absolutePath,
                name = f.name,
                size = if (f.isDirectory) (f.listFiles()?.size?.toLong() ?: 0L) else f.length(),
                mimeType = mime,
                category = if (f.isDirectory) FileCategory.OTHER else FileCategory.fromFileNameAndMime(f.name, mime),
                modifiedAt = f.lastModified(),
                uri = Uri.fromFile(f),
                filePath = f.absolutePath,
                isDirectory = f.isDirectory
            )
        }.sortedWith(compareBy<DeviceFileItem> { !it.isDirectory }.thenBy { it.name.lowercase() })
    }

    suspend fun queryMediaStoreFiles(limit: Int = 500): List<DeviceFileItem> = withContext(Dispatchers.IO) {
        val results = mutableListOf<DeviceFileItem>()
        val collection = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.DATE_MODIFIED
        )
        val selection = "${MediaStore.Files.FileColumns.SIZE} > 0"
        val sortOrder = "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"

        runCatching {
            context.contentResolver.query(collection, projection, selection, null, sortOrder)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)

                var count = 0
                while (cursor.moveToNext() && count < limit) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: continue
                    val size = cursor.getLong(sizeCol)
                    val mime = cursor.getString(mimeCol) ?: guessMimeType(name)
                    val modified = cursor.getLong(dateCol) * 1000L
                    val contentUri = Uri.withAppendedPath(collection, id.toString())
                    results += DeviceFileItem(
                        id = contentUri.toString(),
                        name = name,
                        size = size,
                        mimeType = mime,
                        category = FileCategory.fromFileNameAndMime(name, mime),
                        modifiedAt = modified,
                        uri = contentUri,
                        filePath = null
                    )
                    count++
                }
            }
        }
        results
    }

    fun resolveUriMetadata(uri: Uri): DeviceFileItem? {
        var name = uri.lastPathSegment ?: "file"
        var size = 0L
        val mime = context.contentResolver.getType(uri) ?: guessMimeType(name)
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIdx != -1) name = cursor.getString(nameIdx) ?: name
                if (sizeIdx != -1) size = cursor.getLong(sizeIdx)
            }
        }
        if (size <= 0L && uri.scheme == "file") {
            uri.path?.let { size = File(it).length() }
        }
        return DeviceFileItem(
            id = uri.toString(),
            name = name,
            size = size,
            mimeType = mime,
            category = FileCategory.fromFileNameAndMime(name, mime),
            modifiedAt = System.currentTimeMillis(),
            uri = uri,
            filePath = if (uri.scheme == "file") uri.path else null
        )
    }

    fun openInputStream(uri: Uri?, filePath: String?): InputStream {
        if (filePath != null) {
            val file = File(filePath)
            if (file.exists()) return FileInputStream(file)
        }
        if (uri != null) {
            return context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("Unable to open stream for $uri")
        }
        throw IllegalArgumentException("No valid file path or Uri")
    }

    /**
     * Strips EXIF APP1/COM segments from JPEG streams if privacy mode is enabled.
     */
    fun openPrivacyAwareStream(uri: Uri?, filePath: String?, fileName: String, stripExif: Boolean): InputStream {
        val raw = openInputStream(uri, filePath)
        val lower = fileName.lowercase()
        if (!stripExif || (!lower.endsWith(".jpg") && !lower.endsWith(".jpeg"))) {
            return raw
        }
        return raw.use { input ->
            val bytes = input.readBytes()
            val stripped = stripJpegMetadata(bytes)
            ByteArrayInputStream(stripped)
        }
    }

    /**
     * Opens a stream positioned at [offset] bytes using O(1) FileChannel seek whenever possible.
     */
    fun openSeekableStreamAt(
        uri: Uri?,
        filePath: String?,
        fileName: String,
        stripExif: Boolean,
        offset: Long
    ): Pair<InputStream, Long> {
        val safeOffset = offset.coerceAtLeast(0L)
        if (safeOffset == 0L) {
            return openPrivacyAwareStream(uri, filePath, fileName, stripExif) to 0L
        }
        val lower = fileName.lowercase()
        val mustStrip = stripExif && (lower.endsWith(".jpg") || lower.endsWith(".jpeg"))
        if (!mustStrip) {
            if (filePath != null) {
                val f = File(filePath)
                if (f.exists()) {
                    val fis = FileInputStream(f)
                    val clamped = safeOffset.coerceAtMost(f.length())
                    fis.channel.position(clamped)
                    return fis to clamped
                }
            }
            if (uri != null) {
                val afd = runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r") }.getOrNull()
                if (afd != null) {
                    val sought = runCatching {
                        val fis = afd.createInputStream()
                        val channel = fis.channel
                        val baseStart = afd.startOffset.coerceAtLeast(0L)
                        val declaredLen = afd.declaredLength
                        val clamped = if (declaredLen > 0L) safeOffset.coerceAtMost(declaredLen) else safeOffset
                        channel.position(baseStart + clamped)
                        fis to clamped
                    }.getOrNull()
                    if (sought != null) return sought
                    runCatching { afd.close() }
                }
            }
        }

        val stream = openPrivacyAwareStream(uri, filePath, fileName, stripExif)
        var remaining = safeOffset
        val skipBuf = ByteArray(64 * 1024)
        while (remaining > 0L) {
            val skipped = stream.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
            } else {
                val read = stream.read(skipBuf, 0, minOf(skipBuf.size.toLong(), remaining).toInt())
                if (read <= 0) break
                remaining -= read
            }
        }
        return stream to (safeOffset - remaining).coerceAtLeast(0L)
    }

    fun getPartialTransferDir(): File {
        return File(context.cacheDir, "partial_transfers").apply { if (!exists()) mkdirs() }
    }

    /**
     * Deterministic partial download file path keyed by sanitized fileName and exact fileSize,
     * allowing seamless byte-offset resumption even after stopping/recreating a group via QR.
     */
    fun getDeterministicPartialFile(fileName: String, fileSize: Long): File {
        val safeName = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(80)
        val keyBytes = "$fileName|$fileSize".toByteArray(Charsets.UTF_8)
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(keyBytes)
            .take(6).joinToString("") { "%02x".format(it) }
        return File(getPartialTransferDir(), "part_${hash}_${fileSize}_$safeName")
    }

    private fun stripJpegMetadata(data: ByteArray): ByteArray {
        if (data.size < 4 || data[0] != 0xFF.toByte() || data[1] != 0xD8.toByte()) return data
        val out = ByteArrayOutputStream(data.size)
        out.write(0xFF)
        out.write(0xD8)
        var i = 2
        while (i + 1 < data.size) {
            if (data[i] != 0xFF.toByte()) {
                out.write(data, i, data.size - i)
                break
            }
            val marker = data[i + 1].toInt() and 0xFF
            if (marker == 0xDA) { // Start of Scan: copy rest verbatim
                out.write(data, i, data.size - i)
                break
            }
            if (marker == 0xD9) {
                out.write(0xFF)
                out.write(0xD9)
                break
            }
            if (marker == 0x00 || (marker in 0xD0..0xD7)) {
                out.write(data, i, 2)
                i += 2
                continue
            }
            if (i + 3 >= data.size) break
            val segLen = ((data[i + 2].toInt() and 0xFF) shl 8) or (data[i + 3].toInt() and 0xFF)
            if (segLen < 2 || i + 2 + segLen > data.size) {
                out.write(data, i, data.size - i)
                break
            }
            // Strip APP1 (EXIF/XMP), APP13 (IPTC), COM (0xFE)
            val shouldStrip = marker == 0xE1 || marker == 0xED || marker == 0xFE
            if (!shouldStrip) {
                out.write(data, i, 2 + segLen)
            }
            i += 2 + segLen
        }
        return out.toByteArray()
    }

    /**
     * Saves a received temporary file to the user's configured destination:
     * - Private Vault (AES-256 encrypted)
     * - Custom Folder (Internal or SD card SAF DocumentTree)
     * - Smart Category Folder
     * - Public Downloads/NexoDrop
     */
    suspend fun saveReceivedTempFile(
        tempFile: File,
        fileName: String,
        mimeType: String,
        settings: UserSettings,
        forceVault: Boolean = false
    ): SavedOutputResult = withContext(Dispatchers.IO) {
        val category = FileCategory.fromFileNameAndMime(fileName, mimeType)

        if (forceVault || settings.saveMode == SaveLocationMode.PRIVATE_VAULT) {
            val encResult = tempFile.inputStream().buffered().use { vaultManager.encryptIntoVault(it) }
            val entity = VaultItemEntity(
                originalName = fileName,
                encryptedFileName = encResult.encryptedFileName,
                fileSize = encResult.plainSize,
                mimeType = mimeType,
                category = category.name,
                sha256 = encResult.sha256,
                originalUriOrPath = "vault://${encResult.encryptedFileName}"
            )
            return@withContext SavedOutputResult(
                displayLocation = "Private Vault",
                isInVault = true,
                vaultItem = entity,
                outputUri = null
            )
        }

        if (settings.saveMode == SaveLocationMode.CUSTOM_TREE && settings.customSaveTreeUri.isNotBlank()) {
            val treeUri = Uri.parse(settings.customSaveTreeUri)
            val treeDoc = DocumentFile.fromTreeUri(context, treeUri)
            if (treeDoc != null && treeDoc.canWrite()) {
                val created = treeDoc.createFile(mimeType, fileName)
                if (created != null) {
                    context.contentResolver.openOutputStream(created.uri)?.use { out ->
                        tempFile.inputStream().use { input -> input.copyTo(out, 512 * 1024) }
                    }
                    return@withContext SavedOutputResult(
                        displayLocation = settings.customSaveLabel.ifBlank { created.uri.toString() },
                        isInVault = false,
                        vaultItem = null,
                        outputUri = created.uri,
                        filePath = null
                    )
                }
            }
        }

        // MediaStore Downloads or Smart Category collection
        val targetInfo = saveViaMediaStoreOrLegacy(tempFile, fileName, mimeType, category, settings.saveMode)
        return@withContext targetInfo
    }

    private fun saveViaMediaStoreOrLegacy(
        tempFile: File,
        fileName: String,
        mimeType: String,
        category: FileCategory,
        mode: SaveLocationMode
    ): SavedOutputResult {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val (collection, relativePath) = if (mode == SaveLocationMode.CATEGORY_SMART) {
                when (category) {
                    FileCategory.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI to "${Environment.DIRECTORY_PICTURES}/NexoDrop"
                    FileCategory.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI to "${Environment.DIRECTORY_MOVIES}/NexoDrop"
                    FileCategory.AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to "${Environment.DIRECTORY_MUSIC}/NexoDrop"
                    else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI to "${Environment.DIRECTORY_DOWNLOADS}/NexoDrop"
                }
            } else {
                MediaStore.Downloads.EXTERNAL_CONTENT_URI to "${Environment.DIRECTORY_DOWNLOADS}/NexoDrop"
            }

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val itemUri = context.contentResolver.insert(collection, values)
            if (itemUri != null) {
                context.contentResolver.openOutputStream(itemUri)?.use { out ->
                    tempFile.inputStream().use { input -> input.copyTo(out, 512 * 1024) }
                }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                context.contentResolver.update(itemUri, values, null, null)
                return SavedOutputResult(
                    displayLocation = relativePath,
                    isInVault = false,
                    vaultItem = null,
                    outputUri = itemUri,
                    filePath = null
                )
            }
        }

        // Legacy fallback (API 24-28 or if MediaStore insert unavailable)
        val downloadsDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "NexoDrop"
        ).apply { if (!exists()) mkdirs() }
        val outFile = uniqueFile(downloadsDir, fileName)
        if (!tempFile.renameTo(outFile)) {
            tempFile.inputStream().use { input ->
                outFile.outputStream().use { out -> input.copyTo(out, 512 * 1024) }
            }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outFile)
        return SavedOutputResult(
            displayLocation = outFile.parent ?: "Downloads/NexoDrop",
            isInVault = false,
            vaultItem = null,
            outputUri = uri,
            filePath = outFile.absolutePath
        )
    }

    private fun uniqueFile(dir: File, fileName: String): File {
        var candidate = File(dir, fileName)
        if (!candidate.exists()) return candidate
        val dot = fileName.lastIndexOf('.')
        val base = if (dot > 0) fileName.substring(0, dot) else fileName
        val ext = if (dot > 0) fileName.substring(dot) else ""
        var index = 1
        while (candidate.exists()) {
            candidate = File(dir, "${base}_($index)$ext")
            index++
        }
        return candidate
    }

    fun createNoMediaFile(directoryPath: String): Boolean {
        return runCatching {
            val dir = File(directoryPath)
            if (!dir.exists() || !dir.isDirectory) return@runCatching false
            val noMedia = File(dir, ".nomedia")
            if (!noMedia.exists()) noMedia.createNewFile() else true
        }.getOrDefault(false)
    }

    fun createNoMediaInTree(treeUri: Uri): Boolean {
        return runCatching {
            val treeDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return@runCatching false
            if (treeDoc.findFile(".nomedia") != null) return@runCatching true
            treeDoc.createFile("application/octet-stream", ".nomedia") != null
        }.getOrDefault(false)
    }

    fun getTreeDisplayName(treeUri: Uri): String {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
        return docId ?: (DocumentFile.fromTreeUri(context, treeUri)?.name ?: treeUri.toString())
    }

    fun installApkOrApks(file: File) {
        if (file.name.lowercase().endsWith(".apks")) {
            installSplitApksArchive(file)
        } else {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        }
    }

    private fun installSplitApksArchive(apksFile: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        ZipInputStream(BufferedInputStream(FileInputStream(apksFile))).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.endsWith(".apk")) {
                    session.openWrite(entry.name, 0, -1).use { out ->
                        zis.copyTo(out, 64 * 1024)
                        session.fsync(out)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        val intent = Intent(context, PackageInstallerStatusReceiver::class.java)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
        session.commit(pending.intentSender)
        session.close()
    }

    fun openFileWithSystem(file: File, mimeType: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    suspend fun exportTransferReportCsv(records: List<TransferRecordEntity>): File = withContext(Dispatchers.IO) {
        val outDir = File(context.cacheDir, "reports").apply { if (!exists()) mkdirs() }
        val file = File(outDir, "nexodrop_report_${System.currentTimeMillis()}.csv")
        file.bufferedWriter().use { w ->
            w.appendLine("Timestamp,Direction,FileName,SizeBytes,Category,PeerDevice,Group,SpeedBytesPerSec,DurationMs,Status,InVault,SHA256")
            records.forEach { r ->
                val cleanName = r.fileName.replace("\"", "\"\"")
                w.appendLine("${r.timestamp},${r.direction},\"$cleanName\",${r.fileSize},${r.category},\"${r.peerDeviceName}\",\"${r.groupName}\",${r.speedBytesPerSec},${r.durationMs},${r.status},${r.isInVault},${r.sha256}")
            }
        }
        file
    }

    suspend fun exportTransferReportPdf(records: List<TransferRecordEntity>): File = withContext(Dispatchers.IO) {
        val outDir = File(context.cacheDir, "reports").apply { if (!exists()) mkdirs() }
        val file = File(outDir, "nexodrop_report_${System.currentTimeMillis()}.pdf")
        val doc = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
        var page = doc.startPage(pageInfo)
        var canvas: Canvas = page.canvas

        val titlePaint = Paint().apply {
            color = Color.rgb(0, 120, 115)
            textSize = 20f
            isFakeBoldText = true
        }
        val bodyPaint = Paint().apply {
            color = Color.DKGRAY
            textSize = 10f
        }
        val boldPaint = Paint().apply {
            color = Color.BLACK
            textSize = 11f
            isFakeBoldText = true
        }

        var y = 48f
        canvas.drawText("NexoDrop — Encrypted P2P Transfer Report", 36f, y, titlePaint)
        y += 22f
        val totalSent = records.filter { it.direction == "SENT" && it.status == "COMPLETED" }.sumOf { it.fileSize }
        val totalRecv = records.filter { it.direction == "RECEIVED" && it.status == "COMPLETED" }.sumOf { it.fileSize }
        canvas.drawText(
            "Total Records: ${records.size}  |  Sent: ${Formatters.formatBytes(totalSent)}  |  Received: ${Formatters.formatBytes(totalRecv)}",
            36f, y, boldPaint
        )
        y += 24f

        records.forEach { r ->
            if (y > 800f) {
                doc.finishPage(page)
                page = doc.startPage(pageInfo)
                canvas = page.canvas
                y = 44f
            }
            val line = "${Formatters.formatTimestamp(r.timestamp)} | ${r.direction} | ${r.fileName.take(28)} | ${Formatters.formatBytes(r.fileSize)} | ${Formatters.formatSpeed(r.speedBytesPerSec)} | ${r.status}"
            canvas.drawText(line, 36f, y, bodyPaint)
            y += 16f
        }

        doc.finishPage(page)
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
        file
    }

    fun shareExportedReport(file: File, mimeType: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun getAvailableStorageBytes(): Long {
        return runCatching {
            val stat = android.os.StatFs(Environment.getExternalStorageDirectory().path)
            stat.availableBytes
        }.getOrDefault(Long.MAX_VALUE)
    }

    fun getStorageSpaceInfo(path: String): StorageSpaceInfo? {
        return runCatching {
            val target = File(path).takeIf { it.exists() } ?: Environment.getExternalStorageDirectory()
            val stat = android.os.StatFs(target.absolutePath)
            StorageSpaceInfo(
                freeBytes = stat.availableBytes,
                totalBytes = stat.totalBytes
            )
        }.getOrNull()
    }

    suspend fun collectFolderFilesRecursively(folderPath: String, maxFiles: Int = 400): List<DeviceFileItem> =
        withContext(Dispatchers.IO) {
            val root = File(folderPath)
            if (!root.exists() || !root.isDirectory) return@withContext emptyList()
            val collected = mutableListOf<DeviceFileItem>()
            val queue = ArrayDeque<File>()
            queue.add(root)
            while (queue.isNotEmpty() && collected.size < maxFiles) {
                val dir = queue.removeFirst()
                val children = dir.listFiles() ?: continue
                for (child in children) {
                    if (collected.size >= maxFiles) break
                    if (child.isDirectory) {
                        queue.addLast(child)
                    } else if (child.isFile) {
                        val mime = guessMimeType(child.name)
                        collected += DeviceFileItem(
                            id = child.absolutePath,
                            name = child.name,
                            size = child.length(),
                            mimeType = mime,
                            category = FileCategory.fromFileNameAndMime(child.name, mime),
                            modifiedAt = child.lastModified(),
                            uri = Uri.fromFile(child),
                            filePath = child.absolutePath,
                            isDirectory = false
                        )
                    }
                }
            }
            collected
        }

    fun getTempTransferCacheBytes(): Long {
        var total = 0L
        val dirs = listOf("apk_export", "apk_install", "vault_preview", "reports", "partial_transfers")
        dirs.forEach { dirName ->
            val d = File(context.cacheDir, dirName)
            if (d.exists()) {
                d.walkTopDown().filter { it.isFile }.forEach { total += it.length() }
            }
        }
        context.cacheDir.listFiles()?.forEach { f ->
            if (f.isFile && (f.name.startsWith("part_") || f.name.startsWith("relay_") || f.name.startsWith("web_up_"))) {
                total += f.length()
            }
        }
        return total
    }

    fun clearTempTransferCache(includePartialDownloads: Boolean = false): Long {
        var freed = 0L
        val dirs = mutableListOf("apk_export", "apk_install", "vault_preview", "reports")
        if (includePartialDownloads) {
            dirs += "partial_transfers"
        }
        dirs.forEach { dirName ->
            val d = File(context.cacheDir, dirName)
            if (d.exists()) {
                d.walkTopDown().filter { it.isFile }.forEach { f ->
                    val len = f.length()
                    if (f.delete()) freed += len
                }
            }
        }
        context.cacheDir.listFiles()?.forEach { f ->
            val isTemp = f.isFile && (
                f.name.startsWith("relay_") ||
                    f.name.startsWith("web_up_") ||
                    (includePartialDownloads && f.name.startsWith("part_"))
                )
            if (isTemp) {
                val len = f.length()
                if (f.delete()) freed += len
            }
        }
        return freed
    }

    suspend fun openReceivedFile(uri: Uri?, filePath: String?, fileName: String, mimeType: String) {
        val lower = fileName.lowercase()
        if (lower.endsWith(".apk") || lower.endsWith(".apks")) {
            val installFile = withContext(Dispatchers.IO) {
                if (filePath != null && File(filePath).exists()) {
                    File(filePath)
                } else {
                    val cacheDir = File(context.cacheDir, "apk_install").apply { if (!exists()) mkdirs() }
                    val safe = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                    val temp = File(cacheDir, safe)
                    openInputStream(uri, filePath).use { input ->
                        temp.outputStream().use { out -> input.copyTo(out, 64 * 1024) }
                    }
                    temp
                }
            }
            installApkOrApks(installFile)
            return
        }
        val resolvedUri = when {
            filePath != null && File(filePath).exists() ->
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(filePath))
            uri != null -> uri
            else -> return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(resolvedUri, mimeType.ifBlank { guessMimeType(fileName) })
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, fileName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun shareReceivedFile(uri: Uri?, filePath: String?, fileName: String, mimeType: String) {
        val resolvedUri = when {
            filePath != null && File(filePath).exists() ->
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(filePath))
            uri != null -> uri
            else -> return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType.ifBlank { guessMimeType(fileName) }
            putExtra(Intent.EXTRA_STREAM, resolvedUri)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, fileName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    suspend fun renameReceivedFile(uri: Uri?, filePath: String?, newName: String): Pair<Uri?, String?> =
        withContext(Dispatchers.IO) {
            val clean = newName.trim()
            if (clean.isEmpty()) return@withContext uri to filePath
            if (filePath != null) {
                val src = File(filePath)
                if (src.exists()) {
                    val dst = File(src.parentFile, clean)
                    if (src.renameTo(dst)) {
                        val newUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", dst)
                        return@withContext newUri to dst.absolutePath
                    }
                }
            }
            if (uri != null) {
                val doc = runCatching { DocumentFile.fromSingleUri(context, uri) }.getOrNull()
                if (doc != null && doc.renameTo(clean)) {
                    return@withContext doc.uri to null
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val vals = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, clean) }
                    val updated = runCatching { context.contentResolver.update(uri, vals, null, null) }.getOrDefault(0)
                    if (updated > 0) return@withContext uri to null
                }
            }
            uri to filePath
        }

    suspend fun copyOrMoveReceivedFileToFolder(
        uri: Uri?,
        filePath: String?,
        fileName: String,
        mimeType: String,
        targetTreeUri: Uri,
        deleteOriginal: Boolean
    ): Uri? = withContext(Dispatchers.IO) {
        val treeDoc = DocumentFile.fromTreeUri(context, targetTreeUri) ?: return@withContext null
        if (!treeDoc.canWrite()) return@withContext null
        val created = treeDoc.createFile(mimeType.ifBlank { guessMimeType(fileName) }, fileName)
            ?: return@withContext null
        openInputStream(uri, filePath).use { input ->
            context.contentResolver.openOutputStream(created.uri)?.use { out ->
                input.copyTo(out, 64 * 1024)
            }
        }
        if (deleteOriginal) {
            deleteReceivedFile(uri, filePath)
        }
        created.uri
    }

    suspend fun deleteReceivedFile(uri: Uri?, filePath: String?): Boolean = withContext(Dispatchers.IO) {
        var deleted = false
        if (filePath != null) {
            val f = File(filePath)
            if (f.exists()) deleted = f.delete()
        }
        if (!deleted && uri != null) {
            deleted = runCatching {
                val doc = DocumentFile.fromSingleUri(context, uri)
                if (doc != null && doc.exists()) doc.delete()
                else context.contentResolver.delete(uri, null, null) > 0
            }.getOrDefault(false)
        }
        deleted
    }

    fun guessMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext == "apk") return "application/vnd.android.package-archive"
        if (ext == "apks") return "application/zip"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }
}

data class SavedOutputResult(
    val displayLocation: String,
    val isInVault: Boolean,
    val vaultItem: VaultItemEntity?,
    val outputUri: Uri?,
    val filePath: String? = null
)

class PackageInstallerStatusReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirmIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_INTENT)
            }
            confirmIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let {
                context.startActivity(it)
            }
        }
    }
}
