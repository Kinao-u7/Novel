package com.example.ui.screens.browse

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
fun BrowseRoute(
    onBack: () -> Unit,
    onNovelClick: (novelId: String) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val container = LocalAppContainer.current
    val viewModel: BrowseViewModel = viewModel(
        factory = appViewModelFactory { appContainer, savedStateHandle ->
            BrowseViewModel(
                repository = appContainer.novelRepository,
                savedStateHandle = savedStateHandle
            )
        }
    )

    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val actions = remember(viewModel, onBack, onNovelClick) {
        BrowseActions(
            onBack = onBack,
            onModeChange = viewModel::onModeChange,
            onNovelClick = onNovelClick,
            onLoadMore = viewModel::loadNextPage,
            onOpenFilters = viewModel::onOpenFilters,
            onDismissFilters = viewModel::onDismissFilters,
            onApplyFilters = viewModel::onApplyFilters,
            onRetry = viewModel::retry
        )
    }

    BrowseScreen(
        state = state,
        actions = actions,
        modifier = modifier,
        contentPadding = contentPadding
    )
}
