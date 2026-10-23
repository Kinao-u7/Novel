package com.example.data.local.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.db.dao.BookmarkDao
import com.example.data.local.db.dao.ChapterDao
import com.example.data.local.db.dao.DownloadQueueDao
import com.example.data.local.db.dao.NovelDao
import com.example.data.local.db.dao.ReadingSessionDao
import com.example.data.local.db.entity.BookmarkEntity
import com.example.data.local.db.entity.ChapterEntity
import com.example.data.local.db.entity.DownloadQueueEntity
import com.example.data.local.db.entity.NovelEntity
import com.example.data.local.db.entity.ReadingSessionEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NovelDatabaseTest {

    private lateinit var database: NovelDatabase
    private lateinit var novelDao: NovelDao
    private lateinit var chapterDao: ChapterDao
    private lateinit var bookmarkDao: BookmarkDao
    private lateinit var downloadQueueDao: DownloadQueueDao
    private lateinit var readingSessionDao: ReadingSessionDao

    @Before
    fun setup() {
        com.example.data.download.DownloadCoordinator.resetInstanceForTesting()
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, NovelDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        novelDao = database.novelDao()
        chapterDao = database.chapterDao()
        bookmarkDao = database.bookmarkDao()
        downloadQueueDao = database.downloadQueueDao()
        readingSessionDao = database.readingSessionDao()
    }

    @After
    fun tearDown() {
        database.close()
        com.example.data.download.DownloadCoordinator.resetInstanceForTesting()
    }

    @Test
    fun compositeKeys_allowSameChapterIdAcrossDifferentNovels_andPreventDuplicatesWithinSameNovel() = runBlocking {
        val novel1 = createTestNovel(id = "novel_1", title = "Novel One")
        val novel2 = createTestNovel(id = "novel_2", title = "Novel Two")
        novelDao.insertIgnore(novel1)
        novelDao.insertIgnore(novel2)

        // Same chapter id ("ch_shared") across two different novels must coexist
        val chNovel1 = ChapterEntity(
            novelId = "novel_1",
            id = "ch_shared",
            title = "Chapter 1 in Novel 1",
            chapterIndex = 1
        )
        val chNovel2 = ChapterEntity(
            novelId = "novel_2",
            id = "ch_shared",
            title = "Chapter 1 in Novel 2",
            chapterIndex = 1
        )

        val rowId1 = chapterDao.insertIgnore(chNovel1)
        val rowId2 = chapterDao.insertIgnore(chNovel2)
        assertTrue(rowId1 > 0)
        assertTrue(rowId2 > 0)

        val retrieved1 = chapterDao.getChapter("novel_1", "ch_shared")
        val retrieved2 = chapterDao.getChapter("novel_2", "ch_shared")
        assertNotNull(retrieved1)
        assertNotNull(retrieved2)
        assertEquals("Chapter 1 in Novel 1", retrieved1?.title)
        assertEquals("Chapter 1 in Novel 2", retrieved2?.title)

        // Duplicate composite key in same novel must be ignored by insertIgnore
        val duplicateInsert = chapterDao.insertIgnore(chNovel1)
        assertEquals(-1L, duplicateInsert)

        // Composite key in download_queue: (novel_id, chapter_id)
        val queueItem1 = DownloadQueueEntity(novelId = "novel_1", chapterId = "ch_shared")
        val queueItem2 = DownloadQueueEntity(novelId = "novel_2", chapterId = "ch_shared")
        val qId1 = downloadQueueDao.insertIgnore(queueItem1)
        val qId2 = downloadQueueDao.insertIgnore(queueItem2)
        assertTrue(qId1 > 0)
        assertTrue(qId2 > 0)

        val duplicateQueue = downloadQueueDao.insertIgnore(queueItem1)
        assertEquals(-1L, duplicateQueue)
    }

    @Test
    fun cascadeDelete_removesChaptersAndBookmarks_whenNovelRemoved() = runBlocking {
        val novel = createTestNovel(id = "novel_cascade", title = "Cascade Novel")
        novelDao.insertIgnore(novel)

        val chapter1 = ChapterEntity(novelId = "novel_cascade", id = "ch_1", title = "Ch 1", chapterIndex = 1)
        val chapter2 = ChapterEntity(novelId = "novel_cascade", id = "ch_2", title = "Ch 2", chapterIndex = 2)
        chapterDao.insertIgnore(listOf(chapter1, chapter2))

        val bookmark = BookmarkEntity(
            id = "bm_1",
            novelId = "novel_cascade",
            chapterId = "ch_1",
            chapterTitle = "Ch 1",
            paragraphIndex = 5,
            charOffset = 10,
            excerpt = "Sample text excerpt"
        )
        bookmarkDao.insertIgnore(bookmark)

        val queueItem = DownloadQueueEntity(novelId = "novel_cascade", chapterId = "ch_1")
        downloadQueueDao.insertIgnore(queueItem)

        // Precondition check
        assertEquals(2, chapterDao.getChapters("novel_cascade").size)
        assertEquals(1, bookmarkDao.getBookmarksForNovel("novel_cascade").size)
        assertNotNull(downloadQueueDao.getItem("novel_cascade", "ch_1"))

        // Delete novel
        novelDao.deleteById("novel_cascade")

        // Chapters, bookmarks, and download queue must be cascade deleted
        assertEquals(0, chapterDao.getChapters("novel_cascade").size)
        assertEquals(0, bookmarkDao.getBookmarksForNovel("novel_cascade").size)
        assertNull(downloadQueueDao.getItem("novel_cascade", "ch_1"))
    }

    @Test
    fun readingSessions_surviveNovelDeletion() = runBlocking {
        val novel = createTestNovel(id = "novel_session", title = "Session Novel")
        novelDao.insertIgnore(novel)

        val session = ReadingSessionEntity(
            novelId = "novel_session",
            novelTitle = "Session Novel Snapshot Title",
            chapterId = "ch_42",
            startTime = 1700000000000L,
            durationMs = 180000L,
            wordsRead = 750,
            dateKey = "2026-10-11"
        )
        val sessionId = readingSessionDao.insert(session)
        assertTrue(sessionId > 0)

        // Delete novel
        novelDao.deleteById("novel_session")

        // Reading session must survive novel deletion (no foreign key constraint)
        val sessions = readingSessionDao.getSessionsForNovel("novel_session")
        assertEquals(1, sessions.size)
        val surviving = sessions.first()
        assertEquals("Session Novel Snapshot Title", surviving.novelTitle)
        assertEquals(750, surviving.wordsRead)
        assertEquals("2026-10-11", surviving.dateKey)
    }

    @Test
    fun chapterRefresh_keepsIsRead_position_tier_andBookmarks() = runBlocking {
        val novel = createTestNovel(id = "novel_refresh", title = "Refresh Novel")
        novelDao.insertIgnore(novel)

        // Initial chapter with established reading state, custom storage tier, and bookmarks
        val initialChapter = ChapterEntity(
            novelId = "novel_refresh",
            id = "ch_persist",
            title = "Old Draft Title",
            chapterIndex = 1,
            releaseDate = "2026-01-01",
            wordCount = 1200,
            isRead = true,
            readParagraphIndex = 18,
            readCharOffset = 45,
            storageTier = "DOWNLOADED",
            storageLocation = "INTERNAL",
            fileHash = "sha256_mock_hash"
        )
        chapterDao.insertIgnore(initialChapter)

        val bookmark = BookmarkEntity(
            id = "bm_persist",
            novelId = "novel_refresh",
            chapterId = "ch_persist",
            chapterTitle = "Old Draft Title",
            paragraphIndex = 18,
            charOffset = 45,
            excerpt = "Important sentence"
        )
        bookmarkDao.insertIgnore(bookmark)

        // Refresh batch with updated title, word count, and release date
        val updatedPayload = ChapterEntity(
            novelId = "novel_refresh",
            id = "ch_persist",
            title = "Official Chapter 1: The Beginning",
            chapterIndex = 1,
            releaseDate = "2026-02-15",
            wordCount = 2800,
            isRead = false, // Must NOT overwrite isRead
            readParagraphIndex = 0, // Must NOT overwrite position
            readCharOffset = 0,
            storageTier = "NONE", // Must NOT overwrite storage tier
            storageLocation = "INTERNAL",
            fileHash = ""
        )

        chapterDao.refreshChapters("novel_refresh", listOf(updatedPayload))

        val result = chapterDao.getChapter("novel_refresh", "ch_persist")
        assertNotNull(result)
        // Metadata columns must be updated
        assertEquals("Official Chapter 1: The Beginning", result?.title)
        assertEquals(2800, result?.wordCount)
        assertEquals("2026-02-15", result?.releaseDate)
        assertEquals(1, result?.chapterIndex)

        // Reading state, position, storage tier, location, and hash must be PRESERVED
        assertEquals(true, result?.isRead)
        assertEquals(18, result?.readParagraphIndex)
        assertEquals(45, result?.readCharOffset)
        assertEquals("DOWNLOADED", result?.storageTier)
        assertEquals("INTERNAL", result?.storageLocation)
        assertEquals("sha256_mock_hash", result?.fileHash)

        // Bookmark must still be present
        val bookmarks = bookmarkDao.getBookmarksForNovel("novel_refresh")
        assertEquals(1, bookmarks.size)
        assertEquals("bm_persist", bookmarks[0].id)
    }

    @Test
    fun nullableRating_preservesNullUnrated_andStoresNonNullAccurately() = runBlocking {
        // Novel with null rating (unrated)
        val unratedNovel = createTestNovel(id = "novel_unrated", title = "Unrated Novel", rating = null)
        novelDao.insertIgnore(unratedNovel)

        val retrievedUnrated = novelDao.getNovelById("novel_unrated")
        assertNotNull(retrievedUnrated)
        assertNull(retrievedUnrated?.rating)

        // Novel with explicit rating
        val ratedNovel = createTestNovel(id = "novel_rated", title = "Rated Novel", rating = 4.8f)
        novelDao.insertIgnore(ratedNovel)

        val retrievedRated = novelDao.getNovelById("novel_rated")
        assertNotNull(retrievedRated)
        assertEquals(4.8f, retrievedRated?.rating)

        // Updating rating back to null
        novelDao.updateRating("novel_rated", null)
        val clearedRating = novelDao.getNovelById("novel_rated")
        assertNull(clearedRating?.rating)
    }

    @Test
    fun upsertSafe_keepsInLibrary_rating_andLastReadFields() = runBlocking {
        val original = createTestNovel(
            id = "novel_preserve",
            title = "Original Title",
            rating = 4.5f,
            inLibrary = true
        ).copy(
            lastReadChapterId = "ch_5",
            lastReadTimestamp = 9999L
        )
        novelDao.insertIgnore(original)

        val updatedAttempt = createTestNovel(
            id = "novel_preserve",
            title = "New Remote Title",
            rating = null,
            inLibrary = false
        ).copy(
            lastReadChapterId = null,
            lastReadTimestamp = 0L
        )

        val canonicalId = novelDao.upsertSafe(updatedAttempt)
        assertEquals("novel_preserve", canonicalId)

        val row = novelDao.getNovelById("novel_preserve")
        assertNotNull(row)
        // Source owned fields updated
        assertEquals("New Remote Title", row?.title)
        // User state preserved
        assertEquals(true, row?.inLibrary)
        assertEquals(4.5f, row?.rating)
        assertEquals("ch_5", row?.lastReadChapterId)
        assertEquals(9999L, row?.lastReadTimestamp)
    }

    @Test
    fun upsertSafe_sameSourceAndRawIdWithDifferentId_returnsExistingId() = runBlocking {
        val original = createTestNovel(
            id = "novel_orig",
            title = "Original Novel",
            inLibrary = true
        ).copy(
            sourceId = "src_dedupe",
            rawId = "raw_42"
        )
        novelDao.insertIgnore(original)

        val duplicate = createTestNovel(
            id = "novel_new",
            title = "Duplicate With Different ID",
            inLibrary = false
        ).copy(
            sourceId = "src_dedupe",
            rawId = "raw_42"
        )

        val returnedId = novelDao.upsertSafe(duplicate)
        assertEquals("Must return existing novel ID", "novel_orig", returnedId)

        assertNotNull(novelDao.getNovelById("novel_orig"))
        assertNull("Conflicting ID must not be inserted", novelDao.getNovelById("novel_new"))
        assertEquals("Duplicate With Different ID", novelDao.getNovelById("novel_orig")?.title)
    }

    @Test
    fun upsertSafe_blankRawId_storedAsNull() = runBlocking {
        val novelWithBlankRaw = createTestNovel(
            id = "novel_blank_raw",
            title = "Blank Raw Novel"
        ).copy(rawId = "   ")

        novelDao.upsertSafe(novelWithBlankRaw)

        val retrieved = novelDao.getNovelById("novel_blank_raw")
        assertNotNull(retrieved)
        assertNull("Blank rawId must be normalized to null", retrieved?.rawId)
    }

    private fun createTestNovel(
        id: String,
        title: String,
        rating: Float? = null,
        inLibrary: Boolean = true
    ): NovelEntity {
        return NovelEntity(
            id = id,
            title = title,
            author = "Test Author",
            description = "Test Description",
            sourceId = "test_source",
            sourceName = "Test Source",
            sourceUrl = "https://example.com/novel/$id",
            status = "ONGOING",
            genresJson = "[\"Fantasy\"]",
            inLibrary = inLibrary,
            isEpub = false,
            totalChapters = 10,
            rating = rating,
            lastReadChapterId = null,
            lastReadTimestamp = 0L,
            createdAt = 1000L
        )
    }
}
