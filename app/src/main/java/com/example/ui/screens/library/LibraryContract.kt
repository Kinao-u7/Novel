package com.example.ui.screens.library

import android.net.Uri
import androidx.compose.runtime.Immutable
import com.example.data.model.LibrarySort
import com.example.data.model.LibraryViewMode

enum class LibraryFilter { ALL, READING, FINISHED }

@Immutable data class CategoryItem(val id: String, val name: String)

@Immutable data class LibraryNovelItem(
    val id: String, val title: String, val author: String, val coverUrl: String?,
    val genres: List<String>, val totalChapters: Int, val readCount: Int,
    val downloadedCount: Int, val isEpub: Boolean
) {
    val isFinished: Boolean get() = totalChapters > 0 && readCount >= totalChapters
    val unreadCount: Int get() = (totalChapters - readCount).coerceAtLeast(0)
    val progress: Float
        get() = if (totalChapters > 0) (readCount.toFloat() / totalChapters).coerceIn(0f, 1f) else 0f
}

@Immutable data class ContinueReading(
    val novelId: String, val title: String, val coverUrl: String?,
    val chapterNumber: Int, val chapterTitle: String?, val readCount: Int, val totalChapters: Int
) {
    val progress: Float
        get() = if (totalChapters > 0) (readCount.toFloat() / totalChapters).coerceIn(0f, 1f) else 0f
}

@Immutable data class LibraryUiState(
    val isLoading: Boolean = true,
    val displayName: String = "",
    val continueReading: ContinueReading? = null,
    val novels: List<LibraryNovelItem> = emptyList(),
    val libraryCount: Int = 0,
    val searchQuery: String = "",
    val filter: LibraryFilter = LibraryFilter.ALL,
    val sort: LibrarySort = LibrarySort.RECENTLY_READ,
    val viewMode: LibraryViewMode = LibraryViewMode.GRID,
    val categoriesEnabled: Boolean = false,
    val categories: List<CategoryItem> = emptyList(),
    val selectedCategoryId: String? = null,
    val isImporting: Boolean = false,
    val message: String? = null
) {
    val filterActive: Boolean get() = filter != LibraryFilter.ALL || sort != LibrarySort.RECENTLY_READ
    val showEmptyLibrary: Boolean get() = !isLoading && libraryCount == 0
    val showNoResults: Boolean get() = !isLoading && libraryCount > 0 && novels.isEmpty()
}

@Immutable data class LibraryActions(
    val onSearchChange: (String) -> Unit, val onFilterChange: (LibraryFilter) -> Unit,
    val onSortChange: (LibrarySort) -> Unit, val onViewModeChange: (LibraryViewMode) -> Unit,
    val onResetFilters: () -> Unit, val onNovelClick: (String) -> Unit,
    val onResume: (String) -> Unit, val onSelectCategory: (String?) -> Unit,
    val onCreateCategory: (String) -> Unit, val onEpubPicked: (Uri) -> Unit,
    val onDiscoverClick: () -> Unit, val onWebBrowserClick: () -> Unit,
    val onMessageShown: () -> Unit
) {
    companion object {
        val NoOp = LibraryActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
    }
}
