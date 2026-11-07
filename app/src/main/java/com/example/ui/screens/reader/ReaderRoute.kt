package com.example.ui.screens.reader

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.di.LocalAppContainer
import com.example.di.appViewModelFactory

@Composable
fun ReaderRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val container = LocalAppContainer.current
    val viewModel: ReaderViewModel = viewModel(
        factory = appViewModelFactory { appContainer, savedStateHandle ->
            ReaderViewModel(
                novelRepository = appContainer.novelRepository,
                settingsRepository = appContainer.settingsRepository,
                chapterRepository = appContainer.novelRepository.chapterRepository,
                downloadEngine = appContainer.novelRepository.downloadEngine,
                chapterFinishedListener = appContainer.chapterFinishedListener,
                savedStateHandle = savedStateHandle
            )
        }
    )

    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val actions = remember(viewModel, onBack) {
        ReaderActions(
            onBack = { viewModel.onBack(onBack) },
            onToggleControls = viewModel::onToggleControls,
            onVisibleParagraph = viewModel::onVisibleParagraph,
            onReachedEnd = viewModel::onReachedEnd,
            onPreviousChapter = viewModel::onPreviousChapter,
            onNextChapter = viewModel::onNextChapter,
            onChapterSelected = viewModel::onChapterSelected,
            onOpenPanel = viewModel::onOpenPanel,
            onClosePanel = viewModel::onClosePanel,
            onDisplayChange = viewModel::onDisplayChange,
            onToggleBookmark = viewModel::onToggleBookmark,
            onTtsToggle = {},
            onTtsSkip = {},
            onRetry = viewModel::onRetry,
            onMessageShown = viewModel::onMessageShown
        )
    }

    BackHandler {
        actions.onBack()
    }

    ReaderScreen(
        state = state,
        actions = actions,
        modifier = modifier
    )
}
