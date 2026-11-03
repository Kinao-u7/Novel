package com.example.ui.screens.browse

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview

@Preview(showBackground = true, backgroundColor = 0xFF0A1214, heightDp = 800)
@Composable
private fun BrowseScreenPreview() {
    val titles = listOf(
        "The Jade Lantern Ascent", "Sword of the Nine Rivers", "Dragon-Scale Alchemist",
        "The Last Star Cartographer", "Moonwell Academy", "Ashes of the Heavenly Court"
    )
    MaterialTheme(
        colorScheme = androidx.compose.material3.darkColorScheme(
            primary = Color(0xFF7CF2C3), onPrimary = Color(0xFF06130F),
            background = Color(0xFF0A1214), onBackground = Color(0xFFD9E6E1)
        )
    ) {
        BrowseScreen(
            state = BrowseUiState(
                sourceName = "Lotus Pavilion", subtitle = "English · v2.4.1",
                isLoading = false, filtersSupported = true,
                filters = BrowseFilters(status = BrowseStatus.ONGOING),
                availableGenres = listOf("Xianxia", "Wuxia", "Fantasy", "Romance", "Sci-fi"),
                novels = titles.mapIndexed { i, t ->
                    BrowseNovelItem("id$i", t, "Wen Lan", 4.0f + i / 10f, null, i + 1)
                }
            ),
            actions = BrowseActions.NoOp
        )
    }
}
