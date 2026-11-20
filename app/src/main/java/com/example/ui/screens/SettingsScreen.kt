package com.example.ui.screens

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.SaveLocationMode
import com.example.data.UserSettings
import com.example.ui.theme.ThemeMode
import com.example.util.LocaleHelper
import com.example.util.PermissionSnapshot
import com.example.util.PermissionsHelper

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: UserSettings,
    permissions: PermissionSnapshot,
    isWideScreen: Boolean,
    onSetDeviceName: (String) -> Unit,
    onSetLanguage: (String) -> Unit,
    onSetSaveMode: (SaveLocationMode) -> Unit,
    onSetCustomTree: (Uri) -> Unit,
    onSetThemeMode: (ThemeMode) -> Unit,
    onSetStripExif: (Boolean) -> Unit,
    onSetBiometric: (Boolean) -> Unit,
    onSetSilentNotif: (Boolean) -> Unit,
    onSetCompleteOnlyNotif: (Boolean) -> Unit,
    onRefreshPermissions: () -> Unit
) {
    val context = LocalContext.current
    var deviceNameDraft by remember(settings.deviceName) { mutableStateOf(settings.deviceName) }
    var langMenuOpen by remember { mutableStateOf(false) }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
            onSetCustomTree(uri)
        }
    }

    val runtimePermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        onRefreshPermissions()
    }

    val settingsIntentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        onRefreshPermissions()
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = if (isWideScreen) 840.dp else 640.dp)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Text(
                    text = stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.headlineMedium
                )
            }

            // 1. Device Name
            item {
                SectionCard(title = stringResource(R.string.settings_device_section)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = deviceNameDraft,
                            onValueChange = { deviceNameDraft = it },
                            label = { Text(stringResource(R.string.settings_device_name)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = { onSetDeviceName(deviceNameDraft) },
                            modifier = Modifier.height(52.dp)
                        ) {
                            Text(stringResource(R.string.hub_confirm))
                        }
                    }
                }
            }

            // 2. 10-Language Selector
            item {
                SectionCard(title = stringResource(R.string.settings_language_section)) {
                    val activeLang = LocaleHelper.supportedLanguages.firstOrNull {
                        it.code == settings.languageCode
                    }?.nativeName ?: "System / Default"

                    Box {
                        OutlinedButton(
                            onClick = { langMenuOpen = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag("language_picker_button")
                        ) {
                            Icon(Icons.Rounded.Language, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("${stringResource(R.string.settings_language_label)}: $activeLang")
                        }
                        DropdownMenu(
                            expanded = langMenuOpen,
                            onDismissRequest = { langMenuOpen = false }
                        ) {
                            LocaleHelper.supportedLanguages.forEach { lang ->
                                DropdownMenuItem(
                                    text = { Text(lang.nativeName) },
                                    onClick = {
                                        langMenuOpen = false
                                        onSetLanguage(lang.code)
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // 3. Save Location (Internal, External SD Card, Smart Category, or Private Vault)
            item {
                SectionCard(title = stringResource(R.string.settings_storage_section)) {
                    SaveModeOptionRow(
                        label = stringResource(R.string.settings_save_mode_downloads),
                        selected = settings.saveMode == SaveLocationMode.APP_DOWNLOADS,
                        onClick = { onSetSaveMode(SaveLocationMode.APP_DOWNLOADS) }
                    )
                    SaveModeOptionRow(
                        label = stringResource(R.string.settings_save_mode_smart),
                        selected = settings.saveMode == SaveLocationMode.CATEGORY_SMART,
                        onClick = { onSetSaveMode(SaveLocationMode.CATEGORY_SMART) }
                    )
                    SaveModeOptionRow(
                        label = stringResource(R.string.settings_save_mode_custom),
                        selected = settings.saveMode == SaveLocationMode.CUSTOM_TREE,
                        onClick = { onSetSaveMode(SaveLocationMode.CUSTOM_TREE) }
                    )
                    SaveModeOptionRow(
                        label = stringResource(R.string.settings_save_mode_vault),
                        selected = settings.saveMode == SaveLocationMode.PRIVATE_VAULT,
                        onClick = { onSetSaveMode(SaveLocationMode.PRIVATE_VAULT) }
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { folderPickerLauncher.launch(null) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("pick_custom_folder_button")
                    ) {
                        Icon(Icons.Rounded.FolderOpen, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.settings_pick_folder))
                    }
                    if (settings.customSaveLabel.isNotBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.settings_current_folder, settings.customSaveLabel),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // 4. Theme & Dark Mode
            item {
                SectionCard(title = stringResource(R.string.settings_theme_section)) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = settings.themeMode == ThemeMode.SYSTEM,
                            onClick = { onSetThemeMode(ThemeMode.SYSTEM) },
                            label = { Text(stringResource(R.string.settings_theme_system)) }
                        )
                        FilterChip(
                            selected = settings.themeMode == ThemeMode.LIGHT,
                            onClick = { onSetThemeMode(ThemeMode.LIGHT) },
                            label = { Text(stringResource(R.string.settings_theme_light)) }
                        )
                        FilterChip(
                            selected = settings.themeMode == ThemeMode.DARK,
                            onClick = { onSetThemeMode(ThemeMode.DARK) },
                            label = { Text(stringResource(R.string.settings_theme_dark)) }
                        )
                        FilterChip(
                            selected = settings.themeMode == ThemeMode.AMOLED,
                            onClick = { onSetThemeMode(ThemeMode.AMOLED) },
                            label = { Text(stringResource(R.string.settings_theme_amoled)) }
                        )
                    }
                }
            }

            // 5. Security & Privacy + Smart Notifications
            item {
                SectionCard(title = stringResource(R.string.settings_security_section)) {
                    SwitchSettingRow(
                        title = stringResource(R.string.settings_strip_exif),
                        checked = settings.stripExifOnSend,
                        onCheckedChange = onSetStripExif
                    )
                    SwitchSettingRow(
                        title = stringResource(R.string.settings_biometric_lock),
                        checked = settings.biometricEnabled,
                        onCheckedChange = onSetBiometric
                    )
                }
            }

            item {
                SectionCard(title = stringResource(R.string.settings_notifications_section)) {
                    SwitchSettingRow(
                        title = stringResource(R.string.settings_silent_notif),
                        checked = settings.silentNotifications,
                        onCheckedChange = onSetSilentNotif
                    )
                    SwitchSettingRow(
                        title = stringResource(R.string.settings_complete_only_notif),
                        checked = settings.notifyCompleteOnly,
                        onCheckedChange = onSetCompleteOnlyNotif
                    )
                }
            }

            // 6. System Permissions Center
            item {
                SectionCard(title = stringResource(R.string.settings_permissions_section)) {
                    PermissionStatusRow(
                        title = stringResource(R.string.perm_nearby),
                        granted = permissions.nearbyWifiGranted,
                        onGrant = { runtimePermLauncher.launch(PermissionsHelper.getRuntimeDiscoveryPermissions()) }
                    )
                    PermissionStatusRow(
                        title = stringResource(R.string.perm_location),
                        granted = permissions.locationGranted,
                        onGrant = { runtimePermLauncher.launch(PermissionsHelper.getRuntimeDiscoveryPermissions()) }
                    )
                    PermissionStatusRow(
                        title = stringResource(R.string.perm_wifi_service),
                        granted = permissions.wifiEnabled,
                        onGrant = { settingsIntentLauncher.launch(PermissionsHelper.buildWifiSettingsIntent()) }
                    )
                    PermissionStatusRow(
                        title = stringResource(R.string.perm_location_service),
                        granted = permissions.locationServiceEnabled,
                        onGrant = { settingsIntentLauncher.launch(PermissionsHelper.buildLocationSettingsIntent()) }
                    )
                    PermissionStatusRow(
                        title = stringResource(R.string.perm_camera),
                        granted = permissions.cameraGranted,
                        onGrant = { runtimePermLauncher.launch(arrayOf(android.Manifest.permission.CAMERA)) }
                    )
                    PermissionStatusRow(
                        title = stringResource(R.string.perm_bluetooth),
                        granted = permissions.bluetoothGranted,
                        onGrant = { runtimePermLauncher.launch(PermissionsHelper.getRuntimeDiscoveryPermissions()) }
                    )
                    PermissionStatusRow(
                        title = stringResource(R.string.perm_notifications),
                        granted = permissions.notificationsGranted,
                        onGrant = { runtimePermLauncher.launch(PermissionsHelper.getRuntimeDiscoveryPermissions()) }
                    )
                    PermissionStatusRow(
                        title = stringResource(R.string.perm_all_files),
                        granted = permissions.allFilesGranted,
                        onGrant = { settingsIntentLauncher.launch(PermissionsHelper.buildAllFilesSettingsIntent(context)) }
                    )
                    PermissionStatusRow(
                        title = stringResource(R.string.perm_install_apk),
                        granted = permissions.installPackagesGranted,
                        onGrant = { settingsIntentLauncher.launch(PermissionsHelper.buildInstallUnknownAppsIntent(context)) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun SaveModeOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SwitchSettingRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun PermissionStatusRow(
    title: String,
    granted: Boolean,
    onGrant: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (granted) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.perm_granted),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        } else {
            OutlinedButton(
                onClick = onGrant,
                modifier = Modifier.height(48.dp)
            ) {
                Text(stringResource(R.string.perm_missing))
            }
        }
    }
}
