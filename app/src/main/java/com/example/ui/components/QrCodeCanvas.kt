package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlin.math.ceil

@Composable
fun QrCodeCard(
    content: String,
    size: Dp = 220.dp,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val matrix = remember(content) {
        runCatching {
            val hints = mapOf(
                EncodeHintType.MARGIN to 2,
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.CHARACTER_SET to "UTF-8"
            )
            // width=0, height=0 yields exact native module grid for razor-sharp rendering
            QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints)
        }.getOrNull()
    }

    val clickableModifier = if (onClick != null) {
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White)
            .clickable(onClick = onClick)
            .padding(14.dp)
    } else {
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White)
            .padding(14.dp)
    }

    Box(modifier = clickableModifier) {
        Canvas(modifier = Modifier.size(size)) {
            if (matrix == null) return@Canvas
            val w = matrix.width
            val h = matrix.height
            if (w <= 0 || h <= 0) return@Canvas
            val cellW = this.size.width / w
            val cellH = this.size.height / h
            val drawW = ceil(cellW).coerceAtLeast(cellW + 0.35f)
            val drawH = ceil(cellH).coerceAtLeast(cellH + 0.35f)
            val darkInk = Color(0xFF000000)

            for (x in 0 until w) {
                for (y in 0 until h) {
                    if (matrix.get(x, y)) {
                        drawRect(
                            color = darkInk,
                            topLeft = Offset(x * cellW, y * cellH),
                            size = Size(drawW, drawH)
                        )
                    }
                }
            }
        }
    }
}
