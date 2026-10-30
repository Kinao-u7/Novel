package com.example.ui.screens.discover

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
fun DiscoverRoute(
    onOpenSource: (String) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val container = LocalAppContainer.current
    val viewModel: DiscoverViewModel = viewModel(
        factory = appViewModelFactory { appContainer, _ ->
            DiscoverViewModel(appContainer.settingsRepository)
        }
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val actions = remember(viewModel, onOpenSource) {
        DiscoverActions(
            onMessageShown = viewModel::onMessageShown,
            onToggleSearch = viewModel::onToggleSearch,
            onQueryChange = viewModel::onQueryChange,
            onOpenSource = onOpenSource,
            onTogglePin = viewModel::onTogglePin,
        )
    }
    DiscoverScreen(
        state = state,
        actions = actions,
        modifier = modifier,
        contentPadding = contentPadding,
    )
}
