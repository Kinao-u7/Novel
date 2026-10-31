package com.example.ui.screens.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.example.data.local.db.toModel
import com.example.data.model.Chapter
import com.example.data.model.Novel
import com.example.data.repository.NovelRepository
import com.example.data.source.SourceManager
import com.example.ui.navigation.NovelDetail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DetailViewModel(
    private val repository: NovelRepository,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    companion object {
        private const val KEY_OLDEST_FIRST = "oldestFirst"
    }

    val novelId: String = try {
        savedStateHandle.toRoute<NovelDetail>().novelId
    } catch (_: Exception) {
        savedStateHandle.get<String>("novelId") ?: ""
    }

    private val _isLoading = MutableStateFlow(true)
    private val _error = MutableStateFlow<String?>(null)
    private val _message = MutableStateFlow<String?>(null)
    private val _fallbackNovel = MutableStateFlow<Novel?>(null)

    val oldestFirstFlow: StateFlow<Boolean> = savedStateHandle.getStateFlow(KEY_OLDEST_FIRST, true)

    private val novelFlow = repository.novelDao.getNovelByIdFlow(novelId).map { entity ->
        entity?.toModel()
    }

    private val chaptersFlow = repository.chapterDao.getChaptersFlow(novelId).map { list ->
        list.map { it.toModel() }
    }

    val uiState: StateFlow<DetailUiState> = combine(
        novelFlow,
        chaptersFlow,
        _fallbackNovel,
        oldestFirstFlow,
        _isLoading,
        _error,
        _message
    ) { args: Array<Any?> ->
        val roomNovel = args[0] as? Novel
        @Suppress("UNCHECKED_CAST")
        val chapters = (args[1] as? List<Chapter>) ?: emptyList()
        val fallback = args[2] as? Novel
        val oldestFirst = args[3] as Boolean
        val isLoading = args[4] as Boolean
        val error = args[5] as String?
        val message = args[6] as String?

        val activeNovel = roomNovel ?: fallback
        val sourceName = activeNovel?.let { novel ->
            SourceManager.getSourceById(novel.sourceId)?.name ?: novel.sourceName.ifBlank { novel.sourceId }
        } ?: ""

        val effectiveError = if (!isLoading && activeNovel == null && error == null) {
            "Novel not found"
        } else {
            error
        }

        mapToDetailUiState(
            novel = activeNovel,
            chapters = chapters,
            sourceName = sourceName,
            oldestFirst = oldestFirst,
            isLoading = isLoading,
            error = effectiveError,
            message = message
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DetailUiState(isLoading = true)
        )

    init {
        loadNovel(forceRefresh = false)
    }

    fun loadNovel(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val novel = repository.getNovel(novelId)
                if (novel == null) {
                    _error.value = "Novel not found"
                    return@launch
                }
                _fallbackNovel.value = novel

                // Fetch chapters if not EPUB and either forced refresh or no chapters in Room
                if (!novel.isEpub && novel.sourceId != "local_epub") {
                    try {
                        repository.getChapterList(novel, forceRefresh = forceRefresh)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        // If Room already has chapters, ignore network error; otherwise record error
                        val existing = repository.chapterDao.getChapters(novelId)
                        if (existing.isEmpty()) {
                            _error.value = e.message ?: "Failed to load chapters"
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _error.value = e.message ?: "Failed to load novel"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun onToggleLibrary() {
        viewModelScope.launch {
            try {
                val novelEntity = repository.novelDao.getNovelById(novelId)
                val currentInLibrary = novelEntity?.inLibrary ?: false
                if (!currentInLibrary) {
                    val novel = novelEntity?.toModel() ?: _fallbackNovel.value
                    if (novel != null) {
                        repository.addToLibrary(novel)
                        if (!novel.isEpub && novel.sourceId != "local_epub") {
                            try {
                                repository.getChapterList(novel)
                            } catch (_: Exception) {}
                        }
                    }
                    _message.value = "Added to library"
                } else {
                    repository.removeFromLibrary(novelId)
                    _message.value = "Removed from library"
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _message.value = "Failed to update library: ${e.message}"
            }
        }
    }

    fun onDownload(scope: DownloadScope) {
        viewModelScope.launch {
            try {
                val roomChapters = repository.chapterDao.getChapters(novelId).map { it.toModel() }
                val novel = repository.novelDao.getNovelById(novelId)?.toModel() ?: _fallbackNovel.value
                val currentIdx = novel?.lastReadChapterIndex ?: -1
                val selected = selectChaptersForDownload(roomChapters, currentIdx, scope)
                if (selected.isEmpty()) {
                    _message.value = "No chapters to download"
                    return@launch
                }
                val result = repository.enqueueChapters(novelId, selected.map { it.id })
                if (result.enqueuedCount > 0) {
                    _message.value = "Queued ${result.enqueuedCount} chapters"
                } else if (result.skippedCount > 0) {
                    val reason = result.skippedReasons.values.firstOrNull() ?: "Chapters already queued or downloaded"
                    _message.value = "Skipped: $reason"
                } else {
                    _message.value = "No chapters queued"
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _message.value = "Download failed: ${e.message}"
            }
        }
    }

    fun onSaveDetails(title: String, author: String) {
        viewModelScope.launch {
            try {
                val entity = repository.novelDao.getNovelById(novelId)
                val description = entity?.description ?: ""
                val coverUrl = entity?.coverUrl
                repository.updateNovelDetails(
                    novelId = novelId,
                    newTitle = title,
                    newAuthor = author,
                    newDescription = description,
                    newCoverUrl = coverUrl
                )
                _message.value = "Details saved"
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _message.value = "Failed to save details: ${e.message}"
            }
        }
    }

    fun onMarkAllRead(isRead: Boolean) {
        viewModelScope.launch {
            try {
                repository.setAllChaptersRead(novelId, isRead)
                _message.value = if (isRead) "Marked all as read" else "Marked all as unread"
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _message.value = "Failed to update read status: ${e.message}"
            }
        }
    }

    fun onToggleSortOrder() {
        val current = savedStateHandle.get<Boolean>(KEY_OLDEST_FIRST) ?: true
        savedStateHandle[KEY_OLDEST_FIRST] = !current
    }

    fun onRetry() {
        loadNovel(forceRefresh = true)
    }

    fun onMessageShown() {
        _message.value = null
    }
}
