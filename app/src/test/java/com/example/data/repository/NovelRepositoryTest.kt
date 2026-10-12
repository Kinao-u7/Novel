package com.example.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.db.NovelDatabase
import com.example.data.local.db.NovelDatabaseHolder
import com.example.data.local.db.entity.BookmarkEntity
import com.example.data.local.db.entity.ChapterEntity
import com.example.data.local.db.entity.NotificationEntity
import com.example.data.local.db.toEntity
import com.example.data.model.Bookmark
import com.example.data.model.Chapter
import com.example.data.model.ChapterContent
import com.example.data.model.CustomNovelSource
import com.example.data.model.CustomSourceResult
import com.example.data.model.Novel
import com.example.data.model.TermReplacementRule
import com.example.data.source.NovelSource
import com.example.data.source.SourceManager
import com.example.data.source.webscrape.ScrapedChapter
import com.example.data.source.webscrape.ScrapedNovelData
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

    private fun testNovel(
        id: String = "test_novel_1",
        title: String = "Test Novel",
        author: String = "Author",
        coverUrl: String? = null,
        description: String = "Description",
        genres: List<String> = listOf("Fantasy"),
        status: String = "Ongoing",
        sourceId: String = "test_src",
        sourceName: String = "Test Source",
        inLibrary: Boolean = true,
        lastReadChapterId: String? = null,
        lastReadChapterTitle: String? = null,
        lastReadChapterIndex: Int = 0,
        lastReadScrollPosition: Int = 0,
        totalChapters: Int = 10,
        downloadedChaptersCount: Int = 0,
        rating: Float = 4.5f,
        lastReadTimestamp: Long = 0L,
        isEpub: Boolean = false,
        rawId: String? = null,
        locale: String? = null,
        sourceUrl: String? = null
    ): Novel = Novel(
        id = id,
        title = title,
        author = author,
        coverUrl = coverUrl,
        description = description,
        genres = genres,
        status = status,
        sourceId = sourceId,
        sourceName = sourceName,
        inLibrary = inLibrary,
        lastReadChapterId = lastReadChapterId,
        lastReadChapterTitle = lastReadChapterTitle,
        lastReadChapterIndex = lastReadChapterIndex,
        lastReadScrollPosition = lastReadScrollPosition,
        totalChapters = totalChapters,
        downloadedChaptersCount = downloadedChaptersCount,
        rating = rating,
        lastReadTimestamp = lastReadTimestamp,
        isEpub = isEpub,
        rawId = rawId,
        locale = locale,
        sourceUrl = sourceUrl
    )

    private fun testChapter(
        id: String = "test_ch_1",
        novelId: String = "test_novel_1",
        title: String = "Test Chapter 1",
        chapterIndex: Int = 1,
        releaseDate: String = "Recent",
        isDownloaded: Boolean = false,
        isRead: Boolean = false,
        wordCount: Int = 500
    ): Chapter = Chapter(
        id = id,
        novelId = novelId,
        title = title,
        chapterIndex = chapterIndex,
        releaseDate = releaseDate,
        isDownloaded = isDownloaded,
        isRead = isRead,
        wordCount = wordCount
    )

    private fun testBookmark(
        id: String = "test_bm_1",
        novelId: String = "test_novel_1",
        chapterId: String = "test_ch_1",
        chapterTitle: String = "Chapter 1",
        paragraphIndex: Int = 0,
        excerpt: String = "Excerpt",
        note: String = "",
        timestamp: Long = 1000L
    ): Bookmark = Bookmark(
        id = id,
        novelId = novelId,
        chapterId = chapterId,
        chapterTitle = chapterTitle,
        paragraphIndex = paragraphIndex,
        excerpt = excerpt,
        note = note,
        timestamp = timestamp
    )

    private fun testScrapedChapter(
        title: String = "Ch 1",
        url: String = "https://example.com/ch1",
        order: Int = 1
    ): ScrapedChapter = ScrapedChapter(
        title = title,
        url = url,
        order = order
    )

    private fun testScrapedNovelData(
        title: String = "Test Web Novel",
        author: String = "Author",
        coverUrl: String? = null,
        description: String = "Description",
        genres: List<String> = emptyList(),
        chapters: List<ScrapedChapter> = listOf(testScrapedChapter()),
        sourceUrl: String = "https://example.com/novel/1",
        rawId: String? = null,
        locale: String? = null,
        rating: Float? = null
    ): ScrapedNovelData = ScrapedNovelData(
        title = title,
        author = author,
        coverUrl = coverUrl,
        description = description,
        genres = genres,
        chapters = chapters,
        sourceUrl = sourceUrl,
        rawId = rawId,
        locale = locale,
        rating = rating
    )

    @Test
    fun libraryNovels_startsEmpty_andReflectsInLibraryNovelsOnly() = runBlocking {
        // Starts empty
        val initial = repository.libraryNovels.value
        assertTrue(initial.isEmpty())

        // Add novel to library
        val novel = testNovel(id = "novel_1", inLibrary = true)
        repository.addToLibrary(novel)

        val updated = repository.libraryNovels.first { it.isNotEmpty() }
        assertEquals(1, updated.size)
        assertEquals("novel_1", updated.first().id)
        assertTrue(repository.isNovelInLibrary("novel_1"))

        // Add novel with inLibrary = false
        val browseNovel = testNovel(id = "novel_2", inLibrary = false)
        database.novelDao().upsertSafe(browseNovel.toEntity())

        // Library must still contain only 1 novel
        val libAfterBrowse = repository.libraryNovels.first { it.any { n -> n.id == "novel_1" } }
        assertEquals(1, libAfterBrowse.size)
        assertFalse(repository.isNovelInLibrary("novel_2"))
    }

    @Test
    fun removeFromLibrary_setsInLibraryFalse_doesNotDeleteRow_removeCompletelyDeletesRow() = runBlocking {
        val novel = testNovel(id = "novel_1", inLibrary = true)
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
        val novel = testNovel(id = "novel_refresh", inLibrary = true, sourceId = "test_refresh_src")
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
        val bookmark = testBookmark(
            id = "bm_refresh_1",
            novelId = "novel_refresh",
            chapterId = "ch_1",
            chapterTitle = "Chapter 1 Initial",
            paragraphIndex = 12,
            excerpt = "Important quote",
            note = "Note on quote",
            timestamp = 1000L
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
                testChapter(
                    id = "ch_1",
                    novelId = novelId,
                    title = "Chapter 1 Updated Title",
                    chapterIndex = 1,
                    releaseDate = "2024-02-01",
                    isDownloaded = false,
                    isRead = false,
                    wordCount = 999
                ),
                testChapter(
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
        val novel = testNovel(id = "novel_tiers", inLibrary = true)
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
        val novel = testNovel(id = "novel_read_tier", inLibrary = true, sourceId = "test_read_src")
        repository.addToLibrary(novel)
        repository.libraryNovels.first { it.any { it.id == "novel_read_tier" } }

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
        val novel = testNovel(id = "novel_delete_dl", inLibrary = true)
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
        val data1 = testScrapedNovelData(
            title = "Web Novel One",
            author = "Author One",
            description = "Synopsis One",
            genres = listOf("Action"),
            sourceUrl = "https://wtr-lab.com/novel/123/",
            rawId = "123",
            chapters = listOf(
                testScrapedChapter(title = "Ch 1", url = "https://wtr-lab.com/novel/123/1", order = 1)
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
        val data2 = testScrapedNovelData(
            title = "Web Novel One",
            author = "Author One",
            description = "Synopsis One",
            genres = listOf("Action"),
            sourceUrl = "https://wtr-lab.com/novel/123/alternate-slug",
            rawId = "123",
            chapters = listOf(
                testScrapedChapter(title = "Ch 1", url = "https://wtr-lab.com/novel/123/1", order = 1)
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
        val data3 = testScrapedNovelData(
            title = "Web Novel Two",
            author = "Author Two",
            description = "Synopsis Two",
            genres = listOf("Romance"),
            sourceUrl = "https://www.example.com/novel/xyz/",
            rawId = null,
            chapters = listOf(
                testScrapedChapter(title = "Ch 1", url = "https://www.example.com/novel/xyz/1", order = 1)
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
        val data4 = testScrapedNovelData(
            title = "Web Novel Two",
            author = "Author Two",
            description = "Synopsis Two",
            genres = listOf("Romance"),
            sourceUrl = "https://example.com/novel/xyz",
            rawId = null,
            chapters = listOf(
                testScrapedChapter(title = "Ch 1", url = "https://example.com/novel/xyz/1", order = 1)
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
        val novel = testNovel(id = "novel_new", inLibrary = true, lastReadTimestamp = 0L)
        repository.addToLibrary(novel)

        val novelEntity = database.novelDao().getNovelById("novel_new")
        assertNotNull(novelEntity)
        assertEquals(0L, novelEntity!!.lastReadTimestamp)

        // Web novel added via saveWebScrapedNovel
        val scrapedData = testScrapedNovelData(
            title = "Brand New Web Novel",
            author = "Author",
            description = "Synopsis",
            genres = emptyList(),
            sourceUrl = "https://wtr-lab.com/novel/brand-new",
            chapters = listOf(
                testScrapedChapter(title = "Ch 1", url = "https://wtr-lab.com/novel/brand-new/1", order = 1)
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
        val novel = testNovel(id = "novel_clear_hist", inLibrary = true, lastReadTimestamp = 0L)
        repository.addToLibrary(novel)
        repository.libraryNovels.first { it.any { it.id == "novel_clear_hist" } }

        repository.updateReadingProgress(
            novelId = "novel_clear_hist",
            chapterId = "ch_preserved",
            chapterTitle = "Chapter Preserved",
            chapterIndex = 10
        )
        repository.libraryNovels.first { it.any { it.id == "novel_clear_hist" && it.lastReadTimestamp > 0L } }

        val beforeClear = database.novelDao().getNovelById("novel_clear_hist")
        assertNotNull(beforeClear)
        assertEquals("ch_preserved", beforeClear!!.lastReadChapterId)
        assertTrue(beforeClear.lastReadTimestamp > 0L)
        assertTrue(repository.getReadingHistory().any { it.id == "novel_clear_hist" })

        // Clear history: resets last_read_timestamp to 0, keeps last_read_chapter_id
        repository.clearNovelReadingHistory("novel_clear_hist")
        repository.libraryNovels.first { it.any { it.id == "novel_clear_hist" && it.lastReadTimestamp == 0L } }

        val afterClear = database.novelDao().getNovelById("novel_clear_hist")
        assertNotNull(afterClear)
        assertEquals("ch_preserved", afterClear!!.lastReadChapterId)
        assertEquals(0L, afterClear.lastReadTimestamp)

        // Reading history no longer shows the novel
        assertFalse(repository.getReadingHistory().any { it.id == "novel_clear_hist" })
    }

    @Test
    fun markChapterAsRead_onlySetsIsRead_doesNotTouchLastRead() = runBlocking {
        val novel = testNovel(id = "novel_1", inLibrary = true, lastReadTimestamp = 0L)
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
        val novel = testNovel(id = "novel_read_ids", inLibrary = true)
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
        val novel = testNovel(id = "novel_1", inLibrary = true)
        repository.addToLibrary(novel)

        val bookmark = testBookmark(
            id = "bm_1",
            novelId = "novel_1",
            chapterId = "ch_1",
            chapterTitle = "Chapter 1",
            paragraphIndex = 2,
            excerpt = "Some highlighted text",
            note = "My note",
            timestamp = 1000L
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

    @Test
    fun termRules_globalAndEnableDisable_persistsInRoom() = runBlocking {
        val rule1 = TermReplacementRule(
            id = "rule_test_1",
            original = "cultivator",
            replacement = "practitioner",
            isEnabled = true
        )
        repository.addTermReplacement(rule1)

        val list1 = withTimeout(5000L) {
            repository.termReplacements.first { it.any { r -> r.id == "rule_test_1" } }
        }
        assertEquals(1, list1.size)
        assertTrue(list1.first { it.id == "rule_test_1" }.isEnabled)

        // Verify entity in Room has novel_id = null (global rule)
        val entityInDb = database.termReplacementDao().getById("rule_test_1")
        assertNotNull(entityInDb)
        assertNull(entityInDb!!.novelId)
        assertEquals("cultivator", entityInDb.pattern)
        assertEquals("practitioner", entityInDb.replacement)
        assertTrue(entityInDb.isEnabled)

        // Disable rule
        repository.setTermReplacementEnabled("rule_test_1", false)
        val list2 = withTimeout(5000L) {
            repository.termReplacements.first { it.any { r -> r.id == "rule_test_1" && !r.isEnabled } }
        }
        assertFalse(list2.first { it.id == "rule_test_1" }.isEnabled)

        // Global flow only returns enabled rules
        val globalDisabled = withTimeout(5000L) {
            repository.getGlobalTermReplacementsFlow().first()
        }
        assertTrue(globalDisabled.none { it.id == "rule_test_1" })

        // Re-enable
        repository.setTermReplacementEnabled("rule_test_1", true)
        val globalEnabled = withTimeout(5000L) {
            repository.getGlobalTermReplacementsFlow().first { it.any { r -> r.id == "rule_test_1" } }
        }
        assertTrue(globalEnabled.any { it.id == "rule_test_1" && it.isEnabled })

        // Delete rule
        repository.deleteTermReplacement("rule_test_1")
        val listAfterDelete = withTimeout(5000L) {
            repository.termReplacements.first { it.none { r -> r.id == "rule_test_1" } }
        }
        assertTrue(listAfterDelete.none { it.id == "rule_test_1" })
    }

    @Test
    fun customSource_validationAndDedupe_andSourceManagerPushed() = runBlocking {
        // Invalid URLs rejected
        val res1: CustomSourceResult = repository.addCustomSource("Bad 1", "")
        assertEquals(CustomSourceResult.InvalidUrl, res1)

        val res2: CustomSourceResult = repository.addCustomSource("Bad 2", "https://")
        assertEquals(CustomSourceResult.InvalidUrl, res2)

        val res3: CustomSourceResult = repository.addCustomSource("Bad 3", "http://")
        assertEquals(CustomSourceResult.InvalidUrl, res3)

        val res4: CustomSourceResult = repository.addCustomSource("Bad 4", "   https://   ")
        assertEquals(CustomSourceResult.InvalidUrl, res4)

        // Valid addition & URL normalization (default https, trimmed, no trailing slash, no www)
        val resValid: CustomSourceResult = repository.addCustomSource("Test Site", "www.mysite.com/novels/")
        assertTrue(resValid is CustomSourceResult.Added)
        val addedSource = (resValid as CustomSourceResult.Added).source
        assertEquals("https://mysite.com/novels", addedSource.url)

        // StateFlow receives the source
        val sources = withTimeout(5000L) {
            repository.customSources.first { it.any { s -> s.url == "https://mysite.com/novels" } }
        }
        assertEquals(1, sources.size)
        assertEquals("Test Site", sources.first().name)

        // SourceManager had current list pushed
        val smSources = withTimeout(5000L) {
            SourceManager.customSources.first { it.any { s -> s.url == "https://mysite.com/novels" } }
        }
        assertEquals(1, smSources.size)
        assertEquals("https://mysite.com/novels", smSources.first().url)

        // Duplicate rejected by normalized URL
        val resDup: CustomSourceResult = repository.addCustomSource("Duplicate Site", "https://mysite.com/novels")
        assertEquals(CustomSourceResult.Duplicate, resDup)

        val resDup2: CustomSourceResult = repository.addCustomSource("Duplicate Site 2", "http://mysite.com/novels/")
        assertEquals(CustomSourceResult.Duplicate, resDup2)
    }

    @Test
    fun notifications_cap200_markRead_andUnreadCount() = runBlocking {
        // Post first notification
        repository.postNotification("Notif 1", "Body 1")
        val unread1 = withTimeout(5000L) {
            repository.unreadNotificationCount.first { it == 1 }
        }
        assertEquals(1, unread1)

        val list1 = withTimeout(5000L) {
            repository.notifications.first { it.size == 1 }
        }
        val firstId = list1.first().id

        // Mark read
        repository.markRead(firstId)
        val unreadAfterRead = withTimeout(5000L) {
            repository.unreadNotificationCount.first { it == 0 }
        }
        assertEquals(0, unreadAfterRead)

        // Post 2 more notifications
        repository.postNotification("Notif 2", "Body 2")
        repository.postNotification("Notif 3", "Body 3")
        withTimeout(5000L) {
            repository.unreadNotificationCount.first { it == 2 }
        }

        // Mark all read
        repository.markAllRead()
        val unreadAll = withTimeout(5000L) {
            repository.unreadNotificationCount.first { it == 0 }
        }
        assertEquals(0, unreadAll)

        // Test 200 cap in insertAndTrim
        for (i in 1..250) {
            database.notificationDao().insertAndTrim(
                NotificationEntity(
                    id = "bulk_notif_$i",
                    title = "Title $i",
                    message = "Message $i",
                    type = "info",
                    timestamp = 1000L + i,
                    isRead = false
                )
            )
        }

        val cappedList = withTimeout(5000L) {
            repository.notifications.first { it.size == 200 }
        }
        assertEquals(200, cappedList.size)
        assertEquals("bulk_notif_250", cappedList.first().id)
        assertEquals("bulk_notif_51", cappedList.last().id)

        // Clear all notifications
        repository.clear()
        val emptyList = withTimeout(5000L) {
            repository.notifications.first { it.isEmpty() }
        }
        assertTrue(emptyList.isEmpty())
    }

    @Test
    fun startupPurge_removesOnlyUnusedNovels() = runBlocking {
        // Novel 1: unused browse novel (in_library = 0, last_read = 0, no chapters, no bookmarks) -> PURGED
        val n1 = testNovel(id = "n_unused", inLibrary = false, lastReadTimestamp = 0L)
        database.novelDao().insertIgnore(n1.toEntity())

        // Novel 2: in library (in_library = 1, last_read = 0) -> PRESERVED
        val n2 = testNovel(id = "n_in_lib", inLibrary = true, lastReadTimestamp = 0L)
        database.novelDao().insertIgnore(n2.toEntity())

        // Novel 3: has reading history (in_library = 0, last_read = 1000L) -> PRESERVED
        val n3 = testNovel(id = "n_history", inLibrary = false, lastReadTimestamp = 1000L)
        database.novelDao().insertIgnore(n3.toEntity())

        // Novel 4: has bookmark (in_library = 0, last_read = 0) -> PRESERVED
        val n4 = testNovel(id = "n_bookmarked", inLibrary = false, lastReadTimestamp = 0L)
        database.novelDao().insertIgnore(n4.toEntity())
        database.bookmarkDao().insertIgnore(
            BookmarkEntity(
                id = "bm_purge",
                novelId = "n_bookmarked",
                chapterId = "ch_1",
                chapterTitle = "Ch 1",
                paragraphIndex = 0,
                charOffset = 0,
                excerpt = "text",
                note = "",
                createdAt = 1000L
            )
        )

        // Novel 5: has read chapter (in_library = 0, last_read = 0, is_read = 1) -> PRESERVED
        val n5 = testNovel(id = "n_read_ch", inLibrary = false, lastReadTimestamp = 0L)
        database.novelDao().insertIgnore(n5.toEntity())
        database.chapterDao().insertIgnore(
            ChapterEntity(
                novelId = "n_read_ch",
                id = "ch_5",
                title = "Ch 5",
                chapterIndex = 1,
                isRead = true,
                storageTier = "NONE"
            )
        )

        // Novel 6: has DOWNLOADED chapter -> PRESERVED
        val n6 = testNovel(id = "n_downloaded", inLibrary = false, lastReadTimestamp = 0L)
        database.novelDao().insertIgnore(n6.toEntity())
        database.chapterDao().insertIgnore(
            ChapterEntity(
                novelId = "n_downloaded",
                id = "ch_6",
                title = "Ch 6",
                chapterIndex = 1,
                isRead = false,
                storageTier = "DOWNLOADED"
            )
        )

        // Novel 7: has EPUB chapter -> PRESERVED
        val n7 = testNovel(id = "n_epub", inLibrary = false, lastReadTimestamp = 0L)
        database.novelDao().insertIgnore(n7.toEntity())
        database.chapterDao().insertIgnore(
            ChapterEntity(
                novelId = "n_epub",
                id = "ch_7",
                title = "Ch 7",
                chapterIndex = 1,
                isRead = false,
                storageTier = "EPUB"
            )
        )

        // Novel 8: has only CACHE chapter and unread -> PURGED
        val n8 = testNovel(id = "n_cache_only", inLibrary = false, lastReadTimestamp = 0L)
        database.novelDao().insertIgnore(n8.toEntity())
        database.chapterDao().insertIgnore(
            ChapterEntity(
                novelId = "n_cache_only",
                id = "ch_8",
                title = "Ch 8",
                chapterIndex = 1,
                isRead = false,
                storageTier = "CACHE"
            )
        )

        // Run purge
        val purgedCount = repository.purgeUnusedBrowseNovels()
        assertEquals(2, purgedCount)

        // n_unused and n_cache_only are deleted
        assertNull(database.novelDao().getNovelById("n_unused"))
        assertNull(database.novelDao().getNovelById("n_cache_only"))

        // All other 6 novels are preserved
        assertNotNull(database.novelDao().getNovelById("n_in_lib"))
        assertNotNull(database.novelDao().getNovelById("n_history"))
        assertNotNull(database.novelDao().getNovelById("n_bookmarked"))
        assertNotNull(database.novelDao().getNovelById("n_read_ch"))
        assertNotNull(database.novelDao().getNovelById("n_downloaded"))
        assertNotNull(database.novelDao().getNovelById("n_epub"))
    }

    @Test
    fun preferencesManager_noLongerHoldsMovedDomains() {
        val prefs = context.getSharedPreferences("novel_reader_prefs", Context.MODE_PRIVATE)

        // Write dummy data to the old SharedPreferences keys
        prefs.edit()
            .putString("term_replacements_json", "[{\"id\":\"1\",\"original\":\"a\",\"replacement\":\"b\"}]")
            .putString("custom_novel_sources_json", "[{\"id\":\"1\",\"name\":\"S\",\"url\":\"u\"}]")
            .putStringSet("requested_sources_set", setOf("Req 1"))
            .putString("notifications_json", "[{\"id\":\"1\",\"title\":\"T\",\"message\":\"M\"}]")
            .apply()

        // Create PreferencesManager instance
        val pm = PreferencesManager(context)

        // Check reading stats still works and is retained in PreferencesManager
        val statsBefore = pm.readingStats.value
        pm.recordReadingSession(minutesDelta = 10, wordsDelta = 2500)
        val statsAfter = pm.readingStats.value
        assertEquals(statsBefore.todayMinutesRead + 10, statsAfter.todayMinutesRead)
        assertEquals(statsBefore.totalWordsRead + 2500, statsAfter.totalWordsRead)
    }
}
