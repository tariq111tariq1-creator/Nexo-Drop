package com.example.p2p

import android.net.Uri
import com.example.data.FileCategory

enum class GroupRole {
    NONE, HOST, MEMBER
}

enum class PeerPermission {
    FULL_ACCESS,
    RECEIVE_ONLY,
    SEND_ONLY,
    VIEW_ONLY
}

enum class ConnectionPhase {
    IDLE,
    HOSTING,
    CONNECTING,
    WAITING_APPROVAL,
    CONNECTED
}

data class DiscoveredGroup(
    val groupId: String,
    val groupName: String,
    val hostDeviceName: String,
    val hostIp: String,
    val hostPort: Int,
    val hasPin: Boolean,
    val memberCount: Int,
    val source: String, // "WIFI_DIRECT", "NSD", "LAN"
    val lastSeenAt: Long = System.currentTimeMillis()
)

data class WifiDirectPeer(
    val deviceName: String,
    val deviceAddress: String,
    val status: Int,
    val isNexoService: Boolean = false,
    val serviceGroupName: String = "",
    val servicePort: Int = 45892,
    val hasPin: Boolean = false
)

data class GroupPeer(
    val deviceId: String,
    val deviceName: String,
    val ipAddress: String,
    val permission: PeerPermission,
    val keyFingerprint: String,
    val isHost: Boolean = false
)

data class PendingJoinRequest(
    val requestId: String,
    val deviceId: String,
    val deviceName: String,
    val ipAddress: String,
    val keyFingerprint: String,
    val requestedAt: Long = System.currentTimeMillis()
)

data class LocalSourceDescriptor(
    val itemId: String,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val uri: Uri?,
    val filePath: String?,
    val isAppPackage: Boolean = false,
    val sha256: String = ""
)

data class SharedGroupItem(
    val itemId: String,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val category: FileCategory,
    val ownerDeviceId: String,
    val ownerDeviceName: String,
    val sha256: String,
    val addedAt: Long,
    val isAppPackage: Boolean = false
)

data class GroupChatMessage(
    val id: String,
    val senderId: String,
    val senderName: String,
    val text: String,
    val timestamp: Long
)

data class ActiveTransfer(
    val transferId: String,
    val itemId: String,
    val fileName: String,
    val totalBytes: Long,
    val bytesTransferred: Long,
    val speedBytesPerSec: Long,
    val isUpload: Boolean,
    val peerName: String,
    val saveToVault: Boolean = false,
    val isPaused: Boolean = false,
    val isCompleted: Boolean = false,
    val error: String? = null
) {
    val progress: Float
        get() = if (totalBytes <= 0L) 0f else (bytesTransferred.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
}

data class WifiDirectState(
    val supported: Boolean = true,
    val groupActive: Boolean = false,
    val ssid: String = "",
    val passphrase: String = "",
    val groupOwnerIp: String = "",
    val hotspotActive: Boolean = false,
    val hotspotSsid: String = "",
    val hotspotPassphrase: String = "",
    val discoveredPeers: List<WifiDirectPeer> = emptyList(),
    val statusMessage: String = ""
)

data class ReceivedGroupFile(
    val id: String,
    val itemId: String,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val category: FileCategory,
    val senderName: String,
    val sha256: String,
    val savedLocation: String,
    val outputUri: Uri?,
    val filePath: String?,
    val isInVault: Boolean,
    val vaultItemId: Long? = null,
    val receivedAt: Long = System.currentTimeMillis()
)

data class GroupWorkspaceState(
    val phase: ConnectionPhase = ConnectionPhase.IDLE,
    val role: GroupRole = GroupRole.NONE,
    val groupId: String = "",
    val groupName: String = "",
    val hostIp: String = "",
    val hostPort: Int = 0,
    val hasPin: Boolean = false,
    val groupPin: String = "",
    val keyFingerprint: String = "",
    val autoApprove: Boolean = false,
    val autoReceiveFiles: Boolean = true,
    val connectionStep: Int = 0,
    val webShareUrl: String = "",
    val directQrPayload: String = "",
    val localNetworkEnabled: Boolean = false,
    val myPermission: PeerPermission = PeerPermission.FULL_ACCESS,
    val peers: List<GroupPeer> = emptyList(),
    val pendingRequests: List<PendingJoinRequest> = emptyList(),
    val sharedItems: List<SharedGroupItem> = emptyList(),
    val receivedFiles: List<ReceivedGroupFile> = emptyList(),
    val activeTransfers: List<ActiveTransfer> = emptyList(),
    val chatMessages: List<GroupChatMessage> = emptyList(),
    val discoveredGroups: List<DiscoveredGroup> = emptyList(),
    val isScanning: Boolean = false,
    val wifiDirect: WifiDirectState = WifiDirectState(),
    val lastError: String? = null
)

data class ScannedQrResult(
    val ssid: String,
    val passphrase: String,
    val hostIp: String,
    val hostPort: Int,
    val groupName: String,
    val hasPin: Boolean
)
