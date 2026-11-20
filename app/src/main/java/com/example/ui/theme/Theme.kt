package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

enum class ThemeMode {
    SYSTEM, LIGHT, DARK, AMOLED
}

private val ObsidianDarkScheme = darkColorScheme(
    primary = TealPrimaryDark,
    onPrimary = TealOnPrimaryDark,
    primaryContainer = TealContainerDark,
    onPrimaryContainer = TealOnContainerDark,
    secondary = EmeraldSecondaryDark,
    onSecondary = EmeraldOnSecondaryDark,
    secondaryContainer = EmeraldContainerDark,
    onSecondaryContainer = EmeraldOnContainerDark,
    tertiary = AmberTertiaryDark,
    onTertiary = AmberOnTertiaryDark,
    tertiaryContainer = AmberContainerDark,
    onTertiaryContainer = AmberOnContainerDark,
    background = ObsidianBg,
    onBackground = Color(0xFFEBF2FA),
    surface = ObsidianSurface,
    onSurface = Color(0xFFEBF2FA),
    surfaceVariant = ObsidianSurfaceVariant,
    onSurfaceVariant = Color(0xFFA6B4CC),
    outline = ObsidianOutline,
    error = ErrorRed
)

private val AmoledBlackScheme = ObsidianDarkScheme.copy(
    background = AmoledBg,
    surface = AmoledSurface,
    surfaceVariant = AmoledSurfaceVariant
)

private val AlabasterLightScheme = lightColorScheme(
    primary = TealPrimaryLight,
    onPrimary = TealOnPrimaryLight,
    primaryContainer = TealContainerLight,
    onPrimaryContainer = TealOnContainerLight,
    secondary = EmeraldSecondaryLight,
    onSecondary = EmeraldOnSecondaryLight,
    secondaryContainer = EmeraldContainerLight,
    onSecondaryContainer = EmeraldOnContainerLight,
    tertiary = AmberTertiaryLight,
    onTertiary = AmberOnTertiaryLight,
    tertiaryContainer = AmberContainerLight,
    onTertiaryContainer = AmberOnContainerLight,
    background = AlabasterBg,
    onBackground = Color(0xFF0E1726),
    surface = AlabasterSurface,
    onSurface = Color(0xFF0E1726),
    surfaceVariant = AlabasterSurfaceVariant,
    onSurfaceVariant = Color(0xFF475569),
    outline = AlabasterOutline,
    error = ErrorRed
)

val NexoShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun NexoDropTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val useDark = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.AMOLED -> true
    }

    val colorScheme = when {
        themeMode == ThemeMode.AMOLED -> AmoledBlackScheme
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (useDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        useDark -> ObsidianDarkScheme
        else -> AlabasterLightScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = NexoTypography,
        shapes = NexoShapes,
        content = content
    )
}
