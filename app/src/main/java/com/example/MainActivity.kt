package com.example

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Assessment
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Assessment
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.AccountAuthState
import com.example.data.CloudSyncUiState
import com.example.data.RegisteredDevice
import com.example.data.SyncedTransferRecord
import com.example.p2p.GroupRole
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.data.AppPreferences
import com.example.p2p.ConnectionPhase
import com.example.p2p.TransferForegroundService
import com.example.ui.screens.BackupScreen
import com.example.ui.screens.ExplorerScreen
import com.example.ui.screens.GroupHubScreen
import com.example.ui.screens.OnboardingScreen
import com.example.ui.screens.ReportsScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.VaultScreen
import com.example.ui.theme.NexoDropTheme
import com.example.util.LocaleHelper

const val ROUTE_GROUP = "group"
const val ROUTE_EXPLORER = "explorer"
const val ROUTE_VAULT = "vault"
const val ROUTE_REPORTS = "reports"
const val ROUTE_SETTINGS = "settings"
const val ROUTE_BACKUP = "backup"

private data class NavDest(
    val route: String,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

class MainActivity : FragmentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun attachBaseContext(newBase: Context) {
        val langCode = AppPreferences.getCachedLanguageSync(newBase)
        super.attachBaseContext(LocaleHelper.wrapContext(newBase, langCode))
    }

    private fun handleIncomingIntent(intent: Intent?) {
        when (intent?.action) {
            TransferForegroundService.ACTION_OPEN_JOIN_REQUESTS -> {
                viewModel.triggerFocusJoinApproval()
            }
            Intent.ACTION_SEND -> {
                val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (uri != null || !text.isNullOrBlank()) {
                    viewModel.handleExternalShare(listOfNotNull(uri), text)
                    viewModel.triggerOpenGroupTab()
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris: List<Uri> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
                }
                if (uris.isNotEmpty()) {
                    viewModel.handleExternalShare(uris, null)
                    viewModel.triggerOpenGroupTab()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIncomingIntent(intent)

        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val groupState by viewModel.groupState.collectAsStateWithLifecycle()
            val transferRecords by viewModel.transferRecords.collectAsStateWithLifecycle()
            val vaultItems by viewModel.vaultItems.collectAsStateWithLifecycle()
            val hiddenApps by viewModel.hiddenApps.collectAsStateWithLifecycle()
            val installedApps by viewModel.installedApps.collectAsStateWithLifecycle()
            val deviceFiles by viewModel.deviceFiles.collectAsStateWithLifecycle()
            val storageRoots by viewModel.storageRoots.collectAsStateWithLifecycle()
            val currentFolderPath by viewModel.currentFolderPath.collectAsStateWithLifecycle()
            val currentFolderFiles by viewModel.currentFolderFiles.collectAsStateWithLifecycle()
            val vaultUnlocked by viewModel.vaultUnlocked.collectAsStateWithLifecycle()
            val permissions by viewModel.permissionSnapshot.collectAsStateWithLifecycle()
            val backupState by viewModel.backupState.collectAsStateWithLifecycle()
            val joinApprovalFocusTick by viewModel.joinApprovalFocusTick.collectAsStateWithLifecycle()
            val openGroupTabTick by viewModel.openGroupTabTick.collectAsStateWithLifecycle()
            val pendingExternalShareUris by viewModel.pendingExternalShareUris.collectAsStateWithLifecycle()
            val storageSpaceInfo by viewModel.storageSpaceInfo.collectAsStateWithLifecycle()
            val tempCacheBytes by viewModel.tempCacheBytes.collectAsStateWithLifecycle()
            val accountAuthState by viewModel.accountAuthState.collectAsStateWithLifecycle()
            val cloudDevicesState by viewModel.cloudDevicesState.collectAsStateWithLifecycle()
            val cloudTransfersState by viewModel.cloudTransfersState.collectAsStateWithLifecycle()
            val cloudSyncBusy by viewModel.cloudSyncBusy.collectAsStateWithLifecycle()

            val snackbarHostState = remember { SnackbarHostState() }

            LaunchedEffect(Unit) {
                viewModel.uiMessages.collect { msg ->
                    snackbarHostState.showSnackbar(msg)
                }
            }

            LaunchedEffect(Unit) {
                viewModel.languageChangeRequested.collect {
                    recreate()
                }
            }

            val layoutDir = if (LocaleHelper.isRtl(settings.languageCode)) {
                LayoutDirection.Rtl
            } else {
                LayoutDirection.Ltr
            }

            CompositionLocalProvider(LocalLayoutDirection provides layoutDir) {
                NexoDropTheme(
                    themeMode = settings.themeMode,
                    dynamicColor = settings.dynamicColor
                ) {
                    if (!settings.onboardingCompleted) {
                        OnboardingScreen(
                            permissions = permissions,
                            onRefreshPermissions = { viewModel.refreshPermissions() },
                            onContinue = { viewModel.setOnboardingCompleted() }
                        )
                    } else {
                        MainAdaptiveScaffold(
                            viewModel = viewModel,
                            snackbarHostState = snackbarHostState,
                            settings = settings,
                            groupState = groupState,
                            transferRecords = transferRecords,
                            vaultItems = vaultItems,
                            hiddenApps = hiddenApps,
                            installedApps = installedApps,
                            deviceFiles = deviceFiles,
                            storageRoots = storageRoots,
                            currentFolderPath = currentFolderPath,
                            currentFolderFiles = currentFolderFiles,
                            vaultUnlocked = vaultUnlocked,
                            permissions = permissions,
                            backupState = backupState,
                            joinApprovalFocusTick = joinApprovalFocusTick,
                            openGroupTabTick = openGroupTabTick,
                            pendingExternalShareUris = pendingExternalShareUris,
                            storageSpaceInfo = storageSpaceInfo,
                            tempCacheBytes = tempCacheBytes,
                            accountAuthState = accountAuthState,
                            cloudDevicesState = cloudDevicesState,
                            cloudTransfersState = cloudTransfersState,
                            cloudSyncBusy = cloudSyncBusy
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshPermissions()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MainAdaptiveScaffold(
    viewModel: MainViewModel,
    snackbarHostState: SnackbarHostState,
    settings: com.example.data.UserSettings,
    groupState: com.example.p2p.GroupWorkspaceState,
    transferRecords: List<com.example.data.TransferRecordEntity>,
    vaultItems: List<com.example.data.VaultItemEntity>,
    hiddenApps: List<com.example.data.HiddenAppEntity>,
    installedApps: List<com.example.data.InstalledAppItem>,
    deviceFiles: List<com.example.data.DeviceFileItem>,
    storageRoots: List<com.example.data.StorageRootInfo>,
    currentFolderPath: String,
    currentFolderFiles: List<com.example.data.DeviceFileItem>,
    vaultUnlocked: Boolean,
    permissions: com.example.util.PermissionSnapshot,
    backupState: BackupUiState,
    joinApprovalFocusTick: Int,
    openGroupTabTick: Int,
    pendingExternalShareUris: List<Uri>,
    storageSpaceInfo: com.example.data.StorageSpaceInfo?,
    tempCacheBytes: Long,
    accountAuthState: AccountAuthState,
    cloudDevicesState: CloudSyncUiState<List<RegisteredDevice>>,
    cloudTransfersState: CloudSyncUiState<List<SyncedTransferRecord>>,
    cloudSyncBusy: Boolean
) {
    val activityContext = LocalContext.current
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: ROUTE_GROUP

    val destinations = remember {
        listOf(
            NavDest(ROUTE_GROUP, R.string.nav_group, Icons.Rounded.Groups, Icons.Outlined.Groups, "nav_group"),
            NavDest(ROUTE_EXPLORER, R.string.nav_explorer, Icons.Rounded.Folder, Icons.Outlined.Folder, "nav_explorer"),
            NavDest(ROUTE_VAULT, R.string.nav_vault, Icons.Rounded.Lock, Icons.Outlined.Lock, "nav_vault"),
            NavDest(ROUTE_REPORTS, R.string.nav_reports, Icons.Rounded.Assessment, Icons.Outlined.Assessment, "nav_reports"),
            NavDest(ROUTE_SETTINGS, R.string.nav_settings, Icons.Rounded.Settings, Icons.Outlined.Settings, "nav_settings")
        )
    }

    val navigateTab: (String) -> Unit = { route ->
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    LaunchedEffect(joinApprovalFocusTick, openGroupTabTick) {
        if (joinApprovalFocusTick > 0 || openGroupTabTick > 0) {
            navigateTab(ROUTE_GROUP)
        }
    }

    val isGroupActive = groupState.phase == ConnectionPhase.HOSTING || groupState.phase == ConnectionPhase.CONNECTED
    val openBackupCenter: () -> Unit = {
        navController.navigate(ROUTE_BACKUP) {
            launchSingleTop = true
        }
    }

    val isImeVisible = WindowInsets.isImeVisible

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 600.dp

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                if (!useRail && !isImeVisible) {
                    NavigationBar(
                        windowInsets = WindowInsets.navigationBars
                    ) {
                        destinations.forEach { dest ->
                            val selected = currentRoute == dest.route
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navigateTab(dest.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                        contentDescription = stringResource(dest.labelRes)
                                    )
                                },
                                label = { Text(stringResource(dest.labelRes), maxLines = 1) },
                                modifier = Modifier.testTag(dest.testTag)
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding)
            ) {
                if (useRail) {
                    NavigationRail(
                        modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)
                    ) {
                        destinations.forEach { dest ->
                            val selected = currentRoute == dest.route
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateTab(dest.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                        contentDescription = stringResource(dest.labelRes)
                                    )
                                },
                                label = { Text(stringResource(dest.labelRes), maxLines = 1) },
                                modifier = Modifier.testTag(dest.testTag)
                            )
                        }
                    }
                }

                Column(modifier = Modifier.weight(1f).fillMaxSize()) {
                    AnimatedVisibility(
                        visible = currentRoute != ROUTE_GROUP &&
                            groupState.role == GroupRole.HOST &&
                            groupState.pendingRequests.isNotEmpty()
                    ) {
                        val firstReq = groupState.pendingRequests.firstOrNull()
                        if (firstReq != null) {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                                    .testTag("global_join_request_banner"),
                                shape = RoundedCornerShape(18.dp),
                                border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.NotificationsActive,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column {
                                            Text(
                                                text = stringResource(R.string.hub_global_join_alert, firstReq.deviceName),
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            if (groupState.pendingRequests.size > 1) {
                                                Text(
                                                    text = stringResource(R.string.hub_pending_requests, groupState.pendingRequests.size),
                                                    style = MaterialTheme.typography.labelSmall
                                                )
                                            }
                                        }
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        FilledTonalButton(
                                            onClick = { viewModel.respondJoinRequest(firstReq.requestId, false) },
                                            modifier = Modifier
                                                .height(48.dp)
                                                .testTag("global_join_reject_button")
                                        ) {
                                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.hub_reject), modifier = Modifier.size(18.dp))
                                        }
                                        Button(
                                            onClick = { viewModel.respondJoinRequest(firstReq.requestId, true) },
                                            modifier = Modifier
                                                .height(48.dp)
                                                .testTag("global_join_approve_button")
                                        ) {
                                            Icon(Icons.Rounded.Check, contentDescription = stringResource(R.string.hub_approve), modifier = Modifier.size(18.dp))
                                        }
                                        TextButton(
                                            onClick = { navigateTab(ROUTE_GROUP) },
                                            modifier = Modifier
                                                .height(48.dp)
                                                .testTag("global_join_view_group_button")
                                        ) {
                                            Text(stringResource(R.string.hub_view_group))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        NavHost(
                            navController = navController,
                            startDestination = ROUTE_GROUP
                        ) {
                        composable(ROUTE_GROUP) {
                            GroupHubScreen(
                                deviceName = settings.deviceName,
                                state = groupState,
                                permissions = permissions,
                                isWideScreen = useRail,
                                joinApprovalFocusTick = joinApprovalFocusTick,
                                pendingExternalShareCount = pendingExternalShareUris.size,
                                onSendPendingShare = { viewModel.sendPendingShareToGroup() },
                                onMovePendingShareToVault = { viewModel.movePendingShareToVault() },
                                onDismissPendingShare = { viewModel.dismissPendingShare() },
                                onRefreshPermissions = { viewModel.refreshPermissions() },
                                onStartScan = { viewModel.startDiscovery() },
                                onStopScan = { viewModel.stopDiscovery() },
                                onDiscoverWifiDirect = { viewModel.discoverWifiDirect() },
                                onConnectDirectPeer = { peer, pin ->
                                    viewModel.connectToDirectPeer(peer, pin)
                                },
                                onStartFallbackHotspot = { viewModel.startFallbackHotspot() },
                                onToggleLocalNetwork = { enabled ->
                                    viewModel.toggleLocalNetworkMode(enabled)
                                },
                                onToggleAutoReceive = { enabled ->
                                    viewModel.setAutoReceiveFiles(enabled)
                                },
                                onParseQr = { raw -> viewModel.parseScannedQr(raw) },
                                onJoinViaDirectCredentials = { ssid, pass, ip, port, name, pin, hostMac, groupId, frequency ->
                                    viewModel.joinViaDirectCredentials(ssid, pass, ip, port, name, pin, hostMac, groupId, frequency)
                                },
                                onCreateGroup = { name, pin, auto, web ->
                                    viewModel.createGroup(name, pin, auto, web)
                                },
                                onJoinGroup = { ip, port, name, pin ->
                                    viewModel.joinGroup(ip, port, name, pin)
                                },
                                onLeaveOrCloseGroup = { viewModel.closeOrLeaveGroup() },
                                onRespondJoinRequest = { reqId, approve ->
                                    viewModel.respondJoinRequest(reqId, approve)
                                },
                                onUpdatePeerPermission = { devId, perm ->
                                    viewModel.updatePeerPermission(devId, perm)
                                },
                                onKickOrBanPeer = { devId, ban ->
                                    viewModel.kickOrBanPeer(devId, ban)
                                },
                                onNavigateToExplorer = { navigateTab(ROUTE_EXPLORER) },
                                onOpenBackupCenter = openBackupCenter,
                                onAddPickedUris = { uris ->
                                    viewModel.shareSelectedToGroup(emptyList(), emptyList(), uris)
                                },
                                onDownloadItem = { item, toVault ->
                                    viewModel.downloadGroupItem(item, toVault)
                                },
                                onResendGroupItem = { item ->
                                    viewModel.resendGroupItem(item)
                                },
                                onPauseTransfer = { id -> viewModel.pauseTransfer(id) },
                                onResumeTransfer = { id -> viewModel.resumeTransfer(id) },
                                onCancelTransfer = { id -> viewModel.cancelTransfer(id) },
                                onSendChat = { text -> viewModel.sendChat(text) },
                                onOpenReceivedFile = { rf -> viewModel.openReceivedFile(rf) },
                                onMoveReceivedToVault = { rf -> viewModel.moveReceivedFileToVault(rf) },
                                onCopyOrMoveReceivedFile = { rf, uri, del ->
                                    viewModel.copyOrMoveReceivedFile(rf, uri, del)
                                },
                                onBatchMoveReceivedToVault = { list -> viewModel.moveReceivedFilesToVault(list) },
                                onBatchCopyOrMoveReceivedFiles = { list, uri, del ->
                                    viewModel.copyOrMoveReceivedFiles(list, uri, del)
                                },
                                onBatchDeleteReceivedFiles = { list -> viewModel.deleteReceivedFiles(list) },
                                onRenameReceivedFile = { rf, newName ->
                                    viewModel.renameReceivedFile(rf, newName)
                                },
                                onShareReceivedExternal = { rf ->
                                    viewModel.shareReceivedFileExternally(rf)
                                },
                                onReshareReceivedToGroup = { rf ->
                                    viewModel.reshareReceivedFileToGroup(rf)
                                },
                                onDeleteReceivedFile = { rf -> viewModel.deleteReceivedFile(rf) },
                                onClearError = { viewModel.p2pManager.clearError() }
                            )
                        }

                        composable(ROUTE_EXPLORER) {
                            ExplorerScreen(
                                installedApps = installedApps,
                                hiddenApps = hiddenApps,
                                deviceFiles = deviceFiles,
                                storageRoots = storageRoots,
                                currentFolderPath = currentFolderPath,
                                currentFolderFiles = currentFolderFiles,
                                storageSpaceInfo = storageSpaceInfo,
                                allFilesGranted = permissions.allFilesGranted,
                                isGroupActive = isGroupActive,
                                isWideScreen = useRail,
                                onRefreshApps = { includeSys -> viewModel.refreshStorageAndApps(includeSys) },
                                onRefreshPermissions = { viewModel.refreshPermissions() },
                                onOpenDirectory = { path -> viewModel.openDirectory(path) },
                                onNavigateUpDirectory = { viewModel.navigateUpDirectory() },
                                onCreateNoMedia = { viewModel.createNoMediaInCurrentFolder() },
                                onShareFolderToGroup = { path ->
                                    viewModel.shareFolderToGroup(path)
                                    navigateTab(ROUTE_GROUP)
                                },
                                onShareToGroup = { apps, files, uris ->
                                    viewModel.shareSelectedToGroup(apps, files, uris)
                                    navigateTab(ROUTE_GROUP)
                                },
                                onHideFilesInVault = { files, uris ->
                                    viewModel.hideFilesInVault(files, uris)
                                },
                                onHideApps = { apps -> viewModel.hideInstalledApps(apps) },
                                onOpenBackupCenter = openBackupCenter
                            )
                        }

                        composable(ROUTE_VAULT) {
                            VaultScreen(
                                settings = settings,
                                vaultUnlocked = vaultUnlocked,
                                vaultItems = vaultItems,
                                hiddenApps = hiddenApps,
                                isWideScreen = useRail,
                                onSetupPin = { pin -> viewModel.setupVaultPin(pin) },
                                onVerifyPin = { pin -> viewModel.unlockVaultWithPin(pin) },
                                onBiometricUnlock = { viewModel.unlockVaultWithBiometrics() },
                                onLockVault = { viewModel.lockVault() },
                                onImportUris = { uris -> viewModel.hideFilesInVault(emptyList(), uris) },
                                onOpenItem = { item -> viewModel.openVaultItem(item) },
                                onRestoreItem = { item -> viewModel.exportVaultItemToStorage(item) },
                                onDeleteItem = { item -> viewModel.deleteVaultItem(item) },
                                onUnhideApp = { pkg -> viewModel.unhideApp(pkg) },
                                onOpenBackupCenter = openBackupCenter
                            )
                        }

                        composable(ROUTE_REPORTS) {
                            ReportsScreen(
                                records = transferRecords,
                                isWideScreen = useRail,
                                onExportCsv = { list -> viewModel.exportCsvReport(list) },
                                onExportPdf = { list -> viewModel.exportPdfReport(list) },
                                onClearHistory = { viewModel.clearHistory() }
                            )
                        }

                        composable(ROUTE_SETTINGS) {
                            SettingsScreen(
                                settings = settings,
                                permissions = permissions,
                                tempCacheBytes = tempCacheBytes,
                                isWideScreen = useRail,
                                accountAuthState = accountAuthState,
                                cloudDevicesState = cloudDevicesState,
                                cloudTransfersState = cloudTransfersState,
                                cloudSyncBusy = cloudSyncBusy,
                                onSignInGoogle = { ctx -> viewModel.signInWithGoogle(ctx) },
                                onSignOutGoogle = { viewModel.signOutGoogle() },
                                onSyncCloudNow = { viewModel.syncCloudNow(silent = false) },
                                onApplyCloudPrefs = { viewModel.applyCloudPreferences() },
                                onRemoveCloudDevice = { devId -> viewModel.removeCloudDevice(devId) },
                                onSetDeviceName = { viewModel.setDeviceName(it) },
                                onSetLanguage = { viewModel.setLanguage(it) },
                                onSetSaveMode = { viewModel.setSaveMode(it) },
                                onSetCustomTree = { viewModel.setCustomSaveTree(it) },
                                onSetThemeMode = { viewModel.setThemeMode(it) },
                                onSetStripExif = { viewModel.setStripExif(it) },
                                onSetBiometric = { viewModel.setBiometricEnabled(it) },
                                onSetSilentNotif = { viewModel.setSilentNotifications(it) },
                                onSetCompleteOnlyNotif = { viewModel.setNotifyCompleteOnly(it) },
                                onRefreshPermissions = { viewModel.refreshPermissions() },
                                onClearTempCache = { viewModel.clearTempCache() },
                                onOpenBackupCenter = openBackupCenter
                            )
                        }

                        composable(ROUTE_BACKUP) {
                            BackupScreen(
                                backupState = backupState,
                                installedApps = installedApps,
                                deviceFiles = deviceFiles,
                                storageRoots = storageRoots,
                                currentFolderPath = currentFolderPath,
                                isGroupActive = isGroupActive,
                                isWideScreen = useRail,
                                onNavigateBack = { navController.popBackStack() },
                                onRefreshApps = { includeSys -> viewModel.refreshStorageAndApps(includeSys) },
                                onOpenDirectory = { path -> viewModel.openDirectory(path) },
                                onBackupApps = { apps, zip, treeUri ->
                                    viewModel.backupApps(apps, zip, treeUri)
                                },
                                onBackupFiles = { folder, files, zip, inc, treeUri ->
                                    viewModel.backupFiles(folder, files, zip, inc, treeUri)
                                },
                                onExportEncrypted = { pass, treeUri ->
                                    viewModel.exportEncryptedBackup(pass, treeUri)
                                },
                                onRestoreEncrypted = { uri, pass ->
                                    viewModel.restoreEncryptedBackup(uri, pass)
                                },
                                onShareBackup = { res -> viewModel.shareBackupResult(res) },
                                onSendBackupToGroup = { res ->
                                    viewModel.sendBackupToGroup(res)
                                    navigateTab(ROUTE_GROUP)
                                },
                                onClearBackupStatus = { viewModel.clearBackupStatus() }
                            )
                        }
                    }
                    }
                }
            }
        }
    }
}
