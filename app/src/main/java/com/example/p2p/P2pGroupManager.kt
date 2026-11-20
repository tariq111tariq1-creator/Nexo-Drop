package com.example.p2p

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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

    private val webShareServer = WebShareServer(
        scope = scope,
        storageRepository = storageRepository,
        groupNameProvider = { _state.value.groupName },
        groupPinProvider = { _state.value.groupPin },
        sharedItemsProvider = { _state.value.sharedItems },
        localSourceLookup = { itemId -> localSources[itemId] }
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
    }

    private var pendingDirectJoin: PendingDirectJoin? = null
    private var hostWebShareEnabled: Boolean = false
    private var hostWebSharePort: Int = 0

    init {
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
                    is DirectDiscoveryEvent.WifiDirectCredentialsReady -> {
                        val effectiveIp = event.ownerIp.ifBlank { discoveryManager.getPrimaryDirectIp() }
                        if (_state.value.role == GroupRole.HOST) {
                            updateHostNetworkInfo(
                                hostIp = effectiveIp,
                                ssid = event.ssid,
                                pass = event.passphrase
                            )
                        }
                    }
                    is DirectDiscoveryEvent.HotspotCredentialsReady -> {
                        val effectiveIp = event.ip.ifBlank { discoveryManager.getPrimaryDirectIp() }
                        if (_state.value.role == GroupRole.HOST) {
                            updateHostNetworkInfo(
                                hostIp = effectiveIp,
                                ssid = event.ssid,
                                pass = event.passphrase
                            )
                        }
                    }
                    is DirectDiscoveryEvent.WifiDirectGroupFormedAsClient -> {
                        val pending = pendingDirectJoin
                        val targetName = pending?.groupName ?: "Direct Group"
                        val targetPin = pending?.pin ?: ""
                        val targetPort = pending?.port ?: PRIMARY_P2P_PORT
                        delay(450)
                        joinGroup(event.ownerIp, targetPort, targetName, targetPin)
                    }
                    is DirectDiscoveryEvent.SpecifierNetworkReady -> {
                        val pending = pendingDirectJoin
                        val targetIp = pending?.ipOverride?.ifBlank { "192.168.43.1" } ?: "192.168.43.1"
                        val targetPort = pending?.port ?: PRIMARY_P2P_PORT
                        val targetName = pending?.groupName ?: "Direct Group"
                        val targetPin = pending?.pin ?: ""
                        delay(350)
                        joinGroup(targetIp, targetPort, targetName, targetPin)
                    }
                    is DirectDiscoveryEvent.SpecifierNetworkFailed -> {
                        _state.value = _state.value.copy(
                            phase = ConnectionPhase.IDLE,
                            role = GroupRole.NONE,
                            lastError = event.reason
                        )
                    }
                }
            }
        }
    }

    private fun updateHostNetworkInfo(hostIp: String, ssid: String, pass: String) {
        val st = _state.value
        val qr = buildDirectQrPayload(
            ssid = ssid,
            pass = pass,
            ip = hostIp,
            port = st.hostPort,
            groupName = st.groupName,
            hasPin = st.hasPin
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
        hasPin: Boolean
    ): String {
        return JSONObject().apply {
            put("t", "nx_direct")
            put("ssid", ssid)
            put("pass", pass)
            put("ip", ip)
            put("port", port)
            put("name", groupName)
            put("pin", hasPin)
        }.toString()
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

    fun connectToDirectPeer(peer: WifiDirectPeer, pin: String = "") {
        pendingDirectJoin = PendingDirectJoin(
            groupName = peer.serviceGroupName.ifBlank { peer.deviceName },
            pin = pin,
            port = peer.servicePort.takeIf { it > 0 } ?: PRIMARY_P2P_PORT
        )
        _state.value = _state.value.copy(
            phase = ConnectionPhase.CONNECTING,
            role = GroupRole.MEMBER,
            groupName = peer.serviceGroupName.ifBlank { peer.deviceName },
            lastError = null
        )
        discoveryManager.connectToWifiDirectPeer(peer.deviceAddress)
    }

    fun joinViaDirectQrOrCredentials(
        ssid: String,
        passphrase: String,
        hostIp: String,
        hostPort: Int,
        groupName: String,
        pin: String
    ) {
        if (ssid.isNotBlank()) {
            pendingDirectJoin = PendingDirectJoin(
                groupName = groupName,
                pin = pin,
                port = hostPort.takeIf { it > 0 } ?: PRIMARY_P2P_PORT,
                ipOverride = hostIp.ifBlank { "192.168.43.1" }
            )
            _state.value = _state.value.copy(
                phase = ConnectionPhase.CONNECTING,
                role = GroupRole.MEMBER,
                groupName = groupName,
                lastError = null
            )
            discoveryManager.connectToDirectWifiNetwork(ssid, passphrase)
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
        scope.launch {
            val preserveLanMode = _state.value.localNetworkEnabled
            closeOrLeaveGroup(keepDiscovery = false)
            val myId = preferences.ensureDeviceId()
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
                groupId = groupId,
                groupName = cleanGroupName,
                hostIp = directIp,
                hostPort = tcpPort,
                hasPin = pin.isNotBlank(),
                groupPin = pin.trim(),
                keyFingerprint = "ECDH-P256",
                autoApprove = autoApprove,
                webShareUrl = webUrl,
                directQrPayload = buildDirectQrPayload("", "", directIp, tcpPort, cleanGroupName, pin.isNotBlank()),
                localNetworkEnabled = preserveLanMode,
                myPermission = PeerPermission.FULL_ACCESS,
                peers = listOf(hostPeer),
                pendingRequests = emptyList(),
                sharedItems = emptyList(),
                chatMessages = emptyList(),
                lastError = null
            )

            TransferForegroundService.start(context, cleanGroupName, tcpPort)

            // PRIMARY: Direct Device-to-Device Host (Wi-Fi Direct Group Owner + DNS-SD, auto-fallback to Hotspot)
            discoveryManager.startDirectGroupHost(
                groupId = groupId,
                groupName = cleanGroupName,
                hostDeviceName = myName,
                port = tcpPort,
                hasPin = pin.isNotBlank()
            )

            // SECONDARY: Optional Local Wi-Fi Network advertisement when explicitly enabled
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
            val dis = DataInputStream(BufferedInputStream(socket.getInputStream()))
            val dos = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))

            // Step 1: ECDH Key Exchange
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

            // Step 2: Encrypted Session Mode Frame
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

        // Control loop
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

    /**
     * Opens a TCP socket bound to the direct Wi-Fi / P2P network if available (preventing mobile data hijacking)
     * and retries briefly while direct network routes settle.
     */
    @Suppress("DEPRECATION")
    private suspend fun openTcpSocket(hostIp: String, hostPort: Int, maxAttempts: Int = 5): Socket {
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
                val sock = explicitNet?.socketFactory?.createSocket() ?: Socket()
                sock.connect(InetSocketAddress(hostIp, hostPort), 5500)
                sock.tcpNoDelay = true
                return sock
            } catch (e: Exception) {
                lastException = e
                if (attempt < maxAttempts) {
                    delay(700)
                }
            }
        }
        throw lastException ?: ConnectException("Unable to reach $hostIp:$hostPort")
    }

    /**
     * Join an existing Host group by IP, Port, and optional PIN.
     */
    fun joinGroup(hostIp: String, hostPort: Int, groupName: String, pin: String) {
        scope.launch {
            closeOrLeaveGroup(keepDiscovery = true)
            val myId = preferences.ensureDeviceId()
            val myName = currentSettings.deviceName
            _state.value = _state.value.copy(
                phase = ConnectionPhase.CONNECTING,
                role = GroupRole.MEMBER,
                groupName = groupName,
                hostIp = hostIp,
                hostPort = hostPort,
                groupPin = pin,
                lastError = null
            )

            try {
                val sock = openTcpSocket(hostIp, hostPort, maxAttempts = 5)
                clientControlSocket = sock

                val dis = DataInputStream(BufferedInputStream(sock.getInputStream()))
                val dos = DataOutputStream(BufferedOutputStream(sock.getOutputStream()))
                clientControlOut = dos

                // ECDH
                val keyPair = CryptoEngine.generateEphemeralEcKeyPair()
                val remoteLen = dis.readInt()
                val remotePub = ByteArray(remoteLen)
                dis.readFully(remotePub)

                val localPub = keyPair.public.encoded
                dos.writeInt(localPub.size)
                dos.write(localPub)
                dos.flush()

                // Group ID might come from discovery; for manual IP join wait for server salt or derive using empty/group hint -> Wait! Host used groupId as salt.
                // Let's fix salt consistency: Host & Client can derive using empty salt on initial handshake (ECDH shared secret already has 256-bit entropy!).
                // Wait: Host used `_state.value.groupId.toByteArray()`! Let's make sure Host and Client use identical constant salt `"nexodrop-hkdf-v1".toByteArray()` so manual IP joins work without needing groupId ahead of time!
                val salt = "nexodrop-hkdf-v1".toByteArray(Charsets.UTF_8)
                val (sessionKey, fingerprint) = CryptoEngine.deriveSessionKey(keyPair, remotePub, salt)
                clientControlKey = sessionKey

                // Send control join request
                clientWriteMutex.withLock {
                    CryptoEngine.writeEncryptedFrame(dos, sessionKey, P2pProtocol.msg("JOIN") {
                        put("mode", "CONTROL")
                        put("deviceId", myId)
                        put("deviceName", myName)
                        put("pin", pin)
                    })
                }

                _state.value = _state.value.copy(phase = ConnectionPhase.WAITING_APPROVAL, keyFingerprint = fingerprint)

                val resultFrame = CryptoEngine.readEncryptedFrame(dis, sessionKey)
                val resultJson = P2pProtocol.parse(resultFrame)
                if (!resultJson.optBoolean("approved", false)) {
                    val reason = resultJson.optString("reason", "Connection declined")
                    _state.value = _state.value.copy(
                        phase = ConnectionPhase.IDLE,
                        role = GroupRole.NONE,
                        lastError = reason
                    )
                    sock.close()
                    return@launch
                }

                _state.value = _state.value.copy(
                    phase = ConnectionPhase.CONNECTED,
                    groupId = resultJson.optString("groupId", ""),
                    groupName = resultJson.optString("groupName", groupName),
                    keyFingerprint = fingerprint,
                    myPermission = PeerPermission.FULL_ACCESS
                )
                TransferForegroundService.start(context, _state.value.groupName, hostPort)

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
                                }
                                P2pProtocol.TYPE_PERMISSION_UPDATE -> {
                                    val perm = runCatching {
                                        PeerPermission.valueOf(msg.getString("permission"))
                                    }.getOrDefault(PeerPermission.FULL_ACCESS)
                                    _state.value = _state.value.copy(myPermission = perm)
                                }
                                P2pProtocol.TYPE_KICK -> {
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
                                    launch { pushLocalFileToHostRelay(itemId, requestId) }
                                }
                            }
                        }
                    } catch (_: Exception) {
                        if (_state.value.phase == ConnectionPhase.CONNECTED) {
                            _state.value = _state.value.copy(
                                phase = ConnectionPhase.IDLE,
                                role = GroupRole.NONE,
                                lastError = "Disconnected from group host"
                            )
                            TransferForegroundService.stop(context)
                        }
                    }
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    phase = ConnectionPhase.IDLE,
                    role = GroupRole.NONE,
                    lastError = e.localizedMessage ?: "Connection failed"
                )
            }
        }
    }

    /**
     * Add local files or packaged APKs to the live Shared Group Tray.
     */
    fun addFilesToSharedGroup(descriptors: List<LocalSourceDescriptor>) {
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

    /**
     * Downloads a SharedGroupItem over the encrypted P2P connection and saves it
     * to the chosen storage destination (Internal, SD Card, or Private Encrypted Vault).
     */
    fun downloadGroupItem(item: SharedGroupItem, forceVault: Boolean = false) {
        scope.launch {
            val transferId = UUID.randomUUID().toString().take(10)
            val startTime = System.currentTimeMillis()
            val transfer = ActiveTransfer(
                transferId = transferId,
                itemId = item.itemId,
                fileName = item.fileName,
                totalBytes = item.fileSize,
                bytesTransferred = 0L,
                speedBytesPerSec = 0L,
                isUpload = false,
                peerName = item.ownerDeviceName,
                saveToVault = forceVault
            )
            upsertActiveTransfer(transfer)
            val tempFile = File(context.cacheDir, "dl_${transferId}_${item.fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")}")

            try {
                // If Host is downloading an item owned by a Member, request Member to push to relay cache first
                if (_state.value.role == GroupRole.HOST) {
                    val localSrc = localSources[item.itemId]
                    if (localSrc != null) {
                        // Own file -> direct copy
                        storageRepository.openInputStream(localSrc.uri, localSrc.filePath).use { input ->
                            tempFile.outputStream().use { out -> input.copyTo(out, 64 * 1024) }
                        }
                    } else {
                        val relayed = requestRelayFromOwner(item) ?: throw IllegalStateException("Owner peer unreachable")
                        relayed.inputStream().use { input ->
                            tempFile.outputStream().use { out -> input.copyTo(out, 64 * 1024) }
                        }
                    }
                    val sha = CryptoEngine.sha256Hex(tempFile)
                    val elapsed = (System.currentTimeMillis() - startTime).coerceAtLeast(1L)
                    val speed = (tempFile.length() * 1000L) / elapsed
                    finishDownload(transferId, item, tempFile, sha, speed, elapsed, forceVault)
                    return@launch
                }

                // Member pulling from Host over encrypted DATA_PULL TCP socket
                val hostIp = _state.value.hostIp
                val hostPort = _state.value.hostPort
                val sock = openTcpSocket(hostIp, hostPort, maxAttempts = 3)

                sock.use { s ->
                    val dis = DataInputStream(BufferedInputStream(s.getInputStream()))
                    val dos = DataOutputStream(BufferedOutputStream(s.getOutputStream()))

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
                        put("offset", 0L)
                    })

                    val metaJson = P2pProtocol.parse(CryptoEngine.readEncryptedFrame(dis, sessionKey))
                    if (metaJson.optString("type") == P2pProtocol.TYPE_FILE_ERROR) {
                        throw IllegalStateException(metaJson.optString("error", "File unavailable"))
                    }
                    val expectedSize = metaJson.optLong("fileSize", item.fileSize)
                    val digest = MessageDigest.getInstance("SHA-256")
                    var received = 0L
                    var lastUiUpdate = System.currentTimeMillis()

                    BufferedOutputStream(FileOutputStream(tempFile)).use { out ->
                        while (true) {
                            if (activeCancelledIds.contains(transferId)) {
                                throw IllegalStateException("Cancelled by user")
                            }
                            val chunk = CryptoEngine.readEncryptedFrame(dis, sessionKey)
                            if (chunk.isEmpty()) break // EOF sentinel
                            out.write(chunk)
                            digest.update(chunk)
                            received += chunk.size
                            val now = System.currentTimeMillis()
                            if (now - lastUiUpdate >= 150L) {
                                val elapsed = (now - startTime).coerceAtLeast(1L)
                                val speed = (received * 1000L) / elapsed
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

                    val sha = digest.digest().joinToString("") { "%02x".format(it) }
                    val elapsed = (System.currentTimeMillis() - startTime).coerceAtLeast(1L)
                    val speed = (received * 1000L) / elapsed
                    finishDownload(transferId, item, tempFile, sha, speed, elapsed, forceVault)
                }
            } catch (e: Exception) {
                tempFile.delete()
                val cancelled = activeCancelledIds.remove(transferId)
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
        saved.vaultItem?.let { repository.addVaultItem(it) }
        tempFile.delete()

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
    }

    private suspend fun requestRelayFromOwner(item: SharedGroupItem): File? {
        relayCacheFiles[item.itemId]?.takeIf { it.exists() }?.let { return it }
        val ownerSession = hostClients[item.ownerDeviceId] ?: return null
        val reqId = UUID.randomUUID().toString().take(8)
        val waiter = CompletableDeferred<File?>()
        pendingRelayWaiters[reqId] = waiter
        ownerSession.writeMutex.withLock {
            CryptoEngine.writeEncryptedFrame(ownerSession.out, ownerSession.sessionKey, P2pProtocol.msg(P2pProtocol.TYPE_FILE_PUSH_OFFER) {
                put("itemId", item.itemId)
                put("requestId", reqId)
            })
        }
        return withTimeoutOrNull(60_000L) { waiter.await() }
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
        val cacheFile = File(context.cacheDir, "relay_${itemId}")
        try {
            BufferedOutputStream(FileOutputStream(cacheFile)).use { out ->
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
            cacheFile.delete()
            pendingRelayWaiters.remove(requestId)?.complete(null)
        } finally {
            runCatching { socket.close() }
        }
    }

    private suspend fun pushLocalFileToHostRelay(itemId: String, requestId: String) {
        val src = localSources[itemId] ?: return
        val transferId = UUID.randomUUID().toString().take(10)
        val startTime = System.currentTimeMillis()
        upsertActiveTransfer(
            ActiveTransfer(
                transferId = transferId,
                itemId = itemId,
                fileName = src.fileName,
                totalBytes = src.fileSize,
                bytesTransferred = 0L,
                speedBytesPerSec = 0L,
                isUpload = true,
                peerName = _state.value.groupName
            )
        )
        try {
            val sock = openTcpSocket(_state.value.hostIp, _state.value.hostPort, maxAttempts = 3)
            sock.use { s ->
                val dis = DataInputStream(BufferedInputStream(s.getInputStream()))
                val dos = DataOutputStream(BufferedOutputStream(s.getOutputStream()))

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

                CryptoEngine.writeEncryptedFrame(dos, sessionKey, P2pProtocol.msg("DATA_PUSH") {
                    put("mode", "DATA_PUSH")
                    put("itemId", itemId)
                    put("requestId", requestId)
                })

                val stream = storageRepository.openPrivacyAwareStream(
                    src.uri, src.filePath, src.fileName, currentSettings.stripExifOnSend
                )
                val digest = MessageDigest.getInstance("SHA-256")
                var sent = 0L
                val buf = ByteArray(128 * 1024)
                stream.use { input ->
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        val chunk = if (read == buf.size) buf else buf.copyOf(read)
                        digest.update(chunk)
                        CryptoEngine.writeEncryptedFrame(dos, sessionKey, chunk)
                        sent += read
                        val elapsed = (System.currentTimeMillis() - startTime).coerceAtLeast(1L)
                        updateTransferProgress(transferId, sent, src.fileSize, (sent * 1000L) / elapsed)
                    }
                }
                CryptoEngine.writeEncryptedFrame(dos, sessionKey, ByteArray(0))
                val elapsed = (System.currentTimeMillis() - startTime).coerceAtLeast(1L)
                val speed = (sent * 1000L) / elapsed
                val sha = digest.digest().joinToString("") { "%02x".format(it) }
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

        val localSrc = localSources[itemId]
        val inputStream = if (localSrc != null) {
            storageRepository.openPrivacyAwareStream(
                localSrc.uri, localSrc.filePath, localSrc.fileName, currentSettings.stripExifOnSend
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
            FileInputStream(relayed)
        }

        val transferId = UUID.randomUUID().toString().take(10)
        val startTime = System.currentTimeMillis()
        upsertActiveTransfer(
            ActiveTransfer(
                transferId = transferId,
                itemId = itemId,
                fileName = item.fileName,
                totalBytes = item.fileSize,
                bytesTransferred = 0L,
                speedBytesPerSec = 0L,
                isUpload = true,
                peerName = socket.inetAddress.hostAddress ?: "Peer"
            )
        )

        try {
            CryptoEngine.writeEncryptedFrame(dos, sessionKey, P2pProtocol.msg(P2pProtocol.TYPE_FILE_META) {
                put("fileName", item.fileName)
                put("fileSize", item.fileSize)
            })

            val digest = MessageDigest.getInstance("SHA-256")
            var sent = 0L
            val buf = ByteArray(128 * 1024)
            var lastUi = System.currentTimeMillis()
            inputStream.use { input ->
                while (true) {
                    if (activeCancelledIds.contains(transferId)) throw IllegalStateException("Cancelled")
                    val read = input.read(buf)
                    if (read <= 0) break
                    val chunk = if (read == buf.size) buf else buf.copyOf(read)
                    digest.update(chunk)
                    CryptoEngine.writeEncryptedFrame(dos, sessionKey, chunk)
                    sent += read
                    val now = System.currentTimeMillis()
                    if (now - lastUi >= 150L) {
                        val elapsed = (now - startTime).coerceAtLeast(1L)
                        updateTransferProgress(transferId, sent, item.fileSize, (sent * 1000L) / elapsed)
                        lastUi = now
                    }
                }
            }
            CryptoEngine.writeEncryptedFrame(dos, sessionKey, ByteArray(0)) // EOF
            val elapsed = (System.currentTimeMillis() - startTime).coerceAtLeast(1L)
            val speed = (sent * 1000L) / elapsed
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
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

    fun cancelTransfer(transferId: String) {
        activeCancelledIds.add(transferId)
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

    fun closeOrLeaveGroup(keepDiscovery: Boolean = false) {
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

        relayCacheFiles.values.forEach { it.delete() }
        relayCacheFiles.clear()

        TransferForegroundService.stop(context)

        _state.value = GroupWorkspaceState(
            discoveredGroups = _state.value.discoveredGroups,
            isScanning = _state.value.isScanning,
            localNetworkEnabled = preserveLanMode,
            wifiDirect = if (keepDiscovery) _state.value.wifiDirect else WifiDirectState(supported = _state.value.wifiDirect.supported)
        )
    }

    fun clearError() {
        _state.value = _state.value.copy(lastError = null)
    }
}
