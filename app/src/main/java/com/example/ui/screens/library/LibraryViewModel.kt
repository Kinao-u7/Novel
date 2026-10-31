package com.example.ui.screens.library

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.LibrarySettings
import com.example.data.model.LibrarySort
import com.example.data.model.LibraryViewMode
import com.example.data.model.Novel
import com.example.data.repository.NovelRepository
import com.example.data.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(
    private val repository: NovelRepository,
    private val settingsRepository: SettingsRepository,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    companion object {
        private const val KEY_SEARCH_QUERY = "library_search_query"
        private const val KEY_FILTER = "library_filter"
    }

    private val _isImporting = MutableStateFlow(false)
    private val _message = MutableStateFlow<String?>(null)

    val searchQueryFlow = savedStateHandle.getStateFlow(KEY_SEARCH_QUERY, "")
    val filterFlow = savedStateHandle.getStateFlow(KEY_FILTER, LibraryFilter.ALL)

    val uiState: StateFlow<LibraryUiState> = combine(
        repository.libraryNovels,
        repository.getNovelReadCountsFlow(),
        repository.downloadedChapters.map { map -> map.mapValues { it.value.size } },
        settingsRepository.librarySettings,
        settingsRepository.profileSettings.map { it.displayName },
        searchQueryFlow,
        filterFlow,
        _isImporting,
        _message
    ) { args: Array<Any?> ->
        @Suppress("UNCHECKED_CAST")
        val libraryNovels = args[0] as List<Novel>
        @Suppress("UNCHECKED_CAST")
        val readCounts = args[1] as Map<String, Int>
        @Suppress("UNCHECKED_CAST")
        val downloadedCounts = args[2] as Map<String, Int>
        val librarySettings = args[3] as LibrarySettings
        val displayName = args[4] as String
        val searchQuery = args[5] as String
        val filter = args[6] as LibraryFilter
        val isImporting = args[7] as Boolean
        val message = args[8] as String?

        mapToLibraryUiState(
            libraryNovels = libraryNovels,
            readCounts = readCounts,
            downloadedCounts = downloadedCounts,
            librarySettings = librarySettings,
            displayName = displayName,
            searchQuery = searchQuery,
            filter = filter,
            isImporting = isImporting,
            message = message
        )
    }
        .flowOn(Dispatchers.Default)
        .debounceAfterFirst(250L)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = LibraryUiState(isLoading = true)
        )

    fun onSearchChange(query: String) {
        savedStateHandle[KEY_SEARCH_QUERY] = query
    }

    fun onFilterChange(filter: LibraryFilter) {
        savedStateHandle[KEY_FILTER] = filter
    }

    fun onSortChange(sort: LibrarySort) {
        viewModelScope.launch {
            settingsRepository.updateLibrarySettings { it.copy(sort = sort) }
        }
    }

    fun onViewModeChange(viewMode: LibraryViewMode) {
        viewModelScope.launch {
            settingsRepository.updateLibrarySettings { it.copy(viewMode = viewMode) }
        }
    }

    fun onResetFilters() {
        savedStateHandle[KEY_FILTER] = LibraryFilter.ALL
        savedStateHandle[KEY_SEARCH_QUERY] = ""
    }

    fun onEpubPicked(uri: Uri) {
        viewModelScope.launch {
            _isImporting.value = true
            try {
                val fileName = uri.lastPathSegment ?: "imported.epub"
                val novel = repository.importEpub(uri, fileName)
                _message.value = "Imported ${novel.title}"
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _message.value = "Failed to import EPUB: ${e.message ?: "Unknown error"}"
            } finally {
                _isImporting.value = false
            }
        }
    }

    fun onMessageShown() {
        _message.value = null
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private fun <T> Flow<T>.debounceAfterFirst(timeoutMillis: Long = 250L): Flow<T> {
    var isFirst = true
    return transformLatest { value ->
        if (isFirst) {
            isFirst = false
            emit(value)
        } else {
            delay(timeoutMillis)
            emit(value)
        }
    }
}
