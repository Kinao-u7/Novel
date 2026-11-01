package com.example.ui.screens.detail

import com.example.data.model.Chapter
import org.junit.Assert.assertEquals
import org.junit.Test

class DetailMappingTest {

    private fun createChapter(
        index: Int,
        isRead: Boolean = false,
        isDownloaded: Boolean = false
    ): Chapter = Chapter(
        id = "ch_$index",
        novelId = "novel_1",
        title = "Chapter $index",
        chapterIndex = index,
        isRead = isRead,
        isDownloaded = isDownloaded
    )

    @Test
    fun selectChaptersForDownload_next5_takesFromAfterCurrent() {
        val chapters = (0 until 20).map { i ->
            createChapter(
                index = i,
                isRead = i < 3,
                isDownloaded = (i == 4)
            )
        }
        // Current index is 2. Unread chapters are 3, 4(downloaded), 5, 6, 7, 8...
        // Unread & not downloaded after 2: 3, 5, 6, 7, 8
        val selected = selectChaptersForDownload(chapters, currentChapterIndex = 2, DownloadScope.NEXT_5)
        assertEquals(5, selected.size)
        assertEquals(listOf(3, 5, 6, 7, 8), selected.map { it.chapterIndex })
    }

    @Test
    fun selectChaptersForDownload_next25_takesFromAfterCurrentOrFallback() {
        val chapters = (0 until 40).map { i ->
            createChapter(index = i, isRead = false, isDownloaded = false)
        }
        val selected = selectChaptersForDownload(chapters, currentChapterIndex = 10, DownloadScope.NEXT_25)
        assertEquals(25, selected.size)
        assertEquals((11..35).toList(), selected.map { it.chapterIndex })
    }

    @Test
    fun selectChaptersForDownload_allUnread_skipsReadAndDownloaded() {
        val chapters = listOf(
            createChapter(0, isRead = true, isDownloaded = false),
            createChapter(1, isRead = false, isDownloaded = true),
            createChapter(2, isRead = false, isDownloaded = true),
            createChapter(3, isRead = false, isDownloaded = false),
            createChapter(4, isRead = false, isDownloaded = false)
        )
        val selected = selectChaptersForDownload(chapters, currentChapterIndex = 0, DownloadScope.ALL_UNREAD)
        assertEquals(listOf(3, 4), selected.map { it.chapterIndex })
    }

    @Test
    fun selectChaptersForDownload_nullOrEndOfBook_fallbackToFirstUnread() {
        val chapters = listOf(
            createChapter(0, isRead = false, isDownloaded = false),
            createChapter(1, isRead = false, isDownloaded = false)
        )
        val selected = selectChaptersForDownload(chapters, currentChapterIndex = 10, DownloadScope.NEXT_5)
        assertEquals(listOf(0, 1), selected.map { it.chapterIndex })
    }
}
