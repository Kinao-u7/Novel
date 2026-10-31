package com.example.ui.screens.library

import com.example.data.model.LibrarySettings
import com.example.data.model.LibrarySort
import com.example.data.model.Novel

fun filterNovelsBySearch(novels: List<Novel>, query: String): List<Novel> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return novels
    return novels.filter { novel ->
        novel.title.contains(trimmed, ignoreCase = true) ||
            novel.author.contains(trimmed, ignoreCase = true)
    }
}

fun filterNovelsByShelf(
    novels: List<Novel>,
    readCounts: Map<String, Int>,
    filter: LibraryFilter
): List<Novel> {
    return when (filter) {
        LibraryFilter.ALL -> novels
        LibraryFilter.READING -> novels.filter { novel ->
            val readCount = readCounts[novel.id] ?: 0
            val isFinished = novel.totalChapters > 0 && readCount >= novel.totalChapters
            readCount > 0 && !isFinished
        }
        LibraryFilter.FINISHED -> novels.filter { novel ->
            val readCount = readCounts[novel.id] ?: 0
            novel.totalChapters > 0 && readCount >= novel.totalChapters
        }
    }
}

fun sortLibraryNovels(novels: List<Novel>, sort: LibrarySort): List<Novel> {
    return when (sort) {
        LibrarySort.RECENTLY_READ -> novels.sortedWith { a, b ->
            val aRead = a.lastReadTimestamp > 0L
            val bRead = b.lastReadTimestamp > 0L
            when {
                aRead && bRead -> b.lastReadTimestamp.compareTo(a.lastReadTimestamp)
                aRead && !bRead -> -1
                !aRead && bRead -> 1
                else -> a.title.compareTo(b.title, ignoreCase = true)
            }
        }
        LibrarySort.TITLE -> novels.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
        LibrarySort.AUTHOR -> novels.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.author })
        LibrarySort.TOTAL_CHAPTERS -> novels.sortedByDescending { it.totalChapters }
        LibrarySort.RATING -> novels.sortedWith { a, b ->
            val aRating = a.rating
            val bRating = b.rating
            when {
                aRating != null && bRating != null -> bRating.compareTo(aRating)
                aRating != null && bRating == null -> -1
                aRating == null && bRating != null -> 1
                else -> a.title.compareTo(b.title, ignoreCase = true)
            }
        }
    }
}

fun computeContinueReading(novels: List<Novel>, readCounts: Map<String, Int>): ContinueReading? {
    val candidate = novels
        .filter { it.lastReadTimestamp > 0L }
        .filter { novel ->
            val readCount = readCounts[novel.id] ?: 0
            val isFinished = novel.totalChapters > 0 && readCount >= novel.totalChapters
            !isFinished
        }
        .maxByOrNull { it.lastReadTimestamp } ?: return null

    val readCount = readCounts[candidate.id] ?: 0
    return ContinueReading(
        novelId = candidate.id,
        title = candidate.title,
        coverUrl = candidate.coverUrl,
        chapterNumber = candidate.lastReadChapterIndex,
        chapterTitle = candidate.lastReadChapterTitle,
        readCount = readCount,
        totalChapters = candidate.totalChapters
    )
}

fun buildLibraryNovelItem(novel: Novel, readCount: Int, downloadedCount: Int): LibraryNovelItem {
    return LibraryNovelItem(
        id = novel.id,
        title = novel.title,
        author = novel.author,
        coverUrl = novel.coverUrl,
        genres = novel.genres,
        totalChapters = novel.totalChapters,
        readCount = readCount,
        downloadedCount = downloadedCount,
        isEpub = novel.isEpub
    )
}

fun mapToLibraryUiState(
    libraryNovels: List<Novel>,
    readCounts: Map<String, Int>,
    downloadedCounts: Map<String, Int>,
    librarySettings: LibrarySettings,
    displayName: String,
    searchQuery: String,
    filter: LibraryFilter,
    isImporting: Boolean,
    message: String?
): LibraryUiState {
    val filteredByShelf = filterNovelsByShelf(libraryNovels, readCounts, filter)
    val filteredBySearch = filterNovelsBySearch(filteredByShelf, searchQuery)
    val sorted = sortLibraryNovels(filteredBySearch, librarySettings.sort)

    val novelItems = sorted.map { novel ->
        val readCount = readCounts[novel.id] ?: 0
        val downloadedCount = downloadedCounts[novel.id] ?: novel.downloadedChaptersCount
        buildLibraryNovelItem(novel, readCount, downloadedCount)
    }

    val continueReading = computeContinueReading(libraryNovels, readCounts)

    return LibraryUiState(
        isLoading = false,
        displayName = displayName,
        continueReading = continueReading,
        novels = novelItems,
        libraryCount = libraryNovels.size,
        searchQuery = searchQuery,
        filter = filter,
        sort = librarySettings.sort,
        viewMode = librarySettings.viewMode,
        categoriesEnabled = false,
        categories = emptyList(),
        selectedCategoryId = null,
        isImporting = isImporting,
        message = message
    )
}
