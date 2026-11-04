package com.example.ui.theme

import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.example.R

/** Same tokens as the web build (--bg --tx --mu --go --cd --ln --glow --on --rd, plus the accent). */
@Immutable
data class XianColors(
    val bg: Color, val tx: Color, val mu: Color, val go: Color, val cd: Color,
    val ln: Color, val glow: Color, val on: Color, val rd: Color,
    val accent: Color, val isLight: Boolean
)

object XianPalettes {
    val Ink = XianColors(
        Color(0xFF0A1214), Color(0xFFD9E6E1), Color(0xFF7F9A93), Color(0xFFF3CB83), Color(0xFF111E21),
        Color(0x1AFFFFFF), Color(0xFF17463D), Color(0xFF06130F), Color(0xFFFF8A7A), Color(0xFF7CF2C3), false
    )
    val Black = Ink.copy(
        bg = Color(0xFF000000), tx = Color(0xFFECECEC), mu = Color(0xFF8C8C8C), cd = Color(0xFF0E0E0E),
        ln = Color(0x1CFFFFFF), glow = Color(0xFF000000)
    )
    val Paper = XianColors(
        Color(0xFFF6F3EC), Color(0xFF1D2927), Color(0xFF76827E), Color(0xFFA07A2A), Color(0xFFEBE7DD),
        Color(0x221D2927), Color(0xFFFFFFFF), Color(0xFFFFFFFF), Color(0xFFB3392B), Color(0xFF1F7A62), true
    )
    val White = Paper.copy(
        bg = Color(0xFFFFFFFF), tx = Color(0xFF161A19), mu = Color(0xFF68706D), go = Color(0xFF96691A),
        cd = Color(0xFFF2F2F0), ln = Color(0x20161918)
    )
    val Jade = XianColors(
        Color(0xFF06231B), Color(0xFFDCF5EA), Color(0xFF85B3A2), Color(0xFFF3CB83), Color(0xFF0C3426),
        Color(0x229AF6C9), Color(0xFF14604A), Color(0xFF04201A), Color(0xFFFF9A8A), Color(0xFF9AF6C9), false
    )

    fun forId(id: String): XianColors = when (id) {
        "black" -> Black; "paper" -> Paper; "white" -> White; "jade" -> Jade; else -> Ink
    }
}

val LocalXian = staticCompositionLocalOf { XianPalettes.Ink }

object Xian {
    val colors: XianColors @Composable @ReadOnlyComposable get() = LocalXian.current
}

/**
 * Wrap the app (or just this screen). [themeId] is the SwatchRow id: ink | black | paper | white | jade.
 * Resolve "match system" before calling (themeId = if (isSystemInDarkTheme()) "ink" else "paper").
 * [accent] overrides the palette accent when the user picks one.
 */
@Composable
fun XianTheme(themeId: String, accent: Color? = null, content: @Composable () -> Unit) {
    val base = XianPalettes.forId(themeId)
    val c = if (accent != null) base.copy(accent = accent) else base
    val scheme = if (c.isLight)
        lightColorScheme(primary = c.accent, onPrimary = c.on, background = c.bg, onBackground = c.tx,
            surface = c.cd, onSurface = c.tx, outline = c.ln, error = c.rd)
    else
        darkColorScheme(primary = c.accent, onPrimary = c.on, background = c.bg, onBackground = c.tx,
            surface = c.cd, onSurface = c.tx, outline = c.ln, error = c.rd)
    CompositionLocalProvider(LocalXian provides c) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** Flat background with the soft top glow used on every screen. */
fun Modifier.xianBackdrop(c: XianColors): Modifier = this
    .background(c.bg)
    .drawBehind {
        drawRect(
            Brush.radialGradient(
                listOf(c.glow, Color.Transparent),
                center = Offset(size.width / 2f, 0f),
                radius = size.width * 0.95f
            )
        )
    }

/** Add these to res/font: cinzel_medium, cinzel_semibold, manrope_regular, manrope_semibold, manrope_bold, ma_shan_zheng (.ttf). */
object XianFonts {
    val heading = FontFamily(
        Font(R.font.cinzel_medium, FontWeight.Medium),
        Font(R.font.cinzel_semibold, FontWeight.SemiBold)
    )
    val ui = FontFamily(
        Font(R.font.manrope_regular, FontWeight.Normal),
        Font(R.font.manrope_semibold, FontWeight.SemiBold),
        Font(R.font.manrope_bold, FontWeight.Bold)
    )
    val brush = FontFamily(Font(R.font.ma_shan_zheng))
}
