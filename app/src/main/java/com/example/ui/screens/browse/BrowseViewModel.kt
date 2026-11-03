package com.example.ui.screens.browse

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.example.data.repository.NovelRepository
import com.example.data.source.NovelSource
import com.example.data.source.SourceManager
import com.example.data.source.SourceUnavailable
import com.example.ui.navigation.BrowseSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BrowseViewModel(
    private val repository: NovelRepository,
    private val savedStateHandle: SavedStateHandle,
    private val sourceManager: SourceManager = SourceManager
) : ViewModel() {

    val sourceId: String = savedStateHandle.get<String>("sourceId")
        ?: try {
            savedStateHandle.toRoute<BrowseSource>().sourceId
        } catch (_: Exception) {
            ""
        }

    private val initialSource: NovelSource? = sourceManager.getSourceById(sourceId)

    private val _uiState = MutableStateFlow(
        BrowseUiState(
            sourceName = initialSource?.name ?: "",
            subtitle = initialSource?.let { "${it.language} · v${it.version}" } ?: "",
            latestSupported = initialSource?.latestSupported ?: false,
            filtersSupported = initialSource?.filtersSupported ?: false,
            isLoading = true
        )
    )
    val uiState: StateFlow<BrowseUiState> = _uiState.asStateFlow()

    private var currentPage = 1
    private var hasMore = true
    private var loadJob: Job? = null
    private var failedPage: Int? = null

    init {
        if (initialSource == null) {
            _uiState.update {
                it.copy(
                    isLoading = false,
                    error = "Source not available"
                )
            }
        } else {
            loadPage(page = 1, isInitial = true)
        }
    }

    fun loadNextPage() {
        val state = _uiState.value
        if (state.isLoading || state.isLoadingMore || !hasMore || state.error != null) {
            return
        }
        loadPage(page = currentPage + 1, isInitial = false)
    }

    fun retry() {
        val targetPage = failedPage ?: if (_uiState.value.novels.isEmpty()) 1 else (currentPage + 1)
        loadPage(page = targetPage, isInitial = targetPage == 1)
    }

    fun onModeChange(newMode: BrowseMode) {
        if (_uiState.value.mode == newMode) return
        loadJob?.cancel()
        currentPage = 1
        hasMore = true
        failedPage = null
        _uiState.update {
            it.copy(
                mode = newMode,
                novels = emptyList(),
                isLoading = true,
                isLoadingMore = false,
                error = null
            )
        }
        loadPage(page = 1, isInitial = true)
    }

    fun onOpenFilters() {
        _uiState.update { it.copy(showFilters = true) }
    }

    fun onDismissFilters() {
        _uiState.update { it.copy(showFilters = false) }
    }

    fun onApplyFilters(newFilters: BrowseFilters) {
        _uiState.update { it.copy(filters = newFilters, showFilters = false) }
    }

    private fun loadPage(page: Int, isInitial: Boolean) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (isInitial) {
                _uiState.update { it.copy(isLoading = true, error = null) }
            } else {
                _uiState.update { it.copy(isLoadingMore = true, error = null) }
            }
            failedPage = null

            try {
                val currentSource = sourceManager.getSourceById(sourceId)
                    ?: throw SourceUnavailable(sourceId)
                val mode = _uiState.value.mode
                val rawNovels = withContext(Dispatchers.IO) {
                    when (mode) {
                        BrowseMode.POPULAR -> currentSource.getPopularNovels(page)
                        BrowseMode.LATEST -> currentSource.getLatestNovels(page)
                    }
                }

                if (rawNovels.isEmpty()) {
                    hasMore = false
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isLoadingMore = false,
                            error = null
                        )
                    }
                } else {
                    val savedNovels = repository.saveBrowseNovels(rawNovels)
                    val newItems = BrowseMapping.mapNovels(
                        novels = savedNovels,
                        mode = mode,
                        startingRank = _uiState.value.novels.size + 1
                    )
                    val updatedList = if (isInitial) {
                        newItems
                    } else {
                        BrowseMapping.appendAndDedupe(_uiState.value.novels, newItems, mode)
                    }

                    currentPage = page
                    hasMore = BrowseMapping.hasMorePages(rawNovels.size)
                    _uiState.update {
                        it.copy(
                            novels = updatedList,
                            isLoading = false,
                            isLoadingMore = false,
                            error = null
                        )
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                failedPage = page
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isLoadingMore = false,
                        error = e.localizedMessage ?: "Failed to load novels"
                    )
                }
            }
        }
    }
}
