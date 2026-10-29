package com.example.ui.screens.library

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.example.data.model.AppThemeMode
import com.example.data.model.LibrarySort
import com.example.data.model.LibraryViewMode
import com.example.ui.theme.XianTheme

private val samplePreviewNovels = listOf(
    LibraryNovelItem("jade", "The Jade Lantern Ascent", "Wen Lan", null, listOf("Xianxia"), 126, 2, 10, false),
    LibraryNovelItem("sword", "Sword of the Nine Rivers", "Han Zhou", null, listOf("Wuxia"), 210, 57, 60, false),
    LibraryNovelItem("plum", "Empress Beneath the Plum Snow", "Su Mei", null, listOf("Romance"), 88, 88, 88, false),
    LibraryNovelItem("dragon", "Dragon-Scale Alchemist", "Bai Ren", null, listOf("Xianxia", "Fantasy"), 164, 0, 5, false),
    LibraryNovelItem("star", "The Last Star Cartographer", "Ilya Voss", null, listOf("Sci-fi"), 54, 0, 0, false),
    LibraryNovelItem("moon", "Moonwell Academy", "Tamsin Reed", null, listOf("Fantasy"), 72, 0, 0, false),
    LibraryNovelItem("ash", "Ashes of the Heavenly Court", "Lu Yan", null, listOf("Xianxia"), 301, 0, 0, false),
    LibraryNovelItem("letters", "A Thousand Autumn Letters", "Mei Rowan", null, listOf("Romance"), 46, 0, 0, false),
)

private val sampleContinueReading = ContinueReading(
    novelId = "jade",
    title = "The Jade Lantern Ascent",
    coverUrl = null,
    chapterNumber = 2,
    chapterTitle = "Seven Breaths Before Dawn",
    readCount = 2,
    totalChapters = 126,
)

private val sampleCategories = listOf(
    CategoryItem("fav", "Favorites"),
    CategoryItem("plan", "Plan to read"),
    CategoryItem("re", "Re-reads"),
)

@Preview(name = "Grid - Dark (Ink)", showBackground = true)
@Composable
fun LibraryPreviewGridDark() {
    XianTheme(themeMode = AppThemeMode.DARK) {
        LibraryScreen(
            state = LibraryUiState(
                isLoading = false,
                displayName = "Lin Yue",
                continueReading = sampleContinueReading,
                novels = samplePreviewNovels,
                libraryCount = samplePreviewNovels.size,
                categoriesEnabled = true,
                categories = sampleCategories,
                viewMode = LibraryViewMode.GRID,
                sort = LibrarySort.RECENTLY_READ,
            ),
            actions = LibraryActions.NoOp,
        )
    }
}

@Preview(name = "Grid - Light (White)", showBackground = true)
@Composable
fun LibraryPreviewGridLight() {
    XianTheme(themeMode = AppThemeMode.LIGHT) {
        LibraryScreen(
            state = LibraryUiState(
                isLoading = false,
                displayName = "Lin Yue",
                continueReading = sampleContinueReading,
                novels = samplePreviewNovels,
                libraryCount = samplePreviewNovels.size,
                categoriesEnabled = true,
                categories = sampleCategories,
                viewMode = LibraryViewMode.GRID,
                sort = LibrarySort.RECENTLY_READ,
            ),
            actions = LibraryActions.NoOp,
        )
    }
}

@Preview(name = "List - Sepia (Paper)", showBackground = true)
@Composable
fun LibraryPreviewListSepia() {
    XianTheme(themeMode = AppThemeMode.SEPIA) {
        LibraryScreen(
            state = LibraryUiState(
                isLoading = false,
                displayName = "Lin Yue",
                continueReading = sampleContinueReading,
                novels = samplePreviewNovels,
                libraryCount = samplePreviewNovels.size,
                categoriesEnabled = true,
                categories = sampleCategories,
                viewMode = LibraryViewMode.LIST,
                sort = LibrarySort.RECENTLY_READ,
            ),
            actions = LibraryActions.NoOp,
        )
    }
}

@Preview(name = "Loading State - Dark", showBackground = true)
@Composable
fun LibraryPreviewLoading() {
    XianTheme(themeMode = AppThemeMode.DARK) {
        LibraryScreen(
            state = LibraryUiState(isLoading = true),
            actions = LibraryActions.NoOp,
        )
    }
}

@Preview(name = "Empty State - Sepia", showBackground = true)
@Composable
fun LibraryPreviewEmpty() {
    XianTheme(themeMode = AppThemeMode.SEPIA) {
        LibraryScreen(
            state = LibraryUiState(isLoading = false, libraryCount = 0, novels = emptyList()),
            actions = LibraryActions.NoOp,
        )
    }
}

@Preview(name = "No Results State - Dark", showBackground = true)
@Composable
fun LibraryPreviewNoResults() {
    XianTheme(themeMode = AppThemeMode.DARK) {
        LibraryScreen(
            state = LibraryUiState(
                isLoading = false,
                libraryCount = 8,
                novels = emptyList(),
                searchQuery = "Unknown Title",
            ),
            actions = LibraryActions.NoOp,
        )
    }
}
