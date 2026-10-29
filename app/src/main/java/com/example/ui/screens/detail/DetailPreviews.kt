package com.example.ui.screens.detail

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.example.data.model.AppThemeMode
import com.example.ui.screens.library.CategoryItem
import com.example.ui.theme.XianTheme

private val sampleDetailNovel = DetailNovel(
    id = "jade",
    title = "The Jade Lantern Ascent",
    author = "Wen Lan",
    description = "A servant boy finds a lantern that has burned for nine hundred years, and a voice inside it offers to teach him how to breathe. The mountain, it turns out, has been waiting for him.",
    coverUrl = null,
    genres = listOf("Xianxia", "Cultivation", "Action"),
    sourceName = "WTR-LAB",
    isCompleted = false,
    totalChapters = 126,
    readCount = 2,
    inLibrary = true,
    resumeChapterIndex = 1,
    resumeChapterNumber = 2,
    resumeChapterTitle = "Seven Breaths Before Dawn",
    isEpub = false,
    sourceUrl = "https://wtr-lab.com/novel/jade-lantern-ascent",
)

private val sampleDetailChapters = (1..50).map { i ->
    DetailChapter(
        index = i - 1,
        number = i,
        title = when (i) {
            1 -> "The Lantern in the Dust"
            2 -> "Seven Breaths Before Dawn"
            3 -> "The Cold Iron Mountain"
            4 -> "Sparks in the Fog"
            5 -> "The First Meridian"
            else -> "Path of Jade Step $i"
        },
        isRead = i <= 2,
        isCurrent = i == 2,
        isDownloaded = i <= 10,
    )
}

private val sampleCategories = listOf(
    CategoryItem("fav", "Favorites"),
    CategoryItem("plan", "Plan to read"),
    CategoryItem("re", "Re-reads"),
)

@Preview(name = "Detail - Dark (Ink)", showBackground = true, heightDp = 900)
@Composable
fun DetailPreviewDark() {
    XianTheme(themeMode = AppThemeMode.DARK) {
        DetailScreen(
            state = DetailUiState(
                isLoading = false,
                novel = sampleDetailNovel,
                chapters = sampleDetailChapters,
                oldestFirst = true,
                categoriesEnabled = true,
                categories = sampleCategories,
                novelCategoryIds = setOf("fav"),
            ),
            actions = DetailActions.NoOp,
        )
    }
}

@Preview(name = "Detail - Sepia (Paper)", showBackground = true, heightDp = 900)
@Composable
fun DetailPreviewSepia() {
    XianTheme(themeMode = AppThemeMode.SEPIA) {
        DetailScreen(
            state = DetailUiState(
                isLoading = false,
                novel = sampleDetailNovel,
                chapters = sampleDetailChapters,
                oldestFirst = true,
                categoriesEnabled = true,
                categories = sampleCategories,
                novelCategoryIds = setOf("fav"),
            ),
            actions = DetailActions.NoOp,
        )
    }
}

@Preview(name = "Detail - Loading State", showBackground = true)
@Composable
fun DetailPreviewLoading() {
    XianTheme(themeMode = AppThemeMode.DARK) {
        DetailScreen(
            state = DetailUiState(isLoading = true),
            actions = DetailActions.NoOp,
        )
    }
}

@Preview(name = "Detail - Error State", showBackground = true)
@Composable
fun DetailPreviewError() {
    XianTheme(themeMode = AppThemeMode.DARK) {
        DetailScreen(
            state = DetailUiState(
                isLoading = false,
                error = "Network timeout while fetching novel metadata.",
            ),
            actions = DetailActions.NoOp,
        )
    }
}
