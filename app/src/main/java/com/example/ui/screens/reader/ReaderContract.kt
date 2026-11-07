package com.example.ui.screens.reader

import androidx.compose.runtime.Immutable
import com.example.data.model.FontType
import com.example.data.model.ReaderTheme

@Immutable data class ReaderDisplay(
    val theme: ReaderTheme, val fontType: FontType, val fontSizeSp: Int,
    val lineSpacing: Float, val paragraphSpacingDp: Int, val justified: Boolean,
    val horizontalMarginDp: Int, val keepScreenOn: Boolean, val volumeKeysScroll: Boolean
)

@Immutable data class ReaderChapter(
    val index: Int, val number: Int, val title: String,
    val paragraphs: List<String>, val hasPrevious: Boolean, val hasNext: Boolean
)

@Immutable data class ReaderContentsItem(
    val index: Int, val number: Int, val title: String?,
    val isRead: Boolean, val isCurrent: Boolean, val isDownloaded: Boolean
)

enum class ReaderPanel { NONE, CONTENTS, DISPLAY }

@Immutable data class ReaderUiState(
    val isLoading: Boolean = true,
    val novelTitle: String = "",
    val chapter: ReaderChapter? = null,
    val startParagraph: Int = 0, // scroll here once per chapter (key on chapter.index)
    val chapterCount: Int = 0,
    val display: ReaderDisplay = ReaderDisplay(
        ReaderTheme.SEPIA, FontType.SERIF, 18, 1.6f, 12, true, 20, true, false
    ),
    val contents: List<ReaderContentsItem> = emptyList(),
    val panel: ReaderPanel = ReaderPanel.NONE,
    val controlsVisible: Boolean = true,
    val bookmarkedParagraphs: Set<Int> = emptySet(),
    val ttsAvailable: Boolean = false, // false hides the text-to-speech controls
    val ttsPlaying: Boolean = false,
    val ttsParagraph: Int = -1,
    val error: String? = null,
    val message: String? = null
)

@Immutable data class ReaderActions(
    val onBack: () -> Unit,
    val onToggleControls: () -> Unit,
    val onVisibleParagraph: (index: Int) -> Unit, // first visible paragraph while scrolling
    val onReachedEnd: () -> Unit,                 // the last paragraph became visible
    val onPreviousChapter: () -> Unit,
    val onNextChapter: () -> Unit,
    val onChapterSelected: (chapterIndex: Int) -> Unit,
    val onOpenPanel: (ReaderPanel) -> Unit,
    val onClosePanel: () -> Unit,
    val onDisplayChange: (ReaderDisplay) -> Unit,
    val onToggleBookmark: (paragraphIndex: Int) -> Unit,
    val onTtsToggle: () -> Unit,
    val onTtsSkip: (delta: Int) -> Unit,
    val onRetry: () -> Unit,
    val onMessageShown: () -> Unit
) {
    companion object {
        val NoOp = ReaderActions(
            onBack = {}, onToggleControls = {}, onVisibleParagraph = {}, onReachedEnd = {},
            onPreviousChapter = {}, onNextChapter = {}, onChapterSelected = {},
            onOpenPanel = {}, onClosePanel = {}, onDisplayChange = {}, onToggleBookmark = {},
            onTtsToggle = {}, onTtsSkip = {}, onRetry = {}, onMessageShown = {}
        )
    }
}
