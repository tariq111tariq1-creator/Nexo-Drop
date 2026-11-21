package com.example.ui.components

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.p2p.ReceivedGroupFile
import com.example.util.Formatters

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceivedFileActionsSheet(
    file: ReceivedGroupFile,
    onDismiss: () -> Unit,
    onOpenOrInstall: (ReceivedGroupFile) -> Unit,
    onMoveToVault: (ReceivedGroupFile) -> Unit,
    onCopyOrMoveToFolder: (ReceivedGroupFile, targetTreeUri: Uri, deleteOriginal: Boolean) -> Unit,
    onRename: (ReceivedGroupFile, newName: String) -> Unit,
    onShareExternal: (ReceivedGroupFile) -> Unit,
    onReshareToGroup: (ReceivedGroupFile) -> Unit,
    onDelete: (ReceivedGroupFile) -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDetailsDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var pendingMoveAfterPick by remember { mutableStateOf(false) }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
            onCopyOrMoveToFolder(file, uri, pendingMoveAfterPick)
            onDismiss()
        }
    }

    val isApk = remember(file.fileName) {
        val lower = file.fileName.lowercase()
        lower.endsWith(".apk") || lower.endsWith(".apks")
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag("received_file_actions_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                MediaThumbnailBadge(
                    uri = file.outputUri,
                    filePath = file.filePath,
                    fileName = file.fileName,
                    category = file.category,
                    size = 48.dp
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = file.fileName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${Formatters.formatBytes(file.fileSize)} • ${file.senderName} • ${file.savedLocation}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
            Spacer(modifier = Modifier.height(6.dp))

            SheetActionItem(
                icon = if (isApk) Icons.Rounded.InstallMobile else Icons.Rounded.OpenInNew,
                label = if (isApk) stringResource(R.string.action_install_app) else stringResource(R.string.action_open_file),
                testTag = "action_open_or_install",
                onClick = {
                    onOpenOrInstall(file)
                    onDismiss()
                }
            )

            if (!file.isInVault) {
                SheetActionItem(
                    icon = Icons.Rounded.Lock,
                    label = stringResource(R.string.action_move_to_vault),
                    testTag = "action_move_to_vault",
                    onClick = {
                        onMoveToVault(file)
                        onDismiss()
                    }
                )
                SheetActionItem(
                    icon = Icons.Rounded.ContentCopy,
                    label = stringResource(R.string.action_copy_to_folder),
                    testTag = "action_copy_to_folder",
                    onClick = {
                        pendingMoveAfterPick = false
                        folderPicker.launch(null)
                    }
                )
                SheetActionItem(
                    icon = Icons.Rounded.DriveFileMove,
                    label = stringResource(R.string.action_move_to_folder),
                    testTag = "action_move_to_folder",
                    onClick = {
                        pendingMoveAfterPick = true
                        folderPicker.launch(null)
                    }
                )
                SheetActionItem(
                    icon = Icons.Rounded.Edit,
                    label = stringResource(R.string.action_rename),
                    testTag = "action_rename_file",
                    onClick = { showRenameDialog = true }
                )
            }

            SheetActionItem(
                icon = Icons.Rounded.Share,
                label = stringResource(R.string.action_share_external),
                testTag = "action_share_external",
                onClick = {
                    onShareExternal(file)
                    onDismiss()
                }
            )

            SheetActionItem(
                icon = Icons.Rounded.Send,
                label = stringResource(R.string.action_reshare_group),
                testTag = "action_reshare_group",
                onClick = {
                    onReshareToGroup(file)
                    onDismiss()
                }
            )

            SheetActionItem(
                icon = Icons.Rounded.Security,
                label = stringResource(R.string.action_file_details),
                testTag = "action_file_details",
                onClick = { showDetailsDialog = true }
            )

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 6.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
            )

            SheetActionItem(
                icon = Icons.Rounded.Delete,
                label = stringResource(R.string.action_delete_file),
                tint = MaterialTheme.colorScheme.error,
                testTag = "action_delete_file",
                onClick = { showDeleteConfirm = true }
            )
        }
    }

    if (showRenameDialog) {
        var newName by remember { mutableStateOf(file.fileName) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text(stringResource(R.string.action_rename)) },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text(stringResource(R.string.rename_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    showRenameDialog = false
                    onRename(file, newName)
                    onDismiss()
                }) {
                    Text(stringResource(R.string.hub_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text(stringResource(R.string.hub_cancel))
                }
            }
        )
    }

    if (showDetailsDialog) {
        AlertDialog(
            onDismissRequest = { showDetailsDialog = false },
            title = { Text(stringResource(R.string.action_file_details)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(file.fileName, style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.details_sender_label, file.senderName), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.details_location_label, file.savedLocation), style = MaterialTheme.typography.bodySmall)
                    Text(
                        text = stringResource(R.string.details_sha256_label, file.sha256.ifBlank { "N/A" }),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showDetailsDialog = false }) {
                    Text(stringResource(R.string.hub_confirm))
                }
            }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.delete_confirm_title)) },
            text = { Text(stringResource(R.string.delete_confirm_body, file.fileName)) },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete(file)
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.action_delete_file))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.hub_cancel))
                }
            }
        )
    }
}

@Composable
private fun SheetActionItem(
    icon: ImageVector,
    label: String,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    testTag: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 13.dp, horizontal = 4.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (tint == MaterialTheme.colorScheme.error) tint else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = tint
        )
    }
}
