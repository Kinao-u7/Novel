package com.example.ui.screens.discover

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.di.appViewModelFactory

@Composable
fun DiscoverRoute(
    onOpenSource: (String) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val viewModel: DiscoverViewModel = viewModel(
        factory = appViewModelFactory { appContainer, _ ->
            DiscoverViewModel(
                settingsRepository = appContainer.settingsRepository,
                repositoryStore = appContainer.pluginRepositoryStore,
                installationManager = appContainer.pluginInstallationManager,
                installedPluginSources = appContainer.installedPluginSources,
                stringProvider = { resId, args ->
                    if (args.isEmpty()) appContainer.appContext.getString(resId)
                    else appContainer.appContext.getString(resId, *args)
                }
            )
        }
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val actions = remember(viewModel, onOpenSource) {
        DiscoverActions(
            onMessageShown = viewModel::onMessageShown,
            onToggleSearch = viewModel::onToggleSearch,
            onOpenSettings = viewModel::onOpenSettings,
            onQueryChange = viewModel::onQueryChange,
            onTabChange = viewModel::onTabChange,
            onOpenSource = onOpenSource,
            onTogglePin = viewModel::onTogglePin,
            onUpdateAll = viewModel::onUpdateAll,
            onInstall = viewModel::onInstall,
            onUpdate = viewModel::onUpdate,
            onRequestUninstall = viewModel::onRequestUninstall,
            onDismissSettings = viewModel::onDismissSettings,
            onToggleLanguage = viewModel::onToggleLanguage,
            onToggleRepository = viewModel::onToggleRepository,
            onRemoveRepository = viewModel::onRemoveRepository,
            onAddRepository = viewModel::onAddRepository,
            onConfirmUninstall = viewModel::onConfirmUninstall,
            onDismissUninstall = viewModel::onDismissUninstall,
            onRetryCatalogue = viewModel::onRetryCatalogue,
        )
    }
    DiscoverScreen(
        state = state,
        actions = actions,
        modifier = modifier,
        contentPadding = contentPadding,
    )
}
