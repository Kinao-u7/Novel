package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.example.data.model.AppThemeMode
import com.example.data.model.ThemeSettings

fun parseColorHex(hex: String, fallback: Color = Color(0xFFDEB887)): Color {
    return try {
        val clean = hex.trim().removePrefix("#")
        val parsed = clean.toLong(16)
        if (clean.length == 6) {
            Color(0xFF000000 or parsed)
        } else if (clean.length == 8) {
            Color(parsed)
        } else {
            fallback
        }
    } catch (e: Exception) {
        fallback
    }
}

fun calculateLuminance(color: Color): Float {
    return 0.2126f * color.red + 0.7152f * color.green + 0.0722f * color.blue
}

fun calculateOnColor(color: Color): Color {
    return if (calculateLuminance(color) > 0.48f) Color(0xFF141312) else Color.White
}

private fun lerp(start: Float, stop: Float, fraction: Float): Float {
    return (1f - fraction) * start + fraction * stop
}

fun createCustomColorScheme(
    mode: AppThemeMode,
    accent: Color,
    isSystemDark: Boolean
): ColorScheme {
    val onPrimary = calculateOnColor(accent)
    val effectiveMode = when (mode) {
        AppThemeMode.SYSTEM -> if (isSystemDark) AppThemeMode.DARK else AppThemeMode.LIGHT
        else -> mode
    }

    return when (effectiveMode) {
        AppThemeMode.LIGHT -> {
            val primaryContainer = Color(
                red = lerp(accent.red, 1f, 0.85f),
                green = lerp(accent.green, 1f, 0.85f),
                blue = lerp(accent.blue, 1f, 0.85f),
                alpha = 1f
            )
            val onPrimaryContainer = Color(
                red = accent.red * 0.42f,
                green = accent.green * 0.42f,
                blue = accent.blue * 0.42f,
                alpha = 1f
            )
            lightColorScheme(
                primary = accent,
                onPrimary = onPrimary,
                primaryContainer = primaryContainer,
                onPrimaryContainer = onPrimaryContainer,
                secondary = accent.copy(alpha = 0.85f),
                onSecondary = onPrimary,
                secondaryContainer = primaryContainer.copy(alpha = 0.65f),
                onSecondaryContainer = onPrimaryContainer,
                tertiary = Color(0xFFB45309),
                onTertiary = Color.White,
                tertiaryContainer = Color(0xFFFEF3C7),
                onTertiaryContainer = Color(0xFF78350F),
                background = LightWarmBackground,
                onBackground = LightWarmOnSurface,
                surface = LightWarmSurface,
                onSurface = LightWarmOnSurface,
                surfaceVariant = LightWarmSurfaceVariant,
                onSurfaceVariant = LightWarmOnSurfaceVariant,
                outline = LightWarmOutline,
                outlineVariant = LightWarmOutlineVariant
            )
        }

        AppThemeMode.DARK -> {
            val primaryContainer = Color(
                red = accent.red * 0.28f,
                green = accent.green * 0.28f,
                blue = accent.blue * 0.28f,
                alpha = 1f
            )
            val onPrimaryContainer = Color(
                red = lerp(accent.red, 1f, 0.78f),
                green = lerp(accent.green, 1f, 0.78f),
                blue = lerp(accent.blue, 1f, 0.78f),
                alpha = 1f
            )
            darkColorScheme(
                primary = accent,
                onPrimary = onPrimary,
                primaryContainer = primaryContainer,
                onPrimaryContainer = onPrimaryContainer,
                secondary = accent.copy(alpha = 0.85f),
                onSecondary = onPrimary,
                secondaryContainer = primaryContainer.copy(alpha = 0.7f),
                onSecondaryContainer = onPrimaryContainer,
                tertiary = Color(0xFFF59E0B),
                onTertiary = Color(0xFF451A03),
                tertiaryContainer = Color(0xFF78350F),
                onTertiaryContainer = Color(0xFFFEF3C7),
                background = DarkWarmBackground,
                onBackground = DarkWarmOnSurface,
                surface = DarkWarmSurface,
                onSurface = DarkWarmOnSurface,
                surfaceVariant = DarkWarmSurfaceVariant,
                onSurfaceVariant = DarkWarmOnSurfaceVariant,
                outline = DarkWarmOutline,
                outlineVariant = DarkWarmOutlineVariant
            )
        }

        AppThemeMode.AMOLED -> {
            val primaryContainer = Color(
                red = accent.red * 0.22f,
                green = accent.green * 0.22f,
                blue = accent.blue * 0.22f,
                alpha = 1f
            )
            val onPrimaryContainer = Color(
                red = lerp(accent.red, 1f, 0.82f),
                green = lerp(accent.green, 1f, 0.82f),
                blue = lerp(accent.blue, 1f, 0.82f),
                alpha = 1f
            )
            darkColorScheme(
                primary = accent,
                onPrimary = onPrimary,
                primaryContainer = primaryContainer,
                onPrimaryContainer = onPrimaryContainer,
                secondary = accent.copy(alpha = 0.85f),
                onSecondary = onPrimary,
                secondaryContainer = primaryContainer.copy(alpha = 0.7f),
                onSecondaryContainer = onPrimaryContainer,
                tertiary = Color(0xFFF59E0B),
                onTertiary = Color(0xFF451A03),
                tertiaryContainer = Color(0xFF78350F),
                onTertiaryContainer = Color(0xFFFEF3C7),
                background = AmoledBackground,
                onBackground = DarkWarmOnSurface,
                surface = AmoledSurface,
                onSurface = DarkWarmOnSurface,
                surfaceVariant = AmoledSurfaceVariant,
                onSurfaceVariant = DarkWarmOnSurfaceVariant,
                outline = AmoledOutline,
                outlineVariant = AmoledOutlineVariant
            )
        }

        AppThemeMode.SEPIA -> {
            val primaryContainer = Color(0xFFEADBBE)
            val onPrimaryContainer = Color(0xFF382510)
            lightColorScheme(
                primary = accent,
                onPrimary = onPrimary,
                primaryContainer = primaryContainer,
                onPrimaryContainer = onPrimaryContainer,
                secondary = accent.copy(alpha = 0.85f),
                onSecondary = onPrimary,
                secondaryContainer = primaryContainer.copy(alpha = 0.65f),
                onSecondaryContainer = onPrimaryContainer,
                tertiary = Color(0xFF8B5A2B),
                onTertiary = Color.White,
                tertiaryContainer = Color(0xFFEFE2CB),
                onTertiaryContainer = Color(0xFF3D2712),
                background = SepiaBackground,
                onBackground = SepiaOnSurface,
                surface = SepiaSurface,
                onSurface = SepiaOnSurface,
                surfaceVariant = SepiaSurfaceVariant,
                onSurfaceVariant = SepiaOnSurfaceVariant,
                outline = SepiaOutline,
                outlineVariant = SepiaOutlineVariant
            )
        }

        AppThemeMode.SYSTEM -> {
            darkColorScheme()
        }
    }
}

@Composable
fun MyApplicationTheme(
    themeSettings: ThemeSettings = ThemeSettings(),
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val isDark = when (themeSettings.themeMode) {
        AppThemeMode.SYSTEM -> systemDark
        AppThemeMode.DARK, AppThemeMode.AMOLED -> true
        AppThemeMode.LIGHT, AppThemeMode.SEPIA -> false
    }

    val accent = parseColorHex(themeSettings.accentColorHex)

    val colorScheme = when {
        themeSettings.useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        else -> {
            createCustomColorScheme(
                mode = themeSettings.themeMode,
                accent = accent,
                isSystemDark = systemDark
            )
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
