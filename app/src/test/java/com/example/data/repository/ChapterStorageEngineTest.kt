package com.example.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.db.NovelDatabase
import com.example.data.local.db.entity.ChapterEntity
import com.example.data.local.db.entity.NovelEntity
import com.example.data.local.storage.ChapterStorage
import com.example.data.local.storage.StorageResolver
import com.example.data.model.Chapter
import com.example.data.model.ChapterContent
import com.example.data.model.Novel
import com.example.data.source.NovelSource
import com.example.data.source.SourceManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ChapterStorageEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var database: NovelDatabase
    private lateinit var storageResolver: StorageResolver
    private lateinit var chapterStorage: ChapterStorage
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var chapterRepository: ChapterRepository
    private lateinit var fakeSource: FakeChapterSource

    class FakeChapterSource(override val id: String = "fake_test_src") : NovelSource {
        override val name = "Fake Test Source"
        override val language = "English"
        override val version = "1.0"
        override val description = "Deterministic test source"
        override val isLocal = false

        var fetchCount = 0

        override suspend fun getPopularNovels(page: Int): List<Novel> = emptyList()
        override suspend fun searchNovels(query: String, page: Int): List<Novel> = emptyList()
        override suspend fun getNovelDetails(novelId: String): Novel = Novel(
            id = novelId,
            title = "Test Novel",
            author = "Author",
            description = "Desc",
            sourceId = id,
            sourceName = name
        )
        override suspend fun getChapterList(novelId: String): List<Chapter> = emptyList()

        override suspend fun getChapterContent(novelId: String, chapterId: String): ChapterContent {
            fetchCount++
            return ChapterContent(
                chapterId = chapterId,
                novelId = novelId,
                title = "Title for $chapterId",
                paragraphs = listOf("Paragraph 1 of $chapterId", "Paragraph 2 of $chapterId")
            )
        }
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, NovelDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        storageResolver = StorageResolver(tempFolder.root)
        chapterStorage = ChapterStorage(storageResolver)
        settingsRepository = SettingsRepository.getInstance(context)
        chapterRepository = ChapterRepository(
            chapterDao = database.chapterDao(),
            novelDao = database.novelDao(),
            chapterStorage = chapterStorage,
            storageResolver = storageResolver,
            settingsRepository = settingsRepository
        )

        fakeSource = FakeChapterSource()
        SourceManager.registerSource(fakeSource)
    }

    @After
    fun tearDown() {
        SourceManager.unregisterSource(fakeSource.id)
        database.close()
    }

    private suspend fun insertTestNovel(
        id: String = "novel_1",
        isEpub: Boolean = false,
        sourceId: String = fakeSource.id
    ): NovelEntity {
        val novel = NovelEntity(
            id = id,
            title = "Test Novel",
            author = "Author",
            description = "Desc",
            genresJson = "[]",
            status = "Ongoing",
            sourceId = sourceId,
            sourceName = "Fake Source",
            inLibrary = true,
            isEpub = isEpub,
            totalChapters = 10,
            rating = 4.5f,
            createdAt = System.currentTimeMillis()
        )
        database.novelDao().insertIgnore(novel)
        return novel
    }

    @Test
    fun read_createsFileInCache_withTierCache_andNeverDownloaded() = runBlocking {
        insertTestNovel("novel_1")
        database.chapterDao().insertIgnore(
            ChapterEntity(
                novelId = "novel_1",
                id = "ch_1",
                title = "Chapter 1",
                chapterIndex = 1,
                storageTier = "NONE"
            )
        )

        val content = chapterRepository.getChapterContent("novel_1", "ch_1")
        assertEquals("ch_1", content.chapterId)
        assertFalse("Reading never sets isDownloaded true", content.isDownloaded)

        // Cache file created under cache/chapters/{novelId}/{sha256}.json
        val cacheFile = storageResolver.getCacheChapterFile("novel_1", "ch_1")
        assertTrue("Cache file should exist", cacheFile.exists())

        // Download file does NOT exist
        val downloadFile = storageResolver.getDownloadChapterFile("novel_1", "ch_1")
        assertFalse("Download file must not exist on read", downloadFile.exists())

        // Room tier is CACHE, never DOWNLOADED
        val row = database.chapterDao().getChapter("novel_1", "ch_1")
        assertNotNull(row)
        assertEquals("CACHE", row!!.storageTier)
        assertNotEquals("DOWNLOADED", row.storageTier)
        assertEquals("INTERNAL", row.storageLocation)
        assertEquals(storageResolver.sha256("ch_1"), row.fileHash)
        assertTrue(row.lastAccessedAt > 0L)
    }

    @Test
    fun secondRead_doesNotFetchAgain() = runBlocking {
        insertTestNovel("novel_1")
        database.chapterDao().insertIgnore(
            ChapterEntity(
                novelId = "novel_1",
                id = "ch_fetch_test",
                title = "Chapter 1",
                chapterIndex = 1,
                storageTier = "NONE"
            )
        )

        assertEquals(0, fakeSource.fetchCount)

        // First read fetches from source
        val content1 = chapterRepository.getChapterContent("novel_1", "ch_fetch_test")
        assertEquals(1, fakeSource.fetchCount)
        assertEquals("Title for ch_fetch_test", content1.title)

        // Second read reads from cache and does NOT fetch again
        val content2 = chapterRepository.getChapterContent("novel_1", "ch_fetch_test")
        assertEquals(1, fakeSource.fetchCount)
        assertEquals("Title for ch_fetch_test", content2.title)
    }

    @Test
    fun promote_movesFile_withoutFetch() = runBlocking {
        insertTestNovel("novel_1")
        database.chapterDao().insertIgnore(
            ChapterEntity(
                novelId = "novel_1",
                id = "ch_promote",
                title = "Chapter 1",
                chapterIndex = 1,
                storageTier = "NONE"
            )
        )

        // 1. Read to put in cache
        chapterRepository.getChapterContent("novel_1", "ch_promote")
        assertEquals(1, fakeSource.fetchCount)

        val cacheFile = storageResolver.getCacheChapterFile("novel_1", "ch_promote")
        val downloadFile = storageResolver.getDownloadChapterFile("novel_1", "ch_promote")
        assertTrue("Cache file exists before promotion", cacheFile.exists())
        assertFalse("Download file does not exist before promotion", downloadFile.exists())

        // 2. Download promotes cached file without network fetch
        val novelModel = Novel(
            id = "novel_1",
            title = "Test Novel",
            author = "Author",
            description = "Desc",
            sourceId = fakeSource.id,
            sourceName = "Fake Source"
        )
        val chapterModel = Chapter(
            id = "ch_promote",
            novelId = "novel_1",
            title = "Chapter 1",
            chapterIndex = 1
        )

        val success = chapterRepository.downloadChapter(novelModel, chapterModel)
        assertTrue(success)
        assertEquals("No network fetch when promoting cached file", 1, fakeSource.fetchCount)

        // Cache file was moved; download file exists
        assertFalse("Cache file removed after promotion", cacheFile.exists())
        assertTrue("Download file exists after promotion", downloadFile.exists())

        // Room tier updated to DOWNLOADED
        val row = database.chapterDao().getChapter("novel_1", "ch_promote")
        assertNotNull(row)
        assertEquals("DOWNLOADED", row!!.storageTier)
    }

    @Test
    fun lruEviction_orderAndSizeCap() = runBlocking {
        insertTestNovel("novel_lru")

        // Insert 3 CACHE chapters with different lastAccessedAt and chapterIndex
        val ch1 = ChapterEntity(
            novelId = "novel_lru",
            id = "ch_old_1",
            title = "Old Chapter 1",
            chapterIndex = 1,
            storageTier = "CACHE",
            lastAccessedAt = 1000L
        )
        val ch2 = ChapterEntity(
            novelId = "novel_lru",
            id = "ch_old_2",
            title = "Old Chapter 2 (tie-break)",
            chapterIndex = 2,
            storageTier = "CACHE",
            lastAccessedAt = 1000L // same timestamp as ch1, higher chapterIndex
        )
        val ch3 = ChapterEntity(
            novelId = "novel_lru",
            id = "ch_recent",
            title = "Recent Chapter",
            chapterIndex = 3,
            storageTier = "CACHE",
            lastAccessedAt = 5000L
        )
        database.chapterDao().insertIgnore(listOf(ch1, ch2, ch3))

        // Write files to cache
        chapterStorage.writeToCache(ChapterContent("ch_old_1", "novel_lru", "Old 1", listOf("P1", "P2")))
        chapterStorage.writeToCache(ChapterContent("ch_old_2", "novel_lru", "Old 2", listOf("P1", "P2")))
        chapterStorage.writeToCache(ChapterContent("ch_recent", "novel_lru", "Recent", listOf("P1", "P2")))

        assertTrue(storageResolver.getCacheChapterFile("novel_lru", "ch_old_1").exists())
        assertTrue(storageResolver.getCacheChapterFile("novel_lru", "ch_old_2").exists())
        assertTrue(storageResolver.getCacheChapterFile("novel_lru", "ch_recent").exists())

        // Set cache size cap to 0 MB to trigger eviction
        val initialSettings = settingsRepository.downloadSettings.value
        settingsRepository.updateDownloadSettingsSync(initialSettings.copy(cacheSizeCapMb = 0))

        try {
            // Check LRU query order: ch_old_1 (1000L, idx 1) before ch_old_2 (1000L, idx 2) before ch_recent (5000L, idx 3)
            val lruList = database.chapterDao().getCacheChaptersLru()
            assertEquals("ch_old_1", lruList[0].id)
            assertEquals("ch_old_2", lruList[1].id)
            assertEquals("ch_recent", lruList[2].id)

            chapterRepository.runEviction()

            // All should be evicted when cap is 0
            val row1 = database.chapterDao().getChapter("novel_lru", "ch_old_1")
            val row2 = database.chapterDao().getChapter("novel_lru", "ch_old_2")
            val row3 = database.chapterDao().getChapter("novel_lru", "ch_recent")
            assertEquals("NONE", row1?.storageTier)
            assertEquals("NONE", row2?.storageTier)
            assertEquals("NONE", row3?.storageTier)

            assertFalse(storageResolver.getCacheChapterFile("novel_lru", "ch_old_1").exists())
            assertFalse(storageResolver.getCacheChapterFile("novel_lru", "ch_old_2").exists())
            assertFalse(storageResolver.getCacheChapterFile("novel_lru", "ch_recent").exists())
        } finally {
            settingsRepository.updateDownloadSettingsSync(initialSettings)
        }
    }

    @Test
    fun epubAndDownloaded_neverEvictedOrDeletedByClearCache() = runBlocking {
        insertTestNovel("novel_protect", isEpub = false)

        val chEpub = ChapterEntity(
            novelId = "novel_protect",
            id = "ch_epub",
            title = "EPUB Ch",
            chapterIndex = 1,
            storageTier = "EPUB",
            lastAccessedAt = 100L
        )
        val chDl = ChapterEntity(
            novelId = "novel_protect",
            id = "ch_dl",
            title = "DL Ch",
            chapterIndex = 2,
            storageTier = "DOWNLOADED",
            lastAccessedAt = 100L
        )
        val chCache = ChapterEntity(
            novelId = "novel_protect",
            id = "ch_cache",
            title = "Cache Ch",
            chapterIndex = 3,
            storageTier = "CACHE",
            lastAccessedAt = 100L
        )
        database.chapterDao().insertIgnore(listOf(chEpub, chDl, chCache))

        chapterStorage.writeToEpub(ChapterContent("ch_epub", "novel_protect", "EPUB", listOf("EPUB text")))
        chapterStorage.writeToDownloads(ChapterContent("ch_dl", "novel_protect", "DL", listOf("DL text")))
        chapterStorage.writeToCache(ChapterContent("ch_cache", "novel_protect", "Cache", listOf("Cache text")))

        val epubFile = storageResolver.getEpubChapterFile("novel_protect", "ch_epub")
        val dlFile = storageResolver.getDownloadChapterFile("novel_protect", "ch_dl")
        val cacheFile = storageResolver.getCacheChapterFile("novel_protect", "ch_cache")

        assertTrue(epubFile.exists())
        assertTrue(dlFile.exists())
        assertTrue(cacheFile.exists())

        // 1. Eviction run must NEVER evict EPUB or DOWNLOADED
        val initialSettings = settingsRepository.downloadSettings.value
        settingsRepository.updateDownloadSettingsSync(initialSettings.copy(cacheSizeCapMb = 0))
        try {
            chapterRepository.runEviction()

            // Cache chapter evicted
            assertFalse(cacheFile.exists())
            assertEquals("NONE", database.chapterDao().getChapter("novel_protect", "ch_cache")?.storageTier)

            // EPUB and DOWNLOADED remain intact
            assertTrue("EPUB file must survive eviction", epubFile.exists())
            assertTrue("DOWNLOADED file must survive eviction", dlFile.exists())
            assertEquals("EPUB", database.chapterDao().getChapter("novel_protect", "ch_epub")?.storageTier)
            assertEquals("DOWNLOADED", database.chapterDao().getChapter("novel_protect", "ch_dl")?.storageTier)
        } finally {
            settingsRepository.updateDownloadSettingsSync(initialSettings)
        }

        // 2. Clear cache must NEVER delete EPUB or DOWNLOADED
        chapterStorage.writeToCache(ChapterContent("ch_cache", "novel_protect", "Cache", listOf("Cache text")))
        database.chapterDao().updateStorageTier("novel_protect", "ch_cache", "CACHE", "INTERNAL", storageResolver.sha256("ch_cache"), 100L)
        assertTrue(cacheFile.exists())

        chapterRepository.clearChapterCache()

        assertFalse(cacheFile.exists())
        assertEquals("NONE", database.chapterDao().getChapter("novel_protect", "ch_cache")?.storageTier)

        assertTrue("EPUB file must survive clearChapterCache", epubFile.exists())
        assertTrue("DOWNLOADED file must survive clearChapterCache", dlFile.exists())
        assertEquals("EPUB", database.chapterDao().getChapter("novel_protect", "ch_epub")?.storageTier)
        assertEquals("DOWNLOADED", database.chapterDao().getChapter("novel_protect", "ch_dl")?.storageTier)
    }

    @Test
    fun readMiss_fileDeleted_resetsTierAndRefetches() = runBlocking {
        insertTestNovel("novel_1")
        // Row claims to be CACHE, but no file exists on disk
        database.chapterDao().insertIgnore(
            ChapterEntity(
                novelId = "novel_1",
                id = "ch_missing_file",
                title = "Chapter Title",
                chapterIndex = 1,
                storageTier = "CACHE",
                fileHash = "fakehash",
                lastAccessedAt = 500L
            )
        )

        val cacheFile = storageResolver.getCacheChapterFile("novel_1", "ch_missing_file")
        assertFalse(cacheFile.exists())
        assertEquals(0, fakeSource.fetchCount)

        // Reading detects missing file, resets tier to NONE, refetches from source, writes to cache
        val content = chapterRepository.getChapterContent("novel_1", "ch_missing_file")
        assertEquals(1, fakeSource.fetchCount)
        assertEquals("ch_missing_file", content.chapterId)

        assertTrue("Cache file recreated on miss refetch", cacheFile.exists())
        val row = database.chapterDao().getChapter("novel_1", "ch_missing_file")
        assertEquals("CACHE", row?.storageTier)
        assertEquals(storageResolver.sha256("ch_missing_file"), row?.fileHash)
    }

    @Test
    fun hashNames_stableForUrlChapterIds() {
        val url1 = "https://wtr-lab.com/novel/123/chapter-1?lang=en#part1"
        val url2 = "https://example.org/path/to/novel/ch_42/"

        val hash1a = storageResolver.sha256(url1)
        val hash1b = storageResolver.sha256(url1)
        assertEquals("Hash must be deterministic", hash1a, hash1b)

        val fileName1 = storageResolver.sha256FileName(url1)
        assertEquals("$hash1a.json", fileName1)

        // Must contain only hex digits and .json
        assertTrue(fileName1.matches(Regex("^[a-f0-9]{64}\\.json$")))
        assertFalse(fileName1.contains("/"))
        assertFalse(fileName1.contains(":"))
        assertFalse(fileName1.contains("?"))

        val hash2 = storageResolver.sha256(url2)
        assertNotEquals(hash1a, hash2)
        val fileName2 = storageResolver.sha256FileName(url2)
        assertTrue(fileName2.matches(Regex("^[a-f0-9]{64}\\.json$")))
    }

    @Test
    fun deleteDownloads_leavesEpubRowsAndFiles() = runBlocking {
        insertTestNovel("novel_web", isEpub = false)
        insertTestNovel("novel_epub", isEpub = true)

        val chDl = ChapterEntity(
            novelId = "novel_web",
            id = "ch_web_dl",
            title = "Web DL",
            chapterIndex = 1,
            storageTier = "DOWNLOADED"
        )
        val chEpub = ChapterEntity(
            novelId = "novel_epub",
            id = "ch_epub_1",
            title = "EPUB 1",
            chapterIndex = 1,
            storageTier = "EPUB"
        )
        database.chapterDao().insertIgnore(listOf(chDl, chEpub))

        chapterStorage.writeToDownloads(ChapterContent("ch_web_dl", "novel_web", "Web DL", listOf("text")))
        chapterStorage.writeToEpub(ChapterContent("ch_epub_1", "novel_epub", "EPUB 1", listOf("text")))

        val dlFile = storageResolver.getDownloadChapterFile("novel_web", "ch_web_dl")
        val epubFile = storageResolver.getEpubChapterFile("novel_epub", "ch_epub_1")
        assertTrue(dlFile.exists())
        assertTrue(epubFile.exists())

        // 1. Delete downloads for web novel
        chapterRepository.deleteNovelDownloads("novel_web")
        assertFalse(dlFile.exists())
        assertEquals("NONE", database.chapterDao().getChapter("novel_web", "ch_web_dl")?.storageTier)

        assertTrue(epubFile.exists())
        assertEquals("EPUB", database.chapterDao().getChapter("novel_epub", "ch_epub_1")?.storageTier)

        // 2. Delete all downloads
        chapterStorage.writeToDownloads(ChapterContent("ch_web_dl", "novel_web", "Web DL", listOf("text")))
        database.chapterDao().updateStorageTier("novel_web", "ch_web_dl", "DOWNLOADED", "INTERNAL", storageResolver.sha256("ch_web_dl"), 100L)

        chapterRepository.deleteAllDownloads()

        assertFalse(dlFile.exists())
        assertEquals("NONE", database.chapterDao().getChapter("novel_web", "ch_web_dl")?.storageTier)

        assertTrue("EPUB files must survive deleteAllDownloads", epubFile.exists())
        assertEquals("EPUB", database.chapterDao().getChapter("novel_epub", "ch_epub_1")?.storageTier)
    }

    @Test
    fun parallelReads_fetchOnce_viaChapterMutex() = runBlocking {
        insertTestNovel("novel_parallel")
        database.chapterDao().insertIgnore(
            ChapterEntity(
                novelId = "novel_parallel",
                id = "ch_parallel",
                title = "Parallel Chapter",
                chapterIndex = 1,
                storageTier = "NONE"
            )
        )

        val fetchStarted = CompletableDeferred<Unit>()
        val releaseFetch = CompletableDeferred<Unit>()

        val slowSource = object : NovelSource {
            override val id = "slow_test_src"
            override val name = "Slow Test Source"
            override val language = "English"
            override val version = "1.0"
            override val description = "Slow test source"
            override val isLocal = false

            val fetchCount = java.util.concurrent.atomic.AtomicInteger(0)

            override suspend fun getPopularNovels(page: Int): List<Novel> = emptyList()
            override suspend fun searchNovels(query: String, page: Int): List<Novel> = emptyList()
            override suspend fun getNovelDetails(novelId: String): Novel = Novel(
                id = novelId,
                title = "Test Novel",
                author = "Author",
                description = "Desc",
                sourceId = id,
                sourceName = name
            )
            override suspend fun getChapterList(novelId: String): List<Chapter> = emptyList()
            override suspend fun getChapterContent(novelId: String, chapterId: String): ChapterContent {
                fetchCount.incrementAndGet()
                fetchStarted.complete(Unit)
                releaseFetch.await()
                return ChapterContent(
                    chapterId = chapterId,
                    novelId = novelId,
                    title = "Title for $chapterId",
                    paragraphs = listOf("P1", "P2")
                )
            }
        }

        SourceManager.registerSource(slowSource)
        try {
            val updatedNovel = database.novelDao().getNovelById("novel_parallel")!!.copy(sourceId = slowSource.id)
            database.novelDao().update(updatedNovel)

            coroutineScope {
                val deferred1 = async(Dispatchers.IO) {
                    chapterRepository.getChapterContent("novel_parallel", "ch_parallel")
                }
                val deferred2 = async(Dispatchers.IO) {
                    chapterRepository.getChapterContent("novel_parallel", "ch_parallel")
                }

                fetchStarted.await()
                delay(50)
                releaseFetch.complete(Unit)

                val res1 = deferred1.await()
                val res2 = deferred2.await()

                assertEquals("Both reads return the same title", "Title for ch_parallel", res1.title)
                assertEquals("Both reads return the same title", "Title for ch_parallel", res2.title)
                assertEquals("Both reads return identical content", res1, res2)
                val fetchCount = slowSource.fetchCount.get()
                assertEquals("Two simultaneous reads must fetch uncached chapter exactly once, but fetched $fetchCount", 1, fetchCount)
                assertTrue("chapterLocks must be empty after release", chapterRepository.chapterLocks.isEmpty())
            }
        } finally {
            SourceManager.unregisterSource(slowSource.id)
        }
    }

    @Test
    fun removeNovelCompletely_deletesCoverOriginalEpubAndEpubsFolder_forEpub_andCoversFileOtherwise() = runBlocking {
        // 1. EPUB novel
        insertTestNovel("novel_epub_rm", isEpub = true)
        val coverFileEpub = storageResolver.getCoverFile("novel_epub_rm")
        val origEpubFile = storageResolver.getOriginalEpubFile("novel_epub_rm")
        val epubChapterFile = storageResolver.getEpubChapterFile("novel_epub_rm", "ch_1")

        coverFileEpub.parentFile?.mkdirs()
        coverFileEpub.writeText("cover data")
        origEpubFile.parentFile?.mkdirs()
        origEpubFile.writeText("original epub data")
        chapterStorage.writeToEpub(ChapterContent("ch_1", "novel_epub_rm", "Ch 1", listOf("paragraph text")))

        assertTrue(coverFileEpub.exists())
        assertTrue(origEpubFile.exists())
        assertTrue(epubChapterFile.exists())
        assertTrue(storageResolver.getNovelEpubDir("novel_epub_rm").exists())

        chapterRepository.removeNovelCompletely("novel_epub_rm")

        assertFalse("EPUB cover must be deleted", coverFileEpub.exists())
        assertFalse("original_epubs file must be deleted", origEpubFile.exists())
        assertFalse("epubs folder must be deleted", storageResolver.getNovelEpubDir("novel_epub_rm").exists())
        assertNull("Novel row must be deleted from Room", database.novelDao().getNovelById("novel_epub_rm"))

        // 2. Non-EPUB novel
        insertTestNovel("novel_web_rm", isEpub = false)
        val coverFileWeb = storageResolver.getCoverFile("novel_web_rm")
        coverFileWeb.parentFile?.mkdirs()
        coverFileWeb.writeText("web cover data")
        chapterStorage.writeToDownloads(ChapterContent("ch_web_dl", "novel_web_rm", "Web DL", listOf("text")))
        chapterStorage.writeToCache(ChapterContent("ch_web_c", "novel_web_rm", "Web Cache", listOf("text")))

        val dlFile = storageResolver.getDownloadChapterFile("novel_web_rm", "ch_web_dl")
        val cacheFile = storageResolver.getCacheChapterFile("novel_web_rm", "ch_web_c")

        assertTrue(coverFileWeb.exists())
        assertTrue(dlFile.exists())
        assertTrue(cacheFile.exists())

        chapterRepository.removeNovelCompletely("novel_web_rm")

        assertFalse("Web cover file must be deleted", coverFileWeb.exists())
        assertFalse("Downloads dir must be deleted", storageResolver.getNovelDownloadsDir("novel_web_rm").exists())
        assertFalse("Cache dir must be deleted", storageResolver.getNovelCacheDir("novel_web_rm").exists())
        assertNull("Novel row must be deleted from Room", database.novelDao().getNovelById("novel_web_rm"))
    }
}
