package com.example.ui.screens.profile

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The same 24x24 stroke icons as the web build, parsed from their SVG path data (no icon library needed). */
private fun xi(name: String, vararg d: String): ImageVector {
    val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
    d.forEach {
        b.addPath(
            PathParser().parsePathString(it).toNodes(),
            stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
        )
    }
    return b.build()
}

internal object XIcons {
    val Look = xi("look", "M20 14.5A8.5 8.5 0 1 1 9.5 4a7 7 0 0 0 10.5 10.5z")
    val Read = xi("read", "M5 4h5a2 2 0 0 1 2 2v14a2 2 0 0 0 -2 -2H5zM19 4h-5a2 2 0 0 0 -2 2v14a2 2 0 0 1 2 -2h5z")
    val Lib = xi("lib", "M5 4v16M10 4v16M15 5l4 14")
    val Download = xi("dl", "M12 4v11M7.5 11L12 15.5L16.5 11M5 20h14")
    val Cultivate = xi("cul", "M3.5 12a8.5 8.5 0 1 0 17 0a8.5 8.5 0 1 0 -17 0", "M8.5 12a3.5 3.5 0 1 0 7 0a3.5 3.5 0 1 0 -7 0")
    val Backup = xi("bak", "M7 18a4.5 4.5 0 0 1 -.6 -8.9A6 6 0 0 1 18 10a4 4 0 0 1 0 8z")
    val About = xi("abt", "M3.5 12a8.5 8.5 0 1 0 17 0a8.5 8.5 0 1 0 -17 0", "M12 11v5M12 8v.1")
    val Chevron = xi("chev", "M9 5l7 7l-7 7")
    val Back = xi("back", "M15 5l-7 7l7 7")
    val Search = xi("search", "M4.5 11a6.5 6.5 0 1 0 13 0a6.5 6.5 0 1 0 -13 0", "M16 16l4.5 4.5")
    val Close = xi("close", "M6 6l12 12M18 6L6 18")

    fun forCategory(id: String): ImageVector = when (id) {
        SettingsCategoryIds.APPEARANCE -> Look
        SettingsCategoryIds.READER -> Read
        SettingsCategoryIds.LIBRARY -> Lib
        SettingsCategoryIds.DOWNLOADS -> Download
        SettingsCategoryIds.CULTIVATION -> Cultivate
        SettingsCategoryIds.BACKUP -> Backup
        else -> About
    }
}

@Composable
internal fun XIcon(icon: ImageVector, tint: Color, size: Dp = 20.dp, modifier: Modifier = Modifier) {
    Icon(icon, contentDescription = null, tint = tint, modifier = modifier.size(size))
}
