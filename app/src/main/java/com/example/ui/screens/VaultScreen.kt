package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.example.R
import com.example.data.FileCategory
import com.example.data.HiddenAppEntity
import com.example.data.UserSettings
import com.example.data.VaultItemEntity
import com.example.ui.components.AppIconThumb
import com.example.ui.components.CategoryBadge
import com.example.util.Formatters

@Composable
fun VaultScreen(
    settings: UserSettings,
    vaultUnlocked: Boolean,
    vaultItems: List<VaultItemEntity>,
    hiddenApps: List<HiddenAppEntity>,
    isWideScreen: Boolean,
    onSetupPin: (String) -> Unit,
    onVerifyPin: (String) -> Boolean,
    onBiometricUnlock: () -> Unit,
    onLockVault: () -> Unit,
    onImportUris: (List<Uri>) -> Unit,
    onOpenItem: (VaultItemEntity) -> Unit,
    onRestoreItem: (VaultItemEntity) -> Unit,
    onDeleteItem: (VaultItemEntity) -> Unit,
    onUnhideApp: (String) -> Unit
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val hasPinConfigured = settings.vaultPinHash.isNotBlank()

    val importPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) onImportUris(uris)
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        if (!vaultUnlocked) {
            var pin by remember { mutableStateOf("") }
            var confirmPin by remember { mutableStateOf("") }
            var errorMsg by remember { mutableStateOf<String?>(null) }

            val canBiometric = remember(context, settings.biometricEnabled, hasPinConfigured) {
                hasPinConfigured && settings.biometricEnabled &&
                    BiometricManager.from(context).canAuthenticate(
                        BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
                    ) == BiometricManager.BIOMETRIC_SUCCESS
            }

            Column(
                modifier = Modifier
                    .widthIn(max = 460.dp)
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(56.dp)
                )
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = if (hasPinConfigured) stringResource(R.string.vault_locked_title)
                    else stringResource(R.string.vault_setup_pin_title),
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.vault_setup_pin_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(20.dp))

                OutlinedTextField(
                    value = pin,
                    onValueChange = { if (it.length <= 6) pin = it.filter { ch -> ch.isDigit() } },
                    label = { Text(stringResource(R.string.vault_enter_pin)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("vault_pin_input")
                )

                if (!hasPinConfigured) {
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = confirmPin,
                        onValueChange = { if (it.length <= 6) confirmPin = it.filter { ch -> ch.isDigit() } },
                        label = { Text(stringResource(R.string.vault_confirm_pin)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("vault_confirm_pin_input")
                    )
                }

                if (errorMsg != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = errorMsg!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                val mismatchStr = stringResource(R.string.vault_pin_mismatch)
                val wrongStr = stringResource(R.string.vault_wrong_pin)

                Button(
                    onClick = {
                        if (!hasPinConfigured) {
                            if (pin.length == 6 && pin == confirmPin) {
                                errorMsg = null
                                onSetupPin(pin)
                            } else {
                                errorMsg = mismatchStr
                            }
                        } else {
                            if (onVerifyPin(pin)) {
                                errorMsg = null
                            } else {
                                errorMsg = wrongStr
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("vault_unlock_button"),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Rounded.LockOpen, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.vault_unlock))
                }

                if (canBiometric && activity != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    val title = stringResource(R.string.vault_title)
                    val subtitle = stringResource(R.string.vault_unlock_biometric)
                    OutlinedButton(
                        onClick = {
                            val executor = ContextCompat.getMainExecutor(activity)
                            val prompt = BiometricPrompt(
                                activity,
                                executor,
                                object : BiometricPrompt.AuthenticationCallback() {
                                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                                        onBiometricUnlock()
                                    }
                                }
                            )
                            val info = BiometricPrompt.PromptInfo.Builder()
                                .setTitle(title)
                                .setSubtitle(subtitle)
                                .setAllowedAuthenticators(
                                    BiometricManager.Authenticators.BIOMETRIC_STRONG or
                                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
                                )
                                .build()
                            prompt.authenticate(info)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Rounded.Fingerprint, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.vault_unlock_biometric))
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .widthIn(max = if (isWideScreen) 960.dp else 640.dp)
                    .fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.vault_title),
                            style = MaterialTheme.typography.headlineMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(
                                onClick = { importPicker.launch(arrayOf("*/*")) },
                                modifier = Modifier.height(48.dp)
                            ) {
                                Icon(Icons.Rounded.Add, contentDescription = null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.vault_import_file))
                            }
                            OutlinedButton(
                                onClick = onLockVault,
                                modifier = Modifier.height(48.dp)
                            ) {
                                Icon(Icons.Rounded.Lock, contentDescription = null)
                            }
                        }
                    }
                }

                item {
                    Text(
                        text = stringResource(R.string.vault_hidden_files, vaultItems.size),
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                if (vaultItems.isEmpty()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Text(
                                text = stringResource(R.string.vault_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(20.dp)
                            )
                        }
                    }
                } else {
                    items(vaultItems, key = { it.id }) { item ->
                        val cat = runCatching { FileCategory.valueOf(item.category) }.getOrDefault(FileCategory.OTHER)
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CategoryBadge(category = cat)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.originalName,
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${Formatters.formatBytes(item.fileSize)} • AES-256",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                }
                                IconButton(onClick = { onOpenItem(item) }) {
                                    Icon(Icons.Rounded.Visibility, contentDescription = stringResource(R.string.vault_open))
                                }
                                IconButton(onClick = { onRestoreItem(item) }) {
                                    Icon(Icons.Rounded.Download, contentDescription = stringResource(R.string.vault_export))
                                }
                                IconButton(onClick = { onDeleteItem(item) }) {
                                    Icon(
                                        Icons.Rounded.Delete,
                                        contentDescription = stringResource(R.string.vault_delete),
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.vault_hidden_apps, hiddenApps.size),
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                items(hiddenApps, key = { it.packageName }) { app ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                AppIconThumb(packageName = app.packageName, label = app.appLabel)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(app.appLabel, style = MaterialTheme.typography.bodyLarge)
                                    Text(app.packageName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            OutlinedButton(
                                onClick = { onUnhideApp(app.packageName) },
                                modifier = Modifier.height(48.dp)
                            ) {
                                Text(stringResource(R.string.vault_unhide_app))
                            }
                        }
                    }
                }
            }
        }
    }
}
