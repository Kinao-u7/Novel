package com.example.novelreader.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.example.novelreader.R

enum class ThemeMode(val label: String) { System("System"), Light("Light"), Dark("Dark"), Jade("Jade") }

@Immutable
data class AppColors(
    val bg: Color, val tx: Color, val mu: Color, val ac: Color, val go: Color,
    val cd: Color, val ln: Color, val glow: Color, val on: Color, val dark: Boolean,
)

// Same tokens as the HTML prototype (--bg, --tx, --mu, --ac, --go, --cd, --ln, --glow, --on)
private val LightColors = AppColors(
    Color(0xFFFFFFFF), Color(0xFF161A19), Color(0xFF5F6663), Color(0xFF161A19), Color(0xFF8A6414),
    Color(0xFFF2F2F0), Color(0x22161918), Color.Transparent, Color(0xFFFFFFFF), false,
)
private val DarkColors = AppColors(
    Color(0xFF000000), Color(0xFFECECEC), Color(0xFF9A9A9A), Color(0xFFECECEC), Color(0xFFF3CB83),
    Color(0xFF0E0E0E), Color(0x1FFFFFFF), Color.Transparent, Color(0xFF000000), true,
)
private val JadeColors = AppColors(
    Color(0xFF0A1214), Color(0xFFD9E6E1), Color(0xFF8FA8A1), Color(0xFF7CF2C3), Color(0xFFF3CB83),
    Color(0xFF111E21), Color(0x1AFFFFFF), Color(0xFF17463D), Color(0xFF06130F), true,
)

@Immutable
class AppFonts(val heading: FontFamily, val ui: FontFamily, val brush: FontFamily)

// Drop the .ttf files into res/font: cinzel_medium, cinzel_semibold, manrope_regular,
// manrope_semibold, manrope_bold, ma_shan_zheng
private val Cinzel = FontFamily(
    Font(R.font.cinzel_medium, FontWeight.Medium),
    Font(R.font.cinzel_semibold, FontWeight.SemiBold),
)
private val Manrope = FontFamily(
    Font(R.font.manrope_regular, FontWeight.Normal),
    Font(R.font.manrope_semibold, FontWeight.SemiBold),
    Font(R.font.manrope_bold, FontWeight.Bold),
)
private val MaShanZheng = FontFamily(Font(R.font.ma_shan_zheng))

val LocalAppColors = staticCompositionLocalOf { DarkColors }
val LocalAppFonts = staticCompositionLocalOf { AppFonts(Cinzel, Manrope, MaShanZheng) }

object AppTheme {
    val colors: AppColors @Composable @ReadOnlyComposable get() = LocalAppColors.current
    val fonts: AppFonts @Composable @ReadOnlyComposable get() = LocalAppFonts.current
}

@Composable
fun NovelTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val colors = when (mode) {
        ThemeMode.System -> if (systemDark) DarkColors else LightColors
        ThemeMode.Light -> LightColors
        ThemeMode.Dark -> DarkColors
        ThemeMode.Jade -> JadeColors
    }
    val scheme = if (colors.dark) {
        darkColorScheme(primary = colors.ac, onPrimary = colors.on, background = colors.bg, onBackground = colors.tx,
            surface = colors.cd, onSurface = colors.tx, outline = colors.ln)
    } else {
        lightColorScheme(primary = colors.ac, onPrimary = colors.on, background = colors.bg, onBackground = colors.tx,
            surface = colors.cd, onSurface = colors.tx, outline = colors.ln)
    }
    CompositionLocalProvider(LocalAppColors provides colors) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
