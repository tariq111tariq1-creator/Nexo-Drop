package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.GroupAdd
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.VerifiedUser
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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import com.example.R
import com.example.p2p.ConnectionPhase
import com.example.p2p.DiscoveredGroup
import com.example.p2p.GroupRole
import com.example.p2p.GroupWorkspaceState
import com.example.p2p.PeerPermission
import com.example.p2p.ReceivedGroupFile
import com.example.p2p.ScannedQrResult
import com.example.p2p.SharedGroupItem
import com.example.p2p.WifiDirectPeer
import com.example.ui.components.AnimatedRadarBeacon
import com.example.ui.components.CategoryBadge
import com.example.ui.components.EncryptionPill
import com.example.ui.components.QrCodeCard
import com.example.ui.components.QrScannerDialog
import com.example.ui.components.ReceivedFileActionsSheet
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
    joinApprovalFocusTick: Int = 0,
    onRefreshPermissions: () -> Unit,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onDiscoverWifiDirect: () -> Unit,
    onConnectDirectPeer: (WifiDirectPeer, String) -> Unit,
    onStartFallbackHotspot: () -> Unit,
    onToggleLocalNetwork: (Boolean) -> Unit,
    onToggleAutoReceive: (Boolean) -> Unit,
    onParseQr: (String) -> ScannedQrResult?,
    onJoinViaDirectCredentials: (ssid: String, pass: String, ip: String, port: Int, groupName: String, pin: String, hostMac: String) -> Unit,
    onCreateGroup: (name: String, pin: String, autoApprove: Boolean, webShare: Boolean) -> Unit,
    onJoinGroup: (ip: String, port: Int, groupName: String, pin: String) -> Unit,
    onLeaveOrCloseGroup: () -> Unit,
    onRespondJoinRequest: (requestId: String, approve: Boolean) -> Unit,
    onUpdatePeerPermission: (deviceId: String, permission: PeerPermission) -> Unit,
    onKickOrBanPeer: (deviceId: String, ban: Boolean) -> Unit,
    onNavigateToExplorer: () -> Unit,
    onOpenBackupCenter: () -> Unit,
    onAddPickedUris: (List<android.net.Uri>) -> Unit,
    onDownloadItem: (SharedGroupItem, toVault: Boolean) -> Unit,
    onCancelTransfer: (String) -> Unit,
    onSendChat: (String) -> Unit,
    onOpenReceivedFile: (ReceivedGroupFile) -> Unit,
    onMoveReceivedToVault: (ReceivedGroupFile) -> Unit,
    onCopyOrMoveReceivedFile: (ReceivedGroupFile, android.net.Uri, Boolean) -> Unit,
    onRenameReceivedFile: (ReceivedGroupFile, String) -> Unit,
    onShareReceivedExternal: (ReceivedGroupFile) -> Unit,
    onReshareReceivedToGroup: (ReceivedGroupFile) -> Unit,
    onDeleteReceivedFile: (ReceivedGroupFile) -> Unit,
    onClearError: () -> Unit
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val clipboard = LocalClipboardManager.current
    val listState = rememberLazyListState()

    var showCreateDialog by remember { mutableStateOf(false) }
    var showManualJoinDialog by remember { mutableStateOf(false) }
    var showQrScanner by remember { mutableStateOf(false) }
    var joinTargetGroup by remember { mutableStateOf<DiscoveredGroup?>(null) }
    var joinTargetDirectPeer by remember { mutableStateOf<WifiDirectPeer?>(null) }
    var joinTargetQr by remember { mutableStateOf<ScannedQrResult?>(null) }
    var qrInvalidWarning by remember { mutableStateOf(false) }
    var showQrDialog by remember { mutableStateOf(false) }
    var selectedReceivedFile by remember { mutableStateOf<ReceivedGroupFile?>(null) }
    var activeGroupTab by remember { mutableIntStateOf(0) }
    var chatInput by remember { mutableStateOf("") }
    var highlightJoinRequests by remember { mutableStateOf(false) }

    val pulseTransition = rememberInfiniteTransition(label = "hub_pulse")
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_dot"
    )

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

    val showPrereqBanner = !permissions.nearbyWifiGranted || !permissions.locationGranted || !permissions.wifiEnabled || !permissions.locationServiceEnabled
    val isGroupActive = state.phase == ConnectionPhase.HOSTING || state.phase == ConnectionPhase.CONNECTED

    LaunchedEffect(joinApprovalFocusTick, state.pendingRequests.size, state.phase) {
        if (joinApprovalFocusTick > 0 && state.role == GroupRole.HOST && state.pendingRequests.isNotEmpty() && isGroupActive) {
            val targetIdx = 1 +
                (if (showPrereqBanner) 1 else 0) +
                (if (qrInvalidWarning) 1 else 0) +
                (if (state.lastError != null) 1 else 0) +
                1
            listState.animateScrollToItem(targetIdx)
            highlightJoinRequests = true
            delay(2800)
            highlightJoinRequests = false
        }
    }

    LaunchedEffect(isGroupActive, activeGroupTab, state.chatMessages.size) {
        if (isGroupActive && activeGroupTab == 2) {
            val total = listState.layoutInfo.totalItemsCount
            if (total > 0) {
                listState.animateScrollToItem((total - 1).coerceAtLeast(0))
            }
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = if (isWideScreen) 980.dp else 640.dp)
                .fillMaxSize()
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
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
                            modifier = Modifier
                                .fillMaxWidth()
                                .animateContentSize(
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness = Spring.StiffnessMediumLow
                                    )
                                ),
                            shape = RoundedCornerShape(22.dp),
                            border = BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                            ),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        Brush.verticalGradient(
                                            listOf(
                                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.26f),
                                                MaterialTheme.colorScheme.surface
                                            )
                                        )
                                    )
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
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .clip(CircleShape)
                                                .background(
                                                    MaterialTheme.colorScheme.primary.copy(
                                                        alpha = if (state.isScanning) pulseAlpha else 0.7f
                                                    )
                                                )
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
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
                                            .height(50.dp)
                                            .testTag("create_group_button"),
                                        shape = RoundedCornerShape(16.dp)
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
                                            .height(50.dp)
                                            .testTag("scan_qr_button"),
                                        shape = RoundedCornerShape(16.dp)
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
                                    AssistChip(
                                        onClick = onOpenBackupCenter,
                                        label = { Text(stringResource(R.string.nav_backup)) },
                                        leadingIcon = {
                                            Icon(Icons.Rounded.Backup, contentDescription = null, modifier = Modifier.size(18.dp))
                                        },
                                        modifier = Modifier.testTag("hub_open_backup_chip")
                                    )
                                }
                            }
                        }
                    }

                    // Nearby Wi-Fi Direct Devices (PRIMARY)
                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .animateContentSize(
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness = Spring.StiffnessMediumLow
                                    )
                                ),
                            shape = RoundedCornerShape(20.dp),
                            border = BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)
                            ),
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
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                        Surface(
                                            color = MaterialTheme.colorScheme.secondaryContainer,
                                            shape = RoundedCornerShape(10.dp),
                                            modifier = Modifier.size(34.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    Icons.Rounded.Bolt,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(
                                            text = stringResource(R.string.hub_nearby_direct_title, state.wifiDirect.discoveredPeers.size),
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                    }
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
                                val stepLabel = when (state.connectionStep) {
                                    1 -> stringResource(R.string.hub_step_1)
                                    2 -> stringResource(R.string.hub_step_2)
                                    3 -> stringResource(R.string.hub_step_3)
                                    else -> stringResource(R.string.hub_waiting_approval)
                                }
                                Text(
                                    text = stepLabel,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                LinearProgressIndicator(
                                    progress = { (state.connectionStep.coerceIn(1, 4)) / 4f },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                )
                                if (state.keyFingerprint.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(10.dp))
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
                    // 1. Active Group Header Card with Large Centered Scannable QR
                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .animateContentSize(
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness = Spring.StiffnessMediumLow
                                    )
                                ),
                            shape = RoundedCornerShape(22.dp),
                            border = BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                            ),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        Brush.verticalGradient(
                                            listOf(
                                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f),
                                                MaterialTheme.colorScheme.surface
                                            )
                                        )
                                    )
                                    .padding(16.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                modifier = Modifier
                                                    .size(10.dp)
                                                    .clip(CircleShape)
                                                    .background(MaterialTheme.colorScheme.secondary.copy(alpha = pulseAlpha))
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = if (state.role == GroupRole.HOST) {
                                                    stringResource(R.string.hub_connected_host, state.groupName)
                                                } else {
                                                    stringResource(R.string.hub_connected_member, state.groupName)
                                                },
                                                style = MaterialTheme.typography.titleLarge,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
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

                                // Large Centered Scannable QR + Direct Host Credentials
                                if (state.role == GroupRole.HOST) {
                                    Spacer(modifier = Modifier.height(12.dp))
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                                    Spacer(modifier = Modifier.height(12.dp))

                                    val activeSsid = state.wifiDirect.hotspotSsid.ifBlank { state.wifiDirect.ssid }
                                    val activePass = state.wifiDirect.hotspotPassphrase.ifBlank { state.wifiDirect.passphrase }
                                    val qrText = state.directQrPayload.ifBlank {
                                        "nexodrop://${state.hostIp}:${state.hostPort}?name=${state.groupName}"
                                    }

                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        QrCodeCard(
                                            content = qrText,
                                            size = 240.dp,
                                            onClick = { showQrDialog = true }
                                        )
                                        Text(
                                            text = stringResource(R.string.hub_qr_tap_zoom),
                                            style = MaterialTheme.typography.labelSmall,
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

                    // 2. Pending Join Requests Banner (Always visible to Host regardless of active tab)
                    if (state.role == GroupRole.HOST && state.pendingRequests.isNotEmpty()) {
                        item(key = "pending_join_requests") {
                            val borderTint by animateColorAsState(
                                targetValue = if (highlightJoinRequests) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.tertiary.copy(alpha = 0.45f)
                                },
                                label = "join_req_border"
                            )
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .animateContentSize(
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioMediumBouncy,
                                            stiffness = Spring.StiffnessMediumLow
                                        )
                                    ),
                                shape = RoundedCornerShape(20.dp),
                                border = BorderStroke(if (highlightJoinRequests) 2.dp else 1.dp, borderTint),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(
                                        alpha = if (highlightJoinRequests) 0.72f else 0.48f
                                    )
                                )
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Rounded.NotificationsActive,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = stringResource(R.string.hub_pending_requests, state.pendingRequests.size),
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                    }
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

                    // 3. Clean Workspace Tabs with Modern Icons: Files | Peers | Chat
                    item {
                        val filesBadgeCount = if (state.autoReceiveFiles) {
                            state.receivedFiles.size + state.activeTransfers.size
                        } else {
                            state.receivedFiles.size + state.sharedItems.size
                        }
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surface,
                            tonalElevation = 2.dp
                        ) {
                            TabRow(selectedTabIndex = activeGroupTab) {
                                Tab(
                                    selected = activeGroupTab == 0,
                                    onClick = { activeGroupTab = 0 },
                                    icon = { Icon(Icons.Rounded.Folder, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                    text = { Text("${stringResource(R.string.hub_tab_files)} ($filesBadgeCount)", maxLines = 1) }
                                )
                                Tab(
                                    selected = activeGroupTab == 1,
                                    onClick = { activeGroupTab = 1 },
                                    icon = { Icon(Icons.Rounded.Devices, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                    text = { Text("${stringResource(R.string.hub_tab_peers)} (${state.peers.size})", maxLines = 1) }
                                )
                                Tab(
                                    selected = activeGroupTab == 2,
                                    onClick = { activeGroupTab = 2 },
                                    icon = { Icon(Icons.Rounded.Forum, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                    text = { Text("${stringResource(R.string.hub_tab_chat)} (${state.chatMessages.size})", maxLines = 1) }
                                )
                            }
                        }
                    }

                    if (activeGroupTab == 0) {
                        // Auto-Receive Toggle + Backup Shortcut Card
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                            ) {
                                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = stringResource(R.string.hub_auto_receive),
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Switch(
                                            checked = state.autoReceiveFiles,
                                            onCheckedChange = onToggleAutoReceive,
                                            modifier = Modifier.testTag("auto_receive_switch")
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        FilledTonalButton(
                                            onClick = { quickFilePicker.launch(arrayOf("*/*")) },
                                            modifier = Modifier
                                                .weight(1f)
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
                                        OutlinedButton(
                                            onClick = onOpenBackupCenter,
                                            modifier = Modifier.height(48.dp)
                                        ) {
                                            Icon(Icons.Rounded.Backup, contentDescription = stringResource(R.string.nav_backup), modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }
                            }
                        }

                        // Active Transfers Queue with ETA
                        if (state.activeTransfers.isNotEmpty()) {
                            item {
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .animateContentSize(),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Rounded.SwapVert, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = stringResource(R.string.hub_active_transfers, state.activeTransfers.size),
                                                style = MaterialTheme.typography.titleMedium
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(10.dp))
                                        state.activeTransfers.forEach { t ->
                                            val remainingBytes = (t.totalBytes - t.bytesTransferred).coerceAtLeast(0L)
                                            val etaText = Formatters.formatEta(remainingBytes, t.speedBytesPerSec)
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
                                                    text = buildString {
                                                        append("${Formatters.formatBytes(t.bytesTransferred)} / ${Formatters.formatBytes(t.totalBytes)} • ${Formatters.formatSpeed(t.speedBytesPerSec)}")
                                                        if (etaText.isNotBlank() && !t.isCompleted) {
                                                            append(" • ${stringResource(R.string.hub_eta_left, etaText)}")
                                                        }
                                                    },
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Received Files with Complete In-App Controls
                        item {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .animateContentSize(
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioMediumBouncy,
                                            stiffness = Spring.StiffnessMediumLow
                                        )
                                    ),
                                shape = RoundedCornerShape(20.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Rounded.DownloadDone, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = stringResource(R.string.hub_received_files_title, state.receivedFiles.size),
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    if (state.receivedFiles.isEmpty()) {
                                        Text(
                                            text = stringResource(R.string.hub_received_files_empty),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    } else {
                                        state.receivedFiles.forEach { rf ->
                                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                CategoryBadge(category = rf.category)
                                                Spacer(modifier = Modifier.width(12.dp))
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = rf.fileName,
                                                        style = MaterialTheme.typography.bodyLarge,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Text(
                                                        text = "${Formatters.formatBytes(rf.fileSize)} • ${rf.senderName} • ${rf.savedLocation}",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = if (rf.isInVault) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                                IconButton(
                                                    onClick = { selectedReceivedFile = rf },
                                                    modifier = Modifier
                                                        .size(48.dp)
                                                        .testTag("manage_received_file_${rf.id}")
                                                ) {
                                                    Icon(
                                                        Icons.Rounded.MoreVert,
                                                        contentDescription = stringResource(R.string.action_file_details),
                                                        tint = MaterialTheme.colorScheme.primary
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Manual Shared Group Tray (shown only when autoReceiveFiles is disabled to avoid duplicate entries during instant transfer)
                        if (!state.autoReceiveFiles) {
                            item {
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .animateContentSize(),
                                    shape = RoundedCornerShape(20.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Rounded.Sync, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = stringResource(R.string.hub_shared_tray, state.sharedItems.size),
                                                style = MaterialTheme.typography.titleMedium
                                            )
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
                        }
                    } else if (activeGroupTab == 1) {
                        // Connected Devices & Host Permissions Controls
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
                    } else {
                        // Encrypted Chat & Live Clipboard Sync (Messages list; composer is sticky below LazyColumn above keyboard)
                        item(key = "chat_messages_card") {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .animateContentSize(),
                                shape = RoundedCornerShape(20.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Rounded.Forum, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = stringResource(R.string.hub_chat_title),
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                    }
                                    if (state.chatMessages.isEmpty()) {
                                        Text(
                                            text = stringResource(R.string.hub_chat_hint),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    } else {
                                        state.chatMessages.takeLast(30).forEach { msg ->
                                            Surface(
                                                shape = RoundedCornerShape(14.dp),
                                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.SpaceBetween
                                                ) {
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(
                                                            text = msg.senderName,
                                                            style = MaterialTheme.typography.labelMedium,
                                                            fontWeight = FontWeight.SemiBold,
                                                            color = MaterialTheme.colorScheme.primary
                                                        )
                                                        Spacer(modifier = Modifier.height(2.dp))
                                                        Text(
                                                            text = msg.text,
                                                            style = MaterialTheme.typography.bodyMedium
                                                        )
                                                    }
                                                    IconButton(
                                                        onClick = { clipboard.setText(AnnotatedString(msg.text)) },
                                                        modifier = Modifier.size(48.dp)
                                                    ) {
                                                        Icon(Icons.Rounded.ContentCopy, contentDescription = stringResource(R.string.hub_copy))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            }

            // Sticky Chat Input Bar anchored directly above open soft keyboard
            AnimatedVisibility(visible = isGroupActive && activeGroupTab == 2) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    tonalElevation = 6.dp,
                    shadowElevation = 8.dp,
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = chatInput,
                            onValueChange = { chatInput = it },
                            placeholder = { Text(stringResource(R.string.hub_chat_hint)) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("chat_input_field"),
                            maxLines = 3,
                            shape = RoundedCornerShape(24.dp)
                        )
                        Button(
                            onClick = {
                                if (chatInput.isNotBlank()) {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onSendChat(chatInput)
                                    chatInput = ""
                                }
                            },
                            enabled = chatInput.isNotBlank(),
                            modifier = Modifier
                                .height(50.dp)
                                .testTag("chat_send_button"),
                            shape = RoundedCornerShape(24.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Rounded.Send,
                                contentDescription = stringResource(R.string.hub_send)
                            )
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
                        "",
                        parsed.hostMac
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
                        pin.trim(),
                        parsed.hostMac
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

    // Received File Full Controls Bottom Sheet
    selectedReceivedFile?.let { activeRf ->
        ReceivedFileActionsSheet(
            file = activeRf,
            onDismiss = { selectedReceivedFile = null },
            onOpenOrInstall = onOpenReceivedFile,
            onMoveToVault = onMoveReceivedToVault,
            onCopyOrMoveToFolder = onCopyOrMoveReceivedFile,
            onRename = onRenameReceivedFile,
            onShareExternal = onShareReceivedExternal,
            onReshareToGroup = onReshareReceivedToGroup,
            onDelete = onDeleteReceivedFile
        )
    }

    // Large Zoomed QR Code & Direct Info Dialog (310dp)
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
                    QrCodeCard(content = qrPayload, size = 310.dp)
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
                        QrCodeCard(content = state.webShareUrl, size = 180.dp)
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
