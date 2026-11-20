package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.data.FileCategory
import com.example.util.Formatters

data class CategoryBreakdown(
    val category: FileCategory,
    val totalBytes: Long,
    val color: Color
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategoryBreakdownChart(
    items: List<CategoryBreakdown>,
    modifier: Modifier = Modifier
) {
    val grandTotal = items.sumOf { it.totalBytes }.coerceAtLeast(1L)

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(18.dp)
                .clip(RoundedCornerShape(9.dp))
        ) {
            var xOffset = 0f
            val totalW = size.width
            val gap = 3f
            items.filter { it.totalBytes > 0L }.forEach { slice ->
                val width = ((slice.totalBytes.toFloat() / grandTotal.toFloat()) * totalW)
                    .coerceAtLeast(6f)
                    .coerceAtMost(totalW - xOffset)
                drawRoundRect(
                    color = slice.color,
                    topLeft = Offset(xOffset, 0f),
                    size = Size((width - gap).coerceAtLeast(2f), size.height),
                    cornerRadius = CornerRadius(6f, 6f)
                )
                xOffset += width
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items.filter { it.totalBytes > 0L }.forEach { slice ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(slice.color)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "${slice.category.name}: ${Formatters.formatBytes(slice.totalBytes)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
