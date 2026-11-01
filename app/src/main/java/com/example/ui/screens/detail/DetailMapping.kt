package com.example.ui.screens.detail

import com.example.data.model.Chapter
import com.example.data.model.Novel

fun selectChaptersForDownload(
    chapters: List<Chapter>,
    currentChapterIndex: Int?,
    scope: DownloadScope
): List<Chapter> {
    val unreadNotDownloaded = chapters.filter { chapter ->
        !chapter.isRead && !chapter.isDownloaded
    }

    return when (scope) {
        DownloadScope.NEXT_5 -> {
            val afterCurrent = if (currentChapterIndex != null && currentChapterIndex >= 0) {
                unreadNotDownloaded.filter { it.chapterIndex > currentChapterIndex }
            } else {
                emptyList()
            }
            if (afterCurrent.isNotEmpty()) {
                afterCurrent.take(5)
            } else {
                unreadNotDownloaded.take(5)
            }
        }
        DownloadScope.NEXT_25 -> {
            val afterCurrent = if (currentChapterIndex != null && currentChapterIndex >= 0) {
                unreadNotDownloaded.filter { it.chapterIndex > currentChapterIndex }
            } else {
                emptyList()
            }
            if (afterCurrent.isNotEmpty()) {
                afterCurrent.take(25)
            } else {
                unreadNotDownloaded.take(25)
            }
        }
        DownloadScope.ALL_UNREAD -> unreadNotDownloaded
    }
}

fun mapToDetailNovel(
    novel: Novel,
    chapters: List<Chapter>,
    sourceName: String
): DetailNovel {
    val isCompleted = novel.status.equals("Completed", ignoreCase = true)
    val readCount = chapters.count { it.isRead }
    val totalChapters = if (chapters.isNotEmpty()) chapters.size else novel.totalChapters

    val (resumeIdx, resumeTitle) = if (novel.lastReadTimestamp > 0L) {
        val ch = chapters.firstOrNull { it.chapterIndex == novel.lastReadChapterIndex || it.id == novel.lastReadChapterId }
        val idx = ch?.chapterIndex ?: novel.lastReadChapterIndex
        val title = ch?.title ?: novel.lastReadChapterTitle
        idx to title
    } else {
        val firstUnread = chapters.firstOrNull { !it.isRead } ?: chapters.firstOrNull()
        val idx = firstUnread?.chapterIndex ?: 0
        val title = firstUnread?.title
        idx to title
    }

    return DetailNovel(
        id = novel.id,
        title = novel.title,
        author = novel.author,
        description = novel.description,
        coverUrl = novel.coverUrl,
        genres = novel.genres,
        sourceName = sourceName,
        isCompleted = isCompleted,
        totalChapters = totalChapters,
        readCount = readCount,
        inLibrary = novel.inLibrary,
        resumeChapterIndex = resumeIdx,
        resumeChapterNumber = resumeIdx,
        resumeChapterTitle = resumeTitle,
        isEpub = novel.isEpub || novel.sourceId == "local_epub",
        sourceUrl = novel.sourceUrl
    )
}

fun mapToDetailChapters(
    chapters: List<Chapter>,
    novel: Novel?,
    oldestFirst: Boolean
): List<DetailChapter> {
    val sorted = if (oldestFirst) {
        chapters.sortedBy { it.chapterIndex }
    } else {
        chapters.sortedByDescending { it.chapterIndex }
    }

    return sorted.map { chapter ->
        val isCurrent = novel != null && novel.lastReadTimestamp > 0L &&
            (chapter.chapterIndex == novel.lastReadChapterIndex || chapter.id == novel.lastReadChapterId)
        val isDownloaded = chapter.isDownloaded
        DetailChapter(
            index = chapter.chapterIndex,
            number = chapter.chapterIndex,
            title = chapter.title,
            isRead = chapter.isRead,
            isCurrent = isCurrent,
            isDownloaded = isDownloaded
        )
    }
}

fun mapToDetailUiState(
    novel: Novel?,
    chapters: List<Chapter>,
    sourceName: String,
    oldestFirst: Boolean,
    isLoading: Boolean,
    error: String?,
    message: String?
): DetailUiState {
    if (novel == null) {
        return DetailUiState(
            isLoading = isLoading,
            novel = null,
            chapters = emptyList(),
            oldestFirst = oldestFirst,
            categoriesEnabled = false,
            categories = emptyList(),
            novelCategoryIds = emptySet(),
            error = error,
            message = message
        )
    }

    val detailNovel = mapToDetailNovel(novel, chapters, sourceName)
    val detailChapters = mapToDetailChapters(chapters, novel, oldestFirst)

    return DetailUiState(
        isLoading = isLoading,
        novel = detailNovel,
        chapters = detailChapters,
        oldestFirst = oldestFirst,
        categoriesEnabled = false,
        categories = emptyList(),
        novelCategoryIds = emptySet(),
        error = error,
        message = message
    )
}
