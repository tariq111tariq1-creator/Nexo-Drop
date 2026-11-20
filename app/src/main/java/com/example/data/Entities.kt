package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class FileCategory {
    APP, IMAGE, VIDEO, AUDIO, DOCUMENT, ARCHIVE, OTHER;

    companion object {
        fun fromFileNameAndMime(fileName: String, mimeType: String): FileCategory {
            val lower = fileName.lowercase()
            val mime = mimeType.lowercase()
            return when {
                lower.endsWith(".apk") || lower.endsWith(".apks") || mime.contains("android.package") -> APP
                mime.startsWith("image/") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                    lower.endsWith(".png") || lower.endsWith(".webp") || lower.endsWith(".heic") ||
                    lower.endsWith(".gif") -> IMAGE
                mime.startsWith("video/") || lower.endsWith(".mp4") || lower.endsWith(".mkv") ||
                    lower.endsWith(".mov") || lower.endsWith(".webm") || lower.endsWith(".avi") -> VIDEO
                mime.startsWith("audio/") || lower.endsWith(".mp3") || lower.endsWith(".wav") ||
                    lower.endsWith(".flac") || lower.endsWith(".aac") || lower.endsWith(".ogg") ||
                    lower.endsWith(".m4a") -> AUDIO
                mime.startsWith("text/") || mime.contains("pdf") || mime.contains("document") ||
                    mime.contains("spreadsheet") || mime.contains("presentation") ||
                    lower.endsWith(".pdf") || lower.endsWith(".doc") || lower.endsWith(".docx") ||
                    lower.endsWith(".xls") || lower.endsWith(".xlsx") || lower.endsWith(".ppt") ||
                    lower.endsWith(".txt") || lower.endsWith(".csv") || lower.endsWith(".md") -> DOCUMENT
                lower.endsWith(".zip") || lower.endsWith(".rar") || lower.endsWith(".7z") ||
                    lower.endsWith(".tar") || lower.endsWith(".gz") || mime.contains("zip") -> ARCHIVE
                else -> OTHER
            }
        }
    }
}

enum class TransferDirection {
    SENT, RECEIVED
}

enum class TransferStatus {
    COMPLETED, FAILED, CANCELLED
}

@Entity(tableName = "transfer_records")
data class TransferRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val transferId: String,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val category: String,
    val direction: String,
    val peerDeviceName: String,
    val peerDeviceId: String,
    val groupName: String,
    val speedBytesPerSec: Long,
    val durationMs: Long,
    val status: String,
    val sha256: String,
    val savedLocation: String,
    val isInVault: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "vault_items")
data class VaultItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val originalName: String,
    val encryptedFileName: String,
    val fileSize: Long,
    val mimeType: String,
    val category: String,
    val sha256: String,
    val originalUriOrPath: String,
    val addedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "hidden_apps")
data class HiddenAppEntity(
    @PrimaryKey val packageName: String,
    val appLabel: String,
    val hiddenAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "trusted_peers")
data class TrustedPeerEntity(
    @PrimaryKey val deviceId: String,
    val deviceName: String,
    val lastKeyFingerprint: String,
    val isBanned: Boolean = false,
    val lastConnectedAt: Long = System.currentTimeMillis()
)
