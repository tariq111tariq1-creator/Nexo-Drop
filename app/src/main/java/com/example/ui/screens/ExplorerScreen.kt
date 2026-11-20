package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.DeviceFileItem
import com.example.data.FileCategory
import com.example.data.HiddenAppEntity
import com.example.data.InstalledAppItem
import com.example.data.StorageRootInfo
import com.example.ui.components.AppIconThumb
import com.example.ui.components.CategoryBadge
import com.example.util.Formatters
import com.example.util.PermissionsHelper

private enum class ExplorerSort { NAME, SIZE, DATE }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExplorerScreen(
    installedApps: List<InstalledAppItem>,
    hiddenApps: List<HiddenAppEntity>,
    deviceFiles: List<DeviceFileItem>,
    storageRoots: List<StorageRootInfo>,
    currentFolderPath: String,
    currentFolderFiles: List<DeviceFileItem>,
    allFilesGranted: Boolean,
    isGroupActive: Boolean,
    isWideScreen: Boolean,
    onRefreshApps: (includeSystem: Boolean) -> Unit,
    onRefreshPermissions: () -> Unit,
    onOpenDirectory: (String) -> Unit,
    onNavigateUpDirectory: () -> Boolean,
    onCreateNoMedia: () -> Unit,
    onShareToGroup: (List<InstalledAppItem>, List<DeviceFileItem>, List<Uri>) -> Unit,
    onHideFilesInVault: (List<DeviceFileItem>, List<Uri>) -> Unit,
    onHideApps: (List<InstalledAppItem>) -> Unit
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<FileCategory?>(null) }
    var sortMode by remember { mutableStateOf(ExplorerSort.DATE) }
    var showSystemApps by remember { mutableStateOf(false) }

    var selectedAppPackages by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedFileIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty() && isGroupActive) {
            onShareToGroup(emptyList(), emptyList(), uris)
        } else if (uris.isNotEmpty()) {
            onHideFilesInVault(emptyList(), uris)
        }
    }

    val systemFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty() && isGroupActive) {
            onShareToGroup(emptyList(), emptyList(), uris)
        } else if (uris.isNotEmpty()) {
            onHideFilesInVault(emptyList(), uris)
        }
    }

    val allFilesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        onRefreshPermissions()
    }

    val hiddenPackageSet = remember(hiddenApps) { hiddenApps.map { it.packageName }.toSet() }

    val visibleApps = remember(installedApps, hiddenPackageSet, searchQuery, sortMode) {
        val q = searchQuery.trim().lowercase()
        val filtered = installedApps.filter { app ->
            !hiddenPackageSet.contains(app.packageName) &&
                (q.isEmpty() || app.label.lowercase().contains(q) || app.packageName.lowercase().contains(q))
        }
        when (sortMode) {
            ExplorerSort.NAME -> filtered.sortedBy { it.label.lowercase() }
            ExplorerSort.SIZE -> filtered.sortedByDescending { it.totalSize }
            ExplorerSort.DATE -> filtered.sortedByDescending { it.installedAt }
        }
    }

    val visibleMediaFiles = remember(deviceFiles, searchQuery, selectedCategory, sortMode) {
        val q = searchQuery.trim().lowercase()
        val filtered = deviceFiles.filter { file ->
            (selectedCategory == null || file.category == selectedCategory ||
                (selectedCategory == FileCategory.ARCHIVE && file.category == FileCategory.APP)) &&
                (q.isEmpty() || file.name.lowercase().contains(q) || file.mimeType.lowercase().contains(q))
        }
        when (sortMode) {
            ExplorerSort.NAME -> filtered.sortedBy { it.name.lowercase() }
            ExplorerSort.SIZE -> filtered.sortedByDescending { it.size }
            ExplorerSort.DATE -> filtered.sortedByDescending { it.modifiedAt }
        }
    }

    val totalSelectedCount = selectedAppPackages.size + selectedFileIds.size

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = if (isWideScreen) 980.dp else 640.dp)
                .fillMaxSize()
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    text = stringResource(R.string.explorer_title),
                    style = MaterialTheme.typography.headlineMedium
                )
                Spacer(modifier = Modifier.height(10.dp))

                // Advanced Search Field
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text(stringResource(R.string.explorer_search_hint)) },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Rounded.Close, contentDescription = null)
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("explorer_search_input")
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Quick Sort + Pickers Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AssistChip(
                        onClick = {
                            photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                        },
                        label = { Text(stringResource(R.string.explorer_pick_photos)) },
                        leadingIcon = { Icon(Icons.Rounded.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )
                    AssistChip(
                        onClick = { systemFilePicker.launch(arrayOf("*/*")) },
                        label = { Text(stringResource(R.string.explorer_pick_files)) },
                        leadingIcon = { Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )
                    AssistChip(
                        onClick = {
                            sortMode = when (sortMode) {
                                ExplorerSort.DATE -> ExplorerSort.NAME
                                ExplorerSort.NAME -> ExplorerSort.SIZE
                                ExplorerSort.SIZE -> ExplorerSort.DATE
                            }
                        },
                        label = {
                            val label = when (sortMode) {
                                ExplorerSort.NAME -> stringResource(R.string.explorer_sort_name)
                                ExplorerSort.SIZE -> stringResource(R.string.explorer_sort_size)
                                ExplorerSort.DATE -> stringResource(R.string.explorer_sort_date)
                            }
                            Text(label)
                        },
                        leadingIcon = { Icon(Icons.Rounded.FilterList, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )
                }
            }

            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text(stringResource(R.string.explorer_tab_apps)) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text(stringResource(R.string.explorer_tab_media)) }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text(stringResource(R.string.explorer_tab_folders)) }
                )
            }

            // Selection Action Bar
            AnimatedVisibility(visible = totalSelectedCount > 0) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.explorer_selected_count, totalSelectedCount),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.align(Alignment.CenterVertically)
                        )
                        Button(
                            onClick = {
                                val apps = visibleApps.filter { selectedAppPackages.contains(it.packageName) }
                                val files = (visibleMediaFiles + currentFolderFiles)
                                    .distinctBy { it.id }
                                    .filter { selectedFileIds.contains(it.id) }
                                onShareToGroup(apps, files, emptyList())
                                selectedAppPackages = emptySet()
                                selectedFileIds = emptySet()
                            },
                            enabled = isGroupActive,
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("explorer_share_group_button")
                        ) {
                            Icon(Icons.Rounded.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.explorer_share_to_group))
                        }

                        if (selectedFileIds.isNotEmpty()) {
                            OutlinedButton(
                                onClick = {
                                    val files = (visibleMediaFiles + currentFolderFiles)
                                        .distinctBy { it.id }
                                        .filter { selectedFileIds.contains(it.id) }
                                    onHideFilesInVault(files, emptyList())
                                    selectedFileIds = emptySet()
                                },
                                modifier = Modifier.height(48.dp)
                            ) {
                                Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.explorer_hide_in_vault))
                            }
                        }

                        if (selectedAppPackages.isNotEmpty()) {
                            OutlinedButton(
                                onClick = {
                                    val apps = visibleApps.filter { selectedAppPackages.contains(it.packageName) }
                                    onHideApps(apps)
                                    selectedAppPackages = emptySet()
                                },
                                modifier = Modifier.height(48.dp)
                            ) {
                                Icon(Icons.Rounded.VisibilityOff, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.explorer_hide_app))
                            }
                        }

                        TextButton(onClick = {
                            selectedAppPackages = emptySet()
                            selectedFileIds = emptySet()
                        }) {
                            Text(stringResource(R.string.hub_cancel))
                        }
                    }
                }
            }

            when (selectedTab) {
                0 -> {
                    // Installed Apps Tab
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = showSystemApps,
                                onCheckedChange = {
                                    showSystemApps = it
                                    onRefreshApps(it)
                                }
                            )
                            Text(stringResource(R.string.explorer_show_system_apps), style = MaterialTheme.typography.bodyMedium)
                        }
                        TextButton(onClick = {
                            selectedAppPackages = visibleApps.filterNot { it.isSystemApp }.map { it.packageName }.toSet()
                        }) {
                            Icon(Icons.Rounded.SelectAll, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.explorer_phone_clone))
                        }
                    }

                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (visibleApps.isEmpty()) {
                            item {
                                Text(
                                    text = stringResource(R.string.explorer_empty),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(24.dp)
                                )
                            }
                        } else {
                            items(visibleApps, key = { it.packageName }) { app ->
                                val isSelected = selectedAppPackages.contains(app.packageName)
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selectedAppPackages = if (isSelected) {
                                                selectedAppPackages - app.packageName
                                            } else {
                                                selectedAppPackages + app.packageName
                                            }
                                        },
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isSelected) {
                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                                        } else MaterialTheme.colorScheme.surface
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
                                            Text(
                                                text = app.label,
                                                style = MaterialTheme.typography.bodyLarge,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = "v${app.versionName} • ${Formatters.formatBytes(app.totalSize)}" +
                                                    if (app.isSplitApk) " • ${stringResource(R.string.explorer_split_apk_badge)}" else "",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Icon(
                                            imageVector = if (isSelected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                                            contentDescription = null,
                                            tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                1 -> {
                    // Device Files (MediaStore + Category Filter Chips)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = selectedCategory == null,
                            onClick = { selectedCategory = null },
                            label = { Text(stringResource(R.string.explorer_filter_all)) }
                        )
                        FilterChip(
                            selected = selectedCategory == FileCategory.IMAGE,
                            onClick = { selectedCategory = FileCategory.IMAGE },
                            label = { Text(stringResource(R.string.explorer_filter_images)) }
                        )
                        FilterChip(
                            selected = selectedCategory == FileCategory.VIDEO,
                            onClick = { selectedCategory = FileCategory.VIDEO },
                            label = { Text(stringResource(R.string.explorer_filter_video)) }
                        )
                        FilterChip(
                            selected = selectedCategory == FileCategory.AUDIO,
                            onClick = { selectedCategory = FileCategory.AUDIO },
                            label = { Text(stringResource(R.string.explorer_filter_audio)) }
                        )
                        FilterChip(
                            selected = selectedCategory == FileCategory.DOCUMENT,
                            onClick = { selectedCategory = FileCategory.DOCUMENT },
                            label = { Text(stringResource(R.string.explorer_filter_docs)) }
                        )
                        FilterChip(
                            selected = selectedCategory == FileCategory.ARCHIVE,
                            onClick = { selectedCategory = FileCategory.ARCHIVE },
                            label = { Text(stringResource(R.string.explorer_filter_archives)) }
                        )
                    }

                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (visibleMediaFiles.isEmpty()) {
                            item {
                                Text(
                                    text = stringResource(R.string.explorer_empty),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(24.dp)
                                )
                            }
                        } else {
                            items(visibleMediaFiles, key = { it.id }) { file ->
                                DeviceFileRow(
                                    file = file,
                                    isSelected = selectedFileIds.contains(file.id),
                                    onClick = {
                                        selectedFileIds = if (selectedFileIds.contains(file.id)) {
                                            selectedFileIds - file.id
                                        } else {
                                            selectedFileIds + file.id
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                else -> {
                    // Raw Folders Explorer (Internal & SD Card)
                    Column(modifier = Modifier.fillMaxSize()) {
                        if (!allFilesGranted) {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f)
                                )
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(
                                        text = stringResource(R.string.explorer_all_files_needed),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Button(
                                        onClick = {
                                            allFilesLauncher.launch(PermissionsHelper.buildAllFilesSettingsIntent(context))
                                        },
                                        modifier = Modifier.height(48.dp)
                                    ) {
                                        Text(stringResource(R.string.explorer_grant_all_files))
                                    }
                                }
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            storageRoots.forEach { root ->
                                FilterChip(
                                    selected = currentFolderPath.startsWith(root.path),
                                    onClick = { onOpenDirectory(root.path) },
                                    label = { Text(root.label) }
                                )
                            }
                            AssistChip(
                                onClick = { onNavigateUpDirectory() },
                                label = { Text("..") },
                                leadingIcon = { Icon(Icons.Rounded.ArrowUpward, contentDescription = null, modifier = Modifier.size(16.dp)) }
                            )
                            AssistChip(
                                onClick = onCreateNoMedia,
                                label = { Text(stringResource(R.string.explorer_create_nomedia)) }
                            )
                        }

                        Text(
                            text = currentFolderPath,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        val q = searchQuery.trim().lowercase()
                        val folderFiltered = remember(currentFolderFiles, q) {
                            if (q.isEmpty()) currentFolderFiles
                            else currentFolderFiles.filter { it.name.lowercase().contains(q) }
                        }

                        LazyColumn(
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(folderFiltered, key = { it.id }) { file ->
                                DeviceFileRow(
                                    file = file,
                                    isSelected = selectedFileIds.contains(file.id),
                                    onClick = {
                                        if (file.isDirectory && file.filePath != null) {
                                            onOpenDirectory(file.filePath)
                                        } else {
                                            selectedFileIds = if (selectedFileIds.contains(file.id)) {
                                                selectedFileIds - file.id
                                            } else {
                                                selectedFileIds + file.id
                                            }
                                        }
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

@Composable
private fun DeviceFileRow(
    file: DeviceFileItem,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
            } else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CategoryBadge(category = file.category, isDirectory = file.isDirectory)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (file.isDirectory) "${file.size} items" else "${Formatters.formatBytes(file.size)} • ${Formatters.formatTimestamp(file.modifiedAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!file.isDirectory) {
                Icon(
                    imageVector = if (isSelected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}
