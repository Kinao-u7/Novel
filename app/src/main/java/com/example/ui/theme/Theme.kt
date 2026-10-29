package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import com.example.data.model.AppThemeMode
import com.example.data.model.ThemeSettings

@Immutable
data class AppColors(
    val bg: Color,
    val tx: Color,
    val mu: Color,
    val ac: Color,
    val go: Color,
    val cd: Color,
    val ln: Color,
    val glow: Color,
    val on: Color,
    val dark: Boolean,
)

@Immutable
data class AppFonts(
    val heading: FontFamily = FontFamily.Serif,
    val ui: FontFamily = FontFamily.SansSerif,
    val brush: FontFamily = FontFamily.Default,
)

data class ThemeTokens(
    val bg: Color,
    val tx: Color,
    val mu: Color,
    val go: Color,
    val cd: Color,
    val ln: Color,
    val glow: Color,
    val on: Color,
    val dark: Boolean,
    val error: Color,
)

val InkTokens = ThemeTokens(
    bg = Color(0xFF0A1214),
    tx = Color(0xFFD9E6E1),
    mu = Color(0xFF8FA8A1),
    go = Color(0xFFF3CB83),
    cd = Color(0xFF111E21),
    ln = Color(0x1AFFFFFF),
    glow = Color(0xFF17463D),
    on = Color(0xFF06130F),
    dark = true,
    error = Color(0xFFFF8A7A),
)

val BlackTokens = ThemeTokens(
    bg = Color(0xFF000000),
    tx = Color(0xFFECECEC),
    mu = Color(0xFF9A9A9A),
    go = Color(0xFFF3CB83),
    cd = Color(0xFF0E0E0E),
    ln = Color(0x1FFFFFFF),
    glow = Color.Transparent,
    on = Color(0xFF000000),
    dark = true,
    error = Color(0xFFFF8A7A),
)

val PaperTokens = ThemeTokens(
    bg = Color(0xFFF6F3EC),
    tx = Color(0xFF1D2927),
    mu = Color(0xFF76827E),
    go = Color(0xFFA07A2A),
    cd = Color(0xFFEBE7DD),
    ln = Color(0x221D2927),
    glow = Color.Transparent,
    on = Color(0xFFFFFFFF),
    dark = false,
    error = Color(0xFFB3392B),
)

val WhiteTokens = ThemeTokens(
    bg = Color(0xFFFFFFFF),
    tx = Color(0xFF161A19),
    mu = Color(0xFF5F6663),
    go = Color(0xFF8A6414),
    cd = Color(0xFFF2F2F0),
    ln = Color(0x22161918),
    glow = Color.Transparent,
    on = Color(0xFFFFFFFF),
    dark = false,
    error = Color(0xFFB3392B),
)

val LocalAppColors = staticCompositionLocalOf {
    AppColors(
        bg = InkTokens.bg,
        tx = InkTokens.tx,
        mu = InkTokens.mu,
        ac = Color(0xFF7CF2C3),
        go = InkTokens.go,
        cd = InkTokens.cd,
        ln = InkTokens.ln,
        glow = InkTokens.glow,
        on = InkTokens.on,
        dark = true,
    )
}

val LocalAppFonts = staticCompositionLocalOf { AppFonts() }

object AppTheme {
    val colors: AppColors
        @Composable
        @ReadOnlyComposable
        get() = LocalAppColors.current

    val fonts: AppFonts
        @Composable
        @ReadOnlyComposable
        get() = LocalAppFonts.current
}

fun parseColorHex(hex: String, fallback: Color = Color(0xFF7CF2C3)): Color {
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

fun resolveAccentColor(accentHex: String, isDark: Boolean): Color {
    val clean = accentHex.trim().removePrefix("#").lowercase()
    if (!isDark) {
        when (clean) {
            "7cf2c3" -> return Color(0xFF1F7A62) // jade
            "f3cb83" -> return Color(0xFF96691A) // gold
            "ff9ec4" -> return Color(0xFFB8416F) // rose
            "8fd0ff" -> return Color(0xFF1F6FA8) // azure
        }
    }
    val defaultFallback = if (isDark) Color(0xFF7CF2C3) else Color(0xFF1F7A62)
    return parseColorHex(accentHex, fallback = defaultFallback)
}

@Composable
fun XianTheme(
    themeMode: AppThemeMode = AppThemeMode.DARK,
    accentHex: String = "#7CF2C3",
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val tokens = when (themeMode) {
        AppThemeMode.DARK -> InkTokens
        AppThemeMode.AMOLED -> BlackTokens
        AppThemeMode.SEPIA -> PaperTokens
        AppThemeMode.LIGHT -> WhiteTokens
        AppThemeMode.SYSTEM -> if (systemDark) InkTokens else PaperTokens
    }

    val ac = resolveAccentColor(accentHex, isDark = tokens.dark)

    val appColors = AppColors(
        bg = tokens.bg,
        tx = tokens.tx,
        mu = tokens.mu,
        ac = ac,
        go = tokens.go,
        cd = tokens.cd,
        ln = tokens.ln,
        glow = tokens.glow,
        on = tokens.on,
        dark = tokens.dark,
    )

    val appFonts = AppFonts(
        heading = FontFamily.Serif,
        ui = FontFamily.SansSerif,
        brush = FontFamily.Default,
    )

    val colorScheme = if (tokens.dark) {
        darkColorScheme(
            primary = ac,
            onPrimary = tokens.on,
            secondary = ac,
            onSecondary = tokens.on,
            tertiary = tokens.go,
            onTertiary = tokens.on,
            background = tokens.bg,
            onBackground = tokens.tx,
            surface = tokens.cd,
            onSurface = tokens.tx,
            surfaceVariant = tokens.cd,
            onSurfaceVariant = tokens.mu,
            outline = tokens.ln,
            outlineVariant = tokens.ln,
            error = tokens.error,
        )
    } else {
        lightColorScheme(
            primary = ac,
            onPrimary = tokens.on,
            secondary = ac,
            onSecondary = tokens.on,
            tertiary = tokens.go,
            onTertiary = tokens.on,
            background = tokens.bg,
            onBackground = tokens.tx,
            surface = tokens.cd,
            onSurface = tokens.tx,
            surfaceVariant = tokens.cd,
            onSurfaceVariant = tokens.mu,
            outline = tokens.ln,
            outlineVariant = tokens.ln,
            error = tokens.error,
        )
    }

    CompositionLocalProvider(
        LocalAppColors provides appColors,
        LocalAppFonts provides appFonts,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = Shapes,
            content = content,
        )
    }
}

@Composable
fun XianTheme(
    themeSettings: ThemeSettings,
    content: @Composable () -> Unit,
) {
    XianTheme(
        themeMode = themeSettings.themeMode,
        accentHex = themeSettings.accentColorHex,
        content = content,
    )
}

/**
 * Backward compatibility wrapper.
 */
@Composable
fun MyApplicationTheme(
    themeSettings: ThemeSettings = ThemeSettings(),
    content: @Composable () -> Unit,
) {
    XianTheme(themeSettings = themeSettings, content = content)
}
