package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.GroupAdd
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.p2p.ConnectionPhase
import com.example.p2p.DiscoveredGroup
import com.example.p2p.GroupRole
import com.example.p2p.GroupWorkspaceState
import com.example.p2p.PeerPermission
import com.example.p2p.ScannedQrResult
import com.example.p2p.SharedGroupItem
import com.example.p2p.WifiDirectPeer
import com.example.ui.components.AnimatedRadarBeacon
import com.example.ui.components.CategoryBadge
import com.example.ui.components.EncryptionPill
import com.example.ui.components.QrCodeCard
import com.example.ui.components.QrScannerDialog
import com.example.util.Formatters
import com.example.util.PermissionSnapshot
import com.example.util.PermissionsHelper

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GroupHubScreen(
    deviceName: String,
    state: GroupWorkspaceState,
    permissions: PermissionSnapshot,
    isWideScreen: Boolean,
    onRefreshPermissions: () -> Unit,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onDiscoverWifiDirect: () -> Unit,
    onConnectDirectPeer: (WifiDirectPeer, String) -> Unit,
    onStartFallbackHotspot: () -> Unit,
    onToggleLocalNetwork: (Boolean) -> Unit,
    onParseQr: (String) -> ScannedQrResult?,
    onJoinViaDirectCredentials: (ssid: String, pass: String, ip: String, port: Int, groupName: String, pin: String) -> Unit,
    onCreateGroup: (name: String, pin: String, autoApprove: Boolean, webShare: Boolean) -> Unit,
    onJoinGroup: (ip: String, port: Int, groupName: String, pin: String) -> Unit,
    onLeaveOrCloseGroup: () -> Unit,
    onRespondJoinRequest: (requestId: String, approve: Boolean) -> Unit,
    onUpdatePeerPermission: (deviceId: String, permission: PeerPermission) -> Unit,
    onKickOrBanPeer: (deviceId: String, ban: Boolean) -> Unit,
    onNavigateToExplorer: () -> Unit,
    onAddPickedUris: (List<android.net.Uri>) -> Unit,
    onDownloadItem: (SharedGroupItem, toVault: Boolean) -> Unit,
    onCancelTransfer: (String) -> Unit,
    onSendChat: (String) -> Unit,
    onClearError: () -> Unit
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val clipboard = LocalClipboardManager.current

    var showCreateDialog by remember { mutableStateOf(false) }
    var showManualJoinDialog by remember { mutableStateOf(false) }
    var showQrScanner by remember { mutableStateOf(false) }
    var joinTargetGroup by remember { mutableStateOf<DiscoveredGroup?>(null) }
    var joinTargetDirectPeer by remember { mutableStateOf<WifiDirectPeer?>(null) }
    var joinTargetQr by remember { mutableStateOf<ScannedQrResult?>(null) }
    var qrInvalidWarning by remember { mutableStateOf(false) }
    var showQrDialog by remember { mutableStateOf(false) }
    var chatInput by remember { mutableStateOf("") }

    val quickFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) onAddPickedUris(uris)
    }

    val runtimePermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        onRefreshPermissions()
        onStartScan()
    }

    val settingsPanelLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        onRefreshPermissions()
        onStartScan()
    }

    LaunchedEffect(state.phase, permissions.readyForDirectP2p) {
        if (state.phase == ConnectionPhase.IDLE && permissions.nearbyWifiGranted && permissions.locationGranted && permissions.wifiEnabled) {
            onStartScan()
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = if (isWideScreen) 980.dp else 640.dp)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.hub_title),
                            style = MaterialTheme.typography.headlineMedium
                        )
                        Text(
                            text = stringResource(R.string.hub_device_name_label, deviceName),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    EncryptionPill(text = "AES-256-GCM")
                }
            }

            // Direct Connection Prerequisites Check (Permissions + Wi-Fi + System Location)
            if (!permissions.nearbyWifiGranted || !permissions.locationGranted || !permissions.wifiEnabled || !permissions.locationServiceEnabled) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (!permissions.nearbyWifiGranted || !permissions.locationGranted) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                        Icon(Icons.Rounded.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = stringResource(R.string.hub_prereq_perms_missing),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Button(
                                        onClick = { runtimePermLauncher.launch(PermissionsHelper.getRuntimeDiscoveryPermissions()) },
                                        modifier = Modifier.height(48.dp)
                                    ) {
                                        Text(stringResource(R.string.perm_missing))
                                    }
                                }
                            }
                            if (!permissions.wifiEnabled) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                        Icon(Icons.Rounded.Wifi, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = stringResource(R.string.hub_prereq_wifi_off),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    FilledTonalButton(
                                        onClick = { settingsPanelLauncher.launch(PermissionsHelper.buildWifiSettingsIntent()) },
                                        modifier = Modifier.height(48.dp)
                                    ) {
                                        Text(stringResource(R.string.hub_btn_turn_on_wifi))
                                    }
                                }
                            }
                            if (!permissions.locationServiceEnabled) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                        Icon(Icons.Rounded.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = stringResource(R.string.hub_prereq_location_off),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    FilledTonalButton(
                                        onClick = { settingsPanelLauncher.launch(PermissionsHelper.buildLocationSettingsIntent()) },
                                        modifier = Modifier.height(48.dp)
                                    ) {
                                        Text(stringResource(R.string.hub_btn_turn_on_location))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (qrInvalidWarning) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Rounded.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.hub_qr_invalid), style = MaterialTheme.typography.bodyMedium)
                            }
                            IconButton(onClick = { qrInvalidWarning = false }) {
                                Icon(Icons.Rounded.Close, contentDescription = null)
                            }
                        }
                    }
                }
            }

            if (state.lastError != null) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.14f)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = state.lastError,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = onClearError) {
                                Icon(Icons.Rounded.Close, contentDescription = null)
                            }
                        }
                    }
                }
            }

            when (state.phase) {
                ConnectionPhase.IDLE -> {
                    // Primary Direct Connection Card (No Router Required)
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    shape = RoundedCornerShape(999.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Rounded.WifiTethering,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = stringResource(R.string.hub_direct_mode_badge),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                AnimatedRadarBeacon(isActive = state.isScanning)
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = state.wifiDirect.statusMessage.ifBlank {
                                        if (state.isScanning) stringResource(R.string.hub_scanning)
                                        else stringResource(R.string.hub_no_groups)
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(16.dp))

                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Button(
                                        onClick = {
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            showCreateDialog = true
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                            .testTag("create_group_button"),
                                        shape = RoundedCornerShape(14.dp)
                                    ) {
                                        Icon(Icons.Rounded.GroupAdd, contentDescription = null)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(stringResource(R.string.hub_create_group))
                                    }

                                    FilledTonalButton(
                                        onClick = {
                                            qrInvalidWarning = false
                                            showQrScanner = true
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                            .testTag("scan_qr_button"),
                                        shape = RoundedCornerShape(14.dp)
                                    ) {
                                        Icon(Icons.Rounded.QrCodeScanner, contentDescription = null)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(stringResource(R.string.hub_scan_qr))
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    AssistChip(
                                        onClick = {
                                            if (state.isScanning) onStopScan() else onStartScan()
                                        },
                                        label = { Text(stringResource(R.string.hub_scan_nearby)) },
                                        leadingIcon = {
                                            Icon(Icons.Rounded.Radar, contentDescription = null, modifier = Modifier.size(18.dp))
                                        }
                                    )
                                    AssistChip(
                                        onClick = { showManualJoinDialog = true },
                                        label = { Text(stringResource(R.string.hub_join_manual)) },
                                        modifier = Modifier.testTag("manual_join_button")
                                    )
                                }
                            }
                        }
                    }

                    // Nearby Wi-Fi Direct Devices (PRIMARY)
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = stringResource(R.string.hub_nearby_direct_title, state.wifiDirect.discoveredPeers.size),
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    TextButton(onClick = onDiscoverWifiDirect) {
                                        Text(stringResource(R.string.hub_scan_nearby))
                                    }
                                }

                                if (state.wifiDirect.discoveredPeers.isEmpty()) {
                                    Text(
                                        text = stringResource(R.string.hub_no_groups_hint),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    state.wifiDirect.discoveredPeers.forEach { peer ->
                                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = peer.serviceGroupName.ifBlank { peer.deviceName },
                                                    style = MaterialTheme.typography.titleMedium
                                                )
                                                Text(
                                                    text = buildString {
                                                        append(peer.deviceName)
                                                        if (peer.isNexoService) append(" • NexoDrop Direct")
                                                    },
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = if (peer.isNexoService) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            Button(
                                                onClick = {
                                                    if (peer.hasPin) {
                                                        joinTargetDirectPeer = peer
                                                    } else {
                                                        onConnectDirectPeer(peer, "")
                                                    }
                                                },
                                                modifier = Modifier.height(48.dp)
                                            ) {
                                                if (peer.hasPin) {
                                                    Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                }
                                                Text(stringResource(R.string.hub_connect))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Secondary: Local Wi-Fi Network (LAN) Optional Toggle & Results
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = stringResource(R.string.hub_secondary_lan_toggle),
                                        style = MaterialTheme.typography.titleSmall,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Switch(
                                        checked = state.localNetworkEnabled,
                                        onCheckedChange = onToggleLocalNetwork,
                                        modifier = Modifier.testTag("secondary_lan_switch")
                                    )
                                }
                                if (state.localNetworkEnabled && state.discoveredGroups.isNotEmpty()) {
                                    state.discoveredGroups.forEach { group ->
                                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(group.groupName, style = MaterialTheme.typography.titleMedium)
                                                Text(
                                                    text = "${group.hostDeviceName} • ${group.hostIp}:${group.hostPort} (${group.source})",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            Button(
                                                onClick = {
                                                    if (group.hasPin) joinTargetGroup = group
                                                    else onJoinGroup(group.hostIp, group.hostPort, group.groupName, "")
                                                },
                                                modifier = Modifier.height(48.dp)
                                            ) {
                                                if (group.hasPin) {
                                                    Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                }
                                                Text(stringResource(R.string.hub_join))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                ConnectionPhase.CONNECTING, ConnectionPhase.WAITING_APPROVAL -> {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                CircularProgressIndicator()
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = stringResource(R.string.hub_waiting_approval),
                                    style = MaterialTheme.typography.titleMedium
                                )
                                if (state.keyFingerprint.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    EncryptionPill(
                                        text = stringResource(R.string.hub_e2ee_verified, state.keyFingerprint)
                                    )
                                }
                                Spacer(modifier = Modifier.height(16.dp))
                                OutlinedButton(
                                    onClick = onLeaveOrCloseGroup,
                                    modifier = Modifier.height(48.dp)
                                ) {
                                    Text(stringResource(R.string.hub_cancel))
                                }
                            }
                        }
                    }
                }

                ConnectionPhase.HOSTING, ConnectionPhase.CONNECTED -> {
                    // 1. Active Group Header Card
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = if (state.role == GroupRole.HOST) {
                                                stringResource(R.string.hub_connected_host, state.groupName)
                                            } else {
                                                stringResource(R.string.hub_connected_member, state.groupName)
                                            },
                                            style = MaterialTheme.typography.titleLarge
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "${state.hostIp}:${state.hostPort}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Row {
                                        IconButton(
                                            onClick = { showQrDialog = true },
                                            modifier = Modifier.testTag("show_qr_button")
                                        ) {
                                            Icon(Icons.Rounded.QrCode2, contentDescription = stringResource(R.string.hub_qr_title))
                                        }
                                        Button(
                                            onClick = onLeaveOrCloseGroup,
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = MaterialTheme.colorScheme.error
                                            ),
                                            modifier = Modifier.testTag("leave_close_group_button")
                                        ) {
                                            Text(
                                                if (state.role == GroupRole.HOST) stringResource(R.string.hub_close_group)
                                                else stringResource(R.string.hub_leave_group)
                                            )
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                EncryptionPill(
                                    text = stringResource(R.string.hub_e2ee_verified, state.keyFingerprint)
                                )

                                // Direct Device-to-Device Host Credentials + Embedded QR + Hotspot Fallback
                                if (state.role == GroupRole.HOST) {
                                    Spacer(modifier = Modifier.height(12.dp))
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                                    Spacer(modifier = Modifier.height(12.dp))

                                    val activeSsid = state.wifiDirect.hotspotSsid.ifBlank { state.wifiDirect.ssid }
                                    val activePass = state.wifiDirect.hotspotPassphrase.ifBlank { state.wifiDirect.passphrase }
                                    val qrText = state.directQrPayload.ifBlank {
                                        "nexodrop://${state.hostIp}:${state.hostPort}?name=${state.groupName}"
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        QrCodeCard(content = qrText, size = 124.dp)
                                        Column(
                                            modifier = Modifier.weight(1f),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Text(
                                                text = stringResource(R.string.hub_direct_mode_badge),
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            if (activeSsid.isNotBlank()) {
                                                Text(
                                                    text = if (state.wifiDirect.hotspotActive) {
                                                        stringResource(R.string.hub_direct_hotspot_active, activeSsid)
                                                    } else {
                                                        stringResource(R.string.hub_wifi_direct_active, activeSsid)
                                                    },
                                                    style = MaterialTheme.typography.bodySmall
                                                )
                                            }
                                            if (activePass.isNotBlank()) {
                                                Text(
                                                    text = stringResource(R.string.hub_wifi_direct_pass, activePass),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            Text(
                                                text = stringResource(R.string.hub_direct_credentials_hint),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(10.dp))
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        if (!state.wifiDirect.hotspotActive) {
                                            OutlinedButton(
                                                onClick = onStartFallbackHotspot,
                                                modifier = Modifier
                                                    .height(48.dp)
                                                    .testTag("fallback_hotspot_button")
                                            ) {
                                                Icon(Icons.Rounded.WifiTethering, contentDescription = null, modifier = Modifier.size(18.dp))
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(stringResource(R.string.hub_direct_hotspot_fallback))
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = stringResource(R.string.hub_secondary_lan_toggle),
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Switch(
                                            checked = state.localNetworkEnabled,
                                            onCheckedChange = onToggleLocalNetwork
                                        )
                                    }
                                }

                                if (state.webShareUrl.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                            Icon(
                                                Icons.Rounded.Language,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.secondary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = stringResource(R.string.hub_web_share_url, state.webShareUrl),
                                                style = MaterialTheme.typography.labelSmall,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        IconButton(onClick = {
                                            clipboard.setText(AnnotatedString(state.webShareUrl))
                                        }) {
                                            Icon(Icons.Rounded.ContentCopy, contentDescription = stringResource(R.string.hub_copy))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 2. Pending Join Requests (Host only)
                    if (state.role == GroupRole.HOST && state.pendingRequests.isNotEmpty()) {
                        item {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .animateContentSize(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f)
                                )
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(
                                        text = stringResource(R.string.hub_pending_requests, state.pendingRequests.size),
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    state.pendingRequests.forEach { req ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(req.deviceName, style = MaterialTheme.typography.bodyLarge)
                                                Text(
                                                    "${req.ipAddress} • Key: ${req.keyFingerprint}",
                                                    style = MaterialTheme.typography.labelSmall
                                                )
                                            }
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                FilledTonalButton(
                                                    onClick = { onRespondJoinRequest(req.requestId, false) },
                                                    modifier = Modifier.height(48.dp)
                                                ) {
                                                    Text(stringResource(R.string.hub_reject))
                                                }
                                                Button(
                                                    onClick = {
                                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                        onRespondJoinRequest(req.requestId, true)
                                                    },
                                                    modifier = Modifier.height(48.dp)
                                                ) {
                                                    Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text(stringResource(R.string.hub_approve))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 3. Active Transfers Queue
                    if (state.activeTransfers.isNotEmpty()) {
                        item {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .animateContentSize(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(
                                        text = stringResource(R.string.hub_active_transfers, state.activeTransfers.size),
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    state.activeTransfers.forEach { t ->
                                        Column(modifier = Modifier.padding(vertical = 6.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = t.fileName,
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                if (!t.isCompleted && t.error == null) {
                                                    IconButton(onClick = { onCancelTransfer(t.transferId) }) {
                                                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.hub_cancel))
                                                    }
                                                }
                                            }
                                            LinearProgressIndicator(
                                                progress = { t.progress },
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(6.dp)
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = "${Formatters.formatBytes(t.bytesTransferred)} / ${Formatters.formatBytes(t.totalBytes)} • ${Formatters.formatSpeed(t.speedBytesPerSec)}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 4. Shared Group Tray (Live Synced Files)
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = stringResource(R.string.hub_shared_tray, state.sharedItems.size),
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        FilledTonalButton(
                                            onClick = { quickFilePicker.launch(arrayOf("*/*")) },
                                            modifier = Modifier
                                                .height(48.dp)
                                                .testTag("group_add_files_picker")
                                        ) {
                                            Icon(Icons.Rounded.Add, contentDescription = null)
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(stringResource(R.string.hub_add_files))
                                        }
                                        OutlinedButton(
                                            onClick = onNavigateToExplorer,
                                            modifier = Modifier.height(48.dp)
                                        ) {
                                            Text(stringResource(R.string.nav_explorer))
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                                if (state.sharedItems.isEmpty()) {
                                    Text(
                                        text = stringResource(R.string.hub_shared_tray_empty),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    state.sharedItems.forEach { item ->
                                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            CategoryBadge(category = item.category)
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = item.fileName,
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = "${Formatters.formatBytes(item.fileSize)} • ${item.ownerDeviceName}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            IconButton(
                                                onClick = { onDownloadItem(item, false) },
                                                modifier = Modifier.size(48.dp)
                                            ) {
                                                Icon(
                                                    Icons.Rounded.Download,
                                                    contentDescription = stringResource(R.string.hub_download),
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                            IconButton(
                                                onClick = { onDownloadItem(item, true) },
                                                modifier = Modifier.size(48.dp)
                                            ) {
                                                Icon(
                                                    Icons.Rounded.Lock,
                                                    contentDescription = stringResource(R.string.hub_download_vault),
                                                    tint = MaterialTheme.colorScheme.tertiary
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 5. Connected Devices & Host Permissions Controls
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Rounded.Devices, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = stringResource(R.string.hub_members_title, state.peers.size),
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                                state.peers.forEach { peer ->
                                    var menuExpanded by remember { mutableStateOf(false) }
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "${peer.deviceName} (${if (peer.isHost) stringResource(R.string.hub_role_host) else stringResource(R.string.hub_role_member)})",
                                                style = MaterialTheme.typography.bodyLarge
                                            )
                                            Text(
                                                text = "${peer.ipAddress} • ${peer.permission.name} • Key: ${peer.keyFingerprint}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        if (state.role == GroupRole.HOST && !peer.isHost) {
                                            Box {
                                                TextButton(onClick = { menuExpanded = true }) {
                                                    Text(stringResource(R.string.nav_settings))
                                                }
                                                DropdownMenu(
                                                    expanded = menuExpanded,
                                                    onDismissRequest = { menuExpanded = false }
                                                ) {
                                                    DropdownMenuItem(
                                                        text = { Text(stringResource(R.string.hub_perm_full)) },
                                                        onClick = {
                                                            menuExpanded = false
                                                            onUpdatePeerPermission(peer.deviceId, PeerPermission.FULL_ACCESS)
                                                        }
                                                    )
                                                    DropdownMenuItem(
                                                        text = { Text(stringResource(R.string.hub_perm_receive)) },
                                                        onClick = {
                                                            menuExpanded = false
                                                            onUpdatePeerPermission(peer.deviceId, PeerPermission.RECEIVE_ONLY)
                                                        }
                                                    )
                                                    DropdownMenuItem(
                                                        text = { Text(stringResource(R.string.hub_perm_send)) },
                                                        onClick = {
                                                            menuExpanded = false
                                                            onUpdatePeerPermission(peer.deviceId, PeerPermission.SEND_ONLY)
                                                        }
                                                    )
                                                    DropdownMenuItem(
                                                        text = { Text(stringResource(R.string.hub_perm_view)) },
                                                        onClick = {
                                                            menuExpanded = false
                                                            onUpdatePeerPermission(peer.deviceId, PeerPermission.VIEW_ONLY)
                                                        }
                                                    )
                                                    HorizontalDivider()
                                                    DropdownMenuItem(
                                                        text = { Text(stringResource(R.string.hub_kick)) },
                                                        onClick = {
                                                            menuExpanded = false
                                                            onKickOrBanPeer(peer.deviceId, false)
                                                        }
                                                    )
                                                    DropdownMenuItem(
                                                        text = { Text(stringResource(R.string.hub_ban), color = MaterialTheme.colorScheme.error) },
                                                        onClick = {
                                                            menuExpanded = false
                                                            onKickOrBanPeer(peer.deviceId, true)
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 6. Encrypted Chat & Live Clipboard Sync
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = stringResource(R.string.hub_chat_title),
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                state.chatMessages.takeLast(8).forEach { msg ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = msg.senderName,
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            Text(
                                                text = msg.text,
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                        }
                                        IconButton(onClick = {
                                            clipboard.setText(AnnotatedString(msg.text))
                                        }) {
                                            Icon(Icons.Rounded.ContentCopy, contentDescription = stringResource(R.string.hub_copy))
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedTextField(
                                        value = chatInput,
                                        onValueChange = { chatInput = it },
                                        placeholder = { Text(stringResource(R.string.hub_chat_hint)) },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true
                                    )
                                    Button(
                                        onClick = {
                                            onSendChat(chatInput)
                                            chatInput = ""
                                        },
                                        modifier = Modifier.height(52.dp)
                                    ) {
                                        Icon(Icons.Rounded.Send, contentDescription = stringResource(R.string.hub_send))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Create Group Dialog
    if (showCreateDialog) {
        var gName by remember { mutableStateOf("$deviceName Group") }
        var gPin by remember { mutableStateOf("") }
        var autoApprove by remember { mutableStateOf(false) }
        var webShare by remember { mutableStateOf(true) }

        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text(stringResource(R.string.hub_create_group)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = gName,
                        onValueChange = { gName = it },
                        label = { Text(stringResource(R.string.hub_group_name_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = gPin,
                        onValueChange = { gPin = it },
                        label = { Text(stringResource(R.string.hub_group_pin_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.hub_auto_approve), modifier = Modifier.weight(1f))
                        Switch(checked = autoApprove, onCheckedChange = { autoApprove = it })
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.hub_enable_web_share), modifier = Modifier.weight(1f))
                        Switch(checked = webShare, onCheckedChange = { webShare = it })
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    showCreateDialog = false
                    onCreateGroup(gName, gPin, autoApprove, webShare)
                }) {
                    Text(stringResource(R.string.hub_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text(stringResource(R.string.hub_cancel))
                }
            }
        )
    }

    // Manual IP/Port/PIN Join Dialog
    if (showManualJoinDialog) {
        var ip by remember { mutableStateOf("192.168.49.1") }
        var port by remember { mutableStateOf("45892") }
        var pin by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showManualJoinDialog = false },
            title = { Text(stringResource(R.string.hub_join_manual)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = ip,
                        onValueChange = { ip = it },
                        label = { Text(stringResource(R.string.hub_host_ip_label)) },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it.filter { ch -> ch.isDigit() } },
                        label = { Text(stringResource(R.string.hub_host_port_label)) },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = pin,
                        onValueChange = { pin = it },
                        label = { Text(stringResource(R.string.hub_enter_pin)) },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val parsedPort = port.toIntOrNull() ?: 0
                    if (ip.isNotBlank() && parsedPort > 0) {
                        showManualJoinDialog = false
                        onJoinGroup(ip.trim(), parsedPort, ip.trim(), pin.trim())
                    }
                }) {
                    Text(stringResource(R.string.hub_connect))
                }
            },
            dismissButton = {
                TextButton(onClick = { showManualJoinDialog = false }) {
                    Text(stringResource(R.string.hub_cancel))
                }
            }
        )
    }

    // Discovered PIN Prompt Dialog
    joinTargetGroup?.let { group ->
        var pin by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { joinTargetGroup = null },
            title = { Text(group.groupName) },
            text = {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it },
                    label = { Text(stringResource(R.string.hub_enter_pin)) },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    val target = group
                    joinTargetGroup = null
                    onJoinGroup(target.hostIp, target.hostPort, target.groupName, pin.trim())
                }) {
                    Text(stringResource(R.string.hub_connect))
                }
            },
            dismissButton = {
                TextButton(onClick = { joinTargetGroup = null }) {
                    Text(stringResource(R.string.hub_cancel))
                }
            }
        )
    }

    // QR Scanner Dialog (CameraX + ZXing)
    if (showQrScanner) {
        QrScannerDialog(
            onDismiss = { showQrScanner = false },
            onQrScanned = { raw ->
                showQrScanner = false
                val parsed = onParseQr(raw)
                if (parsed == null) {
                    qrInvalidWarning = true
                } else if (parsed.hasPin) {
                    joinTargetQr = parsed
                } else {
                    onJoinViaDirectCredentials(
                        parsed.ssid,
                        parsed.passphrase,
                        parsed.hostIp,
                        parsed.hostPort,
                        parsed.groupName,
                        ""
                    )
                }
            }
        )
    }

    // Scanned QR PIN Prompt Dialog
    joinTargetQr?.let { parsed ->
        var pin by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { joinTargetQr = null },
            title = { Text(parsed.groupName) },
            text = {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it },
                    label = { Text(stringResource(R.string.hub_enter_pin)) },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    joinTargetQr = null
                    onJoinViaDirectCredentials(
                        parsed.ssid,
                        parsed.passphrase,
                        parsed.hostIp,
                        parsed.hostPort,
                        parsed.groupName,
                        pin.trim()
                    )
                }) {
                    Text(stringResource(R.string.hub_connect))
                }
            },
            dismissButton = {
                TextButton(onClick = { joinTargetQr = null }) {
                    Text(stringResource(R.string.hub_cancel))
                }
            }
        )
    }

    // Direct Wi-Fi Peer PIN Prompt Dialog
    joinTargetDirectPeer?.let { peer ->
        var pin by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { joinTargetDirectPeer = null },
            title = { Text(peer.serviceGroupName.ifBlank { peer.deviceName }) },
            text = {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it },
                    label = { Text(stringResource(R.string.hub_enter_pin)) },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    val target = peer
                    joinTargetDirectPeer = null
                    onConnectDirectPeer(target, pin.trim())
                }) {
                    Text(stringResource(R.string.hub_connect))
                }
            },
            dismissButton = {
                TextButton(onClick = { joinTargetDirectPeer = null }) {
                    Text(stringResource(R.string.hub_cancel))
                }
            }
        )
    }

    // QR Code & Direct Info Dialog
    if (showQrDialog) {
        val qrPayload = state.directQrPayload.ifBlank {
            "nexodrop://${state.hostIp}:${state.hostPort}?name=${state.groupName}"
        }
        AlertDialog(
            onDismissRequest = { showQrDialog = false },
            title = { Text(stringResource(R.string.hub_qr_title)) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    QrCodeCard(content = qrPayload, size = 190.dp)
                    Text(
                        text = "${state.hostIp}:${state.hostPort}",
                        style = MaterialTheme.typography.titleMedium
                    )
                    val activeSsid = state.wifiDirect.hotspotSsid.ifBlank { state.wifiDirect.ssid }
                    val activePass = state.wifiDirect.hotspotPassphrase.ifBlank { state.wifiDirect.passphrase }
                    if (activeSsid.isNotBlank()) {
                        Text(
                            text = "Wi-Fi: $activeSsid • $activePass",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (state.webShareUrl.isNotBlank()) {
                        HorizontalDivider()
                        Text(
                            text = stringResource(R.string.hub_web_share_title),
                            style = MaterialTheme.typography.labelMedium
                        )
                        QrCodeCard(content = state.webShareUrl, size = 140.dp)
                        Text(
                            text = state.webShareUrl,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showQrDialog = false }) {
                    Text(stringResource(R.string.hub_confirm))
                }
            }
        )
    }
}
