package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

@Composable
fun QrCodeCard(
    content: String,
    size: Dp = 180.dp,
    modifier: Modifier = Modifier
) {
    val matrix = remember(content) {
        runCatching {
            val hints = mapOf(EncodeHintType.MARGIN to 1)
            QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 64, 64, hints)
        }.getOrNull()
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White)
            .padding(12.dp)
    ) {
        Canvas(modifier = Modifier.size(size)) {
            if (matrix == null) return@Canvas
            val w = matrix.width
            val h = matrix.height
            val cellW = this.size.width / w
            val cellH = this.size.height / h
            for (x in 0 until w) {
                for (y in 0 until h) {
                    if (matrix.get(x, y)) {
                        drawRect(
                            color = Color(0xFF090D16),
                            topLeft = Offset(x * cellW, y * cellH),
                            size = Size(cellW + 0.5f, cellH + 0.5f)
                        )
                    }
                }
            }
        }
    }
}
