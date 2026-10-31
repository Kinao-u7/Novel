package com.example.ui.screens.library

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.di.LocalAppContainer
import com.example.di.appViewModelFactory

@Composable
fun LibraryRoute(
    onNovelClick: (String) -> Unit,
    onResume: (String) -> Unit,
    onDiscoverClick: () -> Unit,
    onWebBrowserClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val container = LocalAppContainer.current
    val viewModel: LibraryViewModel = viewModel(
        factory = appViewModelFactory { appContainer, savedStateHandle ->
            LibraryViewModel(
                repository = appContainer.novelRepository,
                settingsRepository = appContainer.settingsRepository,
                savedStateHandle = savedStateHandle
            )
        }
    )

    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val actions = remember(viewModel, onNovelClick, onResume, onDiscoverClick, onWebBrowserClick) {
        LibraryActions(
            onSearchChange = viewModel::onSearchChange,
            onFilterChange = viewModel::onFilterChange,
            onSortChange = viewModel::onSortChange,
            onViewModeChange = viewModel::onViewModeChange,
            onResetFilters = viewModel::onResetFilters,
            onNovelClick = onNovelClick,
            onResume = onResume,
            onSelectCategory = {},
            onCreateCategory = {},
            onEpubPicked = viewModel::onEpubPicked,
            onDiscoverClick = onDiscoverClick,
            onWebBrowserClick = onWebBrowserClick,
            onMessageShown = viewModel::onMessageShown
        )
    }

    LibraryScreen(
        state = state,
        actions = actions,
        modifier = modifier
    )
}
