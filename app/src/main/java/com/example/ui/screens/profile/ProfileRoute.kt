package com.example.ui.screens.profile

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.model.NavTab
import com.example.di.LocalAppContainer
import com.example.di.appViewModelFactory

@Composable
fun ProfileRoute(
    onNavigateToTab: (NavTab) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val container = LocalAppContainer.current
    val viewModel: ProfileViewModel = viewModel(
        factory = appViewModelFactory { appContainer, savedStateHandle ->
            ProfileViewModel(
                appContext = appContainer.appContext,
                settingsRepository = appContainer.settingsRepository,
                novelRepository = appContainer.novelRepository,
                savedStateHandle = savedStateHandle
            )
        }
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val actions = remember(viewModel, onNavigateToTab) {
        ProfileActions(
            onOpenCategory = viewModel::onOpenCategory,
            onCloseCategory = viewModel::onCloseCategory,
            onSearchChange = viewModel::onSearchChange,
            onSaveName = viewModel::onSaveName,
            onAction = { key ->
                if (key.startsWith(SettingKeys.OPEN_TAB_PREFIX)) {
                    val tabName = key.removePrefix(SettingKeys.OPEN_TAB_PREFIX)
                    NavTab.entries.firstOrNull { it.name == tabName }?.let(onNavigateToTab)
                } else {
                    viewModel.onAction(key)
                }
            },
            onPicked = viewModel::onPicked,
            onToggle = viewModel::onToggle,
            onSelect = viewModel::onSelect,
            onSlide = viewModel::onSlide,
            onTimeChosen = viewModel::onTimeChosen,
            onConfirm = viewModel::onConfirm,
            onDismissConfirm = viewModel::onDismissConfirm,
            onMessageShown = viewModel::onMessageShown
        )
    }

    ProfileScreen(
        state = state,
        actions = actions,
        modifier = modifier,
        contentPadding = contentPadding
    )
}
