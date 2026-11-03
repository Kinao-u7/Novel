package com.example.ui.screens.browse

import androidx.compose.runtime.Immutable

enum class BrowseMode { LATEST, POPULAR }
enum class BrowseStatus { ANY, ONGOING, COMPLETED }
enum class BrowseSort { DEFAULT, RATING, TITLE }

@Immutable data class BrowseNovelItem(
    val id: String, val title: String, val author: String,
    val rating: Float?, val coverUrl: String?, val rank: Int? // rank shown in POPULAR
)

@Immutable data class BrowseFilters(
    val genres: Set<String> = emptySet(),
    val status: BrowseStatus = BrowseStatus.ANY,
    val sort: BrowseSort = BrowseSort.DEFAULT
) {
    val isActive: Boolean get() = genres.isNotEmpty() || status != BrowseStatus.ANY || sort != BrowseSort.DEFAULT
}

@Immutable data class BrowseUiState(
    val sourceName: String = "", val subtitle: String = "", // e.g. "English · v2.0"
    val mode: BrowseMode = BrowseMode.POPULAR,
    val latestSupported: Boolean = true, // false hides the Latest/Popular switch
    val isLoading: Boolean = true, val isLoadingMore: Boolean = false,
    val novels: List<BrowseNovelItem> = emptyList(),
    val filters: BrowseFilters = BrowseFilters(),
    val availableGenres: List<String> = emptyList(),
    val filtersSupported: Boolean = false, // false hides the filter button and sheet
    val showFilters: Boolean = false,
    val error: String? = null
)

@Immutable data class BrowseActions(
    val onBack: () -> Unit,
    val onModeChange: (BrowseMode) -> Unit,
    val onNovelClick: (novelId: String) -> Unit,
    val onLoadMore: () -> Unit,
    val onOpenFilters: () -> Unit,
    val onDismissFilters: () -> Unit,
    val onApplyFilters: (BrowseFilters) -> Unit, // the sheet keeps a draft until Apply
    val onRetry: () -> Unit
) {
    companion object {
        val NoOp = BrowseActions(
            onBack = {}, onModeChange = {}, onNovelClick = {}, onLoadMore = {},
            onOpenFilters = {}, onDismissFilters = {}, onApplyFilters = {}, onRetry = {}
        )
    }
}
