package com.example.ui.screens.detail

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
fun DetailRoute(
    onBack: () -> Unit,
    onReadClick: (novelId: String, chapterIndex: Int) -> Unit,
    onChapterClick: (novelId: String, chapterIndex: Int) -> Unit,
    onOpenInBrowser: (url: String) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val container = LocalAppContainer.current
    val viewModel: DetailViewModel = viewModel(
        factory = appViewModelFactory { appContainer, savedStateHandle ->
            DetailViewModel(
                repository = appContainer.novelRepository,
                savedStateHandle = savedStateHandle
            )
        }
    )

    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val actions = remember(viewModel, onBack, onReadClick, onChapterClick, onOpenInBrowser, state.novel) {
        DetailActions(
            onBack = onBack,
            onToggleLibrary = viewModel::onToggleLibrary,
            onReadClick = {
                val novelId = state.novel?.id ?: viewModel.novelId
                val chapterIndex = state.novel?.resumeChapterIndex ?: 0
                onReadClick(novelId, chapterIndex)
            },
            onDownload = viewModel::onDownload,
            onChapterClick = { chapterIndex ->
                val novelId = state.novel?.id ?: viewModel.novelId
                onChapterClick(novelId, chapterIndex)
            },
            onToggleSortOrder = viewModel::onToggleSortOrder,
            onSaveDetails = viewModel::onSaveDetails,
            onMarkAllRead = viewModel::onMarkAllRead,
            onToggleCategory = {},
            onOpenInBrowser = {
                val url = state.novel?.sourceUrl
                if (!url.isNullOrBlank()) {
                    onOpenInBrowser(url)
                }
            },
            onRetry = viewModel::onRetry,
            onMessageShown = viewModel::onMessageShown
        )
    }

    DetailScreen(
        state = state,
        actions = actions,
        modifier = modifier
    )
}
