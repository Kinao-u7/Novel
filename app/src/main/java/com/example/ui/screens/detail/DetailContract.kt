package com.example.ui.screens.detail

import androidx.compose.runtime.Immutable
import com.example.ui.screens.library.CategoryItem

enum class DownloadScope { NEXT_5, NEXT_25, ALL_UNREAD }

@Immutable data class DetailNovel(
    val id: String, val title: String, val author: String, val description: String,
    val coverUrl: String?, val genres: List<String>, val sourceName: String,
    val isCompleted: Boolean, val totalChapters: Int, val readCount: Int,
    val inLibrary: Boolean, val resumeChapterIndex: Int?, val resumeChapterNumber: Int?,
    val resumeChapterTitle: String?, val isEpub: Boolean, val sourceUrl: String?
)

@Immutable data class DetailChapter(
    val index: Int, val number: Int, val title: String?,
    val isRead: Boolean, val isCurrent: Boolean, val isDownloaded: Boolean
)

@Immutable data class DetailUiState(
    val isLoading: Boolean = true, val novel: DetailNovel? = null,
    val chapters: List<DetailChapter> = emptyList(), val oldestFirst: Boolean = true,
    val categoriesEnabled: Boolean = false, val categories: List<CategoryItem> = emptyList(),
    val novelCategoryIds: Set<String> = emptySet(),
    val error: String? = null, val message: String? = null
)

@Immutable data class DetailActions(
    val onBack: () -> Unit, val onToggleLibrary: () -> Unit, val onReadClick: () -> Unit,
    val onDownload: (DownloadScope) -> Unit, val onChapterClick: (Int) -> Unit,
    val onToggleSortOrder: () -> Unit, val onSaveDetails: (title: String, author: String) -> Unit,
    val onMarkAllRead: (Boolean) -> Unit, val onToggleCategory: (String) -> Unit,
    val onOpenInBrowser: () -> Unit, val onRetry: () -> Unit, val onMessageShown: () -> Unit
) {
    companion object {
        val NoOp = DetailActions({}, {}, {}, {}, {}, {}, { _, _ -> }, {}, {}, {}, {}, {})
    }
}
