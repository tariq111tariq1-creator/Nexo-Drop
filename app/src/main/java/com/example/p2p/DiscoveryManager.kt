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
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

sealed class DirectDiscoveryEvent {
    data class WifiDirectGroupFormedAsClient(val ownerIp: String) : DirectDiscoveryEvent()
    data class WifiDirectCredentialsReady(val ssid: String, val passphrase: String, val ownerIp: String) : DirectDiscoveryEvent()
    data class HotspotCredentialsReady(val ssid: String, val passphrase: String, val ip: String) : DirectDiscoveryEvent()
    data class SpecifierNetworkReady(val network: Network) : DirectDiscoveryEvent()
    data class SpecifierNetworkFailed(val reason: String) : DirectDiscoveryEvent()
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

    private var udpListenerJob: Job? = null
    private var udpBeaconJob: Job? = null
    private var p2pDiscoveryLoopJob: Job? = null
    private var p2pReceiverRegistered = false

    private val _discoveredGroups = MutableStateFlow<List<DiscoveredGroup>>(emptyList())
    val discoveredGroups: StateFlow<List<DiscoveredGroup>> = _discoveredGroups.asStateFlow()

    private val _wifiDirectState = MutableStateFlow(WifiDirectState(supported = p2pManager != null))
    val wifiDirectState: StateFlow<WifiDirectState> = _wifiDirectState.asStateFlow()

    private val _events = MutableSharedFlow<DirectDiscoveryEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<DirectDiscoveryEvent> = _events.asSharedFlow()

    private var isExpectingClientP2pAutoConnect = false
    private var isWifiP2pEnabled = true

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
                            }?.sortedByDescending { it.isNexoService } ?: emptyList()
                            _wifiDirectState.value = _wifiDirectState.value.copy(discoveredPeers = rawPeers)
                        }
                    }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val manager = p2pManager ?: return
                    val channel = p2pChannel ?: return
                    manager.requestConnectionInfo(channel) { info: WifiP2pInfo? ->
                        if (info != null && info.groupFormed) {
                            val ownerIp = info.groupOwnerAddress?.hostAddress ?: "192.168.49.1"
                            _wifiDirectState.value = _wifiDirectState.value.copy(
                                groupActive = true,
                                groupOwnerIp = ownerIp
                            )
                            if (info.isGroupOwner) {
                                fetchGroupCredentialsWithRetry(ownerIp)
                            } else if (isExpectingClientP2pAutoConnect) {
                                isExpectingClientP2pAutoConnect = false
                                _events.tryEmit(DirectDiscoveryEvent.WifiDirectGroupFormedAsClient(ownerIp))
                            }
                        }
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun fetchGroupCredentialsWithRetry(ownerIp: String) {
        val manager = p2pManager ?: return
        val channel = p2pChannel ?: return
        scope.launch {
            var resolved = false
            repeat(6) {
                if (resolved) return@launch
                runCatching {
                    manager.requestGroupInfo(channel) { group: WifiP2pGroup? ->
                        if (!resolved && group != null && !group.networkName.isNullOrBlank()) {
                            resolved = true
                            val ssid = group.networkName ?: ""
                            val pass = group.passphrase ?: ""
                            _wifiDirectState.value = _wifiDirectState.value.copy(
                                groupActive = true,
                                ssid = ssid,
                                passphrase = pass,
                                groupOwnerIp = ownerIp,
                                statusMessage = "Wi-Fi Direct: $ssid"
                            )
                            _events.tryEmit(DirectDiscoveryEvent.WifiDirectCredentialsReady(ssid, pass, ownerIp))
                        }
                    }
                }
                delay(650)
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

        // CRITICAL for Android 11 (API 30) & older HALs:
        // Cancel ongoing peer/service discovery first so createGroup doesn't fail with BUSY!
        stopDirectPeerDiscovery()
        runCatching { manager.cancelConnect(channel, null) }
        runCatching { manager.clearServiceRequests(channel, null) }

        // Register NexoDrop DNS-SD Service over Wi-Fi Direct
        val record = mapOf(
            "gid" to groupId,
            "gname" to groupName.take(28),
            "hname" to hostDeviceName.take(24),
            "port" to port.toString(),
            "pin" to if (hasPin) "1" else "0"
        )
        localP2pServiceInfo?.let { prev ->
            runCatching { manager.removeLocalService(channel, prev, null) }
        }
        val serviceInfo = WifiP2pDnsSdServiceInfo.newInstance(P2P_SERVICE_NAME, P2P_SERVICE_TYPE, record)
        localP2pServiceInfo = serviceInfo
        runCatching { manager.addLocalService(channel, serviceInfo, null) }

        // Wait briefly for Android 11 wpa_supplicant to exit discovery state, then remove stale group only if needed
        scope.launch {
            delay(280)
            runCatching {
                manager.requestGroupInfo(channel) { existingGroup ->
                    if (existingGroup != null) {
                        manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                            override fun onSuccess() {
                                scope.launch {
                                    delay(380)
                                    createP2pGroupInternal(manager, channel, retryOnBusy = true)
                                }
                            }
                            override fun onFailure(reason: Int) {
                                scope.launch {
                                    delay(300)
                                    createP2pGroupInternal(manager, channel, retryOnBusy = true)
                                }
                            }
                        })
                    } else {
                        createP2pGroupInternal(manager, channel, retryOnBusy = true)
                    }
                }
            }.onFailure {
                createP2pGroupInternal(manager, channel, retryOnBusy = true)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun createP2pGroupInternal(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        retryOnBusy: Boolean
    ) {
        runCatching {
            manager.createGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    _wifiDirectState.value = _wifiDirectState.value.copy(
                        groupActive = true,
                        groupOwnerIp = "192.168.49.1",
                        statusMessage = "Wi-Fi Direct Group Active"
                    )
                    fetchGroupCredentialsWithRetry("192.168.49.1")
                }

                override fun onFailure(reason: Int) {
                    if (retryOnBusy && reason == WifiP2pManager.BUSY) {
                        scope.launch {
                            runCatching { manager.stopPeerDiscovery(channel, null) }
                            delay(500)
                            createP2pGroupInternal(manager, channel, retryOnBusy = false)
                        }
                        return
                    }
                    val reasonText = when (reason) {
                        WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED"
                        WifiP2pManager.BUSY -> "BUSY"
                        else -> "ERROR ($reason)"
                    }
                    _wifiDirectState.value = _wifiDirectState.value.copy(
                        statusMessage = "Wi-Fi Direct: $reasonText — starting Direct Hotspot"
                    )
                    // Automatic fallback to Direct Hotspot!
                    startLocalOnlyHotspot()
                }
            })
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

        // Setup DNS-SD listeners for NexoDrop P2P hosts
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
                    // Refresh peer list immediately
                    val merged = _wifiDirectState.value.discoveredPeers.map { p ->
                        if (p.deviceAddress == srcDevice.deviceAddress) discoveredP2pServices[srcDevice.deviceAddress]!! else p
                    }.ifEmpty { discoveredP2pServices.values.toList() }
                    _wifiDirectState.value = _wifiDirectState.value.copy(discoveredPeers = merged)
                }
            )
        }

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

    fun stopDirectPeerDiscovery() {
        p2pDiscoveryLoopJob?.cancel()
        p2pDiscoveryLoopJob = null
        val manager = p2pManager ?: return
        val channel = p2pChannel ?: return
        runCatching { manager.stopPeerDiscovery(channel, null) }
    }

    @SuppressLint("MissingPermission")
    fun connectToWifiDirectPeer(deviceAddress: String) {
        val manager = p2pManager ?: return
        ensureP2pInitialized()
        val channel = p2pChannel ?: return
        isExpectingClientP2pAutoConnect = true
        val config = WifiP2pConfig().apply {
            this.deviceAddress = deviceAddress
            wps.setup = WpsInfo.PBC
            groupOwnerIntent = 0 // Prefer the existing Host as Group Owner
        }
        runCatching {
            manager.connect(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    _wifiDirectState.value = _wifiDirectState.value.copy(
                        statusMessage = "Pairing via Wi-Fi Direct..."
                    )
                }

                override fun onFailure(reason: Int) {
                    isExpectingClientP2pAutoConnect = false
                    _wifiDirectState.value = _wifiDirectState.value.copy(
                        statusMessage = "Direct connect failed ($reason)"
                    )
                }
            })
        }
    }

    /**
     * Joins a Host's Wi-Fi Direct (DIRECT-...) or LocalOnlyHotspot SSID+Passphrase from QR.
     * On Android 10+ (API 29+), uses native WifiP2pConfig.Builder(ssid, passphrase) first for DIRECT- groups
     * to avoid unnecessary system Wi-Fi/Settings prompts, with automatic fallback to WifiNetworkSpecifier.
     */
    @SuppressLint("MissingPermission")
    fun connectDirectByQrCredentials(ssid: String, passphrase: String) {
        stopDirectPeerDiscovery()
        val manager = p2pManager
        ensureP2pInitialized()
        val channel = p2pChannel

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ssid.startsWith("DIRECT-", ignoreCase = true) &&
            passphrase.length >= 8 &&
            manager != null &&
            channel != null &&
            isWifiP2pEnabled
        ) {
            isExpectingClientP2pAutoConnect = true
            runCatching { manager.cancelConnect(channel, null) }
            runCatching { manager.clearServiceRequests(channel, null) }

            val p2pConfig = WifiP2pConfig.Builder()
                .setNetworkName(ssid)
                .setPassphrase(passphrase)
                .enablePersistentMode(false)
                .build()

            var fallbackTriggered = false
            val timeoutJob = scope.launch {
                delay(7_500L)
                if (isExpectingClientP2pAutoConnect && !fallbackTriggered) {
                    fallbackTriggered = true
                    isExpectingClientP2pAutoConnect = false
                    connectToDirectWifiNetwork(ssid, passphrase)
                }
            }

            scope.launch {
                delay(200)
                runCatching {
                    manager.connect(channel, p2pConfig, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            _wifiDirectState.value = _wifiDirectState.value.copy(
                                statusMessage = "Joining Wi-Fi Direct ($ssid)..."
                            )
                        }

                        override fun onFailure(reason: Int) {
                            timeoutJob.cancel()
                            if (!fallbackTriggered) {
                                fallbackTriggered = true
                                isExpectingClientP2pAutoConnect = false
                                connectToDirectWifiNetwork(ssid, passphrase)
                            }
                        }
                    })
                }.onFailure {
                    timeoutJob.cancel()
                    if (!fallbackTriggered) {
                        fallbackTriggered = true
                        isExpectingClientP2pAutoConnect = false
                        connectToDirectWifiNetwork(ssid, passphrase)
                    }
                }
            }
            return
        }

        connectToDirectWifiNetwork(ssid, passphrase)
    }

    /**
     * Connects to Host's Direct Hotspot or Wi-Fi Direct SSID+Passphrase in-app using WifiNetworkSpecifier (Android 10+).
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
            p2pChannel = manager.initialize(context, Looper.getMainLooper(), null)
        }
        if (!p2pReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(p2pReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(p2pReceiver, filter)
            }
            p2pReceiverRegistered = true
        }
    }

    @SuppressLint("MissingPermission")
    fun removeWifiDirectGroup() {
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
