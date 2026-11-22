package com.example.p2p

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import com.example.data.AppPreferences
import com.example.data.FileCategory
import com.example.data.NexoRepository
import com.example.data.TransferDirection
import com.example.data.TransferRecordEntity
import com.example.data.TransferStatus
import com.example.data.TrustedPeerEntity
import com.example.data.UserSettings
import com.example.security.CryptoEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKey

sealed class P2pEvent {
    data class JoinRequestArrived(val request: PendingJoinRequest) : P2pEvent()
    data class FileReceived(val fileName: String, val sizeBytes: Long, val inVault: Boolean) : P2pEvent()
    data class GroupNotification(val message: String) : P2pEvent()
}

class P2pGroupManager(
    private val context: Context,
    private val preferences: AppPreferences,
    private val repository: NexoRepository,
    private val storageRepository: com.example.data.StorageRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val discoveryManager = DiscoveryManager(context, scope)

    private val _state = MutableStateFlow(GroupWorkspaceState())
    val state: StateFlow<GroupWorkspaceState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<P2pEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<P2pEvent> = _events.asSharedFlow()

    private val localSources = ConcurrentHashMap<String, LocalSourceDescriptor>()
    private val relayCacheFiles = ConcurrentHashMap<String, File>()
    private val pendingRelayWaiters = ConcurrentHashMap<String, CompletableDeferred<File?>>()
    private val activeCancelledIds = ConcurrentHashMap.newKeySet<String>()
    private val pausedTransferIds = ConcurrentHashMap.newKeySet<String>()
    private val autoQueuedItemIds = ConcurrentHashMap.newKeySet<String>()
    private val autoReceiveChannel = Channel<Pair<SharedGroupItem, Boolean>>(Channel.UNLIMITED)
    private val resumeQueueLock = Any()
    private val pendingQueueFile = File(context.filesDir, "pending_resume_queue.json")
    @Volatile
    private var restoredPendingThisSession = false

    private val webShareServer = WebShareServer(
        scope = scope,
        storageRepository = storageRepository,
        cacheDir = context.cacheDir,
        groupNameProvider = { _state.value.groupName },
        groupPinProvider = { _state.value.groupPin },
        sharedItemsProvider = { _state.value.sharedItems },
        localSourceLookup = { itemId -> localSources[itemId] },
        onBrowserFileUploaded = { fileName, tempFile, mimeType ->
            handleBrowserUploadedFile(fileName, tempFile, mimeType)
        }
    )

    // Host state
    private var hostServerSocket: ServerSocket? = null
    private var hostAcceptJob: Job? = null
    private val hostClients = ConcurrentHashMap<String, ConnectedClientSession>()
    private val pendingDecisions = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    // Client state
    private var clientControlSocket: Socket? = null
    private var clientControlKey: SecretKey? = null
    private var clientControlOut: DataOutputStream? = null
    private val clientWriteMutex = Mutex()
    private var clientReadJob: Job? = null

    private var currentSettings: UserSettings = UserSettings()

    private data class PendingDirectJoin(
        val groupName: String,
        val pin: String,
        val port: Int = PRIMARY_P2P_PORT,
        val ipOverride: String? = null
    )

    companion object {
        const val PRIMARY_P2P_PORT = 45892
        private const val CHUNK_SIZE_BYTES = 512 * 1024
        private const val SOCKET_BUF_BYTES = 512 * 1024
        private const val STREAM_BUF_BYTES = 256 * 1024
    }

    private var pendingDirectJoin: PendingDirectJoin? = null
    private var hostWebShareEnabled: Boolean = false
    private var hostWebSharePort: Int = 0
    @Volatile
    private var intentionalDisconnect: Boolean = false
    private var myDeviceId: String = ""
    private var lastConnectedIp: String = ""
    private var lastConnectedPort: Int = 0
    private var lastConnectedName: String = ""
    private var lastConnectedPin: String = ""
    private var reconnectAttempts: Int = 0

    init {
        scope.launch {
            myDeviceId = preferences.ensureDeviceId()
            _state.value = _state.value.copy(myDeviceId = myDeviceId)
        }
        scope.launch {
            for ((item, toVault) in autoReceiveChannel) {
                if (_state.value.phase == ConnectionPhase.HOSTING || _state.value.phase == ConnectionPhase.CONNECTED) {
                    performDownload(item, toVault)
                }
            }
        }
        scope.launch {
            preferences.settingsFlow.collect { settings ->
                currentSettings = settings
            }
        }
        scope.launch {
            discoveryManager.discoveredGroups.collect { groups ->
                _state.value = _state.value.copy(discoveredGroups = groups)
            }
        }
        scope.launch {
            discoveryManager.wifiDirectState.collect { wd ->
                _state.value = _state.value.copy(wifiDirect = wd)
            }
        }
        scope.launch {
            discoveryManager.events.collect { event ->
                when (event) {
                    is DirectDiscoveryEvent.WifiDirectBecameGroupOwner -> {
                        val st = _state.value
                        if (st.role != GroupRole.HOST && (st.phase == ConnectionPhase.IDLE || st.phase == ConnectionPhase.CONNECTING)) {
                            becomeHostForExistingWifiDirectGroup(event.ownerIp, event.ssid, event.passphrase)
                        }
                    }
                    is DirectDiscoveryEvent.WifiDirectCredentialsReady -> {
                        val effectiveIp = event.ownerIp.ifBlank { discoveryManager.getPrimaryDirectIp() }
                        if (_state.value.role == GroupRole.HOST) {
                            updateHostNetworkInfo(
                                hostIp = effectiveIp,
                                ssid = event.ssid,
                                pass = event.passphrase,
                                hostMac = event.hostMac,
                                frequency = event.frequency
                            )
                        }
                    }
                    is DirectDiscoveryEvent.HotspotCredentialsReady -> {
                        val effectiveIp = event.ip.ifBlank { discoveryManager.getPrimaryDirectIp() }
                        if (_state.value.role == GroupRole.HOST) {
                            updateHostNetworkInfo(
                                hostIp = effectiveIp,
                                ssid = event.ssid,
                                pass = event.passphrase,
                                hostMac = "",
                                frequency = 0
                            )
                        }
                    }
                    is DirectDiscoveryEvent.WifiDirectGroupFormedAsClient -> {
                        val st = _state.value
                        if (st.role != GroupRole.HOST && (st.phase == ConnectionPhase.IDLE || st.phase == ConnectionPhase.CONNECTING)) {
                            val pending = pendingDirectJoin
                            val targetName = pending?.groupName ?: st.groupName.ifBlank { "Direct Group" }
                            val targetPin = pending?.pin ?: ""
                            val targetPort = pending?.port ?: PRIMARY_P2P_PORT
                            discoveryManager.waitForDirectSubnetIp(4_500L)
                            joinGroup(event.ownerIp, targetPort, targetName, targetPin, maxConnectAttempts = 18)
                        }
                    }
                    is DirectDiscoveryEvent.SpecifierNetworkReady -> {
                        val pending = pendingDirectJoin
                        val targetIp = pending?.ipOverride?.ifBlank { "192.168.43.1" } ?: "192.168.43.1"
                        val targetPort = pending?.port ?: PRIMARY_P2P_PORT
                        val targetName = pending?.groupName ?: "Direct Group"
                        val targetPin = pending?.pin ?: ""
                        discoveryManager.waitForDirectSubnetIp(3_500L)
                        joinGroup(targetIp, targetPort, targetName, targetPin, maxConnectAttempts = 12)
                    }
                    is DirectDiscoveryEvent.SpecifierNetworkFailed -> {
                        _state.value = _state.value.copy(
                            phase = ConnectionPhase.IDLE,
                            role = GroupRole.NONE,
                            connectionStep = 0,
                            lastError = event.reason
                        )
                    }
                    is DirectDiscoveryEvent.WifiDirectPairFailed -> {
                        _state.value = _state.value.copy(
                            phase = ConnectionPhase.IDLE,
                            role = GroupRole.NONE,
                            connectionStep = 0,
                            lastError = event.reason
                        )
                    }
                }
            }
        }
    }

    private suspend fun becomeHostForExistingWifiDirectGroup(ownerIp: String, ssid: String, passphrase: String) {
        stopScanning()
        val preserveAutoReceive = _state.value.autoReceiveFiles
        val preserveLanMode = _state.value.localNetworkEnabled
        closeOrLeaveGroup(keepDiscovery = true, resetToIdle = false)
        val myId = preferences.ensureDeviceId()
        myDeviceId = myId
        val myName = currentSettings.deviceName
        val groupId = UUID.randomUUID().toString().take(8)
        val server = bindServerSocket()
        hostServerSocket = server
        val tcpPort = server.localPort
        val directIp = ownerIp.ifBlank { discoveryManager.getPrimaryDirectIp() }
        val cleanGroupName = "$myName's Group"
        val hostPeer = GroupPeer(
            deviceId = myId,
            deviceName = myName,
            ipAddress = directIp,
            permission = PeerPermission.FULL_ACCESS,
            keyFingerprint = "HOST",
            isHost = true
        )

        _state.value = _state.value.copy(
            phase = ConnectionPhase.HOSTING,
            role = GroupRole.HOST,
            myDeviceId = myId,
            groupId = groupId,
            groupName = cleanGroupName,
            hostIp = directIp,
            hostPort = tcpPort,
            hasPin = false,
            groupPin = "",
            keyFingerprint = "ECDH-P256",
            autoApprove = true,
            autoReceiveFiles = preserveAutoReceive,
            connectionStep = 4,
            webShareUrl = "",
            directQrPayload = buildDirectQrPayload(ssid, passphrase, directIp, tcpPort, cleanGroupName, false, groupId = groupId),
            localNetworkEnabled = preserveLanMode,
            myPermission = PeerPermission.FULL_ACCESS,
            peers = listOf(hostPeer),
            pendingRequests = emptyList(),
            sharedItems = emptyList(),
            deliveryReceipts = emptyMap(),
            receivedFiles = emptyList(),
            chatMessages = emptyList(),
            lastError = null
        )

        TransferForegroundService.start(context, cleanGroupName, tcpPort)
        hostAcceptJob = scope.launch {
            while (isActive && !server.isClosed) {
                val sock = runCatching { server.accept() }.getOrNull() ?: break
                launch { handleIncomingSocketOnHost(sock) }
            }
        }
    }

    private fun updateHostNetworkInfo(
        hostIp: String,
        ssid: String,
        pass: String,
        hostMac: String = "",
        frequency: Int = 0
    ) {
        val st = _state.value
        val qr = buildDirectQrPayload(
            ssid = ssid,
            pass = pass,
            ip = hostIp,
            port = st.hostPort,
            groupName = st.groupName,
            hasPin = st.hasPin,
            hostMac = hostMac,
            groupId = st.groupId,
            frequency = frequency
        )
        val webUrl = if (hostWebShareEnabled && hostWebSharePort > 0) {
            "http://$hostIp:$hostWebSharePort"
        } else st.webShareUrl
        _state.value = st.copy(
            hostIp = hostIp,
            directQrPayload = qr,
            webShareUrl = webUrl
        )
    }

    private fun buildDirectQrPayload(
        ssid: String,
        pass: String,
        ip: String,
        port: Int,
        groupName: String,
        hasPin: Boolean,
        hostMac: String = "",
        groupId: String = "",
        frequency: Int = 0
    ): String {
        return JSONObject().apply {
            put("t", "nx_direct")
            put("ssid", ssid)
            put("pass", pass)
            put("ip", ip)
            put("port", port)
            put("name", groupName)
            put("pin", hasPin)
            if (hostMac.isNotBlank()) put("mac", hostMac)
            if (groupId.isNotBlank()) put("gid", groupId)
            if (frequency > 0) put("freq", frequency)
        }.toString()
    }

    // =========================================================================
    // Cross-Session Pending Transfer Resume Queue Persistence
    // =========================================================================

    private fun persistPendingOutgoingDescriptors(descriptors: List<LocalSourceDescriptor>) {
        if (descriptors.isEmpty()) return
        synchronized(resumeQueueLock) {
            runCatching {
                val arr = if (pendingQueueFile.exists()) {
                    runCatching { JSONArray(pendingQueueFile.readText()) }.getOrDefault(JSONArray())
                } else JSONArray()

                val existingKeys = HashSet<String>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    existingKeys.add("${o.optString("fileName")}|${o.optLong("fileSize")}")
                }
                descriptors.forEach { d ->
                    val hasValidSource = d.uri != null || (d.filePath != null && File(d.filePath).exists())
                    if (hasValidSource && existingKeys.add("${d.fileName}|${d.fileSize}")) {
                        arr.put(JSONObject().apply {
                            put("itemId", d.itemId)
                            put("fileName", d.fileName)
                            put("fileSize", d.fileSize)
                            put("mimeType", d.mimeType)
                            put("uri", d.uri?.toString() ?: "")
                            put("filePath", d.filePath ?: "")
                            put("isAppPackage", d.isAppPackage)
                            put("sha256", d.sha256)
                        })
                    }
                }
                pendingQueueFile.writeText(arr.toString())
            }
        }
    }

    private fun removePendingOutgoingEntry(fileName: String, fileSize: Long) {
        if (fileName.isBlank()) return
        synchronized(resumeQueueLock) {
            runCatching {
                if (!pendingQueueFile.exists()) return
                val arr = runCatching { JSONArray(pendingQueueFile.readText()) }.getOrNull() ?: return
                val kept = JSONArray()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val fn = o.optString("fileName")
                    val fs = o.optLong("fileSize")
                    if (fn != fileName || fs != fileSize) {
                        kept.put(o)
                    }
                }
                if (kept.length() == 0) {
                    pendingQueueFile.delete()
                } else {
                    pendingQueueFile.writeText(kept.toString())
                }
            }
        }
    }

    private fun loadPendingOutgoingDescriptors(): List<LocalSourceDescriptor> {
        synchronized(resumeQueueLock) {
            if (!pendingQueueFile.exists()) return emptyList()
            val list = mutableListOf<LocalSourceDescriptor>()
            val validArray = JSONArray()
            runCatching {
                val arr = JSONArray(pendingQueueFile.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val uriStr = o.optString("uri", "")
                    val pathStr = o.optString("filePath", "").ifBlank { null }
                    val uri = uriStr.takeIf { it.isNotBlank() }?.let { runCatching { Uri.parse(it) }.getOrNull() }
                    val pathExists = pathStr != null && File(pathStr).exists()
                    val uriReadable = if (!pathExists && uri != null) {
                        runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false }.getOrDefault(false)
                    } else false
                    if (pathExists || uriReadable) {
                        validArray.put(o)
                        list += LocalSourceDescriptor(
                            itemId = o.optString("itemId").ifBlank { UUID.randomUUID().toString().take(10) },
                            fileName = o.getString("fileName"),
                            fileSize = o.optLong("fileSize", 0L),
                            mimeType = o.optString("mimeType", "application/octet-stream"),
                            uri = uri,
                            filePath = pathStr,
                            isAppPackage = o.optBoolean("isAppPackage", false),
                            sha256 = o.optString("sha256", "")
                        )
                    }
                }
                if (validArray.length() == 0) pendingQueueFile.delete()
                else if (validArray.length() != arr.length()) pendingQueueFile.writeText(validArray.toString())
            }
            return list
        }
    }

    private fun restorePendingOutgoingSharesIfAny() {
        if (restoredPendingThisSession) return
        val phase = _state.value.phase
        if (phase != ConnectionPhase.HOSTING && phase != ConnectionPhase.CONNECTED) return
        val pending = loadPendingOutgoingDescriptors()
        if (pending.isEmpty()) return
        restoredPendingThisSession = true
        val existingKeys = _state.value.sharedItems.map { "${it.fileName}|${it.fileSize}" }.toSet()
        val toRestore = pending.filterNot { existingKeys.contains("${it.fileName}|${it.fileSize}") }
        if (toRestore.isNotEmpty()) {
            addFilesToSharedGroup(toRestore)
        }
    }

    private class ConnectedClientSession(
        val deviceId: String,
        val deviceName: String,
        val ipAddress: String,
        val socket: Socket,
        val sessionKey: SecretKey,
        val out: DataOutputStream,
        val writeMutex: Mutex = Mutex(),
        var permission: PeerPermission = PeerPermission.FULL_ACCESS,
        val keyFingerprint: String
    )

    fun startScanning() {
        val ownGroupId = if (_state.value.role == GroupRole.HOST) _state.value.groupId else null
        _state.value = _state.value.copy(isScanning = true, lastError = null)
        discoveryManager.startDirectPeerDiscovery()
        if (_state.value.localNetworkEnabled) {
            discoveryManager.startLocalNetworkScanning(ownGroupId)
        }
    }

    fun stopScanning() {
        discoveryManager.stopDirectPeerDiscovery()
        discoveryManager.stopLocalNetworkScanning()
        _state.value = _state.value.copy(isScanning = false)
    }

    fun toggleLocalNetworkMode(enabled: Boolean) {
        _state.value = _state.value.copy(localNetworkEnabled = enabled)
        val st = _state.value
        if (enabled) {
            if (st.role == GroupRole.HOST) {
                discoveryManager.startLocalNetworkAdvertising(
                    groupId = st.groupId,
                    groupName = st.groupName,
                    hostDeviceName = currentSettings.deviceName,
                    port = st.hostPort,
                    hasPin = st.hasPin,
                    memberCountProvider = { _state.value.peers.size }
                )
            } else if (st.isScanning) {
                discoveryManager.startLocalNetworkScanning(null)
            }
        } else {
            discoveryManager.stopLocalNetworkAdvertising()
            discoveryManager.stopLocalNetworkScanning()
        }
    }

    fun startFallbackHotspot() {
        discoveryManager.startLocalOnlyHotspot()
    }

    fun setAutoReceiveFiles(enabled: Boolean) {
        _state.value = _state.value.copy(autoReceiveFiles = enabled)
    }

    fun updateReceivedFile(id: String, transform: (ReceivedGroupFile) -> ReceivedGroupFile) {
        _state.value = _state.value.copy(
            receivedFiles = _state.value.receivedFiles.map { if (it.id == id) transform(it) else it }
        )
    }

    fun removeReceivedFile(id: String) {
        _state.value = _state.value.copy(
            receivedFiles = _state.value.receivedFiles.filterNot { it.id == id }
        )
    }

    private fun enqueueAutoReceiveIfNeeded(items: List<SharedGroupItem>) {
        val st = _state.value
        if (!st.autoReceiveFiles) return
        if (st.myPermission != PeerPermission.FULL_ACCESS && st.myPermission != PeerPermission.RECEIVE_ONLY) return
        val selfId = myDeviceId
        items.forEach { item ->
            if (item.ownerDeviceId != selfId && autoQueuedItemIds.add(item.itemId)) {
                autoReceiveChannel.trySend(item to false)
            }
        }
    }

    fun connectToDirectPeer(peer: WifiDirectPeer, pin: String = "") {
        stopScanning()
        pendingDirectJoin = PendingDirectJoin(
            groupName = peer.serviceGroupName.ifBlank { peer.deviceName },
            pin = pin,
            port = peer.servicePort.takeIf { it > 0 } ?: PRIMARY_P2P_PORT
        )
        _state.value = _state.value.copy(
            phase = ConnectionPhase.CONNECTING,
            role = GroupRole.MEMBER,
            connectionStep = 1,
            groupName = peer.serviceGroupName.ifBlank { peer.deviceName },
            lastError = null
        )
        discoveryManager.connectToWifiDirectPeer(peer.deviceAddress, preferPeerAsOwner = peer.isNexoService)
    }

    fun joinViaDirectQrOrCredentials(
        ssid: String,
        passphrase: String,
        hostIp: String,
        hostPort: Int,
        groupName: String,
        pin: String,
        hostMac: String = "",
        groupId: String = "",
        frequency: Int = 0
    ) {
        stopScanning()
        if (ssid.isNotBlank()) {
            pendingDirectJoin = PendingDirectJoin(
                groupName = groupName,
                pin = pin,
                port = hostPort.takeIf { it > 0 } ?: PRIMARY_P2P_PORT,
                ipOverride = hostIp.ifBlank {
                    if (ssid.startsWith("DIRECT-", ignoreCase = true)) "192.168.49.1" else "192.168.43.1"
                }
            )
            _state.value = _state.value.copy(
                phase = ConnectionPhase.CONNECTING,
                role = GroupRole.MEMBER,
                connectionStep = 1,
                groupName = groupName,
                lastError = null
            )
            discoveryManager.connectDirectByQrCredentials(ssid, passphrase, hostMac, groupId, frequency)
        } else if (hostIp.isNotBlank()) {
            joinGroup(hostIp, hostPort.takeIf { it > 0 } ?: PRIMARY_P2P_PORT, groupName, pin)
        }
    }

    fun startWifiDirectGroup() {
        val st = _state.value
        discoveryManager.startDirectGroupHost(
            groupId = st.groupId.ifBlank { UUID.randomUUID().toString().take(8) },
            groupName = st.groupName.ifBlank { "${currentSettings.deviceName} Group" },
            hostDeviceName = currentSettings.deviceName,
            port = st.hostPort.takeIf { it > 0 } ?: PRIMARY_P2P_PORT,
            hasPin = st.hasPin
        )
    }

    fun discoverWifiDirect() {
        discoveryManager.startDirectPeerDiscovery()
    }

    private fun bindServerSocket(): ServerSocket {
        for (p in PRIMARY_P2P_PORT..(PRIMARY_P2P_PORT + 7)) {
            val attempt = runCatching {
                ServerSocket().apply {
                    reuseAddress = true
                    runCatching { receiveBufferSize = SOCKET_BUF_BYTES }
                    bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), p))
                }
            }.getOrNull()
            if (attempt != null) return attempt
        }
        return ServerSocket(0)
    }

    fun createGroup(
        groupName: String,
        pin: String,
        autoApprove: Boolean,
        enableWebShare: Boolean
    ) {
        stopScanning()
        val preserveAutoReceive = _state.value.autoReceiveFiles
        val preserveLanMode = _state.value.localNetworkEnabled
        _state.value = _state.value.copy(
            phase = ConnectionPhase.CONNECTING,
            role = GroupRole.HOST,
            connectionStep = 1,
            lastError = null
        )
        scope.launch {
            closeOrLeaveGroup(keepDiscovery = false, resetToIdle = false)
            val myId = preferences.ensureDeviceId()
            myDeviceId = myId
            val myName = currentSettings.deviceName
            val groupId = UUID.randomUUID().toString().take(8)
            val server = bindServerSocket()
            hostServerSocket = server
            val tcpPort = server.localPort
            val directIp = discoveryManager.getPrimaryDirectIp()

            hostWebShareEnabled = enableWebShare
            hostWebSharePort = if (enableWebShare) webShareServer.start() else 0
            val webUrl = if (enableWebShare && hostWebSharePort > 0) {
                "http://$directIp:$hostWebSharePort"
            } else ""

            val cleanGroupName = groupName.trim().ifBlank { "$myName's Group" }
            val hostPeer = GroupPeer(
                deviceId = myId,
                deviceName = myName,
                ipAddress = directIp,
                permission = PeerPermission.FULL_ACCESS,
                keyFingerprint = "HOST",
                isHost = true
            )

            _state.value = _state.value.copy(
                phase = ConnectionPhase.HOSTING,
                role = GroupRole.HOST,
                myDeviceId = myId,
                groupId = groupId,
                groupName = cleanGroupName,
                hostIp = directIp,
                hostPort = tcpPort,
                hasPin = pin.isNotBlank(),
                groupPin = pin.trim(),
                keyFingerprint = "ECDH-P256",
                autoApprove = autoApprove,
                autoReceiveFiles = preserveAutoReceive,
                connectionStep = 4,
                webShareUrl = webUrl,
                directQrPayload = buildDirectQrPayload("", "", directIp, tcpPort, cleanGroupName, pin.isNotBlank(), groupId = groupId),
                localNetworkEnabled = preserveLanMode,
                myPermission = PeerPermission.FULL_ACCESS,
                peers = listOf(hostPeer),
                pendingRequests = emptyList(),
                sharedItems = emptyList(),
                deliveryReceipts = emptyMap(),
                receivedFiles = emptyList(),
                chatMessages = emptyList(),
                lastError = null
            )

            TransferForegroundService.start(context, cleanGroupName, tcpPort)

            discoveryManager.startDirectGroupHost(
                groupId = groupId,
                groupName = cleanGroupName,
                hostDeviceName = myName,
                port = tcpPort,
                hasPin = pin.isNotBlank()
            )

            if (preserveLanMode) {
                discoveryManager.startLocalNetworkAdvertising(
                    groupId = groupId,
                    groupName = cleanGroupName,
                    hostDeviceName = myName,
                    port = tcpPort,
                    hasPin = pin.isNotBlank(),
                    memberCountProvider = { _state.value.peers.size }
                )
            }

            hostAcceptJob = scope.launch {
                while (isActive && !server.isClosed) {
                    val sock = runCatching { server.accept() }.getOrNull() ?: break
                    launch { handleIncomingSocketOnHost(sock) }
                }
            }
        }
    }

    private suspend fun handleIncomingSocketOnHost(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            runCatching {
                socket.sendBufferSize = SOCKET_BUF_BYTES
                socket.receiveBufferSize = SOCKET_BUF_BYTES
            }
            val dis = DataInputStream(BufferedInputStream(socket.getInputStream(), STREAM_BUF_BYTES))
            val dos = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), STREAM_BUF_BYTES))

            val keyPair = CryptoEngine.generateEphemeralEcKeyPair()
            val localPub = keyPair.public.encoded
            dos.writeInt(localPub.size)
            dos.write(localPub)
            dos.flush()

            val remoteLen = dis.readInt()
            if (remoteLen <= 0 || remoteLen > 4096) {
                socket.close()
                return
            }
            val remotePub = ByteArray(remoteLen)
            dis.readFully(remotePub)

            val salt = "nexodrop-hkdf-v1".toByteArray(Charsets.UTF_8)
            val (sessionKey, fingerprint) = CryptoEngine.deriveSessionKey(keyPair, remotePub, salt)

            val modeBytes = CryptoEngine.readEncryptedFrame(dis, sessionKey)
            val modeJson = P2pProtocol.parse(modeBytes)
            when (modeJson.optString("mode")) {
                "CONTROL" -> handleHostControlClient(socket, dis, dos, sessionKey, fingerprint, modeJson)
                "DATA_PULL" -> handleHostServeDataPull(socket, dis, dos, sessionKey, modeJson)
                "DATA_PUSH" -> handleHostReceiveRelayPush(socket, dis, dos, sessionKey, modeJson)
                else -> socket.close()
            }
        } catch (_: Exception) {
            runCatching { socket.close() }
        }
    }

    private suspend fun handleHostControlClient(
        socket: Socket,
        dis: DataInputStream,
        dos: DataOutputStream,
        sessionKey: SecretKey,
        fingerprint: String,
        joinInfo: JSONObject
    ) {
        val deviceId = joinInfo.getString("deviceId")
        val deviceName = joinInfo.optString("deviceName", "Android Device")
        val providedPin = joinInfo.optString("pin", "")
        val ipAddr = socket.inetAddress.hostAddress ?: ""

        if (repository.isPeerBanned(deviceId)) {
            CryptoEngine.writeEncryptedFrame(dos, sessionKey, P2pProtocol.msg(P2pProtocol.TYPE_JOIN_RESULT) {
                put("approved", false)
                put("reason", "Device is banned from this group")
            })
            socket.close()
            return
        }

        val requiredPin = _state.value.groupPin
        if (requiredPin.isNotBlank() && providedPin != requiredPin) {
            CryptoEngine.writeEncryptedFrame(dos, sessionKey, P2pProtocol.msg(P2pProtocol.TYPE_JOIN_RESULT) {
                put("approved", false)
                put("reason", "Incorrect Group PIN")
            })
            socket.close()
            return
        }

        val approved = if (_state.value.autoApprove) {
            true
        } else {
            val reqId = UUID.randomUUID().toString().take(8)
            val req = PendingJoinRequest(
                requestId = reqId,
                deviceId = deviceId,
                deviceName = deviceName,
                ipAddress = ipAddr,
                keyFingerprint = fingerprint
            )
            val deferred = CompletableDeferred<Boolean>()
            pendingDecisions[reqId] = deferred
            _state.value = _state.value.copy(pendingRequests = _state.value.pendingRequests + req)
            _events.tryEmit(P2pEvent.JoinRequestArrived(req))
            val result = withTimeoutOrNull(90_000L) { deferred.await() } ?: false
            pendingDecisions.remove(reqId)
            _state.value = _state.value.copy(
                pendingRequests = _state.value.pendingRequests.filterNot { it.requestId == reqId }
            )
            result
        }

        if (!approved) {
            CryptoEngine.writeEncryptedFrame(dos, sessionKey, P2pProtocol.msg(P2pProtocol.TYPE_JOIN_RESULT) {
                put("approved", false)
                put("reason", "Rejected by Host")
            })
            socket.close()
            return
        }

        repository.upsertTrustedPeer(
            TrustedPeerEntity(
                deviceId = deviceId,
                deviceName = deviceName,
                lastKeyFingerprint = fingerprint,
                isBanned = false
            )
        )

        val session = ConnectedClientSession(
            deviceId = deviceId,
            deviceName = deviceName,
            ipAddress = ipAddr,
            socket = socket,
            sessionKey = sessionKey,
            out = dos,
            permission = PeerPermission.FULL_ACCESS,
            keyFingerprint = fingerprint
        )
        hostClients[deviceId] = session

        val newPeer = GroupPeer(
            deviceId = deviceId,
            deviceName = deviceName,
            ipAddress = ipAddr,
            permission = PeerPermission.FULL_ACCESS,
            keyFingerprint = fingerprint,
            isHost = false
        )
        _state.value = _state.value.copy(
            peers = (_state.value.peers.filterNot { it.deviceId == deviceId } + newPeer)
        )

        session.writeMutex.withLock {
            CryptoEngine.writeEncryptedFrame(dos, sessionKey, P2pProtocol.msg(P2pProtocol.TYPE_JOIN_RESULT) {
                put("approved", true)
                put("groupId", _state.value.groupId)
                put("groupName", _state.value.groupName)
                put("fingerprint", fingerprint)
            })
        }

        broadcastPeerList()
        sendManifestToClient(session)
        // Auto-resume unfinished outgoing shares from any interrupted previous session
        restorePendingOutgoingSharesIfAny()

        try {
            while (!socket.isClosed) {
                val frame = CryptoEngine.readEncryptedFrame(dis, sessionKey)
                val msg = P2pProtocol.parse(frame)
                when (msg.optString("type")) {
                    P2pProtocol.TYPE_MANIFEST_ADD -> {
                        if (session.permission == PeerPermission.FULL_ACCESS || session.permission == PeerPermission.SEND_ONLY) {
                            val items = P2pProtocol.decodeItems(msg.getJSONArray("items"))
                            val merged = (_state.value.sharedItems + items).distinctBy { it.itemId }
                            _state.value = _state.value.copy(sharedItems = merged)
                            broadcastManifest()
                            enqueueAutoReceiveIfNeeded(items)
                        }
                    }
                    P2pProtocol.TYPE_CHAT -> {
                        val chat = GroupChatMessage(
                            id = msg.optString("id", UUID.randomUUID().toString()),
                            senderId = session.deviceId,
                            senderName = session.deviceName,
                            text = msg.getString("text"),
                            timestamp = msg.optLong("timestamp", System.currentTimeMillis())
                        )
                        _state.value = _state.value.copy(chatMessages = _state.value.chatMessages + chat)
                        broadcastChat(chat, excludeDeviceId = session.deviceId)
                    }
                    P2pProtocol.TYPE_DELIVERY_ACK -> {
                        val itemId = msg.optString("itemId", "")
                        val receiverName = msg.optString("receiverName", session.deviceName)
                        if (itemId.isNotBlank()) {
                            recordAndBroadcastDeliveryAck(itemId, receiverName)
                        }
                    }
                }
            }
        } finally {
            hostClients.remove(deviceId)
            _state.value = _state.value.copy(
                peers = _state.value.peers.filterNot { it.deviceId == deviceId }
            )
            broadcastPeerList()
            runCatching { socket.close() }
        }
    }

    fun respondToJoinRequest(requestId: String, approve: Boolean) {
        pendingDecisions[requestId]?.complete(approve)
    }

    fun updatePeerPermission(deviceId: String, permission: PeerPermission) {
        scope.launch {
            val session = hostClients[deviceId] ?: return@launch
            session.permission = permission
            _state.value = _state.value.copy(
                peers = _state.value.peers.map {
                    if (it.deviceId == deviceId) it.copy(permission = permission) else it
                }
            )
            session.writeMutex.withLock {
                CryptoEngine.writeEncryptedFrame(session.out, session.sessionKey, P2pProtocol.msg(P2pProtocol.TYPE_PERMISSION_UPDATE) {
                    put("permission", permission.name)
                })
            }
            broadcastPeerList()
        }
    }

    fun kickOrBanPeer(deviceId: String, ban: Boolean) {
        scope.launch {
            if (ban) {
                val peer = _state.value.peers.firstOrNull { it.deviceId == deviceId }
                repository.upsertTrustedPeer(
                    TrustedPeerEntity(
                        deviceId = deviceId,
                        deviceName = peer?.deviceName ?: "Banned Peer",
                        lastKeyFingerprint = peer?.keyFingerprint ?: "",
                        isBanned = true
                    )
                )
            }
            val session = hostClients.remove(deviceId)
            if (session != null) {
                runCatching {
                    session.writeMutex.withLock {
                        CryptoEngine.writeEncryptedFrame(session.out, session.sessionKey, P2pProtocol.msg(P2pProtocol.TYPE_KICK) {
                            put("banned", ban)
                        })
                    }
                    session.socket.close()
                }
            }
            _state.value = _state.value.copy(
                peers = _state.value.peers.filterNot { it.deviceId == deviceId }
            )
            broadcastPeerList()
        }
    }

    private suspend fun broadcastPeerList() {
        val arr = P2pProtocol.encodePeers(_state.value.peers)
        val payload = P2pProtocol.msg(P2pProtocol.TYPE_PEER_LIST) { put("peers", arr) }
        hostClients.values.forEach { client ->
            runCatching {
                client.writeMutex.withLock {
                    CryptoEngine.writeEncryptedFrame(client.out, client.sessionKey, payload)
                }
            }
        }
    }

    private suspend fun broadcastManifest() {
        val arr = P2pProtocol.encodeItems(_state.value.sharedItems)
        val payload = P2pProtocol.msg(P2pProtocol.TYPE_MANIFEST_SYNC) { put("items", arr) }
        hostClients.values.forEach { client ->
            runCatching {
                client.writeMutex.withLock {
                    CryptoEngine.writeEncryptedFrame(client.out, client.sessionKey, payload)
                }
            }
        }
    }

    private suspend fun sendManifestToClient(client: ConnectedClientSession) {
        val arr = P2pProtocol.encodeItems(_state.value.sharedItems)
        val payload = P2pProtocol.msg(P2pProtocol.TYPE_MANIFEST_SYNC) { put("items", arr) }
        runCatching {
            client.writeMutex.withLock {
                CryptoEngine.writeEncryptedFrame(client.out, client.sessionKey, payload)
            }
        }
    }

    private suspend fun broadcastChat(chat: GroupChatMessage, excludeDeviceId: String? = null) {
        val payload = P2pProtocol.msg(P2pProtocol.TYPE_CHAT) {
            put("id", chat.id)
            put("senderId", chat.senderId)
            put("senderName", chat.senderName)
            put("text", chat.text)
            put("timestamp", chat.timestamp)
        }
        hostClients.values.filter { it.deviceId != excludeDeviceId }.forEach { client ->
            runCatching {
                client.writeMutex.withLock {
                    CryptoEngine.writeEncryptedFrame(client.out, client.sessionKey, payload)
                }
            }
        }
    }

    private suspend fun recordAndBroadcastDeliveryAck(itemId: String, receiverName: String) {
        val currentMap = _state.value.deliveryReceipts.toMutableMap()
        val existing = currentMap[itemId].orEmpty()
        if (!existing.contains(receiverName)) {
            currentMap[itemId] = existing + receiverName
            _state.value = _state.value.copy(deliveryReceipts = currentMap)
        }
        _state.value.sharedItems.firstOrNull { it.itemId == itemId }?.let { ackItem ->
            removePendingOutgoingEntry(ackItem.fileName, ackItem.fileSize)
        }
        if (_state.value.role == GroupRole.HOST) {
            val payload = P2pProtocol.msg(P2pProtocol.TYPE_DELIVERY_ACK) {
                put("itemId", itemId)
                put("receiverName", receiverName)
            }
            hostClients.values.forEach { client ->
                runCatching {
                    client.writeMutex.withLock {
                        CryptoEngine.writeEncryptedFrame(client.out, client.sessionKey, payload)
                    }
                }
            }
        }
    }

    private fun handleBrowserUploadedFile(fileName: String, tempFile: File, mimeType: String) {
        scope.launch {
            runCatching {
                val size = tempFile.length()
                val sha = CryptoEngine.sha256Hex(tempFile)
                val category = FileCategory.fromFileNameAndMime(fileName, mimeType)
                val saved = storageRepository.saveReceivedTempFile(
                    tempFile = tempFile,
                    fileName = fileName,
                    mimeType = mimeType,
                    settings = currentSettings,
                    forceVault = false
                )
                tempFile.delete()
                val itemId = UUID.randomUUID().toString().take(10)
                val recvEntry = ReceivedGroupFile(
                    id = itemId,
                    itemId = itemId,
                    fileName = fileName,
                    fileSize = size,
                    mimeType = mimeType,
                    category = category,
                    senderName = "Web Browser",
                    sha256 = sha,
                    savedLocation = saved.displayLocation,
                    outputUri = saved.outputUri,
                    filePath = saved.filePath,
                    isInVault = saved.isInVault,
                    vaultItemId = saved.vaultItem?.let { repository.addVaultItem(it) }
                )
                _state.value = _state.value.copy(
                    receivedFiles = listOf(recvEntry) + _state.value.receivedFiles
                )
                repository.recordTransfer(
                    TransferRecordEntity(
                        transferId = itemId,
                        fileName = fileName,
                        fileSize = size,
                        mimeType = mimeType,
                        category = category.name,
                        direction = TransferDirection.RECEIVED.name,
                        peerDeviceName = "Web Browser",
                        peerDeviceId = "web-browser",
                        groupName = _state.value.groupName,
                        speedBytesPerSec = size,
                        durationMs = 1L,
                        status = TransferStatus.COMPLETED.name,
                        sha256 = sha,
                        savedLocation = saved.displayLocation,
                        isInVault = saved.isInVault
                    )
                )
                _events.tryEmit(P2pEvent.FileReceived(fileName, size, saved.isInVault))

                if (_state.value.role == GroupRole.HOST && !saved.isInVault) {
                    val desc = LocalSourceDescriptor(
                        itemId = itemId,
                        fileName = fileName,
                        fileSize = size,
                        mimeType = mimeType,
                        uri = saved.outputUri,
                        filePath = saved.filePath,
                        isAppPackage = fileName.lowercase().let { it.endsWith(".apk") || it.endsWith(".apks") },
                        sha256 = sha
                    )
                    addFilesToSharedGroup(listOf(desc))
                }
            }.onFailure {
                tempFile.delete()
            }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun openTcpSocket(hostIp: String, hostPort: Int, maxAttempts: Int = 6): Socket {
        var lastException: Exception? = null
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        for (attempt in 1..maxAttempts) {
            try {
                val explicitNet = discoveryManager.boundWifiNetwork ?: cm?.allNetworks?.firstOrNull { net ->
                    val caps = cm.getNetworkCapabilities(net)
                    val lp = cm.getLinkProperties(net)
                    val matchesWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                    val matchesSubnet = lp?.linkAddresses?.any { la ->
                        val addr = la.address?.hostAddress ?: ""
                        addr.startsWith("192.168.49.") || addr.startsWith("192.168.43.") ||
                            hostIp.substringBeforeLast(".") == addr.substringBeforeLast(".")
                    } == true
                    matchesSubnet || (matchesWifi && caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == false)
                }
                val sock = explicitNet?.socketFactory?.createSocket() ?: Socket().also { rawSock ->
                    val targetPrefix = hostIp.substringBeforeLast(".") + "."
                    val matchingLocalIp = discoveryManager.getLocalIpv4Addresses()
                        .firstOrNull { it.startsWith(targetPrefix) && it != hostIp }
                    if (matchingLocalIp != null) {
                        runCatching { rawSock.bind(InetSocketAddress(matchingLocalIp, 0)) }
                    }
                }
                sock.tcpNoDelay = true
                runCatching {
                    sock.sendBufferSize = SOCKET_BUF_BYTES
                    sock.receiveBufferSize = SOCKET_BUF_BYTES
                }
                sock.connect(InetSocketAddress(hostIp, hostPort), 5000)
                return sock
            } catch (e: Exception) {
                lastException = e
                if (attempt < maxAttempts) {
                    delay(450)
                }
            }
        }
        throw lastException ?: ConnectException("Unable to reach $hostIp:$hostPort")
    }

    fun joinGroup(hostIp: String, hostPort: Int, groupName: String, pin: String, maxConnectAttempts: Int = 6) {
        stopScanning()
        _state.value = _state.value.copy(
            phase = ConnectionPhase.CONNECTING,
            role = GroupRole.MEMBER,
            connectionStep = 2,
            groupName = groupName,
            hostIp = hostIp,
            hostPort = hostPort,
            groupPin = pin,
            lastError = null
        )
        scope.launch {
            closeOrLeaveGroup(keepDiscovery = true, resetToIdle = false)
            val myId = preferences.ensureDeviceId()
            myDeviceId = myId
            val myName = currentSettings.deviceName
            intentionalDisconnect = false
            _state.value = _state.value.copy(
                phase = ConnectionPhase.CONNECTING,
                role = GroupRole.MEMBER,
                connectionStep = 2,
                groupName = groupName,
                hostIp = hostIp,
                hostPort = hostPort,
                groupPin = pin,
                lastError = null
            )

            try {
                val sock = openTcpSocket(hostIp, hostPort, maxAttempts = maxConnectAttempts)
                clientControlSocket = sock

                val dis = DataInputStream(BufferedInputStream(sock.getInputStream(), STREAM_BUF_BYTES))
                val dos = DataOutputStream(BufferedOutputStream(sock.getOutputStream(), STREAM_BUF_BYTES))
                clientControlOut = dos

                val keyPair = CryptoEngine.generateEphemeralEcKeyPair()
                val remoteLen = dis.readInt()
                val remotePub = ByteArray(remoteLen)
                dis.readFully(remotePub)

                val localPub = keyPair.public.encoded
                dos.writeInt(localPub.size)
                dos.write(localPub)
                dos.flush()

                val salt = "nexodrop-hkdf-v1".toByteArray(Charsets.UTF_8)
                val (sessionKey, fingerprint) = CryptoEngine.deriveSessionKey(keyPair, remotePub, salt)
                clientControlKey = sessionKey

                clientWriteMutex.withLock {
                    CryptoEngine.writeEncryptedFrame(dos, sessionKey, P2pProtocol.msg("JOIN") {
                        put("mode", "CONTROL")
                        put("deviceId", myId)
                        put("deviceName", myName)
                        put("pin", pin)
                    })
                }

                _state.value = _state.value.copy(
                    phase = ConnectionPhase.WAITING_APPROVAL,
                    connectionStep = 3,
                    keyFingerprint = fingerprint
                )

                val resultFrame = CryptoEngine.readEncryptedFrame(dis, sessionKey)
                val resultJson = P2pProtocol.parse(resultFrame)
                if (!resultJson.optBoolean("approved", false)) {
                    val reason = resultJson.optString("reason", "Connection declined")
                    _state.value = _state.value.copy(
                        phase = ConnectionPhase.IDLE,
                        role = GroupRole.NONE,
                        connectionStep = 0,
                        lastError = reason
                    )
                    sock.close()
                    return@launch
                }

                lastConnectedIp = hostIp
                lastConnectedPort = hostPort
                lastConnectedName = resultJson.optString("groupName", groupName)
                lastConnectedPin = pin
                reconnectAttempts = 0

                _state.value = _state.value.copy(
                    phase = ConnectionPhase.CONNECTED,
                    myDeviceId = myId,
                    connectionStep = 4,
                    groupId = resultJson.optString("groupId", ""),
                    groupName = lastConnectedName,
                    keyFingerprint = fingerprint,
                    deliveryReceipts = emptyMap(),
                    myPermission = PeerPermission.FULL_ACCESS
                )
                TransferForegroundService.start(context, _state.value.groupName, hostPort)
                restorePendingOutgoingSharesIfAny()

                clientReadJob = scope.launch {
                    try {
                        while (isActive && !sock.isClosed) {
                            val frame = CryptoEngine.readEncryptedFrame(dis, sessionKey)
                            val msg = P2pProtocol.parse(frame)
                            when (msg.optString("type")) {
                                P2pProtocol.TYPE_PEER_LIST -> {
                                    val peers = P2pProtocol.decodePeers(msg.getJSONArray("peers"))
                                    _state.value = _state.value.copy(peers = peers)
                                }
                                P2pProtocol.TYPE_MANIFEST_SYNC -> {
                                    val items = P2pProtocol.decodeItems(msg.getJSONArray("items"))
                                    _state.value = _state.value.copy(sharedItems = items)
                                    enqueueAutoReceiveIfNeeded(items)
                                }
                                P2pProtocol.TYPE_PERMISSION_UPDATE -> {
                                    val perm = runCatching {
                                        PeerPermission.valueOf(msg.getString("permission"))
                                    }.getOrDefault(PeerPermission.FULL_ACCESS)
                                    _state.value = _state.value.copy(myPermission = perm)
                                }
                                P2pProtocol.TYPE_KICK -> {
                                    intentionalDisconnect = true
                                    _events.tryEmit(P2pEvent.GroupNotification("Removed from group by Host"))
                                    closeOrLeaveGroup()
                                    break
                                }
                                P2pProtocol.TYPE_CHAT -> {
                                    val chat = GroupChatMessage(
                                        id = msg.optString("id", UUID.randomUUID().toString()),
                                        senderId = msg.getString("senderId"),
                                        senderName = msg.getString("senderName"),
                                        text = msg.getString("text"),
                                        timestamp = msg.optLong("timestamp", System.currentTimeMillis())
                                    )
                                    _state.value = _state.value.copy(chatMessages = _state.value.chatMessages + chat)
                                }
                                P2pProtocol.TYPE_FILE_PUSH_OFFER -> {
                                    val itemId = msg.getString("itemId")
                                    val requestId = msg.getString("requestId")
                                    val offset = msg.optLong("offset", 0L).coerceAtLeast(0L)
                                    launch { pushLocalFileToHostRelay(itemId, requestId, offset) }
                                }
                                P2pProtocol.TYPE_DELIVERY_ACK -> {
                                    val itemId = msg.optString("itemId", "")
                                    val receiverName = msg.optString("receiverName", "")
                                    if (itemId.isNotBlank() && receiverName.isNotBlank()) {
                                        val currentMap = _state.value.deliveryReceipts.toMutableMap()
                                        val existing = currentMap[itemId].orEmpty()
                                        if (!existing.contains(receiverName)) {
                                            currentMap[itemId] = existing + receiverName
                                            _state.value = _state.value.copy(deliveryReceipts = currentMap)
                                        }
                                        _state.value.sharedItems.firstOrNull { it.itemId == itemId }?.let { ackItem ->
                                            if (ackItem.ownerDeviceId == myDeviceId) {
                                                removePendingOutgoingEntry(ackItem.fileName, ackItem.fileSize)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {
                        if (!intentionalDisconnect && _state.value.phase == ConnectionPhase.CONNECTED) {
                            if (reconnectAttempts < 3 && lastConnectedIp.isNotBlank()) {
                                reconnectAttempts++
                                _events.tryEmit(P2pEvent.GroupNotification("Reconnecting ($reconnectAttempts/3)..."))
                                delay(1200)
                                joinGroup(lastConnectedIp, lastConnectedPort, lastConnectedName, lastConnectedPin, maxConnectAttempts = 10)
                            } else {
                                _state.value = _state.value.copy(
                                    phase = ConnectionPhase.IDLE,
                                    role = GroupRole.NONE,
                                    connectionStep = 0,
                                    lastError = "Disconnected from group host"
                                )
                                TransferForegroundService.stop(context)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    phase = ConnectionPhase.IDLE,
                    role = GroupRole.NONE,
                    connectionStep = 0,
                    lastError = e.localizedMessage ?: "Connection failed"
                )
            }
        }
    }

    fun addFilesToSharedGroup(descriptors: List<LocalSourceDescriptor>) {
        persistPendingOutgoingDescriptors(descriptors)
        scope.launch {
            val myId = preferences.ensureDeviceId()
            val myName = currentSettings.deviceName
            val addedItems = descriptors.map { d ->
                localSources[d.itemId] = d
                SharedGroupItem(
                    itemId = d.itemId,
                    fileName = d.fileName,
                    fileSize = d.fileSize,
                    mimeType = d.mimeType,
                    category = FileCategory.fromFileNameAndMime(d.fileName, d.mimeType),
                    ownerDeviceId = myId,
                    ownerDeviceName = myName,
                    sha256 = d.sha256,
                    addedAt = System.currentTimeMillis(),
                    isAppPackage = d.isAppPackage
                )
            }

            if (_state.value.role == GroupRole.HOST) {
                val merged = (_state.value.sharedItems + addedItems).distinctBy { it.itemId }
                _state.value = _state.value.copy(sharedItems = merged)
                broadcastManifest()
            } else if (_state.value.role == GroupRole.MEMBER) {
                val out = clientControlOut ?: return@launch
                val key = clientControlKey ?: return@launch
                val arr = P2pProtocol.encodeItems(addedItems)
                clientWriteMutex.withLock {
                    CryptoEngine.writeEncryptedFrame(out, key, P2pProtocol.msg(P2pProtocol.TYPE_MANIFEST_ADD) {
                        put("items", arr)
                    })
                }
            }
        }
    }

    fun resendItemToGroup(item: SharedGroupItem) {
        val localSrc = localSources[item.itemId] ?: return
        val hasValidSource = localSrc.uri != null || (localSrc.filePath != null && File(localSrc.filePath).exists())
        if (!hasValidSource) return
        val freshDesc = localSrc.copy(itemId = UUID.randomUUID().toString().take(10))
        addFilesToSharedGroup(listOf(freshDesc))
    }

    private class TransferPausedException : Exception("Paused by user")

    fun sendChatMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        scope.launch {
            val myId = preferences.ensureDeviceId()
            val myName = currentSettings.deviceName
            val chat = GroupChatMessage(
                id = UUID.randomUUID().toString().take(8),
                senderId = myId,
                senderName = myName,
                text = trimmed,
                timestamp = System.currentTimeMillis()
            )
            _state.value = _state.value.copy(chatMessages = _state.value.chatMessages + chat)

            if (_state.value.role == GroupRole.HOST) {
                broadcastChat(chat)
            } else if (_state.value.role == GroupRole.MEMBER) {
                val out = clientControlOut ?: return@launch
                val key = clientControlKey ?: return@launch
                clientWriteMutex.withLock {
                    CryptoEngine.writeEncryptedFrame(out, key, P2pProtocol.msg(P2pProtocol.TYPE_CHAT) {
                        put("id", chat.id)
                        put("senderId", chat.senderId)
                        put("senderName", chat.senderName)
                        put("text", chat.text)
                        put("timestamp", chat.timestamp)
                    })
                }
            }
        }
    }

    fun downloadGroupItem(item: SharedGroupItem, forceVault: Boolean = false) {
        scope.launch {
            performDownload(item, forceVault)
        }
    }

    private suspend fun performDownload(item: SharedGroupItem, forceVault: Boolean) {
        val transferId = UUID.randomUUID().toString().take(10)
        val startTime = System.currentTimeMillis()
        if (!forceVault && item.fileSize > 0L) {
            val freeBytes = storageRepository.getAvailableStorageBytes()
            if (freeBytes < item.fileSize + 1024 * 1024L) {
                _state.value = _state.value.copy(
                    lastError = "Not enough storage space for ${item.fileName}"
                )
                return
            }
        }

        // Deterministic partial file key survives stops/reconnections across sessions!
        val partFile = storageRepository.getDeterministicPartialFile(item.fileName, item.fileSize)
        if (partFile.exists() && item.fileSize > 0L && partFile.length() > item.fileSize) {
            partFile.delete()
        }
        val initialExistingBytes = if (partFile.exists() && item.fileSize > 0L && partFile.length() <= item.fileSize) {
            partFile.length()
        } else 0L

        val transfer = ActiveTransfer(
            transferId = transferId,
            itemId = item.itemId,
            fileName = item.fileName,
            totalBytes = item.fileSize,
            bytesTransferred = initialExistingBytes,
            speedBytesPerSec = 0L,
            isUpload = false,
            peerName = item.ownerDeviceName,
            saveToVault = forceVault,
            sourceItem = item
        )
        upsertActiveTransfer(transfer)

        try {
            // Host downloading a local or member-owned file (with deterministic cross-session resume)
            if (_state.value.role == GroupRole.HOST) {
                val localSrc = localSources[item.itemId]
                if (localSrc != null) {
                    val resumeOff = if (partFile.exists() && partFile.length() in 1L until item.fileSize) partFile.length() else 0L
                    val (srcStream, effectiveOff) = storageRepository.openSeekableStreamAt(
                        localSrc.uri, localSrc.filePath, localSrc.fileName, currentSettings.stripExifOnSend, resumeOff
                    )
                    val appendMode = effectiveOff > 0L && effectiveOff == resumeOff
                    var copied = if (appendMode) effectiveOff else 0L
                    val digest = if (!appendMode) MessageDigest.getInstance("SHA-256") else null
                    srcStream.use { input ->
                        BufferedOutputStream(FileOutputStream(partFile, appendMode), STREAM_BUF_BYTES).use { out ->
                            val buf = ByteArray(CHUNK_SIZE_BYTES)
                            var lastUi = System.currentTimeMillis()
                            while (true) {
                                if (activeCancelledIds.contains(transferId)) throw IllegalStateException("Cancelled by user")
                                if (pausedTransferIds.contains(transferId)) throw TransferPausedException()
                                val n = input.read(buf)
                                if (n <= 0) break
                                digest?.update(buf, 0, n)
                                out.write(buf, 0, n)
                                copied += n
                                val now = System.currentTimeMillis()
                                if (now - lastUi >= 150L) {
                                    val elapsed = (now - startTime).coerceAtLeast(1L)
                                    val deltaCopied = (copied - effectiveOff).coerceAtLeast(0L)
                                    updateTransferProgress(transferId, copied, item.fileSize, (deltaCopied * 1000L) / elapsed)
                                    lastUi = now
                                }
                            }
                            out.flush()
                        }
                    }
                    val sha = digest?.digest()?.joinToString("") { "%02x".format(it) } ?: CryptoEngine.sha256Hex(partFile)
                    val elapsed = (System.currentTimeMillis() - startTime).coerceAtLeast(1L)
                    val deltaCopied = (copied - effectiveOff).coerceAtLeast(0L)
                    val speed = (deltaCopied * 1000L) / elapsed
                    finishDownload(transferId, item, partFile, sha, speed, elapsed, forceVault)
                    return
                } else {
                    val relayed = requestRelayFromOwner(item) ?: throw IllegalStateException("Owner peer unreachable")
                    val sha = CryptoEngine.sha256Hex(relayed)
                    val elapsed = (System.currentTimeMillis() - startTime).coerceAtLeast(1L)
                    val speed = (relayed.length() * 1000L) / elapsed
                    finishDownload(transferId, item, relayed, sha, speed, elapsed, forceVault)
                    return
                }
            }

            // If 100% of bytes already arrived in partial_transfers right before disconnect, finalize immediately
            if (item.fileSize > 0L && partFile.exists() && partFile.length() == item.fileSize) {
                val diskSha = CryptoEngine.sha256Hex(partFile)
                if (item.sha256.isBlank() || diskSha.equals(item.sha256, ignoreCase = true)) {
                    finishDownload(transferId, item, partFile, diskSha, item.fileSize, 1L, forceVault)
                    return
                }
            }

            // Member pulling from Host over encrypted DATA_PULL TCP socket with instant offset resume
            val hostIp = _state.value.hostIp
            val hostPort = _state.value.hostPort
            val sock = openTcpSocket(hostIp, hostPort, maxAttempts = 6)
            val existingOffset = if (partFile.exists() && partFile.length() in 1L until item.fileSize) partFile.length() else 0L

            sock.use { s ->
                val dis = DataInputStream(BufferedInputStream(s.getInputStream(), STREAM_BUF_BYTES))
                val dos = DataOutputStream(BufferedOutputStream(s.getOutputStream(), STREAM_BUF_BYTES))

                val keyPair = CryptoEngine.generateEphemeralEcKeyPair()
                val remoteLen = dis.readInt()
                val remotePub = ByteArray(remoteLen)
                dis.readFully(remotePub)
                val localPub = keyPair.public.encoded
                dos.writeInt(localPub.size)
                dos.write(localPub)
                dos.flush()

                val salt = "nexodrop-hkdf-v1".toByteArray(Charsets.UTF_8)
                val (sessionKey, _) = CryptoEngine.deriveSessionKey(keyPair, remotePub, salt)

                CryptoEngine.writeEncryptedFrame(dos, sessionKey, P2pProtocol.msg("DATA_PULL") {
                    put("mode", "DATA_PULL")
                    put("itemId", item.itemId)
                    put("offset", existingOffset)
                })

                val metaJson = P2pProtocol.parse(CryptoEngine.readEncryptedFrame(dis, sessionKey))
                if (metaJson.optString("type") == P2pProtocol.TYPE_FILE_ERROR) {
                    throw IllegalStateException(metaJson.optString("error", "File unavailable"))
                }
                val expectedSize = metaJson.optLong("fileSize", item.fileSize)
                val resumedFrom = metaJson.optLong("resumedFrom", 0L)
                val appendMode = resumedFrom > 0L && resumedFrom == existingOffset
                var received = if (appendMode) resumedFrom else 0L
                val inlineDigest = if (!appendMode) MessageDigest.getInstance("SHA-256") else null
                if (appendMode) {
                    updateTransferProgress(transferId, received, expectedSize, 0L)
                }
                var lastUiUpdate = System.currentTimeMillis()

                BufferedOutputStream(FileOutputStream(partFile, appendMode), STREAM_BUF_BYTES).use { out ->
                    while (true) {
                        if (activeCancelledIds.contains(transferId)) {
                            throw IllegalStateException("Cancelled by user")
                        }
                        if (pausedTransferIds.contains(transferId)) {
                            throw TransferPausedException()
                        }
                        val chunk = CryptoEngine.readEncryptedFrame(dis, sessionKey)
                        if (chunk.isEmpty()) break
                        inlineDigest?.update(chunk)
                        out.write(chunk)
                        received += chunk.size
                        val now = System.currentTimeMillis()
                        if (now - lastUiUpdate >= 150L) {
                            val elapsed = (now - startTime).coerceAtLeast(1L)
                            val deltaReceived = (received - resumedFrom).coerceAtLeast(0L)
                            val speed = (deltaReceived * 1000L) / elapsed
                            updateTransferProgress(transferId, received, expectedSize, speed)
                            TransferForegroundService.updateProgress(
                                context, item.fileName,
                                if (expectedSize > 0) ((received * 100) / expectedSize).toInt() else 0,
                                speed
                            )
                            lastUiUpdate = now
                        }
                    }
                    out.flush()
                }

                val sha = inlineDigest?.digest()?.joinToString("") { "%02x".format(it) }
                    ?: CryptoEngine.sha256Hex(partFile)
                val elapsed = (System.currentTimeMillis() - startTime).coerceAtLeast(1L)
                val deltaReceived = (received - resumedFrom).coerceAtLeast(0L)
                val speed = (deltaReceived * 1000L) / elapsed
                finishDownload(transferId, item, partFile, sha, speed, elapsed, forceVault)
            }
        } catch (_: TransferPausedException) {
            pausedTransferIds.remove(transferId)
            val savedBytes = if (partFile.exists()) partFile.length() else 0L
            _state.value = _state.value.copy(
                activeTransfers = _state.value.activeTransfers.map {
                    if (it.transferId == transferId) {
                        it.copy(
                            bytesTransferred = savedBytes.coerceAtLeast(it.bytesTransferred),
                            speedBytesPerSec = 0L,
                            isPaused = true,
                            sourceItem = item
                        )
                    } else it
                }
            )
            TransferForegroundService.stop(context)
        } catch (e: Exception) {
            val cancelled = activeCancelledIds.remove(transferId)
            if (cancelled) partFile.delete()
            val status = if (cancelled) TransferStatus.CANCELLED else TransferStatus.FAILED
            markTransferFinished(transferId, error = if (cancelled) "Cancelled" else e.localizedMessage)
            repository.recordTransfer(
                TransferRecordEntity(
                    transferId = transferId,
                    fileName = item.fileName,
                    fileSize = item.fileSize,
                    mimeType = item.mimeType,
                    category = item.category.name,
                    direction = TransferDirection.RECEIVED.name,
                    peerDeviceName = item.ownerDeviceName,
                    peerDeviceId = item.ownerDeviceId,
                    groupName = _state.value.groupName,
                    speedBytesPerSec = 0L,
                    durationMs = System.currentTimeMillis() - startTime,
                    status = status.name,
                    sha256 = "",
                    savedLocation = "",
                    isInVault = forceVault
                )
            )
        }
    }

    private suspend fun finishDownload(
        transferId: String,
        item: SharedGroupItem,
        tempFile: File,
        sha256: String,
        speed: Long,
        elapsed: Long,
        forceVault: Boolean
    ) {
        val saved = storageRepository.saveReceivedTempFile(
            tempFile = tempFile,
            fileName = item.fileName,
            mimeType = item.mimeType,
            settings = currentSettings,
            forceVault = forceVault
        )
        val insertedVaultId = saved.vaultItem?.let { repository.addVaultItem(it) }
        tempFile.delete()

        val recvEntry = ReceivedGroupFile(
            id = transferId,
            itemId = item.itemId,
            fileName = item.fileName,
            fileSize = item.fileSize,
            mimeType = item.mimeType,
            category = item.category,
            senderName = item.ownerDeviceName,
            sha256 = sha256,
            savedLocation = saved.displayLocation,
            outputUri = saved.outputUri,
            filePath = saved.filePath,
            isInVault = saved.isInVault,
            vaultItemId = insertedVaultId
        )
        _state.value = _state.value.copy(
            receivedFiles = listOf(recvEntry) + _state.value.receivedFiles
        )

        markTransferFinished(transferId, completed = true, finalBytes = item.fileSize, speed = speed)
        repository.recordTransfer(
            TransferRecordEntity(
                transferId = transferId,
                fileName = item.fileName,
                fileSize = item.fileSize,
                mimeType = item.mimeType,
                category = item.category.name,
                direction = TransferDirection.RECEIVED.name,
                peerDeviceName = item.ownerDeviceName,
                peerDeviceId = item.ownerDeviceId,
                groupName = _state.value.groupName,
                speedBytesPerSec = speed,
                durationMs = elapsed,
                status = TransferStatus.COMPLETED.name,
                sha256 = sha256,
                savedLocation = saved.displayLocation,
                isInVault = saved.isInVault
            )
        )
        _events.tryEmit(P2pEvent.FileReceived(item.fileName, item.fileSize, saved.isInVault))

        if (_state.value.role == GroupRole.HOST) {
            recordAndBroadcastDeliveryAck(item.itemId, currentSettings.deviceName)
        } else if (_state.value.role == GroupRole.MEMBER) {
            val out = clientControlOut
            val key = clientControlKey
            if (out != null && key != null) {
                runCatching {
                    clientWriteMutex.withLock {
                        CryptoEngine.writeEncryptedFrame(out, key, P2pProtocol.msg(P2pProtocol.TYPE_DELIVERY_ACK) {
                            put("itemId", item.itemId)
                            put("receiverName", currentSettings.deviceName)
                        })
                    }
                }
            }
        }
    }

    private suspend fun requestRelayFromOwner(item: SharedGroupItem): File? {
        relayCacheFiles[item.itemId]?.takeIf { it.exists() && it.length() >= item.fileSize }?.let { return it }
        val ownerSession = hostClients[item.ownerDeviceId] ?: return null
        val partialRelay = storageRepository.getDeterministicPartialFile(item.fileName, item.fileSize)
        if (item.fileSize > 0L && partialRelay.exists() && partialRelay.length() == item.fileSize) {
            if (item.sha256.isBlank() || CryptoEngine.sha256Hex(partialRelay).equals(item.sha256, ignoreCase = true)) {
                relayCacheFiles[item.itemId] = partialRelay
                return partialRelay
            }
        }
        val existingOffset = if (partialRelay.exists() && partialRelay.length() in 1L until item.fileSize) {
            partialRelay.length()
        } else 0L

        val reqId = UUID.randomUUID().toString().take(8)
        val waiter = CompletableDeferred<File?>()
        pendingRelayWaiters[reqId] = waiter
        ownerSession.writeMutex.withLock {
            CryptoEngine.writeEncryptedFrame(ownerSession.out, ownerSession.sessionKey, P2pProtocol.msg(P2pProtocol.TYPE_FILE_PUSH_OFFER) {
                put("itemId", item.itemId)
                put("requestId", reqId)
                put("offset", existingOffset)
            })
        }
        return withTimeoutOrNull(120_000L) { waiter.await() }
    }

    private suspend fun handleHostReceiveRelayPush(
        socket: Socket,
        dis: DataInputStream,
        dos: DataOutputStream,
        sessionKey: SecretKey,
        meta: JSONObject
    ) {
        val itemId = meta.getString("itemId")
        val requestId = meta.getString("requestId")
        val resumedFrom = meta.optLong("resumedFrom", 0L).coerceAtLeast(0L)
        val item = _state.value.sharedItems.firstOrNull { it.itemId == itemId }
        val fileName = meta.optString("fileName", item?.fileName ?: "relay_$itemId")
        val fileSize = meta.optLong("fileSize", item?.fileSize ?: 0L)
        val cacheFile = if (fileName.isNotBlank() && fileSize > 0L) {
            storageRepository.getDeterministicPartialFile(fileName, fileSize)
        } else {
            File(context.cacheDir, "relay_${itemId}")
        }
        val appendMode = resumedFrom > 0L && cacheFile.exists() && cacheFile.length() == resumedFrom
        try {
            BufferedOutputStream(FileOutputStream(cacheFile, appendMode), STREAM_BUF_BYTES).use { out ->
                while (true) {
                    val chunk = CryptoEngine.readEncryptedFrame(dis, sessionKey)
                    if (chunk.isEmpty()) break
                    out.write(chunk)
                }
                out.flush()
            }
            relayCacheFiles[itemId] = cacheFile
            pendingRelayWaiters.remove(requestId)?.complete(cacheFile)
        } catch (_: Exception) {
            // Preserve partial relay bytes across session drops so future reconnections can resume from offset
            pendingRelayWaiters.remove(requestId)?.complete(null)
        } finally {
            runCatching { socket.close() }
        }
    }

    private suspend fun pushLocalFileToHostRelay(itemId: String, requestId: String, requestedOffset: Long = 0L) {
        val src = localSources[itemId] ?: return
        val transferId = UUID.randomUUID().toString().take(10)
        val startTime = System.currentTimeMillis()
        upsertActiveTransfer(
            ActiveTransfer(
                transferId = transferId,
                itemId = itemId,
                fileName = src.fileName,
                totalBytes = src.fileSize,
                bytesTransferred = requestedOffset,
                speedBytesPerSec = 0L,
                isUpload = true,
                peerName = _state.value.groupName
            )
        )
        try {
            val sock = openTcpSocket(_state.value.hostIp, _state.value.hostPort, maxAttempts = 5)
            sock.use { s ->
                val dis = DataInputStream(BufferedInputStream(s.getInputStream(), STREAM_BUF_BYTES))
                val dos = DataOutputStream(BufferedOutputStream(s.getOutputStream(), STREAM_BUF_BYTES))

                val keyPair = CryptoEngine.generateEphemeralEcKeyPair()
                val remoteLen = dis.readInt()
                val remotePub = ByteArray(remoteLen)
                dis.readFully(remotePub)
                val localPub = keyPair.public.encoded
                dos.writeInt(localPub.size)
                dos.write(localPub)
                dos.flush()

                val salt = "nexodrop-hkdf-v1".toByteArray(Charsets.UTF_8)
                val (sessionKey, _) = CryptoEngine.deriveSessionKey(keyPair, remotePub, salt)

                val (stream, effectiveOffset) = storageRepository.openSeekableStreamAt(
                    src.uri, src.filePath, src.fileName, currentSettings.stripExifOnSend, requestedOffset
                )

                CryptoEngine.writeEncryptedFrame(dos, sessionKey, P2pProtocol.msg("DATA_PUSH") {
                    put("mode", "DATA_PUSH")
                    put("itemId", itemId)
                    put("requestId", requestId)
                    put("fileName", src.fileName)
                    put("fileSize", src.fileSize)
                    put("resumedFrom", effectiveOffset)
                })

                val digest = if (effectiveOffset == 0L) MessageDigest.getInstance("SHA-256") else null
                var sent = effectiveOffset
                val buf = ByteArray(CHUNK_SIZE_BYTES)
                var lastUi = System.currentTimeMillis()
                stream.use { input ->
                    while (true) {
                        if (activeCancelledIds.contains(transferId)) throw IllegalStateException("Cancelled")
                        val read = input.read(buf)
                        if (read <= 0) break
                        digest?.update(buf, 0, read)
                        CryptoEngine.writeEncryptedChunk(dos, sessionKey, buf, 0, read, flush = false)
                        sent += read
                        val now = System.currentTimeMillis()
                        if (now - lastUi >= 150L) {
                            val elapsed = (now - startTime).coerceAtLeast(1L)
                            val deltaSent = (sent - effectiveOffset).coerceAtLeast(0L)
                            updateTransferProgress(transferId, sent, src.fileSize, (deltaSent * 1000L) / elapsed)
                            lastUi = now
                        }
                    }
                }
                CryptoEngine.writeEncryptedFrame(dos, sessionKey, ByteArray(0))
                val elapsed = (System.currentTimeMillis() - startTime).coerceAtLeast(1L)
                val deltaSent = (sent - effectiveOffset).coerceAtLeast(0L)
                val speed = (deltaSent * 1000L) / elapsed
                val sha = digest?.digest()?.joinToString("") { "%02x".format(it) } ?: src.sha256
                markTransferFinished(transferId, completed = true, finalBytes = sent, speed = speed)
                repository.recordTransfer(
                    TransferRecordEntity(
                        transferId = transferId,
                        fileName = src.fileName,
                        fileSize = sent,
                        mimeType = src.mimeType,
                        category = FileCategory.fromFileNameAndMime(src.fileName, src.mimeType).name,
                        direction = TransferDirection.SENT.name,
                        peerDeviceName = _state.value.groupName,
                        peerDeviceId = _state.value.groupId,
                        groupName = _state.value.groupName,
                        speedBytesPerSec = speed,
                        durationMs = elapsed,
                        status = TransferStatus.COMPLETED.name,
                        sha256 = sha,
                        savedLocation = "Group Relay",
                        isInVault = false
                    )
                )
            }
        } catch (e: Exception) {
            markTransferFinished(transferId, error = e.localizedMessage)
        }
    }

    private suspend fun handleHostServeDataPull(
        socket: Socket,
        dis: DataInputStream,
        dos: DataOutputStream,
        sessionKey: SecretKey,
        req: JSONObject
    ) {
        val itemId = req.getString("itemId")
        val item = _state.value.sharedItems.firstOrNull { it.itemId == itemId }
        if (item == null) {
            CryptoEngine.writeEncryptedFrame(dos, sessionKey, P2pProtocol.msg(P2pProtocol.TYPE_FILE_ERROR) {
                put("error", "File no longer shared")
            })
            socket.close()
            return
        }

        val requestedOffset = req.optLong("offset", 0L).coerceAtLeast(0L)
        val localSrc = localSources[itemId]
        val (inputStream, effectiveOffset) = if (localSrc != null) {
            storageRepository.openSeekableStreamAt(
                localSrc.uri, localSrc.filePath, localSrc.fileName, currentSettings.stripExifOnSend, requestedOffset
            )
        } else {
            val relayed = requestRelayFromOwner(item)
            if (relayed == null || !relayed.exists()) {
                CryptoEngine.writeEncryptedFrame(dos, sessionKey, P2pProtocol.msg(P2pProtocol.TYPE_FILE_ERROR) {
                    put("error", "Owner offline")
                })
                socket.close()
                return
            }
            storageRepository.openSeekableStreamAt(
                null, relayed.absolutePath, item.fileName, false, requestedOffset
            )
        }

        val transferId = UUID.randomUUID().toString().take(10)
        val startTime = System.currentTimeMillis()
        upsertActiveTransfer(
            ActiveTransfer(
                transferId = transferId,
                itemId = itemId,
                fileName = item.fileName,
                totalBytes = item.fileSize,
                bytesTransferred = effectiveOffset,
                speedBytesPerSec = 0L,
                isUpload = true,
                peerName = socket.inetAddress.hostAddress ?: "Peer"
            )
        )

        try {
            CryptoEngine.writeEncryptedFrame(dos, sessionKey, P2pProtocol.msg(P2pProtocol.TYPE_FILE_META) {
                put("fileName", item.fileName)
                put("fileSize", item.fileSize)
                put("resumedFrom", effectiveOffset)
            })

            val digest = if (effectiveOffset == 0L) MessageDigest.getInstance("SHA-256") else null
            var sent = effectiveOffset
            val buf = ByteArray(CHUNK_SIZE_BYTES)
            var lastUi = System.currentTimeMillis()
            inputStream.use { input ->
                while (true) {
                    if (activeCancelledIds.contains(transferId)) throw IllegalStateException("Cancelled")
                    val read = input.read(buf)
                    if (read <= 0) break
                    digest?.update(buf, 0, read)
                    CryptoEngine.writeEncryptedChunk(dos, sessionKey, buf, 0, read, flush = false)
                    sent += read
                    val now = System.currentTimeMillis()
                    if (now - lastUi >= 150L) {
                        val elapsed = (now - startTime).coerceAtLeast(1L)
                        val deltaSent = (sent - effectiveOffset).coerceAtLeast(0L)
                        updateTransferProgress(transferId, sent, item.fileSize, (deltaSent * 1000L) / elapsed)
                        lastUi = now
                    }
                }
            }
            CryptoEngine.writeEncryptedFrame(dos, sessionKey, ByteArray(0))
            val elapsed = (System.currentTimeMillis() - startTime).coerceAtLeast(1L)
            val deltaSent = (sent - effectiveOffset).coerceAtLeast(0L)
            val speed = (deltaSent * 1000L) / elapsed
            val sha = digest?.digest()?.joinToString("") { "%02x".format(it) } ?: item.sha256
            markTransferFinished(transferId, completed = true, finalBytes = sent, speed = speed)
            repository.recordTransfer(
                TransferRecordEntity(
                    transferId = transferId,
                    fileName = item.fileName,
                    fileSize = sent,
                    mimeType = item.mimeType,
                    category = item.category.name,
                    direction = TransferDirection.SENT.name,
                    peerDeviceName = socket.inetAddress.hostAddress ?: "Group Peer",
                    peerDeviceId = item.ownerDeviceId,
                    groupName = _state.value.groupName,
                    speedBytesPerSec = speed,
                    durationMs = elapsed,
                    status = TransferStatus.COMPLETED.name,
                    sha256 = sha,
                    savedLocation = "Sent to Group",
                    isInVault = false
                )
            )
        } catch (e: Exception) {
            markTransferFinished(transferId, error = e.localizedMessage)
        } finally {
            runCatching { socket.close() }
        }
    }

    fun pauseTransfer(transferId: String) {
        val current = _state.value.activeTransfers.firstOrNull { it.transferId == transferId } ?: return
        if (current.isUpload || current.isCompleted || current.isPaused) return
        pausedTransferIds.add(transferId)
    }

    fun resumeTransfer(transferId: String) {
        val paused = _state.value.activeTransfers.firstOrNull { it.transferId == transferId } ?: return
        val item = paused.sourceItem ?: _state.value.sharedItems.firstOrNull { it.itemId == paused.itemId } ?: return
        pausedTransferIds.remove(transferId)
        _state.value = _state.value.copy(
            activeTransfers = _state.value.activeTransfers.filterNot { it.transferId == transferId }
        )
        downloadGroupItem(item, paused.saveToVault)
    }

    fun cancelTransfer(transferId: String) {
        val target = _state.value.activeTransfers.firstOrNull { it.transferId == transferId }
        pausedTransferIds.remove(transferId)
        activeCancelledIds.add(transferId)
        if (target != null) {
            if (target.isUpload) {
                removePendingOutgoingEntry(target.fileName, target.totalBytes)
            } else {
                storageRepository.getDeterministicPartialFile(target.fileName, target.totalBytes)
                    .takeIf { it.exists() }?.delete()
            }
        }
        _state.value = _state.value.copy(
            activeTransfers = _state.value.activeTransfers.filterNot { it.transferId == transferId }
        )
    }

    private fun upsertActiveTransfer(transfer: ActiveTransfer) {
        val current = _state.value.activeTransfers.toMutableList()
        current.add(0, transfer)
        _state.value = _state.value.copy(activeTransfers = current)
    }

    private fun updateTransferProgress(transferId: String, bytes: Long, total: Long, speed: Long) {
        _state.value = _state.value.copy(
            activeTransfers = _state.value.activeTransfers.map {
                if (it.transferId == transferId) {
                    it.copy(bytesTransferred = bytes, totalBytes = total, speedBytesPerSec = speed)
                } else it
            }
        )
    }

    private fun markTransferFinished(
        transferId: String,
        completed: Boolean = false,
        finalBytes: Long = 0L,
        speed: Long = 0L,
        error: String? = null
    ) {
        _state.value = _state.value.copy(
            activeTransfers = _state.value.activeTransfers.map {
                if (it.transferId == transferId) {
                    it.copy(
                        bytesTransferred = if (completed) finalBytes else it.bytesTransferred,
                        speedBytesPerSec = speed,
                        isCompleted = completed,
                        error = error
                    )
                } else it
            }
        )
        scope.launch {
            delay(3000)
            _state.value = _state.value.copy(
                activeTransfers = _state.value.activeTransfers.filterNot { it.transferId == transferId && (it.isCompleted || it.error != null) }
            )
        }
    }

    fun closeOrLeaveGroup(keepDiscovery: Boolean = false, resetToIdle: Boolean = true) {
        if (resetToIdle) {
            intentionalDisconnect = true
            reconnectAttempts = 0
            restoredPendingThisSession = false
        }
        val preserveAutoReceive = _state.value.autoReceiveFiles
        val preserveLanMode = _state.value.localNetworkEnabled
        pendingDirectJoin = if (keepDiscovery) pendingDirectJoin else null
        hostWebShareEnabled = false
        hostWebSharePort = 0

        discoveryManager.stopLocalNetworkAdvertising()
        if (!keepDiscovery) {
            discoveryManager.removeWifiDirectGroup()
            discoveryManager.stopLocalOnlyHotspot()
            discoveryManager.unregisterNetworkCallback()
        }
        webShareServer.stop()
        hostAcceptJob?.cancel()
        hostAcceptJob = null
        runCatching { hostServerSocket?.close() }
        hostServerSocket = null

        hostClients.values.forEach { runCatching { it.socket.close() } }
        hostClients.clear()
        pendingDecisions.values.forEach { it.complete(false) }
        pendingDecisions.clear()

        clientReadJob?.cancel()
        clientReadJob = null
        runCatching { clientControlSocket?.close() }
        clientControlSocket = null
        clientControlKey = null
        clientControlOut = null

        val partialDirCanonical = runCatching { storageRepository.getPartialTransferDir().canonicalPath }.getOrNull()
        relayCacheFiles.values.forEach { f ->
            val inPartialDir = partialDirCanonical != null && runCatching { f.canonicalPath.startsWith(partialDirCanonical) }.getOrDefault(false)
            if (!inPartialDir) f.delete()
        }
        relayCacheFiles.clear()
        autoQueuedItemIds.clear()
        pausedTransferIds.clear()
        activeCancelledIds.clear()
        if (resetToIdle) {
            // Preserve partial_transfers across session close so reconnecting via QR resumes mid-file!
            storageRepository.clearTempTransferCache(includePartialDownloads = false)
        }

        TransferForegroundService.stop(context)

        if (resetToIdle) {
            _state.value = GroupWorkspaceState(
                myDeviceId = myDeviceId,
                autoReceiveFiles = preserveAutoReceive,
                discoveredGroups = _state.value.discoveredGroups,
                isScanning = _state.value.isScanning,
                localNetworkEnabled = preserveLanMode,
                wifiDirect = if (keepDiscovery) _state.value.wifiDirect else WifiDirectState(supported = _state.value.wifiDirect.supported)
            )
        }
    }

    fun clearError() {
        _state.value = _state.value.copy(lastError = null)
    }
}
