package com.example.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp

@Composable
fun AnimatedRadarBeacon(
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val infinite = rememberInfiniteTransition(label = "radar")

    val pulse1 by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse1"
    )
    val pulse2 by infinite.animateFloat(
        initialValue = 0.5f,
        targetValue = 1.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse2"
    )
    val sweepAngle by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(3200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sweep"
    )

    Box(
        modifier = modifier.size(136.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2f
            val center = Offset(size.width / 2f, size.height / 2f)

            // Static guide rings
            drawCircle(
                color = primary.copy(alpha = 0.14f),
                radius = radius * 0.95f,
                center = center,
                style = Stroke(width = 1.5f)
            )
            drawCircle(
                color = primary.copy(alpha = 0.22f),
                radius = radius * 0.62f,
                center = center,
                style = Stroke(width = 1.5f)
            )

            if (isActive) {
                val p1 = pulse1 % 1f
                val p2 = pulse2 % 1f
                drawCircle(
                    color = primary.copy(alpha = (1f - p1) * 0.45f),
                    radius = radius * (0.25f + p1 * 0.72f),
                    center = center,
                    style = Stroke(width = 3f)
                )
                drawCircle(
                    color = secondary.copy(alpha = (1f - p2) * 0.35f),
                    radius = radius * (0.25f + p2 * 0.72f),
                    center = center,
                    style = Stroke(width = 2.5f)
                )
                rotate(degrees = sweepAngle, pivot = center) {
                    drawArc(
                        brush = Brush.sweepGradient(
                            0.0f to Color.Transparent,
                            0.72f to Color.Transparent,
                            1.0f to primary.copy(alpha = 0.36f),
                            center = center
                        ),
                        startAngle = 0f,
                        sweepAngle = 90f,
                        useCenter = true
                    )
                }
            }

            drawCircle(
                color = primary.copy(alpha = if (isActive) 0.24f else 0.12f),
                radius = radius * 0.32f,
                center = center
            )
        }

        Icon(
            imageVector = Icons.Rounded.WifiTethering,
            contentDescription = null,
            tint = primary,
            modifier = Modifier.size(34.dp)
        )
    }
}
