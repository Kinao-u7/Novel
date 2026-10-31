package com.example.ui.screens.library

import com.example.data.model.LibrarySort
import com.example.data.model.Novel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryMappingTest {

    private fun createNovel(
        id: String,
        title: String = "Title $id",
        author: String = "Author $id",
        totalChapters: Int = 100,
        lastReadTimestamp: Long = 0L,
        lastReadIndex: Int = 0,
        lastReadTitle: String? = null,
        rating: Float? = null
    ): Novel = Novel(
        id = id,
        title = title,
        author = author,
        description = "Description $id",
        sourceId = "test_source",
        sourceName = "Test Source",
        totalChapters = totalChapters,
        lastReadTimestamp = lastReadTimestamp,
        lastReadChapterIndex = lastReadIndex,
        lastReadChapterTitle = lastReadTitle,
        rating = rating
    )

    @Test
    fun filterNovelsBySearch_matchesTitleAndAuthorCaseInsensitive() {
        val n1 = createNovel("1", title = "The Great Grandmaster", author = "John Doe")
        val n2 = createNovel("2", title = "Cultivation Path", author = "Alice Grand")
        val n3 = createNovel("3", title = "Solo Hunter", author = "Bob Smith")
        val novels = listOf(n1, n2, n3)

        assertEquals(listOf(n1, n2), filterNovelsBySearch(novels, "grand"))
        assertEquals(listOf(n1, n2), filterNovelsBySearch(novels, "GRAND"))
        assertEquals(listOf(n3), filterNovelsBySearch(novels, "hunter"))
        assertEquals(listOf(n1, n2, n3), filterNovelsBySearch(novels, "   "))
        assertEquals(emptyList<Novel>(), filterNovelsBySearch(novels, "xyz123"))
    }

    @Test
    fun filterNovelsByShelf_handlesAllReadingFinished() {
        val unread = createNovel("unread", totalChapters = 10)
        val reading = createNovel("reading", totalChapters = 10)
        val finished = createNovel("finished", totalChapters = 10)
        val novels = listOf(unread, reading, finished)
        val readCounts = mapOf(
            "unread" to 0,
            "reading" to 4,
            "finished" to 10
        )

        assertEquals(novels, filterNovelsByShelf(novels, readCounts, LibraryFilter.ALL))
        assertEquals(listOf(reading), filterNovelsByShelf(novels, readCounts, LibraryFilter.READING))
        assertEquals(listOf(finished), filterNovelsByShelf(novels, readCounts, LibraryFilter.FINISHED))
    }

    @Test
    fun sortLibraryNovels_recentlyReadPutsNeverReadLastAlphabetically() {
        val n1 = createNovel("1", title = "Bravo", lastReadTimestamp = 100L)
        val n2 = createNovel("2", title = "Alpha", lastReadTimestamp = 200L)
        val n3 = createNovel("3", title = "Delta", lastReadTimestamp = 0L)
        val n4 = createNovel("4", title = "Charlie", lastReadTimestamp = 0L)
        val novels = listOf(n3, n1, n4, n2)

        val sorted = sortLibraryNovels(novels, LibrarySort.RECENTLY_READ)
        assertEquals(listOf("2", "1", "4", "3"), sorted.map { it.id })
    }

    @Test
    fun sortLibraryNovels_titleAndAuthorAndChapters() {
        val n1 = createNovel("1", title = "Zeta", author = "Beta", totalChapters = 50)
        val n2 = createNovel("2", title = "Alpha", author = "Omega", totalChapters = 200)
        val novels = listOf(n1, n2)

        assertEquals(listOf("2", "1"), sortLibraryNovels(novels, LibrarySort.TITLE).map { it.id })
        assertEquals(listOf("1", "2"), sortLibraryNovels(novels, LibrarySort.AUTHOR).map { it.id })
        assertEquals(listOf("2", "1"), sortLibraryNovels(novels, LibrarySort.TOTAL_CHAPTERS).map { it.id })
    }

    @Test
    fun sortLibraryNovels_ratingDescendingWithNullLast() {
        val n1 = createNovel("1", title = "Mid", rating = 3.5f)
        val n2 = createNovel("2", title = "High", rating = 4.9f)
        val n3 = createNovel("3", title = "None", rating = null)
        val n4 = createNovel("4", title = "Low", rating = 2.0f)
        val novels = listOf(n3, n1, n4, n2)

        val sorted = sortLibraryNovels(novels, LibrarySort.RATING)
        assertEquals(listOf("2", "1", "4", "3"), sorted.map { it.id })
    }

    @Test
    fun computeContinueReading_picksLatestUnfinished() {
        val finished = createNovel("finished", lastReadTimestamp = 500L, totalChapters = 10)
        val active1 = createNovel("active1", lastReadTimestamp = 200L, lastReadIndex = 5, lastReadTitle = "Ch 5", totalChapters = 50)
        val active2 = createNovel("active2", lastReadTimestamp = 400L, lastReadIndex = 12, lastReadTitle = "Ch 12", totalChapters = 50)
        val unread = createNovel("unread", lastReadTimestamp = 0L, totalChapters = 30)

        val novels = listOf(finished, active1, active2, unread)
        val readCounts = mapOf(
            "finished" to 10,
            "active1" to 5,
            "active2" to 12,
            "unread" to 0
        )

        val cr = computeContinueReading(novels, readCounts)
        assertNotNull(cr)
        assertEquals("active2", cr?.novelId)
        assertEquals(12, cr?.chapterNumber)
        assertEquals("Ch 12", cr?.chapterTitle)
        assertEquals(12, cr?.readCount)
    }

    @Test
    fun computeContinueReading_returnsNullWhenAllFinishedOrNeverRead() {
        val finished = createNovel("finished", lastReadTimestamp = 500L, totalChapters = 10)
        val unread = createNovel("unread", lastReadTimestamp = 0L, totalChapters = 30)
        val readCounts = mapOf("finished" to 10, "unread" to 0)

        assertNull(computeContinueReading(listOf(finished, unread), readCounts))
    }
}
