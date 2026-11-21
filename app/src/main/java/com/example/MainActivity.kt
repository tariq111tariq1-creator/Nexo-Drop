package com.example

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Assessment
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Assessment
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Settings
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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

    private fun handleNotificationIntent(intent: Intent?) {
        if (intent?.action == TransferForegroundService.ACTION_OPEN_JOIN_REQUESTS) {
            viewModel.triggerFocusJoinApproval()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleNotificationIntent(intent)

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
                            joinApprovalFocusTick = joinApprovalFocusTick
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
    joinApprovalFocusTick: Int
) {
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

    LaunchedEffect(joinApprovalFocusTick) {
        if (joinApprovalFocusTick > 0) {
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

                Box(modifier = Modifier.weight(1f)) {
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
                                onJoinViaDirectCredentials = { ssid, pass, ip, port, name, pin, hostMac ->
                                    viewModel.joinViaDirectCredentials(ssid, pass, ip, port, name, pin, hostMac)
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
                                onCancelTransfer = { id -> viewModel.cancelTransfer(id) },
                                onSendChat = { text -> viewModel.sendChat(text) },
                                onOpenReceivedFile = { rf -> viewModel.openReceivedFile(rf) },
                                onMoveReceivedToVault = { rf -> viewModel.moveReceivedFileToVault(rf) },
                                onCopyOrMoveReceivedFile = { rf, uri, del ->
                                    viewModel.copyOrMoveReceivedFile(rf, uri, del)
                                },
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
                                allFilesGranted = permissions.allFilesGranted,
                                isGroupActive = isGroupActive,
                                isWideScreen = useRail,
                                onRefreshApps = { includeSys -> viewModel.refreshStorageAndApps(includeSys) },
                                onRefreshPermissions = { viewModel.refreshPermissions() },
                                onOpenDirectory = { path -> viewModel.openDirectory(path) },
                                onNavigateUpDirectory = { viewModel.navigateUpDirectory() },
                                onCreateNoMedia = { viewModel.createNoMediaInCurrentFolder() },
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
                                isWideScreen = useRail,
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
