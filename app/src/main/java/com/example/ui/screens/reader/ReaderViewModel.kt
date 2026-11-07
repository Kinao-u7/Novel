package com.example.ui.screens.reader

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.example.data.download.DownloadEngine
import com.example.data.local.db.toModel
import com.example.data.model.Bookmark
import com.example.data.model.Chapter
import com.example.data.repository.ChapterRepository
import com.example.data.repository.NovelRepository
import com.example.data.repository.SettingsRepository
import com.example.di.ChapterFinishedListener
import com.example.ui.navigation.Reader
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class ReaderViewModel(
    private val novelRepository: NovelRepository,
    private val settingsRepository: SettingsRepository,
    private val chapterRepository: ChapterRepository = novelRepository.chapterRepository,
    private val downloadEngine: DownloadEngine = novelRepository.downloadEngine,
    private val chapterFinishedListener: ChapterFinishedListener = ChapterFinishedListener.NoOp,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    companion object {
        const val KEY_CHAPTER_INDEX = "current_chapter_index"
    }

    val route: Reader = try {
        savedStateHandle.toRoute<Reader>()
    } catch (_: Exception) {
        Reader(
            novelId = savedStateHandle.get<String>("novelId") ?: "",
            chapterIndex = savedStateHandle.get<Int>("chapterIndex") ?: 0
        )
    }

    val novelId: String = route.novelId

    private val currentChapterIndexFlow: StateFlow<Int> =
        savedStateHandle.getStateFlow(KEY_CHAPTER_INDEX, route.chapterIndex)

    private data class ChapterLoadState(
        val isLoading: Boolean = true,
        val novelTitle: String = "",
        val chapter: ReaderChapter? = null,
        val startParagraph: Int = 0,
        val chapterCount: Int = 0,
        val contents: List<ReaderContentsItem> = emptyList(),
        val error: String? = null
    )

    private val _chapterLoadState = MutableStateFlow(ChapterLoadState())
    private val _panel = MutableStateFlow(ReaderPanel.NONE)
    private val _controlsVisible = MutableStateFlow(true)
    private val _message = MutableStateFlow<String?>(null)

    private var currentChapterModel: Chapter? = null
    private var currentChapterParagraphs: List<String> = emptyList()
    private var hasReachedEndForCurrentChapter = false
    private var loadJob: Job? = null

    private data class UiControlsLocal(
        val panel: ReaderPanel,
        val controlsVisible: Boolean,
        val message: String?
    )

    private val controlsFlow = combine(
        _panel,
        _controlsVisible,
        _message
    ) { panel, controlsVisible, message ->
        UiControlsLocal(panel, controlsVisible, message)
    }

    val uiState: StateFlow<ReaderUiState> = combine(
        _chapterLoadState,
        settingsRepository.settings,
        novelRepository.bookmarks,
        controlsFlow
    ) { loadState, settings, allBookmarks, controls ->
        val currentChapterId = currentChapterModel?.id
        val bookmarkedParagraphs = if (currentChapterId != null) {
            allBookmarks.filter { it.novelId == novelId && it.chapterId == currentChapterId }
                .map { it.paragraphIndex }
                .toSet()
        } else emptySet()

        ReaderUiState(
            isLoading = loadState.isLoading,
            novelTitle = loadState.novelTitle,
            chapter = loadState.chapter,
            startParagraph = loadState.startParagraph,
            chapterCount = loadState.chapterCount,
            display = ReaderMapping.mapToReaderDisplay(settings.reader),
            contents = loadState.contents,
            panel = controls.panel,
            controlsVisible = controls.controlsVisible,
            bookmarkedParagraphs = bookmarkedParagraphs,
            ttsAvailable = false,
            ttsPlaying = false,
            ttsParagraph = -1,
            error = loadState.error,
            message = controls.message
        )
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ReaderUiState())

    init {
        viewModelScope.launch {
            currentChapterIndexFlow.collect { index ->
                loadChapter(index)
            }
        }
    }

    private fun loadChapter(chapterIndex: Int) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _chapterLoadState.value = _chapterLoadState.value.copy(isLoading = true, error = null)
            hasReachedEndForCurrentChapter = false
            try {
                val novel = novelRepository.novelDao.getNovelById(novelId)?.toModel()
                val chapters = novelRepository.chapterDao.getChapters(novelId).map { it.toModel() }
                val novelTitle = novel?.title.orEmpty()
                val chapterCount = chapters.size

                if (chapters.isEmpty()) {
                    _chapterLoadState.value = ChapterLoadState(
                        isLoading = false,
                        novelTitle = novelTitle,
                        chapterCount = 0,
                        error = "No chapters found for this novel"
                    )
                    return@launch
                }

                val targetChapter = chapters.firstOrNull { it.chapterIndex == chapterIndex }
                    ?: chapters.first()
                currentChapterModel = targetChapter

                val content = chapterRepository.getChapterContent(novelId, targetChapter.id)
                var paragraphs = content.paragraphs

                val settings = settingsRepository.settings.value
                if (settings.reader.globalTermReplacementsEnabled) {
                    paragraphs = ReaderMapping.applyTermReplacements(
                        paragraphs,
                        novelRepository.termReplacements.value
                    )
                }
                currentChapterParagraphs = paragraphs

                val currentIndexInList = chapters.indexOfFirst { it.id == targetChapter.id }
                val hasPrevious = currentIndexInList > 0
                val hasNext = currentIndexInList in 0 until chapters.lastIndex

                val readerChapter = ReaderChapter(
                    index = targetChapter.chapterIndex,
                    number = targetChapter.chapterIndex,
                    title = targetChapter.title,
                    paragraphs = paragraphs,
                    hasPrevious = hasPrevious,
                    hasNext = hasNext
                )

                val rawSavedPos = novelRepository.getChapterReadingPosition(novelId, targetChapter.id)
                val startParagraph = if (settings.reader.saveReadingPosition) rawSavedPos else 0

                val contents = ReaderMapping.mapToReaderContents(chapters, targetChapter.chapterIndex)

                _chapterLoadState.value = ChapterLoadState(
                    isLoading = false,
                    novelTitle = novelTitle,
                    chapter = readerChapter,
                    startParagraph = startParagraph,
                    chapterCount = chapterCount,
                    contents = contents,
                    error = null
                )

                val autoAhead = settings.downloads.autoDownloadAhead
                if (autoAhead > 0 && novel?.isEpub != true) {
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            downloadEngine.autoDownloadNext(novelId, targetChapter.chapterIndex, autoAhead)
                        } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _chapterLoadState.value = _chapterLoadState.value.copy(
                    isLoading = false,
                    error = ReaderMapping.mapReaderError(e)
                )
            }
        }
    }

    fun onRetry() {
        loadChapter(currentChapterIndexFlow.value)
    }

    fun onToggleControls() {
        _controlsVisible.value = !_controlsVisible.value
    }

    fun onOpenPanel(panel: ReaderPanel) {
        _panel.value = panel
    }

    fun onClosePanel() {
        _panel.value = ReaderPanel.NONE
    }

    fun onDisplayChange(display: ReaderDisplay) {
        settingsRepository.updateReaderSettings { current ->
            ReaderMapping.mapToReaderSettings(current, display)
        }
    }

    fun onVisibleParagraph(paragraphIndex: Int) {
        val chapter = currentChapterModel ?: return
        val settings = settingsRepository.settings.value
        if (settings.reader.saveReadingPosition) {
            novelRepository.saveChapterReadingPositionDebounced(novelId, chapter.id, paragraphIndex, 0)
            viewModelScope.launch(Dispatchers.IO) {
                novelRepository.novelDao.updateLastRead(novelId, chapter.id, System.currentTimeMillis())
            }
        }
    }

    fun onReachedEnd() {
        if (hasReachedEndForCurrentChapter) return
        hasReachedEndForCurrentChapter = true
        val chapter = currentChapterModel ?: return
        viewModelScope.launch {
            val wasUnread = novelRepository.markChapterAsRead(novelId, chapter.id)
            chapterFinishedListener.onChapterFinished(novelId, chapter.chapterIndex, wasUnread)
        }
    }

    fun onPreviousChapter() {
        val currentContents = _chapterLoadState.value.contents
        val currentIndexInList = currentContents.indexOfFirst { it.isCurrent }
        if (currentIndexInList > 0) {
            val prev = currentContents[currentIndexInList - 1]
            onChapterSelected(prev.index)
        }
    }

    fun onNextChapter() {
        val currentContents = _chapterLoadState.value.contents
        val currentIndexInList = currentContents.indexOfFirst { it.isCurrent }
        if (currentIndexInList in 0 until currentContents.lastIndex) {
            val next = currentContents[currentIndexInList + 1]
            onChapterSelected(next.index)
        }
    }

    fun onChapterSelected(chapterIndex: Int) {
        if (currentChapterIndexFlow.value == chapterIndex && _chapterLoadState.value.chapter != null) {
            _panel.value = ReaderPanel.NONE
            return
        }
        viewModelScope.launch {
            novelRepository.flushReadingPosition()
        }
        _panel.value = ReaderPanel.NONE
        savedStateHandle[KEY_CHAPTER_INDEX] = chapterIndex
    }

    fun onToggleBookmark(paragraphIndex: Int) {
        val chapter = currentChapterModel ?: return
        val existing = novelRepository.bookmarks.value.firstOrNull {
            it.novelId == novelId && it.chapterId == chapter.id && it.paragraphIndex == paragraphIndex
        }
        viewModelScope.launch {
            if (existing != null) {
                novelRepository.removeBookmark(existing.id)
            } else {
                val excerpt = currentChapterParagraphs.getOrNull(paragraphIndex)?.take(120).orEmpty()
                val bm = Bookmark(
                    id = UUID.randomUUID().toString(),
                    novelId = novelId,
                    chapterId = chapter.id,
                    chapterTitle = chapter.title,
                    paragraphIndex = paragraphIndex,
                    excerpt = excerpt
                )
                novelRepository.addBookmark(bm)
            }
        }
    }

    fun onBack(navigateUp: () -> Unit) {
        viewModelScope.launch {
            try {
                novelRepository.flushReadingPosition()
            } catch (_: Exception) {}
            navigateUp()
        }
    }

    fun onMessageShown() {
        _message.value = null
    }

    override fun onCleared() {
        super.onCleared()
        runBlocking {
            try {
                novelRepository.flushReadingPosition()
            } catch (_: Exception) {}
        }
    }
}
