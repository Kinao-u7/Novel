package com.example.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.db.NovelDatabase
import com.example.data.local.db.NovelDatabaseHolder
import com.example.data.local.db.entity.ChapterEntity
import com.example.data.local.db.toEntity
import com.example.data.model.Bookmark
import com.example.data.model.Chapter
import com.example.data.model.ChapterContent
import com.example.data.model.Novel
import com.example.data.source.NovelSource
import com.example.data.source.SourceManager
import com.example.data.source.webscrape.ScrapedChapter
import com.example.data.source.webscrape.ScrapedNovelData
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NovelRepositoryTest {

    private lateinit var context: Context
    private lateinit var database: NovelDatabase
    private lateinit var repository: NovelRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, NovelDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        NovelDatabaseHolder.setInstanceForTesting(database)
        repository = NovelRepository(context)
    }

    @After
    fun tearDown() {
        NovelDatabaseHolder.clearInstanceForTesting()
        database.close()
    }

    private fun createTestNovel(
        id: String = "test_novel_1",
        title: String = "Test Novel",
        inLibrary: Boolean = true,
        lastReadTimestamp: Long = 0L,
        sourceId: String = "test_src"
    ): Novel {
        return Novel(
            id = id,
            title = title,
            author = "Author",
            coverUrl = null,
            description = "Description",
            genres = listOf("Fantasy"),
            status = "Ongoing",
            sourceId = sourceId,
            sourceName = "Test Source",
            inLibrary = inLibrary,
            totalChapters = 10,
            downloadedChaptersCount = 0,
            rating = 4.5f,
            lastReadTimestamp = lastReadTimestamp
        )
    }

    @Test
    fun libraryNovels_startsEmpty_andReflectsInLibraryNovelsOnly() = runBlocking {
        // Starts empty
        val initial = repository.libraryNovels.value
        assertTrue(initial.isEmpty())

        // Add novel to library
        val novel = createTestNovel(id = "novel_1", inLibrary = true)
        repository.addToLibrary(novel)

        val updated = repository.libraryNovels.first { it.isNotEmpty() }
        assertEquals(1, updated.size)
        assertEquals("novel_1", updated.first().id)
        assertTrue(repository.isNovelInLibrary("novel_1"))

        // Add novel with inLibrary = false
        val browseNovel = createTestNovel(id = "novel_2", inLibrary = false)
        database.novelDao().upsertSafe(browseNovel.toEntity())

        // Library must still contain only 1 novel
        val libAfterBrowse = repository.libraryNovels.first { it.any { n -> n.id == "novel_1" } }
        assertEquals(1, libAfterBrowse.size)
        assertFalse(repository.isNovelInLibrary("novel_2"))
    }

    @Test
    fun removeFromLibrary_setsInLibraryFalse_doesNotDeleteRow_removeCompletelyDeletesRow() = runBlocking {
        val novel = createTestNovel(id = "novel_1", inLibrary = true)
        repository.addToLibrary(novel)
        repository.libraryNovels.first { it.isNotEmpty() }

        // Remove from library (sets in_library = 0)
        repository.removeFromLibrary("novel_1")
        val afterRemove = repository.libraryNovels.first { it.isEmpty() }
        assertTrue(afterRemove.isEmpty())
        assertFalse(repository.isNovelInLibrary("novel_1"))

        // Row still exists in Room database
        val entityInDb = database.novelDao().getNovelById("novel_1")
        assertNotNull(entityInDb)
        assertFalse(entityInDb!!.inLibrary)

        // removeNovelCompletely deletes row completely
        repository.removeNovelCompletely("novel_1")
        val deletedRow = database.novelDao().getNovelById("novel_1")
        assertNull(deletedRow)
    }

    @Test
    fun chapterRefresh_preservesReadState_position_tier_andBookmarks() = runBlocking {
        val novel = createTestNovel(id = "novel_refresh", inLibrary = true, sourceId = "test_refresh_src")
        repository.addToLibrary(novel)

        // Insert initial chapter with custom read state, position, and tier
        val initialChapter = ChapterEntity(
            novelId = "novel_refresh",
            id = "ch_1",
            title = "Chapter 1 Initial",
            chapterIndex = 1,
            wordCount = 500,
            releaseDate = "2024-01-01",
            isRead = true,
            readParagraphIndex = 12,
            readCharOffset = 45,
            storageTier = "DOWNLOADED",
            storageLocation = "INTERNAL",
            fileHash = "initial_hash",
            lastAccessedAt = 12345L
        )
        database.chapterDao().insertIgnore(initialChapter)

        // Add a bookmark on this chapter
        val bookmark = Bookmark(
            id = "bm_refresh_1",
            novelId = "novel_refresh",
            chapterId = "ch_1",
            chapterTitle = "Chapter 1 Initial",
            paragraphIndex = 12,
            charOffset = 45,
            excerpt = "Important quote",
            note = "Note on quote",
            createdAt = 1000L
        )
        repository.addBookmark(bookmark)

        // Register a test source that returns updated title/wordCount for ch_1 and a new ch_2
        val testSource = object : NovelSource {
            override val id = "test_refresh_src"
            override val name = "Test Refresh Source"
            override val language = "English"
            override val version = "1.0"
            override val description = "Test source"
            override val isLocal = true

            override suspend fun getPopularNovels(page: Int) = emptyList<Novel>()
            override suspend fun searchNovels(query: String, page: Int) = emptyList<Novel>()
            override suspend fun getNovelDetails(novelId: String) = novel
            override suspend fun getChapterList(novelId: String): List<Chapter> = listOf(
                Chapter(
                    id = "ch_1",
                    novelId = novelId,
                    title = "Chapter 1 Updated Title",
                    chapterIndex = 1,
                    releaseDate = "2024-02-01",
                    isDownloaded = false,
                    isRead = false,
                    wordCount = 999
                ),
                Chapter(
                    id = "ch_2",
                    novelId = novelId,
                    title = "Chapter 2 New",
                    chapterIndex = 2,
                    releaseDate = "2024-02-01",
                    isDownloaded = false,
                    isRead = false,
                    wordCount = 1500
                )
            )
            override suspend fun getChapterContent(novelId: String, chapterId: String) =
                ChapterContent(chapterId, novelId, "Title", listOf("Paragraph 1"))
        }

        SourceManager.registerSource(testSource)
        try {
            val refreshedChapters = repository.getChapterList(novel, forceRefresh = true)
            assertEquals(2, refreshedChapters.size)

            val ch1After = database.chapterDao().getChapter("novel_refresh", "ch_1")
            assertNotNull(ch1After)
            // Updated metadata
            assertEquals("Chapter 1 Updated Title", ch1After!!.title)
            assertEquals(999, ch1After.wordCount)
            assertEquals("2024-02-01", ch1After.releaseDate)

            // Preserved read state, position, and tier
            assertTrue(ch1After.isRead)
            assertEquals(12, ch1After.readParagraphIndex)
            assertEquals(45, ch1After.readCharOffset)
            assertEquals("DOWNLOADED", ch1After.storageTier)
            assertEquals("INTERNAL", ch1After.storageLocation)
            assertEquals("initial_hash", ch1After.fileHash)
            assertEquals(12345L, ch1After.lastAccessedAt)

            // Preserved bookmark
            val bookmarks = repository.bookmarks.first { it.any { b -> b.id == "bm_refresh_1" } }
            assertTrue(bookmarks.any { it.id == "bm_refresh_1" && it.chapterId == "ch_1" })
        } finally {
            SourceManager.unregisterSource(testSource.id)
        }
    }

    @Test
    fun downloadedCount_isDerivedFromTiers_downloadedAndEpubCount_cacheAndNoneDoNot() = runBlocking {
        val novel = createTestNovel(id = "novel_tiers", inLibrary = true)
        repository.addToLibrary(novel)

        val chDownloaded = ChapterEntity(
            novelId = "novel_tiers",
            id = "ch_dl",
            title = "Downloaded Ch",
            chapterIndex = 1,
            storageTier = "DOWNLOADED"
        )
        val chEpub = ChapterEntity(
            novelId = "novel_tiers",
            id = "ch_ep",
            title = "EPUB Ch",
            chapterIndex = 2,
            storageTier = "EPUB"
        )
        val chCache = ChapterEntity(
            novelId = "novel_tiers",
            id = "ch_ca",
            title = "Cache Ch",
            chapterIndex = 3,
            storageTier = "CACHE"
        )
        val chNone = ChapterEntity(
            novelId = "novel_tiers",
            id = "ch_no",
            title = "None Ch",
            chapterIndex = 4,
            storageTier = "NONE"
        )
        database.chapterDao().insertIgnore(listOf(chDownloaded, chEpub, chCache, chNone))

        // Check libraryNovels derived count
        val libraryNovel = repository.libraryNovels.first { list ->
            list.any { it.id == "novel_tiers" && it.downloadedChaptersCount > 0 }
        }.first { it.id == "novel_tiers" }

        // DOWNLOADED (1) + EPUB (1) = 2; CACHE and NONE do not count
        assertEquals(2, libraryNovel.downloadedChaptersCount)

        // Check downloadedChapters StateFlow mapping
        val downloadedMap = repository.downloadedChapters.first { map ->
            map.containsKey("novel_tiers") && map["novel_tiers"]!!.isNotEmpty()
        }
        val downloadedSet = downloadedMap["novel_tiers"] ?: emptySet()
        assertEquals(setOf("ch_dl", "ch_ep"), downloadedSet)
        assertFalse(downloadedSet.contains("ch_ca"))
        assertFalse(downloadedSet.contains("ch_no"))
    }

    @Test
    fun readingChapter_setsCache_andNeverDownloaded() = runBlocking {
        val novel = createTestNovel(id = "novel_read_tier", inLibrary = true, sourceId = "test_read_src")
        repository.addToLibrary(novel)

        val chapter = ChapterEntity(
            novelId = "novel_read_tier",
            id = "ch_read_1",
            title = "Chapter 1",
            chapterIndex = 1,
            storageTier = "NONE"
        )
        database.chapterDao().insertIgnore(chapter)

        val testSource = object : NovelSource {
            override val id = "test_read_src"
            override val name = "Test Read Source"
            override val language = "English"
            override val version = "1.0"
            override val description = "Test source"
            override val isLocal = true

            override suspend fun getPopularNovels(page: Int) = emptyList<Novel>()
            override suspend fun searchNovels(query: String, page: Int) = emptyList<Novel>()
            override suspend fun getNovelDetails(novelId: String) = novel
            override suspend fun getChapterList(novelId: String) = emptyList<Chapter>()
            override suspend fun getChapterContent(novelId: String, chapterId: String) =
                ChapterContent(chapterId, novelId, "Chapter 1", listOf("Read paragraph content"))
        }

        SourceManager.registerSource(testSource)
        try {
            // Read chapter content
            val content = repository.getChapterContent("novel_read_tier", "ch_read_1")
            assertEquals("ch_read_1", content.chapterId)

            val chapterInDb = database.chapterDao().getChapter("novel_read_tier", "ch_read_1")
            assertNotNull(chapterInDb)
            assertEquals("CACHE", chapterInDb!!.storageTier)
            assertNotEquals("DOWNLOADED", chapterInDb.storageTier)

            // Reading does not increment downloadedChaptersCount
            val libraryNovel = repository.libraryNovels.first { list -> list.any { it.id == "novel_read_tier" } }
                .first { it.id == "novel_read_tier" }
            assertEquals(0, libraryNovel.downloadedChaptersCount)
        } finally {
            SourceManager.unregisterSource(testSource.id)
        }
    }

    @Test
    fun deleteDownloads_resetsDownloadedAndCacheToNone_leavesEpubRows() = runBlocking {
        val novel = createTestNovel(id = "novel_delete_dl", inLibrary = true)
        repository.addToLibrary(novel)

        val chDl = ChapterEntity(novelId = "novel_delete_dl", id = "ch_dl", title = "DL", chapterIndex = 1, storageTier = "DOWNLOADED")
        val chCache = ChapterEntity(novelId = "novel_delete_dl", id = "ch_cache", title = "Cache", chapterIndex = 2, storageTier = "CACHE")
        val chEpub = ChapterEntity(novelId = "novel_delete_dl", id = "ch_epub", title = "Epub", chapterIndex = 3, storageTier = "EPUB")
        val chNone = ChapterEntity(novelId = "novel_delete_dl", id = "ch_none", title = "None", chapterIndex = 4, storageTier = "NONE")
        database.chapterDao().insertIgnore(listOf(chDl, chCache, chEpub, chNone))

        repository.deleteDownloadedNovelChapters("novel_delete_dl")

        val chDlAfter = database.chapterDao().getChapter("novel_delete_dl", "ch_dl")
        val chCacheAfter = database.chapterDao().getChapter("novel_delete_dl", "ch_cache")
        val chEpubAfter = database.chapterDao().getChapter("novel_delete_dl", "ch_epub")
        val chNoneAfter = database.chapterDao().getChapter("novel_delete_dl", "ch_none")

        // DOWNLOADED and CACHE are reset to NONE
        assertEquals("NONE", chDlAfter?.storageTier)
        assertEquals("NONE", chCacheAfter?.storageTier)
        // EPUB is preserved
        assertEquals("EPUB", chEpubAfter?.storageTier)
        // NONE stays NONE
        assertEquals("NONE", chNoneAfter?.storageTier)
    }

    @Test
    fun webNovelDedupe_bySourceAndRawId_andByNormalizedSourceUrl() = runBlocking {
        // 1. Dedupe by (source_id, raw_id)
        val data1 = ScrapedNovelData(
            title = "Web Novel One",
            sourceUrl = "https://wtr-lab.com/novel/123/",
            rawId = "123",
            chapters = listOf(
                ScrapedChapter(title = "Ch 1", url = "https://wtr-lab.com/novel/123/1")
            )
        )
        val novel1 = repository.saveWebScrapedNovel(
            scrapedData = data1,
            customTitle = "Web Novel One",
            customAuthor = "Author One",
            customSynopsis = "Synopsis One",
            customTags = listOf("Action"),
            customCoverUrl = null
        )

        // Same rawId with different URL path
        val data2 = ScrapedNovelData(
            title = "Web Novel One",
            sourceUrl = "https://wtr-lab.com/novel/123/alternate-slug",
            rawId = "123",
            chapters = listOf(
                ScrapedChapter(title = "Ch 1", url = "https://wtr-lab.com/novel/123/1")
            )
        )
        val novel2 = repository.saveWebScrapedNovel(
            scrapedData = data2,
            customTitle = "Web Novel One",
            customAuthor = "Author One",
            customSynopsis = "Synopsis One",
            customTags = listOf("Action"),
            customCoverUrl = null
        )
        assertEquals(novel1.id, novel2.id)

        // 2. Dedupe by normalized source_url
        val data3 = ScrapedNovelData(
            title = "Web Novel Two",
            sourceUrl = "https://www.example.com/novel/xyz/",
            rawId = null,
            chapters = listOf(
                ScrapedChapter(title = "Ch 1", url = "https://www.example.com/novel/xyz/1")
            )
        )
        val novel3 = repository.saveWebScrapedNovel(
            scrapedData = data3,
            customTitle = "Web Novel Two",
            customAuthor = "Author Two",
            customSynopsis = "Synopsis Two",
            customTags = listOf("Romance"),
            customCoverUrl = null
        )

        // Same URL without www, without trailing slash
        val data4 = ScrapedNovelData(
            title = "Web Novel Two",
            sourceUrl = "https://example.com/novel/xyz",
            rawId = null,
            chapters = listOf(
                ScrapedChapter(title = "Ch 1", url = "https://example.com/novel/xyz/1")
            )
        )
        val novel4 = repository.saveWebScrapedNovel(
            scrapedData = data4,
            customTitle = "Web Novel Two",
            customAuthor = "Author Two",
            customSynopsis = "Synopsis Two",
            customTags = listOf("Romance"),
            customCoverUrl = null
        )
        assertEquals(novel3.id, novel4.id)
    }

    @Test
    fun newNovels_haveLastReadTimestampZero() = runBlocking {
        // Novel added directly
        val novel = createTestNovel(id = "novel_new", inLibrary = true, lastReadTimestamp = 0L)
        repository.addToLibrary(novel)

        val novelEntity = database.novelDao().getNovelById("novel_new")
        assertNotNull(novelEntity)
        assertEquals(0L, novelEntity!!.lastReadTimestamp)

        // Web novel added via saveWebScrapedNovel
        val scrapedData = ScrapedNovelData(
            title = "Brand New Web Novel",
            sourceUrl = "https://wtr-lab.com/novel/brand-new",
            chapters = listOf(
                ScrapedChapter(title = "Ch 1", url = "https://wtr-lab.com/novel/brand-new/1")
            )
        )
        val webNovel = repository.saveWebScrapedNovel(
            scrapedData = scrapedData,
            customTitle = "Brand New Web Novel",
            customAuthor = "Author",
            customSynopsis = "Synopsis",
            customTags = emptyList(),
            customCoverUrl = null
        )
        assertEquals(0L, webNovel.lastReadTimestamp)
        val webEntity = database.novelDao().getNovelById(webNovel.id)
        assertNotNull(webEntity)
        assertEquals(0L, webEntity!!.lastReadTimestamp)

        // Neither novel appears in reading history
        val history = repository.getReadingHistory()
        assertFalse(history.any { it.id == "novel_new" || it.id == webNovel.id })
    }

    @Test
    fun clearNovelReadingHistory_keepsLastReadChapterId() = runBlocking {
        val novel = createTestNovel(id = "novel_clear_hist", inLibrary = true, lastReadTimestamp = 0L)
        repository.addToLibrary(novel)

        repository.updateReadingProgress(
            novelId = "novel_clear_hist",
            chapterId = "ch_preserved",
            chapterTitle = "Chapter Preserved",
            chapterIndex = 10
        )

        val beforeClear = database.novelDao().getNovelById("novel_clear_hist")
        assertNotNull(beforeClear)
        assertEquals("ch_preserved", beforeClear!!.lastReadChapterId)
        assertTrue(beforeClear.lastReadTimestamp > 0L)
        assertTrue(repository.getReadingHistory().any { it.id == "novel_clear_hist" })

        // Clear history: resets last_read_timestamp to 0, keeps last_read_chapter_id
        repository.clearNovelReadingHistory("novel_clear_hist")

        val afterClear = database.novelDao().getNovelById("novel_clear_hist")
        assertNotNull(afterClear)
        assertEquals("ch_preserved", afterClear!!.lastReadChapterId)
        assertEquals(0L, afterClear.lastReadTimestamp)

        // Reading history no longer shows the novel
        assertFalse(repository.getReadingHistory().any { it.id == "novel_clear_hist" })
    }

    @Test
    fun markChapterAsRead_onlySetsIsRead_doesNotTouchLastRead() = runBlocking {
        val novel = createTestNovel(id = "novel_1", inLibrary = true, lastReadTimestamp = 0L)
        repository.addToLibrary(novel)

        val chapterEntity = ChapterEntity(
            novelId = "novel_1",
            id = "ch_1",
            title = "Chapter 1",
            chapterIndex = 1,
            isRead = false
        )
        database.chapterDao().insertIgnore(chapterEntity)

        repository.markChapterAsRead("novel_1", "ch_1")

        val chAfter = database.chapterDao().getChapter("novel_1", "ch_1")
        assertNotNull(chAfter)
        assertTrue(chAfter!!.isRead)

        // Novel's lastReadTimestamp should still be 0L
        val novelEntity = database.novelDao().getNovelById("novel_1")
        assertEquals(0L, novelEntity!!.lastReadTimestamp)
        assertNull(novelEntity.lastReadChapterId)
    }

    @Test
    fun getReadChapterIds_returnsReadChapterIdsFlow() = runBlocking {
        val novel = createTestNovel(id = "novel_read_ids", inLibrary = true)
        repository.addToLibrary(novel)

        val ch1 = ChapterEntity(novelId = "novel_read_ids", id = "ch_1", title = "Ch 1", chapterIndex = 1, isRead = false)
        val ch2 = ChapterEntity(novelId = "novel_read_ids", id = "ch_2", title = "Ch 2", chapterIndex = 2, isRead = false)
        database.chapterDao().insertIgnore(listOf(ch1, ch2))

        val initialIds = repository.getReadChapterIds("novel_read_ids").first()
        assertTrue(initialIds.isEmpty())

        repository.markChapterAsRead("novel_read_ids", "ch_1")

        val updatedIds = repository.getReadChapterIds("novel_read_ids").first { it.isNotEmpty() }
        assertEquals(setOf("ch_1"), updatedIds)
    }

    @Test
    fun bookmarks_crud_persistsInRoom() = runBlocking {
        val novel = createTestNovel(id = "novel_1", inLibrary = true)
        repository.addToLibrary(novel)

        val bookmark = Bookmark(
            id = "bm_1",
            novelId = "novel_1",
            chapterId = "ch_1",
            chapterTitle = "Chapter 1",
            paragraphIndex = 2,
            charOffset = 15,
            excerpt = "Some highlighted text",
            note = "My note",
            createdAt = 1000L
        )

        repository.addBookmark(bookmark)

        val bookmarksForNovel = repository.bookmarks.first { it.isNotEmpty() }
        assertEquals(1, bookmarksForNovel.size)
        assertEquals("bm_1", bookmarksForNovel.first().id)
        assertEquals("Some highlighted text", bookmarksForNovel.first().excerpt)

        repository.removeBookmark("bm_1")
        val bookmarksAfterRemove = repository.bookmarks.first { it.isEmpty() }
        assertTrue(bookmarksAfterRemove.isEmpty())
    }
}
