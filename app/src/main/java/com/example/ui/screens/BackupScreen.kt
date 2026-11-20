package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.BackupUiState
import com.example.R
import com.example.data.BackupResult
import com.example.data.DeviceFileItem
import com.example.data.InstalledAppItem
import com.example.data.StorageRootInfo
import com.example.ui.components.AppIconThumb
import com.example.util.Formatters

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BackupScreen(
    backupState: BackupUiState,
    installedApps: List<InstalledAppItem>,
    deviceFiles: List<DeviceFileItem>,
    storageRoots: List<StorageRootInfo>,
    currentFolderPath: String,
    isGroupActive: Boolean,
    isWideScreen: Boolean,
    onNavigateBack: () -> Unit,
    onRefreshApps: (Boolean) -> Unit,
    onOpenDirectory: (String) -> Unit,
    onBackupApps: (List<InstalledAppItem>, compressZip: Boolean, targetTreeUri: Uri?) -> Unit,
    onBackupFiles: (sourceFolderPath: String?, files: List<DeviceFileItem>, compressZip: Boolean, incremental: Boolean, targetTreeUri: Uri?) -> Unit,
    onExportEncrypted: (password: String, targetTreeUri: Uri?) -> Unit,
    onRestoreEncrypted: (backupUri: Uri, password: String) -> Unit,
    onShareBackup: (BackupResult) -> Unit,
    onSendBackupToGroup: (BackupResult) -> Unit,
    onClearBackupStatus: () -> Unit
) {
    BackHandler { onNavigateBack() }

    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }
    var targetTreeUri by remember { mutableStateOf<Uri?>(null) }
    var targetTreeLabel by remember { mutableStateOf("") }

    // Apps tab state
    var includeSystemApps by remember { mutableStateOf(false) }
    var appsCompressZip by remember { mutableStateOf(true) }
    var selectedPackages by remember { mutableStateOf<Set<String>>(emptySet()) }

    // Files tab state
    var filesCompressZip by remember { mutableStateOf(true) }
    var filesIncremental by remember { mutableStateOf(true) }
    var useCurrentFolderSource by remember { mutableStateOf(true) }
    var selectedFileIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    // Encrypted Vault & Data tab state
    var exportPassword by remember { mutableStateOf("") }
    var restorePassword by remember { mutableStateOf("") }
    var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }

    val destPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
            targetTreeUri = uri
            targetTreeLabel = uri.lastPathSegment ?: uri.toString()
        }
    }

    val restoreFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            pendingRestoreUri = uri
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = if (isWideScreen) 920.dp else 640.dp)
                .fillMaxSize()
        ) {
            TopAppBar(
                title = { Text(stringResource(R.string.backup_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.hub_cancel))
                    }
                }
            )

            // Destination Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(
                                R.string.backup_dest_label,
                                targetTreeLabel.ifBlank { stringResource(R.string.backup_dest_default) }
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = { destPicker.launch(null) },
                        modifier = Modifier.height(48.dp)
                    ) {
                        Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.backup_choose_folder))
                    }
                }
            }

            // Progress & Result Cards
            if (backupState.inProgress) {
                val p = backupState.progress
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val frac = if (p != null && p.total > 0) p.current.toFloat() / p.total.toFloat() else 0f
                        LinearProgressIndicator(
                            progress = { frac },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                        )
                        if (p != null) {
                            Text(
                                text = "${p.current} / ${p.total} • ${p.currentLabel}",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
            }

            backupState.lastBackupResult?.takeIf { it.success }?.let { res ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                    )
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(
                                    R.string.backup_success_msg,
                                    res.fileCount,
                                    Formatters.formatBytes(res.totalBytes)
                                ),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = onClearBackupStatus) {
                                Text(stringResource(R.string.hub_confirm))
                            }
                        }
                        if (res.skippedCount > 0) {
                            Text(
                                text = stringResource(R.string.backup_skipped_msg, res.skippedCount),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (res.outputUri != null) {
                                FilledTonalButton(
                                    onClick = { onShareBackup(res) },
                                    modifier = Modifier.height(48.dp)
                                ) {
                                    Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(stringResource(R.string.backup_share_external))
                                }
                            }
                            if (isGroupActive && (res.outputUri != null || res.outputPath != null)) {
                                Button(
                                    onClick = { onSendBackupToGroup(res) },
                                    modifier = Modifier.height(48.dp)
                                ) {
                                    Icon(Icons.Rounded.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(stringResource(R.string.backup_send_to_group))
                                }
                            }
                        }
                    }
                }
            }

            backupState.lastRestoreResult?.takeIf { it.success }?.let { r ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(
                                R.string.backup_restore_success,
                                r.vaultItemsRestored,
                                r.hiddenAppsRestored,
                                r.historyRestored
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = onClearBackupStatus) {
                            Text(stringResource(R.string.hub_confirm))
                        }
                    }
                }
            }

            TabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text(stringResource(R.string.backup_tab_apps)) })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text(stringResource(R.string.backup_tab_files)) })
                Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text(stringResource(R.string.backup_tab_vault)) })
            }

            when (selectedTab) {
                0 -> {
                    // Apps Backup Tab
                    Column(modifier = Modifier.fillMaxSize()) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = includeSystemApps,
                                        onCheckedChange = {
                                            includeSystemApps = it
                                            onRefreshApps(it)
                                        }
                                    )
                                    Text(stringResource(R.string.backup_include_system_apps), style = MaterialTheme.typography.bodySmall)
                                }
                                TextButton(onClick = {
                                    selectedPackages = if (selectedPackages.size == installedApps.size) {
                                        emptySet()
                                    } else {
                                        installedApps.map { it.packageName }.toSet()
                                    }
                                }) {
                                    Text(stringResource(R.string.backup_select_all))
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(stringResource(R.string.backup_zip_bundle), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                Switch(checked = appsCompressZip, onCheckedChange = { appsCompressZip = it })
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Button(
                                onClick = {
                                    val chosen = installedApps.filter { selectedPackages.contains(it.packageName) }
                                    onBackupApps(chosen, appsCompressZip, targetTreeUri)
                                },
                                enabled = selectedPackages.isNotEmpty() && !backupState.inProgress,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("backup_apps_start_button"),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Icon(Icons.Rounded.Backup, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.backup_start_apps, selectedPackages.size))
                            }
                        }

                        LazyColumn(
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(installedApps, key = { it.packageName }) { app ->
                                val isSel = selectedPackages.contains(app.packageName)
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selectedPackages = if (isSel) selectedPackages - app.packageName else selectedPackages + app.packageName
                                        },
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isSel) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                                        else MaterialTheme.colorScheme.surface
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        AppIconThumb(packageName = app.packageName, label = app.label)
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(app.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text("v${app.versionName} • ${Formatters.formatBytes(app.totalSize)}", style = MaterialTheme.typography.labelSmall)
                                        }
                                        Icon(
                                            imageVector = if (isSel) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                                            contentDescription = null,
                                            tint = if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                1 -> {
                    // Files & Folders Backup Tab
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        item {
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text(
                                        text = stringResource(R.string.backup_select_folder_source, currentFolderPath.ifBlank { "/" }),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        storageRoots.forEach { root ->
                                            FilterChip(
                                                selected = useCurrentFolderSource && currentFolderPath.startsWith(root.path),
                                                onClick = {
                                                    useCurrentFolderSource = true
                                                    onOpenDirectory(root.path)
                                                },
                                                label = { Text(root.label) }
                                            )
                                        }
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(stringResource(R.string.backup_zip_bundle), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                        Switch(checked = filesCompressZip, onCheckedChange = { filesCompressZip = it })
                                    }
                                    if (!filesCompressZip) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(stringResource(R.string.backup_incremental), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                            Switch(checked = filesIncremental, onCheckedChange = { filesIncremental = it })
                                        }
                                    }
                                    Button(
                                        onClick = {
                                            val picked = deviceFiles.filter { selectedFileIds.contains(it.id) }
                                            val folderSrc = if (picked.isEmpty() && useCurrentFolderSource) currentFolderPath else null
                                            onBackupFiles(folderSrc, picked, filesCompressZip, filesIncremental, targetTreeUri)
                                        },
                                        enabled = !backupState.inProgress && (selectedFileIds.isNotEmpty() || currentFolderPath.isNotBlank()),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(48.dp)
                                            .testTag("backup_files_start_button"),
                                        shape = RoundedCornerShape(14.dp)
                                    ) {
                                        Icon(Icons.Rounded.Backup, contentDescription = null)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(stringResource(R.string.backup_start_files))
                                    }
                                }
                            }
                        }

                        items(deviceFiles.take(150), key = { it.id }) { file ->
                            val isSel = selectedFileIds.contains(file.id)
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedFileIds = if (isSel) selectedFileIds - file.id else selectedFileIds + file.id
                                    },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSel) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                                    else MaterialTheme.colorScheme.surface
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(file.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(Formatters.formatBytes(file.size), style = MaterialTheme.typography.labelSmall)
                                    }
                                    Icon(
                                        imageVector = if (isSel) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                                        contentDescription = null,
                                        tint = if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                    )
                                }
                            }
                        }
                    }
                }

                else -> {
                    // Encrypted Vault & Full App Data Backup & Restore Tab
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        item {
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Rounded.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(stringResource(R.string.backup_tab_vault), style = MaterialTheme.typography.titleMedium)
                                    }
                                    OutlinedTextField(
                                        value = exportPassword,
                                        onValueChange = { exportPassword = it },
                                        label = { Text(stringResource(R.string.backup_password_label)) },
                                        visualTransformation = PasswordVisualTransformation(),
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                        singleLine = true,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("backup_export_password_input")
                                    )
                                    Button(
                                        onClick = { onExportEncrypted(exportPassword, targetTreeUri) },
                                        enabled = exportPassword.length >= 4 && !backupState.inProgress,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(48.dp)
                                            .testTag("backup_encrypted_export_button"),
                                        shape = RoundedCornerShape(14.dp)
                                    ) {
                                        Icon(Icons.Rounded.Backup, contentDescription = null)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(stringResource(R.string.backup_start_encrypted))
                                    }
                                }
                            }
                        }

                        item {
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Rounded.Restore, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(stringResource(R.string.backup_restore_title), style = MaterialTheme.typography.titleMedium)
                                    }
                                    OutlinedTextField(
                                        value = restorePassword,
                                        onValueChange = { restorePassword = it },
                                        label = { Text(stringResource(R.string.backup_password_label)) },
                                        visualTransformation = PasswordVisualTransformation(),
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    OutlinedButton(
                                        onClick = { restoreFilePicker.launch(arrayOf("*/*")) },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(48.dp)
                                    ) {
                                        Icon(Icons.Rounded.FolderOpen, contentDescription = null)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(stringResource(R.string.backup_restore_button))
                                    }
                                    pendingRestoreUri?.let { uri ->
                                        Button(
                                            onClick = {
                                                onRestoreEncrypted(uri, restorePassword)
                                                pendingRestoreUri = null
                                            },
                                            enabled = restorePassword.isNotBlank() && !backupState.inProgress,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(48.dp)
                                        ) {
                                            Text(stringResource(R.string.hub_confirm))
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
