package com.example.p2p

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import kotlin.coroutines.resume

sealed class DirectDiscoveryEvent {
    data class WifiDirectBecameGroupOwner(val ownerIp: String, val ssid: String, val passphrase: String) : DirectDiscoveryEvent()
    data class WifiDirectGroupFormedAsClient(val ownerIp: String) : DirectDiscoveryEvent()
    data class WifiDirectCredentialsReady(
        val ssid: String,
        val passphrase: String,
        val ownerIp: String,
        val frequency: Int = 0,
        val hostMac: String = ""
    ) : DirectDiscoveryEvent()
    data class HotspotCredentialsReady(val ssid: String, val passphrase: String, val ip: String) : DirectDiscoveryEvent()
    data class SpecifierNetworkReady(val network: Network) : DirectDiscoveryEvent()
    data class SpecifierNetworkFailed(val reason: String) : DirectDiscoveryEvent()
    data class WifiDirectPairFailed(val reason: String) : DirectDiscoveryEvent()
}

/**
 * Primary Direct Device-to-Device Discovery & Pairing Engine (NO router required):
 * 1. Wi-Fi Direct (WifiP2pManager): autonomous Group Owner + DNS-SD service advertisement + Peer/Service discovery + 1-tap connect
 * 2. Direct Local-Only Hotspot (startLocalOnlyHotspot) + WifiNetworkSpecifier (Android 10+ in-app direct Wi-Fi join via QR/SSID)
 * 3. Secondary Local Network (NSD + UDP LAN broadcast) when enabled
 */
class DiscoveryManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val NSD_TYPE = "_nexodrop._tcp."
        private const val P2P_SERVICE_NAME = "nexodrop"
        private const val P2P_SERVICE_TYPE = "_nexodrop._tcp"
        private const val UDP_DISCOVERY_PORT = 45891
        private const val ACTION_SUCCESS = -1
    }

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val p2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var p2pChannel: WifiP2pManager.Channel? = null

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private var hotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var activeNetworkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile
    var boundWifiNetwork: Network? = null
        private set

    private var localP2pServiceInfo: WifiP2pDnsSdServiceInfo? = null
    private val discoveredP2pServices = LinkedHashMap<String, WifiDirectPeer>()
    private val discoveredGroupIdsByAddress = LinkedHashMap<String, String>()

    private var udpListenerJob: Job? = null
    private var udpBeaconJob: Job? = null
    private var p2pDiscoveryLoopJob: Job? = null
    private var p2pReceiverRegistered = false
    private var qrConnectJob: Job? = null
    private var peerConnectJob: Job? = null

    private val _discoveredGroups = MutableStateFlow<List<DiscoveredGroup>>(emptyList())
    val discoveredGroups: StateFlow<List<DiscoveredGroup>> = _discoveredGroups.asStateFlow()

    private val _wifiDirectState = MutableStateFlow(WifiDirectState(supported = p2pManager != null))
    val wifiDirectState: StateFlow<WifiDirectState> = _wifiDirectState.asStateFlow()

    private val _events = MutableSharedFlow<DirectDiscoveryEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<DirectDiscoveryEvent> = _events.asSharedFlow()

    @Volatile
    private var isExpectingClientP2pAutoConnect = false
    @Volatile
    private var isWifiP2pEnabled = true
    @Volatile
    private var localDeviceAddress: String = ""

    private val p2pReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(
                        WifiP2pManager.EXTRA_WIFI_STATE,
                        WifiP2pManager.WIFI_P2P_STATE_DISABLED
                    )
                    isWifiP2pEnabled = (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED)
                    if (!isWifiP2pEnabled) {
                        _wifiDirectState.value = _wifiDirectState.value.copy(
                            statusMessage = "Wi-Fi Direct disabled in system Wi-Fi"
                        )
                    }
                }
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    val dev: WifiP2pDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, WifiP2pDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                    }
                    val addr = dev?.deviceAddress.orEmpty()
                    if (addr.isNotBlank() && addr != "02:00:00:00:00:00") {
                        localDeviceAddress = addr
                    }
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    val manager = p2pManager ?: return
                    val channel = p2pChannel ?: return
                    runCatching {
                        manager.requestPeers(channel) { peers: WifiP2pDeviceList? ->
                            val rawPeers = peers?.deviceList?.map { d: WifiP2pDevice ->
                                val svc = discoveredP2pServices[d.deviceAddress]
                                WifiDirectPeer(
                                    deviceName = svc?.serviceGroupName?.ifBlank { d.deviceName } ?: (d.deviceName.ifBlank { d.deviceAddress }),
                                    deviceAddress = d.deviceAddress,
                                    status = d.status,
                                    isNexoService = svc != null,
                                    serviceGroupName = svc?.serviceGroupName ?: "",
                                    servicePort = svc?.servicePort ?: 45892,
                                    hasPin = svc?.hasPin ?: false
                                )
                            }?.sortedWith(
                                compareByDescending<WifiDirectPeer> { it.isNexoService }
                                    .thenBy { it.status == WifiP2pDevice.FAILED }
                            ) ?: emptyList()
                            _wifiDirectState.value = _wifiDirectState.value.copy(discoveredPeers = rawPeers)
                        }
                    }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val manager = p2pManager ?: return
                    val channel = p2pChannel ?: return
                    runCatching {
                        manager.requestConnectionInfo(channel) { info: WifiP2pInfo? ->
                            if (info != null && info.groupFormed) {
                                val ownerIp = info.groupOwnerAddress?.hostAddress ?: "192.168.49.1"
                                _wifiDirectState.value = _wifiDirectState.value.copy(
                                    groupActive = true,
                                    groupOwnerIp = ownerIp
                                )
                                isExpectingClientP2pAutoConnect = false
                                if (info.isGroupOwner) {
                                    fetchGroupCredentialsWithRetry(ownerIp, emitOwnerFormed = true)
                                } else {
                                    _events.tryEmit(DirectDiscoveryEvent.WifiDirectGroupFormedAsClient(ownerIp))
                                }
                            } else {
                                _wifiDirectState.value = _wifiDirectState.value.copy(groupActive = false)
                            }
                        }
                    }
                }
            }
        }
    }

    init {
        // Register P2P receiver eagerly so isWifiP2pEnabled & peer events are always up to date from app launch
        ensureP2pInitialized()
    }

    private suspend fun awaitP2pAction(
        timeoutMs: Long = 2_000L,
        action: (WifiP2pManager.ActionListener) -> Unit
    ): Int {
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                runCatching {
                    action(object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            if (cont.isActive) cont.resume(ACTION_SUCCESS)
                        }
                        override fun onFailure(reason: Int) {
                            if (cont.isActive) cont.resume(reason)
                        }
                    })
                }.onFailure {
                    if (cont.isActive) cont.resume(WifiP2pManager.ERROR)
                }
            }
        } ?: WifiP2pManager.BUSY
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestGroupInfoSuspend(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel
    ): WifiP2pGroup? {
        return withTimeoutOrNull(1_800L) {
            suspendCancellableCoroutine { cont ->
                runCatching {
                    manager.requestGroupInfo(channel) { group ->
                        if (cont.isActive) cont.resume(group)
                    }
                }.onFailure {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestConnectionInfoSuspend(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel
    ): WifiP2pInfo? {
        return withTimeoutOrNull(1_800L) {
            suspendCancellableCoroutine { cont ->
                runCatching {
                    manager.requestConnectionInfo(channel) { info ->
                        if (cont.isActive) cont.resume(info)
                    }
                }.onFailure {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestLocalP2pMacSuspend(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel
    ): String {
        if (localDeviceAddress.isNotBlank() && localDeviceAddress != "02:00:00:00:00:00") {
            return localDeviceAddress
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return ""
        val addr = withTimeoutOrNull(1_200L) {
            suspendCancellableCoroutine { cont ->
                runCatching {
                    manager.requestDeviceInfo(channel) { device ->
                        val mac = device?.deviceAddress
                            ?.takeIf { it.isNotBlank() && it != "02:00:00:00:00:00" }
                            ?: ""
                        if (cont.isActive) cont.resume(mac)
                    }
                }.onFailure {
                    if (cont.isActive) cont.resume("")
                }
            }
        } ?: ""
        if (addr.isNotBlank()) localDeviceAddress = addr
        return addr
    }

    @SuppressLint("MissingPermission")
    private fun fetchGroupCredentialsWithRetry(ownerIp: String, emitOwnerFormed: Boolean = false) {
        val manager = p2pManager ?: return
        val channel = p2pChannel ?: return
        scope.launch {
            val cachedMac = requestLocalP2pMacSuspend(manager, channel)
            var resolved = false
            repeat(10) { attempt ->
                if (resolved) return@launch
                if (attempt > 0) delay(350)
                val group = requestGroupInfoSuspend(manager, channel)
                if (group != null && !group.networkName.isNullOrBlank()) {
                    resolved = true
                    val ssid = group.networkName ?: ""
                    val pass = group.passphrase ?: ""
                    val freq = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) group.frequency else 0
                    val groupOwnerMac = group.owner?.deviceAddress
                        ?.takeIf { it.isNotBlank() && it != "02:00:00:00:00:00" }
                        ?: cachedMac
                    _wifiDirectState.value = _wifiDirectState.value.copy(
                        groupActive = true,
                        ssid = ssid,
                        passphrase = pass,
                        groupOwnerIp = ownerIp,
                        statusMessage = "Wi-Fi Direct: $ssid"
                    )
                    _events.tryEmit(DirectDiscoveryEvent.WifiDirectCredentialsReady(ssid, pass, ownerIp, freq, groupOwnerMac))
                    if (emitOwnerFormed) {
                        _events.tryEmit(DirectDiscoveryEvent.WifiDirectBecameGroupOwner(ownerIp, ssid, pass))
                    }
                }
            }
            if (emitOwnerFormed && !resolved) {
                _events.tryEmit(DirectDiscoveryEvent.WifiDirectBecameGroupOwner(ownerIp, "", ""))
            }
        }
    }

    fun getLocalIpv4Addresses(): List<String> {
        val result = mutableListOf<String>()
        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                if (!intf.isUp || intf.isLoopback) continue
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        result += addr.hostAddress ?: continue
                    }
                }
            }
        }
        return result
    }

    fun getPrimaryDirectIp(): String {
        val ips = getLocalIpv4Addresses()
        return ips.firstOrNull { it.startsWith("192.168.49.") }
            ?: ips.firstOrNull { it.startsWith("192.168.43.") || it.startsWith("192.168.") }
            ?: ips.firstOrNull { it.startsWith("10.") || it.startsWith("172.") }
            ?: _wifiDirectState.value.groupOwnerIp.ifBlank { "192.168.49.1" }
    }

    fun getPrimaryLocalIp(): String {
        val ips = getLocalIpv4Addresses()
        return ips.firstOrNull { it.startsWith("192.168.") || it.startsWith("10.") || it.startsWith("172.") }
            ?: ips.firstOrNull()
            ?: "127.0.0.1"
    }

    /**
     * Waits briefly for p2p0 / SoftAP DHCP interface to receive its local 192.168.49.* or 192.168.43.*
     * address before attempting TCP socket connections (prevents cellular interface routing failures).
     */
    suspend fun waitForDirectSubnetIp(maxWaitMs: Long = 4_500L): String? {
        val deadline = System.currentTimeMillis() + maxWaitMs
        while (System.currentTimeMillis() < deadline) {
            val directIp = getLocalIpv4Addresses().firstOrNull {
                it.startsWith("192.168.49.") || it.startsWith("192.168.43.")
            }
            if (directIp != null) return directIp
            delay(200)
        }
        return getLocalIpv4Addresses().firstOrNull {
            it.startsWith("192.168.49.") || it.startsWith("192.168.43.")
        }
    }

    // =========================================================================
    // PRIMARY: Direct Device-to-Device (Wi-Fi Direct + LocalOnlyHotspot)
    // =========================================================================

    @SuppressLint("MissingPermission")
    fun startDirectGroupHost(
        groupId: String,
        groupName: String,
        hostDeviceName: String,
        port: Int,
        hasPin: Boolean
    ) {
        val manager = p2pManager
        if (manager == null) {
            _wifiDirectState.value = _wifiDirectState.value.copy(
                supported = false,
                statusMessage = "Wi-Fi Direct unsupported, trying Direct Hotspot..."
            )
            startLocalOnlyHotspot()
            return
        }
        ensureP2pInitialized()
        val channel = p2pChannel
        if (channel == null || !isWifiP2pEnabled) {
            _wifiDirectState.value = _wifiDirectState.value.copy(
                statusMessage = "Wi-Fi Direct unavailable — starting Direct Hotspot"
            )
            startLocalOnlyHotspot()
            return
        }

        // Stop periodic discovery loop immediately so it doesn't race during createGroup
        p2pDiscoveryLoopJob?.cancel()
        p2pDiscoveryLoopJob = null

        scope.launch {
            // Sequential cleanup for Android 11 (API 30) & older HALs so wpa_supplicant exits scan/connect cleanly
            awaitP2pAction(1_200L) { manager.stopPeerDiscovery(channel, it) }
            awaitP2pAction(1_000L) { manager.clearServiceRequests(channel, it) }

            val record = mapOf(
                "gid" to groupId,
                "gname" to groupName.take(28),
                "hname" to hostDeviceName.take(24),
                "port" to port.toString(),
                "pin" to if (hasPin) "1" else "0"
            )
            localP2pServiceInfo?.let { prev ->
                awaitP2pAction(1_000L) { manager.removeLocalService(channel, prev, it) }
            }
            val serviceInfo = WifiP2pDnsSdServiceInfo.newInstance(P2P_SERVICE_NAME, P2P_SERVICE_TYPE, record)
            localP2pServiceInfo = serviceInfo
            awaitP2pAction(1_200L) { manager.addLocalService(channel, serviceInfo, it) }

            val existingGroup = requestGroupInfoSuspend(manager, channel)
            if (existingGroup != null) {
                awaitP2pAction(2_000L) { manager.removeGroup(channel, it) }
                delay(420)
            } else {
                // Only cancel pending outgoing connect if no active group exists
                awaitP2pAction(800L) { manager.cancelConnect(channel, it) }
                delay(220)
            }

            createP2pGroupWithRetry(manager, channel, remainingRetries = 3)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun createP2pGroupWithRetry(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        remainingRetries: Int
    ) {
        val delays = longArrayOf(550L, 950L, 1400L)
        var attempt = 0
        while (attempt <= remainingRetries) {
            val result = awaitP2pAction(4_000L) { manager.createGroup(channel, it) }
            if (result == ACTION_SUCCESS) {
                _wifiDirectState.value = _wifiDirectState.value.copy(
                    groupActive = true,
                    groupOwnerIp = "192.168.49.1",
                    statusMessage = "Wi-Fi Direct Group Active"
                )
                fetchGroupCredentialsWithRetry("192.168.49.1")
                return
            }

            val shouldRetry = (result == WifiP2pManager.BUSY || result == WifiP2pManager.ERROR) && attempt < remainingRetries
            if (!shouldRetry) {
                val reasonText = when (result) {
                    WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED"
                    WifiP2pManager.BUSY -> "BUSY"
                    else -> "ERROR ($result)"
                }
                _wifiDirectState.value = _wifiDirectState.value.copy(
                    statusMessage = "Wi-Fi Direct: $reasonText — starting Direct Hotspot"
                )
                startLocalOnlyHotspot()
                return
            }

            // Before retrying on Android 11 HAL, ensure peer discovery & stale group are cleared
            awaitP2pAction(1_000L) { manager.stopPeerDiscovery(channel, it) }
            val stale = requestGroupInfoSuspend(manager, channel)
            if (stale != null) {
                awaitP2pAction(1_500L) { manager.removeGroup(channel, it) }
            }
            val backoff = delays.getOrElse(attempt) { 1200L }
            delay(backoff)
            attempt++
        }
    }

    @SuppressLint("MissingPermission")
    fun startLocalOnlyHotspot() {
        val wm = wifiManager ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            _wifiDirectState.value = _wifiDirectState.value.copy(
                statusMessage = "Local-Only Hotspot requires Android 8.0+"
            )
            return
        }
        stopLocalOnlyHotspot()
        runCatching {
            wm.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                    hotspotReservation = reservation
                    val (ssid, pass) = extractHotspotCredentials(reservation)
                    val ip = getPrimaryDirectIp()
                    _wifiDirectState.value = _wifiDirectState.value.copy(
                        hotspotActive = true,
                        hotspotSsid = ssid,
                        hotspotPassphrase = pass,
                        groupOwnerIp = ip,
                        statusMessage = "Direct Hotspot Active: $ssid"
                    )
                    _events.tryEmit(DirectDiscoveryEvent.HotspotCredentialsReady(ssid, pass, ip))
                }

                override fun onFailed(reason: Int) {
                    _wifiDirectState.value = _wifiDirectState.value.copy(
                        hotspotActive = false,
                        statusMessage = "Hotspot failed ($reason). Ensure Wi-Fi & Location are ON."
                    )
                }

                override fun onStopped() {
                    hotspotReservation = null
                    _wifiDirectState.value = _wifiDirectState.value.copy(hotspotActive = false)
                }
            }, Handler(Looper.getMainLooper()))
        }.onFailure { err ->
            _wifiDirectState.value = _wifiDirectState.value.copy(
                statusMessage = "Hotspot error: ${err.localizedMessage}"
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun extractHotspotCredentials(reservation: WifiManager.LocalOnlyHotspotReservation): Pair<String, String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val softAp = reservation.softApConfiguration
            val ssid = softAp?.ssid ?: "NexoDrop-Hotspot"
            val pass = softAp?.passphrase ?: ""
            ssid to pass
        } else {
            val cfg = reservation.wifiConfiguration
            val ssid = cfg?.SSID ?: "NexoDrop-Hotspot"
            val pass = cfg?.preSharedKey ?: ""
            ssid to pass
        }
    }

    fun stopLocalOnlyHotspot() {
        runCatching { hotspotReservation?.close() }
        hotspotReservation = null
        _wifiDirectState.value = _wifiDirectState.value.copy(
            hotspotActive = false,
            hotspotSsid = "",
            hotspotPassphrase = ""
        )
    }

    @SuppressLint("MissingPermission")
    fun startDirectPeerDiscovery() {
        val manager = p2pManager ?: return
        ensureP2pInitialized()
        val channel = p2pChannel ?: return

        attachDnsSdListeners(manager, channel)

        p2pDiscoveryLoopJob?.cancel()
        p2pDiscoveryLoopJob = scope.launch {
            while (isActive) {
                runCatching {
                    manager.clearServiceRequests(channel, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            manager.addServiceRequest(channel, WifiP2pDnsSdServiceRequest.newInstance(), null)
                            manager.discoverServices(channel, null)
                        }
                        override fun onFailure(reason: Int) {
                            manager.discoverPeers(channel, null)
                        }
                    })
                    manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            _wifiDirectState.value = _wifiDirectState.value.copy(
                                statusMessage = "Direct P2P scanning active"
                            )
                        }
                        override fun onFailure(reason: Int) {
                            _wifiDirectState.value = _wifiDirectState.value.copy(
                                statusMessage = "P2P scan code $reason (check Wi-Fi & Location ON)"
                            )
                        }
                    })
                }
                delay(12_000L)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun attachDnsSdListeners(manager: WifiP2pManager, channel: WifiP2pManager.Channel) {
        runCatching {
            manager.setDnsSdResponseListeners(
                channel,
                { instanceName, _, srcDevice ->
                    if (instanceName.contains(P2P_SERVICE_NAME, ignoreCase = true)) {
                        val existing = discoveredP2pServices[srcDevice.deviceAddress]
                        discoveredP2pServices[srcDevice.deviceAddress] = (existing ?: WifiDirectPeer(
                            deviceName = srcDevice.deviceName,
                            deviceAddress = srcDevice.deviceAddress,
                            status = srcDevice.status,
                            isNexoService = true
                        )).copy(isNexoService = true)
                    }
                },
                { _, txtRecordMap, srcDevice ->
                    val gid = txtRecordMap["gid"] ?: ""
                    if (gid.isNotBlank()) {
                        discoveredGroupIdsByAddress[srcDevice.deviceAddress] = gid
                    }
                    val gname = txtRecordMap["gname"] ?: srcDevice.deviceName
                    val port = txtRecordMap["port"]?.toIntOrNull() ?: 45892
                    val hasPin = txtRecordMap["pin"] == "1"
                    discoveredP2pServices[srcDevice.deviceAddress] = WifiDirectPeer(
                        deviceName = srcDevice.deviceName,
                        deviceAddress = srcDevice.deviceAddress,
                        status = srcDevice.status,
                        isNexoService = true,
                        serviceGroupName = gname,
                        servicePort = port,
                        hasPin = hasPin
                    )
                    val merged = _wifiDirectState.value.discoveredPeers.map { p ->
                        if (p.deviceAddress == srcDevice.deviceAddress) discoveredP2pServices[srcDevice.deviceAddress]!! else p
                    }.ifEmpty { discoveredP2pServices.values.toList() }
                    _wifiDirectState.value = _wifiDirectState.value.copy(discoveredPeers = merged)
                }
            )
        }
    }

    fun stopDirectPeerDiscovery() {
        p2pDiscoveryLoopJob?.cancel()
        p2pDiscoveryLoopJob = null
        val manager = p2pManager ?: return
        val channel = p2pChannel ?: return
        runCatching { manager.stopPeerDiscovery(channel, null) }
    }

    /**
     * Connects to a discovered Wi-Fi Direct peer.
     * - When `preferPeerAsOwner` is true (peer is already hosting NexoDrop service), yields with intent = 0.
     * - When `preferPeerAsOwner` is false (both devices opened app idle without creating a group),
     *   uses high Group Owner intent (14) so this initiator cleanly assumes Host role and peer auto-joins!
     */
    @SuppressLint("MissingPermission")
    fun connectToWifiDirectPeer(deviceAddress: String, preferPeerAsOwner: Boolean = true) {
        stopDirectPeerDiscovery()
        val manager = p2pManager ?: return
        ensureP2pInitialized()
        val channel = p2pChannel ?: return

        peerConnectJob?.cancel()
        peerConnectJob = scope.launch {
            // Check if the other side already initiated L2 group formation moments ago
            val existingInfo = requestConnectionInfoSuspend(manager, channel)
            if (existingInfo != null && existingInfo.groupFormed) {
                val ownerIp = existingInfo.groupOwnerAddress?.hostAddress ?: "192.168.49.1"
                isExpectingClientP2pAutoConnect = false
                if (existingInfo.isGroupOwner) {
                    fetchGroupCredentialsWithRetry(ownerIp, emitOwnerFormed = true)
                } else {
                    _events.tryEmit(DirectDiscoveryEvent.WifiDirectGroupFormedAsClient(ownerIp))
                }
                return@launch
            }

            isExpectingClientP2pAutoConnect = true
            awaitP2pAction(1_200L) { manager.stopPeerDiscovery(channel, it) }
            awaitP2pAction(1_000L) { manager.clearServiceRequests(channel, it) }

            val staleGroup = requestGroupInfoSuspend(manager, channel)
            if (staleGroup != null) {
                awaitP2pAction(1_800L) { manager.removeGroup(channel, it) }
                delay(350)
            }

            val config = WifiP2pConfig().apply {
                this.deviceAddress = deviceAddress
                wps.setup = WpsInfo.PBC
                groupOwnerIntent = if (preferPeerAsOwner) 0 else 14
            }

            var connectedOk = false
            var lastReason = WifiP2pManager.ERROR
            repeat(3) { attempt ->
                if (connectedOk) return@repeat
                if (attempt > 0) {
                    // Refresh peer cache cleanly if HAL forgot the peer entry
                    awaitP2pAction(1_000L) { manager.cancelConnect(channel, it) }
                    awaitP2pAction(1_200L) { manager.discoverPeers(channel, it) }
                    delay(550)
                    awaitP2pAction(1_000L) { manager.stopPeerDiscovery(channel, it) }
                }
                val res = awaitP2pAction(4_000L) { manager.connect(channel, config, it) }
                if (res == ACTION_SUCCESS) {
                    connectedOk = true
                    _wifiDirectState.value = _wifiDirectState.value.copy(
                        statusMessage = "Pairing via Wi-Fi Direct..."
                    )
                } else {
                    lastReason = res
                }
            }

            if (!connectedOk) {
                isExpectingClientP2pAutoConnect = false
                _wifiDirectState.value = _wifiDirectState.value.copy(
                    statusMessage = "Direct connect failed ($lastReason)"
                )
                _events.tryEmit(DirectDiscoveryEvent.WifiDirectPairFailed("Wi-Fi Direct peer connect failed ($lastReason)"))
                return@launch
            }

            // Guard against peer never answering the invitation (16s timeout)
            delay(16_000L)
            if (isExpectingClientP2pAutoConnect) {
                val lateInfo = requestConnectionInfoSuspend(manager, channel)
                if (lateInfo != null && lateInfo.groupFormed) {
                    val ownerIp = lateInfo.groupOwnerAddress?.hostAddress ?: "192.168.49.1"
                    isExpectingClientP2pAutoConnect = false
                    if (lateInfo.isGroupOwner) {
                        fetchGroupCredentialsWithRetry(ownerIp, emitOwnerFormed = true)
                    } else {
                        _events.tryEmit(DirectDiscoveryEvent.WifiDirectGroupFormedAsClient(ownerIp))
                    }
                } else {
                    isExpectingClientP2pAutoConnect = false
                    awaitP2pAction(1_000L) { manager.cancelConnect(channel, it) }
                    _events.tryEmit(DirectDiscoveryEvent.WifiDirectPairFailed("Peer did not respond to Wi-Fi Direct invite. Try QR or Tap Nearby again."))
                }
            }
        }
    }

    /**
     * Joins a Host's Wi-Fi Direct (DIRECT-...) or LocalOnlyHotspot SSID+Passphrase from QR.
     * - For DIRECT- Wi-Fi Direct groups: NEVER triggers WifiNetworkSpecifier (which forces Android 13+'s
     *   system Wi-Fi / Direct Connection chooser sheet!). Instead uses WifiP2pManager.Config.Builder
     *   first, and if Android 11 Host's direct group rejects/times out Builder pairing, automatically
     *   falls back in-app to silent DNS-SD / peer MAC discovery + PBC connect.
     * - For non-DIRECT SoftAP Hotspots: uses WifiNetworkSpecifier directly.
     */
    @SuppressLint("MissingPermission")
    fun connectDirectByQrCredentials(
        ssid: String,
        passphrase: String,
        hostMac: String = "",
        groupId: String = "",
        frequency: Int = 0
    ) {
        stopDirectPeerDiscovery()
        val manager = p2pManager
        ensureP2pInitialized()
        val channel = p2pChannel

        if (ssid.startsWith("DIRECT-", ignoreCase = true)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                passphrase.length >= 8 &&
                manager != null &&
                channel != null &&
                isWifiP2pEnabled
            ) {
                qrConnectJob?.cancel()
                qrConnectJob = scope.launch {
                    isExpectingClientP2pAutoConnect = true
                    awaitP2pAction(1_200L) { manager.stopPeerDiscovery(channel, it) }
                    awaitP2pAction(1_000L) { manager.clearServiceRequests(channel, it) }

                    // Check if already connected to the target DIRECT- group
                    val currentGroup = requestGroupInfoSuspend(manager, channel)
                    if (currentGroup != null) {
                        if (currentGroup.networkName.equals(ssid, ignoreCase = true)) {
                            val info = requestConnectionInfoSuspend(manager, channel)
                            if (info != null && info.groupFormed && !info.isGroupOwner) {
                                isExpectingClientP2pAutoConnect = false
                                val ownerIp = info.groupOwnerAddress?.hostAddress ?: "192.168.49.1"
                                _events.tryEmit(DirectDiscoveryEvent.WifiDirectGroupFormedAsClient(ownerIp))
                                return@launch
                            }
                        }
                        awaitP2pAction(2_000L) { manager.removeGroup(channel, it) }
                        delay(350)
                    } else {
                        awaitP2pAction(800L) { manager.cancelConnect(channel, it) }
                    }

                    // Stage 1: Direct passphrase join via WifiP2pConfig.Builder (stays strictly inside app)
                    val builderSuccess = tryConnectDirectBuilder(manager, channel, ssid, passphrase)
                    if (builderSuccess) {
                        // Wait up to 5.5s for group formation broadcast/poll before switching to silent P2P peer fallback
                        val deadline = System.currentTimeMillis() + 5_500L
                        while (System.currentTimeMillis() < deadline && isExpectingClientP2pAutoConnect) {
                            val info = requestConnectionInfoSuspend(manager, channel)
                            if (info != null && info.groupFormed) {
                                isExpectingClientP2pAutoConnect = false
                                val ownerIp = info.groupOwnerAddress?.hostAddress ?: "192.168.49.1"
                                if (!info.isGroupOwner) {
                                    _events.tryEmit(DirectDiscoveryEvent.WifiDirectGroupFormedAsClient(ownerIp))
                                } else {
                                    fetchGroupCredentialsWithRetry(ownerIp, emitOwnerFormed = true)
                                }
                                return@launch
                            }
                            delay(400)
                        }
                    }

                    if (!isExpectingClientP2pAutoConnect) return@launch

                    // Stage 2: Android 11 -> 13+ silent in-app fallback via MAC or NexoDrop DNS-SD match (NO system settings dialog!)
                    awaitP2pAction(1_000L) { manager.cancelConnect(channel, it) }
                    val matchedAddress = findHostAddressSilently(manager, channel, hostMac, groupId)
                    if (matchedAddress != null && isExpectingClientP2pAutoConnect) {
                        val fallbackOk = tryConnectByPeerAddress(manager, channel, matchedAddress)
                        if (fallbackOk) {
                            val deadline2 = System.currentTimeMillis() + 8_500L
                            while (System.currentTimeMillis() < deadline2 && isExpectingClientP2pAutoConnect) {
                                val info = requestConnectionInfoSuspend(manager, channel)
                                if (info != null && info.groupFormed) {
                                    isExpectingClientP2pAutoConnect = false
                                    val ownerIp = info.groupOwnerAddress?.hostAddress ?: "192.168.49.1"
                                    if (!info.isGroupOwner) {
                                        _events.tryEmit(DirectDiscoveryEvent.WifiDirectGroupFormedAsClient(ownerIp))
                                    } else {
                                        fetchGroupCredentialsWithRetry(ownerIp, emitOwnerFormed = true)
                                    }
                                    return@launch
                                }
                                delay(450)
                            }
                        }
                    }

                    if (isExpectingClientP2pAutoConnect) {
                        isExpectingClientP2pAutoConnect = false
                        awaitP2pAction(1_000L) { manager.cancelConnect(channel, it) }
                        _events.tryEmit(
                            DirectDiscoveryEvent.WifiDirectPairFailed(
                                "Could not pair with '$ssid' over Wi-Fi Direct. Tap Host in Nearby Direct Devices or enable Direct Hotspot."
                            )
                        )
                    }
                }
            } else {
                _events.tryEmit(
                    DirectDiscoveryEvent.WifiDirectPairFailed(
                        "Wi-Fi Direct unavailable for '$ssid'. Switch Host to Direct Hotspot or connect via Nearby Peers."
                    )
                )
            }
            return
        }

        // LocalOnlyHotspot (non-DIRECT- SoftAP): WifiNetworkSpecifier works cleanly
        connectToDirectWifiNetwork(ssid, passphrase)
    }

    @SuppressLint("MissingPermission")
    private suspend fun tryConnectDirectBuilder(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        ssid: String,
        passphrase: String
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val p2pConfig = WifiP2pConfig.Builder()
            .setNetworkName(ssid)
            .setPassphrase(passphrase)
            .enablePersistentMode(false)
            .build()

        repeat(2) { attempt ->
            val res = awaitP2pAction(3_500L) { manager.connect(channel, p2pConfig, it) }
            if (res == ACTION_SUCCESS) {
                _wifiDirectState.value = _wifiDirectState.value.copy(
                    statusMessage = "Joining Wi-Fi Direct ($ssid)..."
                )
                return true
            }
            if (res != WifiP2pManager.BUSY || attempt == 1) return false
            delay(450)
        }
        return false
    }

    @SuppressLint("MissingPermission")
    private suspend fun findHostAddressSilently(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        hostMac: String,
        groupId: String
    ): String? {
        val validMac = hostMac.takeIf { it.isNotBlank() && it != "02:00:00:00:00:00" }
        attachDnsSdListeners(manager, channel)
        awaitP2pAction(1_000L) { manager.clearServiceRequests(channel, it) }
        awaitP2pAction(1_000L) {
            manager.addServiceRequest(channel, WifiP2pDnsSdServiceRequest.newInstance(), it)
        }
        manager.discoverServices(channel, null)
        manager.discoverPeers(channel, null)

        val deadline = System.currentTimeMillis() + 4_500L
        while (System.currentTimeMillis() < deadline && isExpectingClientP2pAutoConnect) {
            if (groupId.isNotBlank()) {
                val byGid = discoveredGroupIdsByAddress.entries.firstOrNull { it.value == groupId }?.key
                if (byGid != null) {
                    awaitP2pAction(800L) { manager.stopPeerDiscovery(channel, it) }
                    return byGid
                }
            }
            if (validMac != null) {
                val peerHit = _wifiDirectState.value.discoveredPeers.firstOrNull {
                    it.deviceAddress.equals(validMac, ignoreCase = true)
                }?.deviceAddress
                if (peerHit != null) {
                    awaitP2pAction(800L) { manager.stopPeerDiscovery(channel, it) }
                    return peerHit
                }
            }
            val nexoHit = _wifiDirectState.value.discoveredPeers.firstOrNull { it.isNexoService }?.deviceAddress
            if (nexoHit != null && groupId.isBlank()) {
                awaitP2pAction(800L) { manager.stopPeerDiscovery(channel, it) }
                return nexoHit
            }
            delay(350)
        }
        awaitP2pAction(800L) { manager.stopPeerDiscovery(channel, it) }
        return validMac ?: discoveredP2pServices.keys.firstOrNull()
    }

    @SuppressLint("MissingPermission")
    private suspend fun tryConnectByPeerAddress(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        peerAddress: String
    ): Boolean {
        val macCfg = WifiP2pConfig().apply {
            deviceAddress = peerAddress
            wps.setup = WpsInfo.PBC
            groupOwnerIntent = 0
        }
        val res = awaitP2pAction(3_500L) { manager.connect(channel, macCfg, it) }
        if (res == ACTION_SUCCESS) {
            _wifiDirectState.value = _wifiDirectState.value.copy(
                statusMessage = "Pairing via P2P MAC..."
            )
            return true
        }
        return false
    }

    /**
     * Connects to Host's Direct Hotspot SSID+Passphrase in-app using WifiNetworkSpecifier (Android 10+).
     */
    fun connectToDirectWifiNetwork(ssid: String, passphrase: String) {
        val cm = connectivityManager ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            _events.tryEmit(DirectDiscoveryEvent.SpecifierNetworkFailed("On Android 9 or below, please connect to Wi-Fi '$ssid' in system Wi-Fi settings."))
            return
        }
        unregisterNetworkCallback()
        val specBuilder = WifiNetworkSpecifier.Builder().setSsid(ssid)
        if (passphrase.isNotBlank()) {
            specBuilder.setWpa2Passphrase(passphrase)
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specBuilder.build())
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                boundWifiNetwork = network
                _events.tryEmit(DirectDiscoveryEvent.SpecifierNetworkReady(network))
            }

            override fun onUnavailable() {
                boundWifiNetwork = null
                _events.tryEmit(DirectDiscoveryEvent.SpecifierNetworkFailed("Could not join Wi-Fi '$ssid'"))
            }

            override fun onLost(network: Network) {
                if (boundWifiNetwork == network) boundWifiNetwork = null
            }
        }
        activeNetworkCallback = callback
        runCatching {
            cm.requestNetwork(request, callback)
        }.onFailure { err ->
            _events.tryEmit(DirectDiscoveryEvent.SpecifierNetworkFailed(err.localizedMessage ?: "Network request failed"))
        }
    }

    fun unregisterNetworkCallback() {
        activeNetworkCallback?.let { cb ->
            runCatching { connectivityManager?.unregisterNetworkCallback(cb) }
        }
        activeNetworkCallback = null
        boundWifiNetwork = null
    }

    // =========================================================================
    // SECONDARY: Local Wi-Fi Network (NSD + UDP Broadcast)
    // =========================================================================

    fun startLocalNetworkAdvertising(
        groupId: String,
        groupName: String,
        hostDeviceName: String,
        port: Int,
        hasPin: Boolean,
        memberCountProvider: () -> Int
    ) {
        stopLocalNetworkAdvertising()
        acquireMulticastLock()

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "NexoDrop-${groupName.take(20)}-$groupId"
            serviceType = NSD_TYPE
            this.port = port
            setAttribute("gid", groupId)
            setAttribute("gname", groupName)
            setAttribute("hname", hostDeviceName)
            setAttribute("pin", if (hasPin) "1" else "0")
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        registrationListener = listener
        runCatching { nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener) }

        udpBeaconJob = scope.launch(Dispatchers.IO) {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                while (isActive) {
                    val json = JSONObject().apply {
                        put("magic", "NEXODROP_BEACON")
                        put("groupId", groupId)
                        put("groupName", groupName)
                        put("hostDeviceName", hostDeviceName)
                        put("hostIp", getPrimaryLocalIp())
                        put("port", port)
                        put("hasPin", hasPin)
                        put("memberCount", memberCountProvider())
                    }.toString()
                    val data = json.toByteArray(Charsets.UTF_8)
                    getBroadcastAddresses().forEach { bcast ->
                        runCatching {
                            socket.send(DatagramPacket(data, data.size, bcast, UDP_DISCOVERY_PORT))
                        }
                    }
                    delay(1500)
                }
            }
        }
    }

    fun stopLocalNetworkAdvertising() {
        udpBeaconJob?.cancel()
        udpBeaconJob = null
        registrationListener?.let { listener ->
            runCatching { nsdManager?.unregisterService(listener) }
        }
        registrationListener = null
    }

    fun startLocalNetworkScanning(localGroupIdToIgnore: String? = null) {
        stopLocalNetworkScanning()
        acquireMulticastLock()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.serviceType != NSD_TYPE && !service.serviceType.contains("nexodrop")) return
                runCatching {
                    @Suppress("DEPRECATION")
                    nsdManager?.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
                        override fun onServiceResolved(resolved: NsdServiceInfo) {
                            val attrs = resolved.attributes
                            val gid = attrs["gid"]?.let { String(it) } ?: resolved.serviceName
                            if (gid == localGroupIdToIgnore) return
                            val gname = attrs["gname"]?.let { String(it) } ?: resolved.serviceName
                            val hname = attrs["hname"]?.let { String(it) } ?: "NexoDrop Host"
                            val hasPin = attrs["pin"]?.let { String(it) == "1" } ?: false
                            @Suppress("DEPRECATION")
                            val hostIp = resolved.host?.hostAddress ?: return
                            upsertDiscoveredGroup(
                                DiscoveredGroup(
                                    groupId = gid,
                                    groupName = gname,
                                    hostDeviceName = hname,
                                    hostIp = hostIp,
                                    hostPort = resolved.port,
                                    hasPin = hasPin,
                                    memberCount = 1,
                                    source = "NSD"
                                )
                            )
                        }
                    })
                }
            }
            override fun onServiceLost(service: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        discoveryListener = listener
        runCatching { nsdManager?.discoverServices(NSD_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }

        udpListenerJob = scope.launch(Dispatchers.IO) {
            runCatching {
                val socket = DatagramSocket(UDP_DISCOVERY_PORT).apply {
                    reuseAddress = true
                    soTimeout = 2000
                }
                socket.use { sock ->
                    val buf = ByteArray(2048)
                    while (isActive) {
                        val packet = DatagramPacket(buf, buf.size)
                        val received = runCatching {
                            sock.receive(packet)
                            true
                        }.getOrDefault(false)
                        if (received) {
                            runCatching {
                                val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                                val json = JSONObject(text)
                                if (json.optString("magic") == "NEXODROP_BEACON") {
                                    val gid = json.getString("groupId")
                                    if (gid != localGroupIdToIgnore) {
                                        val senderIp = packet.address.hostAddress ?: json.optString("hostIp")
                                        upsertDiscoveredGroup(
                                            DiscoveredGroup(
                                                groupId = gid,
                                                groupName = json.getString("groupName"),
                                                hostDeviceName = json.getString("hostDeviceName"),
                                                hostIp = senderIp,
                                                hostPort = json.getInt("port"),
                                                hasPin = json.optBoolean("hasPin", false),
                                                memberCount = json.optInt("memberCount", 1),
                                                source = "LAN"
                                            )
                                        )
                                    }
                                }
                            }
                        }
                        pruneStaleGroups()
                    }
                }
            }
        }
    }

    fun stopLocalNetworkScanning() {
        udpListenerJob?.cancel()
        udpListenerJob = null
        discoveryListener?.let { listener ->
            runCatching { nsdManager?.stopServiceDiscovery(listener) }
        }
        discoveryListener = null
    }

    private fun upsertDiscoveredGroup(group: DiscoveredGroup) {
        val current = _discoveredGroups.value.toMutableList()
        val idx = current.indexOfFirst { it.groupId == group.groupId }
        if (idx >= 0) current[idx] = group else current.add(group)
        _discoveredGroups.value = current
    }

    private fun pruneStaleGroups() {
        val now = System.currentTimeMillis()
        val filtered = _discoveredGroups.value.filter { now - it.lastSeenAt < 10_000L }
        if (filtered.size != _discoveredGroups.value.size) {
            _discoveredGroups.value = filtered
        }
    }

    private fun getBroadcastAddresses(): List<InetAddress> {
        val result = mutableListOf<InetAddress>()
        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                if (!intf.isUp || intf.isLoopback) continue
                intf.interfaceAddresses.forEach { ia ->
                    ia.broadcast?.let { result += it }
                }
            }
        }
        runCatching { result += InetAddress.getByName("255.255.255.255") }
        return result.distinct()
    }

    private fun ensureP2pInitialized() {
        val manager = p2pManager ?: return
        if (p2pChannel == null) {
            p2pChannel = runCatching { manager.initialize(context, Looper.getMainLooper(), null) }.getOrNull()
        }
        if (!p2pReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(p2pReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    context.registerReceiver(p2pReceiver, filter)
                }
                p2pReceiverRegistered = true
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun removeWifiDirectGroup() {
        qrConnectJob?.cancel()
        qrConnectJob = null
        peerConnectJob?.cancel()
        peerConnectJob = null
        isExpectingClientP2pAutoConnect = false
        val manager = p2pManager ?: return
        val channel = p2pChannel ?: return
        localP2pServiceInfo?.let { svc ->
            runCatching { manager.removeLocalService(channel, svc, null) }
        }
        localP2pServiceInfo = null
        runCatching { manager.removeGroup(channel, null) }
        runCatching { manager.cancelConnect(channel, null) }
        _wifiDirectState.value = WifiDirectState(supported = true)
    }

    private fun acquireMulticastLock() {
        runCatching {
            if (multicastLock == null) {
                multicastLock = wifiManager?.createMulticastLock("NexoDropDiscoveryLock")?.apply {
                    setReferenceCounted(false)
                }
            }
            if (multicastLock?.isHeld == false) {
                multicastLock?.acquire()
            }
        }
    }

    fun release() {
        qrConnectJob?.cancel()
        peerConnectJob?.cancel()
        stopLocalNetworkAdvertising()
        stopLocalNetworkScanning()
        stopDirectPeerDiscovery()
        stopLocalOnlyHotspot()
        unregisterNetworkCallback()
        if (p2pReceiverRegistered) {
            runCatching { context.unregisterReceiver(p2pReceiver) }
            p2pReceiverRegistered = false
        }
        runCatching {
            if (multicastLock?.isHeld == true) multicastLock?.release()
        }
    }
}
