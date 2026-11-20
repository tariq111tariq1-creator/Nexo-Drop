package com.example.ui.screens

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
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.FileCategory
import com.example.data.TransferDirection
import com.example.data.TransferRecordEntity
import com.example.data.TransferStatus
import com.example.ui.components.CategoryBadge
import com.example.ui.components.CategoryBreakdown
import com.example.ui.components.CategoryBreakdownChart
import com.example.util.Formatters

private enum class ReportFilter { ALL, SENT, RECEIVED, FAILED, VAULT }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReportsScreen(
    records: List<TransferRecordEntity>,
    isWideScreen: Boolean,
    onExportCsv: (List<TransferRecordEntity>) -> Unit,
    onExportPdf: (List<TransferRecordEntity>) -> Unit,
    onClearHistory: () -> Unit
) {
    var filter by remember { mutableStateOf(ReportFilter.ALL) }
    var search by remember { mutableStateOf("") }

    val filtered = remember(records, filter, search) {
        val q = search.trim().lowercase()
        records.filter { r ->
            val matchFilter = when (filter) {
                ReportFilter.ALL -> true
                ReportFilter.SENT -> r.direction == TransferDirection.SENT.name
                ReportFilter.RECEIVED -> r.direction == TransferDirection.RECEIVED.name
                ReportFilter.FAILED -> r.status != TransferStatus.COMPLETED.name
                ReportFilter.VAULT -> r.isInVault
            }
            val matchQuery = q.isEmpty() ||
                r.fileName.lowercase().contains(q) ||
                r.peerDeviceName.lowercase().contains(q) ||
                r.groupName.lowercase().contains(q)
            matchFilter && matchQuery
        }
    }

    val completed = remember(records) { records.filter { it.status == TransferStatus.COMPLETED.name } }
    val totalSent = remember(completed) {
        completed.filter { it.direction == TransferDirection.SENT.name }.sumOf { it.fileSize }
    }
    val totalReceived = remember(completed) {
        completed.filter { it.direction == TransferDirection.RECEIVED.name }.sumOf { it.fileSize }
    }
    val avgSpeed = remember(completed) {
        if (completed.isEmpty()) 0L else completed.sumOf { it.speedBytesPerSec } / completed.size
    }
    val successRate = remember(records, completed) {
        if (records.isEmpty()) 0 else ((completed.size * 100) / records.size)
    }

    val breakdowns = remember(completed) {
        val colors = mapOf(
            FileCategory.APP to Color(0xFF4CD99B),
            FileCategory.IMAGE to Color(0xFF00E5D4),
            FileCategory.VIDEO to Color(0xFFFF6B6B),
            FileCategory.AUDIO to Color(0xFFB388FF),
            FileCategory.DOCUMENT to Color(0xFF38BDF8),
            FileCategory.ARCHIVE to Color(0xFFFFB74D),
            FileCategory.OTHER to Color(0xFF94A3B8)
        )
        FileCategory.entries.map { cat ->
            val sum = completed.filter { it.category == cat.name }.sumOf { it.fileSize }
            CategoryBreakdown(cat, sum, colors[cat] ?: Color.Gray)
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.reports_title),
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.weight(1f)
                    )
                    if (records.isNotEmpty()) {
                        TextButton(onClick = onClearHistory) {
                            Icon(Icons.Rounded.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.reports_clear))
                        }
                    }
                }
            }

            item {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    maxItemsInEachRow = if (isWideScreen) 4 else 2
                ) {
                    MetricCard(
                        label = stringResource(R.string.reports_total_sent),
                        value = Formatters.formatBytes(totalSent),
                        modifier = Modifier.weight(1f)
                    )
                    MetricCard(
                        label = stringResource(R.string.reports_total_received),
                        value = Formatters.formatBytes(totalReceived),
                        modifier = Modifier.weight(1f)
                    )
                    MetricCard(
                        label = stringResource(R.string.reports_avg_speed),
                        value = Formatters.formatSpeed(avgSpeed),
                        modifier = Modifier.weight(1f)
                    )
                    MetricCard(
                        label = stringResource(R.string.reports_success_rate),
                        value = "$successRate%",
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            if (completed.isNotEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = stringResource(R.string.reports_chart_title),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            CategoryBreakdownChart(items = breakdowns)
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FilledTonalButton(
                        onClick = { onExportCsv(filtered) },
                        enabled = filtered.isNotEmpty(),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("export_csv_button")
                    ) {
                        Icon(Icons.Rounded.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.reports_export_csv))
                    }
                    OutlinedButton(
                        onClick = { onExportPdf(filtered) },
                        enabled = filtered.isNotEmpty(),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("export_pdf_button")
                    ) {
                        Icon(Icons.Rounded.PictureAsPdf, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.reports_export_pdf))
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    placeholder = { Text(stringResource(R.string.explorer_search_hint)) },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = filter == ReportFilter.ALL,
                        onClick = { filter = ReportFilter.ALL },
                        label = { Text(stringResource(R.string.reports_filter_all)) }
                    )
                    FilterChip(
                        selected = filter == ReportFilter.SENT,
                        onClick = { filter = ReportFilter.SENT },
                        label = { Text(stringResource(R.string.reports_filter_sent)) }
                    )
                    FilterChip(
                        selected = filter == ReportFilter.RECEIVED,
                        onClick = { filter = ReportFilter.RECEIVED },
                        label = { Text(stringResource(R.string.reports_filter_received)) }
                    )
                    FilterChip(
                        selected = filter == ReportFilter.FAILED,
                        onClick = { filter = ReportFilter.FAILED },
                        label = { Text(stringResource(R.string.reports_filter_failed)) }
                    )
                    FilterChip(
                        selected = filter == ReportFilter.VAULT,
                        onClick = { filter = ReportFilter.VAULT },
                        label = { Text(stringResource(R.string.reports_filter_vault)) }
                    )
                }
            }

            if (filtered.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Text(
                            text = stringResource(R.string.reports_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(24.dp)
                        )
                    }
                }
            } else {
                items(filtered, key = { it.id }) { record ->
                    val cat = runCatching { FileCategory.valueOf(record.category) }.getOrDefault(FileCategory.OTHER)
                    val isSent = record.direction == TransferDirection.SENT.name
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
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = if (isSent) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward,
                                        contentDescription = record.direction,
                                        tint = if (isSent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = record.fileName,
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Text(
                                    text = "${Formatters.formatBytes(record.fileSize)} • ${Formatters.formatSpeed(record.speedBytesPerSec)} • ${record.peerDeviceName}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "${Formatters.formatTimestamp(record.timestamp)} • ${record.savedLocation.ifBlank { record.status }}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (record.sha256.isNotBlank()) {
                                Icon(
                                    imageVector = Icons.Rounded.VerifiedUser,
                                    contentDescription = "SHA-256 Verified",
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(18.dp)
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
private fun MetricCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
