package com.example.ui.screens.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.repository.SettingsRepository
import com.example.data.source.SourceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DiscoverViewModel(
    private val settingsRepository: SettingsRepository,
    private val sourceManager: SourceManager = SourceManager
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val isSearchOpen = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)

    val uiState: StateFlow<DiscoverUiState> = combine(
        sourceManager.sources,
        settingsRepository.discoverSettings,
        query,
        isSearchOpen,
        message
    ) { sources, discoverSettings, q, searchOpen, msg ->
        mapToDiscoverUiState(
            sources = sources,
            discoverSettings = discoverSettings,
            query = q,
            isSearchOpen = searchOpen,
            message = msg
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = mapToDiscoverUiState(
            sources = sourceManager.sources.value,
            discoverSettings = settingsRepository.discoverSettings.value,
            query = "",
            isSearchOpen = false,
            message = null
        )
    )

    fun onToggleSearch() {
        val willOpen = !isSearchOpen.value
        isSearchOpen.value = willOpen
        if (!willOpen) {
            query.value = ""
        }
    }

    fun onQueryChange(newQuery: String) {
        query.value = newQuery
    }

    fun onTogglePin(sourceId: String) {
        viewModelScope.launch {
            settingsRepository.updateDiscoverSettings { current ->
                val pinned = current.pinnedSourceIds.toMutableList()
                if (sourceId in pinned) {
                    pinned.remove(sourceId)
                } else {
                    pinned.add(sourceId)
                }
                current.copy(pinnedSourceIds = pinned)
            }
        }
    }

    fun onMessageShown() {
        message.value = null
    }
}
