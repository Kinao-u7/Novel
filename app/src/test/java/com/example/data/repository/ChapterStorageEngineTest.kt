package com.example.data.repository

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.db.NovelDatabase
import com.example.data.local.db.NovelDatabaseHolder
import com.example.data.local.db.entity.ChapterEntity
import com.example.data.local.db.entity.NovelEntity
import com.example.data.local.storage.ChapterCache
import com.example.data.local.storage.ChapterFileStore
import com.example.data.local.storage.ChapterReadException
import com.example.data.local.storage.ChapterStorage
import com.example.data.local.storage.InternalChapterFileStore
import com.example.data.local.storage.StorageResolver
import com.example.data.local.storage.StorageUnavailableException
import com.example.data.model.Chapter
import com.example.data.model.ChapterContent
import com.example.data.model.Novel
import com.example.data.source.NovelSource
import com.example.data.source.SourceManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume
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
    private lateinit var novelRepository: NovelRepository
    private lateinit var fakeSource: FakeChapterSource
    private lateinit var failingFileStore: FakeFailingFileStore

    class FakeFailingFileStore(
        private val delegate: ChapterFileStore
    ) : ChapterFileStore by delegate {
        var failOnRead: Boolean = false

        override suspend fun read(novelId: String, chapterId: String, tier: String, location: String): ChapterContent? {
            if (failOnRead) {
                throw ChapterReadException(novelId, chapterId, IOException("Simulated unreadable file"))
            }
            return delegate.read(novelId, chapterId, tier, location)
        }
    }

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
        com.example.data.download.DownloadCoordinator.resetInstanceForTesting()
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, NovelDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        NovelDatabaseHolder.setInstanceForTesting(database)

        val filesDir = tempFolder.newFolder("files")
        val cacheDir = tempFolder.newFolder("cache")
        storageResolver = StorageResolver(filesDir = filesDir, cacheDir = cacheDir)
        val internalStore = InternalChapterFileStore(resolver = storageResolver, ioDispatcher = Dispatchers.IO)
        failingFileStore = FakeFailingFileStore(internalStore)
        val chapterCache = ChapterCache(cacheDir = cacheDir, ioDispatcher = Dispatchers.IO)
        chapterStorage = ChapterStorage(
            resolver = storageResolver,
            fileStore = failingFileStore,
            chapterCache = chapterCache,
            ioDispatcher = Dispatchers.IO
        )
        settingsRepository = SettingsRepository.getInstance(context)
        chapterRepository = ChapterRepository(
            chapterDao = database.chapterDao(),
            novelDao = database.novelDao(),
            chapterStorage = chapterStorage,
            storageResolver = storageResolver,
            settingsRepository = settingsRepository,
            downloadQueueDao = database.downloadQueueDao(),
            downloadCoordinator = com.example.data.download.DownloadCoordinator.getInstance(),
            chapterCache = chapterCache,
            ioDispatcher = Dispatchers.IO
        )
        novelRepository = NovelRepository(context)

        fakeSource = FakeChapterSource()
        SourceManager.registerSource(fakeSource)
    }

    @After
    fun tearDown() {
        SourceManager.unregisterSource(fakeSource.id)
        NovelDatabaseHolder.clearInstanceForTesting()
        database.close()
        com.example.data.download.DownloadCoordinator.resetInstanceForTesting()
    }

    private fun testNovel(
        id: String = "test_novel_1",
        title: String = "Test Novel",
        author: String = "Author",
        coverUrl: String? = null,
        description: String = "Test Description",
        genres: List<String> = emptyList(),
        status: String = "Ongoing",
        sourceId: String = "fake_source",
        sourceName: String = "Fake Source",
        inLibrary: Boolean = true
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
        inLibrary = inLibrary
    )

    private fun testNovelEntity(
        id: String = "novel_1",
        title: String = "Test Novel",
        author: String = "Author",
        description: String = "Desc",
        sourceId: String = "fake_source",
        sourceName: String = "Fake Source",
        status: String = "Ongoing",
        genresJson: String = "[]",
        inLibrary: Boolean = true,
        isEpub: Boolean = false,
        totalChapters: Int = 10,
        rating: Float? = 4.5f
    ): NovelEntity = NovelEntity(
        id = id,
        title = title,
        author = author,
        description = description,
        sourceId = sourceId,
        sourceName = sourceName,
        status = status,
        genresJson = genresJson,
        inLibrary = inLibrary,
        isEpub = isEpub,
        totalChapters = totalChapters,
        rating = rating
    )

    private suspend fun insertTestNovel(
        id: String = "novel_1",
        isEpub: Boolean = false,
        sourceId: String = fakeSource.id
    ): NovelEntity {
        val novel = testNovelEntity(
            id = id,
            isEpub = isEpub,
            sourceId = sourceId,
            sourceName = "Fake Source"
        )
        database.novelDao().insertIgnore(novel)
        return novel
    }

    @Test
    fun read_createsFileInCache_leavesTierNone_andNeverDownloaded() = runBlocking {
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

        // Cache file created under cacheDir/chapters/{novelId}/{sha256}.json
        assertTrue("Cache file should exist in ChapterCache", chapterRepository.chapterCache.contains("novel_1", "ch_1"))
        val cacheFile = chapterRepository.chapterCache.getCacheFile("novel_1", "ch_1")
        assertTrue("Cache file should exist on disk", cacheFile.exists())

        // Download file does NOT exist
        val downloadFile = storageResolver.getDownloadChapterFile("novel_1", "ch_1")
        assertFalse("Download file must not exist on read", downloadFile.exists())

        // Room tier remains NONE, untracked reader cache, never DOWNLOADED
        val row = database.chapterDao().getChapter("novel_1", "ch_1")
        assertNotNull(row)
        assertEquals("NONE", row!!.storageTier)
        assertNotEquals("DOWNLOADED", row.storageTier)
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

        val cacheFile = chapterRepository.chapterCache.getCacheFile("novel_1", "ch_promote")
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

        val result = chapterRepository.downloadChapter(novelModel, chapterModel)
        assertTrue(result.isSuccess)
        assertEquals("No network fetch when promoting cached file", 1, fakeSource.fetchCount)

        // Cache file was removed; download file exists
        assertFalse("Cache file removed after promotion", cacheFile.exists())
        assertTrue("Download file exists after promotion", downloadFile.exists())

        // Room tier updated to DOWNLOADED
        val row = database.chapterDao().getChapter("novel_1", "ch_promote")
        assertNotNull(row)
        assertEquals("DOWNLOADED", row!!.storageTier)
    }

    @Test
    fun chapterCache_writeAndRead_atomicRenameAndTempFilePlacement() = runBlocking {
        val novelId = "novel_cache_atomic"
        val chapterId = "ch_cache_atomic"
        val content = ChapterContent(
            chapterId = chapterId,
            novelId = novelId,
            title = "Atomic Chapter",
            paragraphs = listOf("Line 1", "Line 2")
        )

        val writtenFile = chapterRepository.chapterCache.write(content)
        assertNotNull(writtenFile)
        assertTrue(writtenFile!!.exists())
        assertEquals(chapterRepository.chapterCache.getCacheFile(novelId, chapterId).absolutePath, writtenFile.absolutePath)

        // Verify no leftover .tmp files in the directory
        val dir = writtenFile.parentFile
        val tmpFiles = dir?.listFiles { f -> f.name.endsWith(".tmp") } ?: emptyArray()
        assertEquals("No temp files should remain after atomic rename", 0, tmpFiles.size)

        // Read back content
        val readBack = chapterRepository.chapterCache.read(novelId, chapterId)
        assertNotNull(readBack)
        assertEquals(content.title, readBack?.title)
        assertEquals(content.paragraphs, readBack?.paragraphs)
    }

    @Test
    fun chapterCache_trim_evictsOldestModifiedUnderCap_andCleansStaleTempFiles() = runBlocking {
        val novelId = "novel_trim"
        val c1 = ChapterContent("ch_1", novelId, "C1", listOf("Some text 1"))
        val c2 = ChapterContent("ch_2", novelId, "C2", listOf("Some text 2"))
        val c3 = ChapterContent("ch_3", novelId, "C3", listOf("Some text 3"))

        val f1 = chapterRepository.chapterCache.write(c1)!!
        val f2 = chapterRepository.chapterCache.write(c2)!!
        val f3 = chapterRepository.chapterCache.write(c3)!!

        val now = System.currentTimeMillis()
        f1.setLastModified(now - 10_000L)
        f2.setLastModified(now - 20_000L) // oldest
        f3.setLastModified(now - 5_000L)  // newest

        // Create a stale .tmp file (>10 minutes) and a recent .tmp file (<10 minutes) in the novel cache folder
        val novelCacheDir = f1.parentFile!!
        val staleTmp = File(novelCacheDir, "stale_upload.tmp")
        staleTmp.writeText("stale temp")
        staleTmp.setLastModified(now - 15 * 60 * 1000L)

        val recentTmp = File(novelCacheDir, "recent_upload.tmp")
        recentTmp.writeText("recent temp")
        recentTmp.setLastModified(now - 2 * 60 * 1000L)

        // Trim cap set to keep roughly 2 files (f1 and f3)
        val capBytes = f1.length() + f3.length() + 10L
        chapterRepository.chapterCache.trimToBytes(maxBytes = capBytes)

        // f2 was oldest modified -> evicted
        assertFalse("Oldest modified chapter (f2) must be evicted", f2.exists())
        assertTrue("Newer chapter (f1) must be preserved", f1.exists())
        assertTrue("Newest chapter (f3) must be preserved", f3.exists())

        // Stale temp file must be cleaned up on trim
        assertFalse("Stale temp file older than 10m must be deleted on trim", staleTmp.exists())
        // Recent temp file must be preserved
        assertTrue("Recent temp file newer than 10m must be preserved", recentTmp.exists())
    }

    @Test
    fun chapterCache_read_refreshesLastModifiedTimestamp() = runBlocking {
        val novelId = "novel_refresh_mtime"
        val chapterId = "ch_mtime"
        val content = ChapterContent(chapterId, novelId, "Title", listOf("Paragraph"))
        val file = chapterRepository.chapterCache.write(content)!!

        val past = System.currentTimeMillis() - 60_000L
        file.setLastModified(past)
        assertEquals(past, file.lastModified())

        // Reading refreshes lastModified
        val readBack = chapterRepository.chapterCache.read(novelId, chapterId)
        assertNotNull(readBack)
        assertTrue("Reading cache must refresh file modified time", file.lastModified() > past)
    }

    @Test
    fun chapterCache_clear_deletesOnlyCacheFiles_neverTouchesDownloadsOrEpub() = runBlocking {
        insertTestNovel("novel_protect", isEpub = false)

        val chEpub = ChapterEntity(
            novelId = "novel_protect",
            id = "ch_epub",
            title = "EPUB Ch",
            chapterIndex = 1,
            storageTier = "EPUB"
        )
        val chDl = ChapterEntity(
            novelId = "novel_protect",
            id = "ch_dl",
            title = "DL Ch",
            chapterIndex = 2,
            storageTier = "DOWNLOADED"
        )
        val chCache = ChapterEntity(
            novelId = "novel_protect",
            id = "ch_cache",
            title = "Cache Ch",
            chapterIndex = 3,
            storageTier = "NONE"
        )
        database.chapterDao().insertIgnore(listOf(chEpub, chDl, chCache))

        chapterStorage.writeToEpub(ChapterContent("ch_epub", "novel_protect", "EPUB", listOf("EPUB text")))
        chapterStorage.writeToDownloads(ChapterContent("ch_dl", "novel_protect", "DL", listOf("DL text")))
        chapterRepository.chapterCache.write(ChapterContent("ch_cache", "novel_protect", "Cache", listOf("Cache text")))

        val epubFile = storageResolver.getEpubChapterFile("novel_protect", "ch_epub")
        val dlFile = storageResolver.getDownloadChapterFile("novel_protect", "ch_dl")
        val cacheFile = chapterRepository.chapterCache.getCacheFile("novel_protect", "ch_cache")

        assertTrue(epubFile.exists())
        assertTrue(dlFile.exists())
        assertTrue(cacheFile.exists())

        // Clear cache deletes cache folder only
        chapterRepository.clearChapterCache()

        assertFalse("Cache file must be deleted by clearChapterCache", cacheFile.exists())
        assertTrue("EPUB file must survive clearChapterCache", epubFile.exists())
        assertTrue("DOWNLOADED file must survive clearChapterCache", dlFile.exists())
        assertEquals("EPUB", database.chapterDao().getChapter("novel_protect", "ch_epub")?.storageTier)
        assertEquals("DOWNLOADED", database.chapterDao().getChapter("novel_protect", "ch_dl")?.storageTier)
    }

    @Test
    fun chapterCache_corruptFile_isDeletedAndReturnsMiss() = runBlocking {
        val novelId = "novel_corrupt_cache"
        val chapterId = "ch_corrupt"
        val file = chapterRepository.chapterCache.getCacheFile(novelId, chapterId)
        file.parentFile?.mkdirs()
        file.writeText("not valid json content at all")
        assertTrue(file.exists())

        val read = chapterRepository.chapterCache.read(novelId, chapterId)
        assertNull("Corrupt cache file must return null (miss)", read)
        assertFalse("Corrupt cache file must be deleted upon miss", file.exists())
    }

    @Test
    fun chapterCache_fileNotFoundDuringRead_returnsMissNotError() = runBlocking {
        val read = chapterRepository.chapterCache.read("non_existent_novel", "non_existent_ch")
        assertNull("Missing cache file must return null without error", read)
    }

    @Test
    fun chapterCache_concurrentWritesOfSameKey_areSafe() = runBlocking {
        val novelId = "novel_concurrent_cache"
        val chapterId = "ch_concurrent"

        coroutineScope {
            (1..10).map { idx ->
                async(Dispatchers.IO) {
                    val content = ChapterContent(
                        chapterId = chapterId,
                        novelId = novelId,
                        title = "Title $idx",
                        paragraphs = listOf("Paragraph $idx")
                    )
                    chapterRepository.chapterCache.write(content)
                }
            }.forEach { it.await() }
        }

        val read = chapterRepository.chapterCache.read(novelId, chapterId)
        assertNotNull("Concurrent writes must result in valid readable file", read)
        assertEquals(chapterId, read?.chapterId)
    }

    @Test
    fun reader_missingDownloaded_selfHealsAsDownloadedInOriginalLocation() = runBlocking {
        insertTestNovel("novel_1")
        database.chapterDao().insertIgnore(
            ChapterEntity(
                novelId = "novel_1",
                id = "ch_missing_dl",
                title = "Chapter Title",
                chapterIndex = 1,
                storageTier = "DOWNLOADED",
                storageLocation = "INTERNAL",
                fileHash = "fakehash"
            )
        )

        val dlFile = storageResolver.getDownloadChapterFile("novel_1", "ch_missing_dl")
        assertFalse(dlFile.exists())
        assertEquals(0, fakeSource.fetchCount)

        // Reading detects missing downloaded file, refetches from source, self-heals back as DOWNLOADED
        val content = chapterRepository.getChapterContent("novel_1", "ch_missing_dl")
        assertEquals(1, fakeSource.fetchCount)
        assertEquals("ch_missing_dl", content.chapterId)

        assertTrue("Download file recreated on miss refetch", dlFile.exists())
        val row = database.chapterDao().getChapter("novel_1", "ch_missing_dl")
        assertEquals("DOWNLOADED", row?.storageTier)
        assertEquals(storageResolver.sha256("ch_missing_dl"), row?.fileHash)
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
        database.chapterDao().updateStorageTier("novel_web", "ch_web_dl", "DOWNLOADED", "INTERNAL", storageResolver.sha256("ch_web_dl"))

        chapterRepository.deleteAllDownloads()

        assertFalse(dlFile.exists())
        assertEquals("NONE", database.chapterDao().getChapter("novel_web", "ch_web_dl")?.storageTier)

        assertTrue("EPUB files must survive deleteAllDownloads", epubFile.exists())
        assertEquals("EPUB", database.chapterDao().getChapter("novel_epub", "ch_epub_1")?.storageTier)
    }

    @Test
    fun parallelReads_fetchOnce_viaChapterMutex() = runBlocking {
        withTimeout(10_000L) {
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
                database.openHelper.writableDatabase.execSQL("UPDATE novels SET source_id = '${slowSource.id}' WHERE id = 'novel_parallel'")

                try {
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
                        assertTrue("inFlightFetches must be empty after release", chapterRepository.inFlightFetches.isEmpty())
                    }
                } finally {
                    releaseFetch.complete(Unit)
                }
            } finally {
                releaseFetch.complete(Unit)
                SourceManager.unregisterSource(slowSource.id)
            }
        }
    }

    @Test
    fun failingFetch_propagatesExceptionToAllWaiters_andSubsequentFetchRetries() = runBlocking {
        withTimeout(10_000L) {
            insertTestNovel("novel_fail_retry")
            database.chapterDao().insertIgnore(
                ChapterEntity(
                    novelId = "novel_fail_retry",
                    id = "ch_fail_retry",
                    title = "Fail Retry Chapter",
                    chapterIndex = 1,
                    storageTier = "NONE"
                )
            )

            val fetchStarted = CompletableDeferred<Unit>()
            val releaseFetch = CompletableDeferred<Unit>()
            val shouldFail = AtomicBoolean(true)
            val fetchCount = AtomicInteger(0)

            val failSource = object : NovelSource {
                override val id = "fail_test_src"
                override val name = "Fail Test Source"
                override val language = "English"
                override val version = "1.0"
                override val description = "Fail test source"
                override val isLocal = false

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
                    if (shouldFail.get()) {
                        fetchStarted.complete(Unit)
                        releaseFetch.await()
                        throw IOException("Simulated network failure")
                    } else {
                        return ChapterContent(
                            chapterId = chapterId,
                            novelId = novelId,
                            title = "Retried Title for $chapterId",
                            paragraphs = listOf("Retry P1", "Retry P2")
                        )
                    }
                }
            }

            SourceManager.registerSource(failSource)
            try {
                database.openHelper.writableDatabase.execSQL("UPDATE novels SET source_id = '${failSource.id}' WHERE id = 'novel_fail_retry'")

                try {
                    supervisorScope {
                        val deferred1 = async(Dispatchers.IO) {
                            chapterRepository.getChapterContent("novel_fail_retry", "ch_fail_retry")
                        }
                        val deferred2 = async(Dispatchers.IO) {
                            chapterRepository.getChapterContent("novel_fail_retry", "ch_fail_retry")
                        }

                        fetchStarted.await()
                        delay(50)
                        releaseFetch.complete(Unit)

                        var ex1: Throwable? = null
                        try {
                            deferred1.await()
                        } catch (t: Throwable) {
                            ex1 = t
                        }

                        var ex2: Throwable? = null
                        try {
                            deferred2.await()
                        } catch (t: Throwable) {
                            ex2 = t
                        }

                        assertTrue("First waiter received IOException", ex1 is IOException)
                        assertTrue("Second waiter received IOException", ex2 is IOException)
                        assertEquals("Both waiters should fail after 1 fetch attempt", 1, fetchCount.get())
                    }
                } finally {
                    releaseFetch.complete(Unit)
                }

                assertTrue("inFlightFetches must be removed after failure", chapterRepository.inFlightFetches.isEmpty())

                // Subsequent read should retry and succeed
                shouldFail.set(false)
                val retriedContent = chapterRepository.getChapterContent("novel_fail_retry", "ch_fail_retry")
                assertEquals("Retried Title for ch_fail_retry", retriedContent.title)
                assertEquals(2, fetchCount.get())
                assertTrue("inFlightFetches must be empty after retry succeeds", chapterRepository.inFlightFetches.isEmpty())

                val chapterRow = database.chapterDao().getChapter("novel_fail_retry", "ch_fail_retry")
                assertNotNull(chapterRow)
                assertEquals("NONE", chapterRow?.storageTier)
                assertTrue("File exists in ChapterCache", chapterRepository.chapterCache.contains("novel_fail_retry", "ch_fail_retry"))
            } finally {
                releaseFetch.complete(Unit)
                SourceManager.unregisterSource(failSource.id)
            }
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
        chapterRepository.chapterCache.write(ChapterContent("ch_web_c", "novel_web_rm", "Web Cache", listOf("text")))

        val dlFile = storageResolver.getDownloadChapterFile("novel_web_rm", "ch_web_dl")
        val cacheFile = chapterRepository.chapterCache.getCacheFile("novel_web_rm", "ch_web_c")

        assertTrue(coverFileWeb.exists())
        assertTrue(dlFile.exists())
        assertTrue(cacheFile.exists())

        chapterRepository.removeNovelCompletely("novel_web_rm")

        assertFalse("Web cover file must be deleted", coverFileWeb.exists())
        assertFalse("Downloads dir must be deleted", storageResolver.getNovelDownloadsDir("novel_web_rm").exists())
        assertFalse("Cache dir must be deleted", chapterRepository.chapterCache.getNovelDir("novel_web_rm").exists())
        assertNull("Novel row must be deleted from Room", database.novelDao().getNovelById("novel_web_rm"))
    }

    private fun createTestEpubFile(
        fileName: String,
        title: String = "Test Novel",
        author: String = "Test Author",
        hasCover: Boolean = true
    ): Pair<File, Uri> {
        val file = File(context.cacheDir, fileName)
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            val containerXml = """<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>"""
            zos.putNextEntry(ZipEntry("META-INF/container.xml"))
            zos.write(containerXml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            if (hasCover) {
                zos.putNextEntry(ZipEntry("OEBPS/cover.jpg"))
                zos.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))
                zos.closeEntry()
            }

            val opf = """<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>$title</dc:title>
    <dc:creator>$author</dc:creator>
  </metadata>
  <manifest>
    ${if (hasCover) """<item id="cover-image" href="cover.jpg" media-type="image/jpeg" properties="cover-image"/>""" else ""}
    <item id="ch1" href="chapter1.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine>
    <itemref idref="ch1"/>
  </spine>
</package>"""
            zos.putNextEntry(ZipEntry("OEBPS/content.opf"))
            zos.write(opf.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            val ch1 = """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><title>Chapter 1</title></head>
<body>
  <h1>Chapter 1</h1>
  <p>Test text paragraph.</p>
</body>
</html>"""
            zos.putNextEntry(ZipEntry("OEBPS/chapter1.xhtml"))
            zos.write(ch1.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        file.writeBytes(baos.toByteArray())
        return Pair(file, Uri.fromFile(file))
    }

    @Test
    fun importEpub_savesCoverPathAndOriginalEpubCopy() = runBlocking {
        withTimeout(10_000L) {
            val (file, uri) = createTestEpubFile(
                fileName = "book_with_cover_cse.epub",
                title = "Epub With Cover",
                author = "Epub Author",
                hasCover = true
            )

            val novel = novelRepository.importEpub(uri, "book_with_cover_cse.epub")
            assertNotNull(novel)
            assertEquals("Epub With Cover", novel.title)
            assertEquals("Epub Author", novel.author)
            assertTrue(novel.isEpub)

            assertNotNull("coverUrl must be set", novel.coverUrl)
            val expectedCover = storageResolver.getCoverFile(novel.id)
            assertEquals(expectedCover.absolutePath, novel.coverUrl)
            assertTrue("Cover file must exist on disk", expectedCover.exists())
            assertEquals("${novel.id}.jpg", expectedCover.name)
            assertFalse("Must not use timestamp cover naming", expectedCover.name.startsWith("cover_"))

            val expectedOriginal = storageResolver.getOriginalEpubFile(novel.id)
            assertTrue("Original epub file must exist on disk", expectedOriginal.exists())
            assertEquals("${novel.id}.epub", expectedOriginal.name)
            assertTrue("Original epub copy matches source file", expectedOriginal.readBytes().contentEquals(file.readBytes()))

            val chapterFile = storageResolver.getEpubChapterFile(novel.id, "${novel.id}_ch_1")
            assertTrue("EPUB chapter file must exist on disk", chapterFile.exists())
        }
    }

    @Test
    fun importEpub_dedupesOnSecondImport_andWritesNothing() = runBlocking {
        withTimeout(10_000L) {
            val (_, uri) = createTestEpubFile(
                fileName = "dedupe_book_cse.epub",
                title = "Dedupe Title",
                author = "Dedupe Author",
                hasCover = true
            )

            val novelFirst = novelRepository.importEpub(uri, "dedupe_book_cse.epub")
            assertNotNull(novelFirst)

            val coverFile = storageResolver.getCoverFile(novelFirst.id)
            val originalFile = storageResolver.getOriginalEpubFile(novelFirst.id)
            assertTrue(coverFile.exists())
            assertTrue(originalFile.exists())

            val coverModBefore = coverFile.lastModified()
            val origModBefore = originalFile.lastModified()

            val novelSecond = novelRepository.importEpub(uri, "dedupe_book_cse.epub")
            assertEquals("Second import must return the existing novel", novelFirst.id, novelSecond.id)

            assertEquals("Cover file must not be modified", coverModBefore, coverFile.lastModified())
            assertEquals("Original epub file must not be modified", origModBefore, originalFile.lastModified())

            val existingInRoom = database.novelDao().getNovelById(novelFirst.id)
            assertNotNull(existingInRoom)
            assertEquals(novelFirst.id, existingInRoom!!.id)
        }
    }

    @Test
    fun importEpub_cleanupFilesOnFailure() = runBlocking {
        withTimeout(10_000L) {
            val (_, uri) = createTestEpubFile(
                fileName = "fail_test_cse.epub",
                title = "Fail Book",
                author = "Fail Author",
                hasCover = true
            )

            database.close()

            var caughtException = false
            try {
                novelRepository.importEpub(uri, "fail_test_cse.epub")
            } catch (e: Throwable) {
                caughtException = true
            }
            assertTrue("Exception must be rethrown on failure", caughtException)

            val covers = storageResolver.getCoversDir().listFiles()?.filter { it.name.startsWith("epub_") } ?: emptyList()
            val origs = storageResolver.getOriginalEpubsDir().listFiles()?.filter { it.name.startsWith("epub_") } ?: emptyList()
            val epubs = storageResolver.getEpubsDir().listFiles()?.filter { it.name.startsWith("epub_") } ?: emptyList()

            assertTrue("No leftover covers after failure", covers.isEmpty())
            assertTrue("No leftover original epubs after failure", origs.isEmpty())
            assertTrue("No leftover epubs folder after failure", epubs.isEmpty())

            database = Room.inMemoryDatabaseBuilder(context, NovelDatabase::class.java).allowMainThreadQueries().build()
            NovelDatabaseHolder.setInstanceForTesting(database)
        }
    }

    @Test
    fun getChapterContent_epubMissingFile_throwsEpubFileMissing() = runTest {
        withTimeout(10_000) {
            database.novelDao().insertIgnore(
                testNovelEntity(
                    id = "epub_novel_missing_file",
                    title = "Epub Novel",
                    author = "Author",
                    description = "Epub Description",
                    sourceId = "local_epub",
                    sourceName = "Local EPUB",
                    status = "Completed",
                    genresJson = "[]",
                    isEpub = true
                )
            )
            database.chapterDao().insertIgnore(
                ChapterEntity(
                    novelId = "epub_novel_missing_file",
                    id = "ch_epub_1",
                    title = "Chapter 1",
                    chapterIndex = 1,
                    storageTier = "EPUB",
                    storageLocation = "INTERNAL"
                )
            )

            try {
                chapterRepository.getChapterContent("epub_novel_missing_file", "ch_epub_1")
                fail("Expected EpubFileMissing exception")
            } catch (e: EpubFileMissing) {
                assertEquals("epub_novel_missing_file", e.novelId)
                assertEquals("ch_epub_1", e.chapterId)
            }
        }
    }

    @Test
    fun getChapterContent_missingChapterRow_throwsChapterRowMissing() = runTest {
        withTimeout(10_000) {
            database.novelDao().insertIgnore(
                testNovelEntity(
                    id = "epub_novel_missing_row",
                    title = "Epub Novel",
                    author = "Author",
                    description = "Epub Description",
                    sourceId = "local_epub",
                    sourceName = "Local EPUB",
                    status = "Completed",
                    genresJson = "[]",
                    isEpub = true
                )
            )

            try {
                chapterRepository.getChapterContent("epub_novel_missing_row", "ch_non_existent")
                fail("Expected ChapterRowMissing exception")
            } catch (e: ChapterRowMissing) {
                assertEquals("epub_novel_missing_row", e.novelId)
                assertEquals("ch_non_existent", e.chapterId)
            }
        }
    }

    @Test
    fun singleFlight_twoConcurrentCallsFetchOnce_thirdCallAfterCompletionFetchesAgain() = runTest {
        withTimeout(10_000) {
            val novelId = "nov_single_flight_3"
            val chapterId = "ch_sf_3"

            val novel = testNovelEntity(
                id = novelId,
                title = "Single Flight Novel",
                author = "Author",
                description = "Single flight description",
                genresJson = "[]",
                sourceId = "sf_source",
                sourceName = "Gating Source",
                status = "Ongoing",
                inLibrary = true
            )
            val chapter = ChapterEntity(
                novelId = novelId,
                id = chapterId,
                title = "Chapter SF",
                chapterIndex = 1,
                storageTier = "NONE",
                storageLocation = "INTERNAL"
            )
            database.novelDao().insertIgnore(novel)
            database.chapterDao().insertIgnore(chapter)

            val fetchStarted = CompletableDeferred<Unit>()
            val releaseFetch = CompletableDeferred<Unit>()
            val fetchCount = AtomicInteger(0)

            val gatingSource = object : NovelSource {
                override val id = "sf_source"
                override val name = "Gating Source"
                override val language = "en"
                override val version = "1.0"
                override val description = "Source for single flight testing"
                override val isLocal = false

                override suspend fun getPopularNovels(page: Int) = emptyList<Novel>()
                override suspend fun searchNovels(query: String, page: Int) = emptyList<Novel>()
                override suspend fun getNovelDetails(novelId: String): Novel = testNovel(
                    id = novelId,
                    title = "Single Flight Novel",
                    author = "Author",
                    description = "Desc",
                    sourceId = id,
                    sourceName = name
                )
                override suspend fun getChapterList(novelId: String) = emptyList<Chapter>()
                override suspend fun getChapterContent(novelId: String, chapterId: String): ChapterContent {
                    val count = fetchCount.incrementAndGet()
                    if (count == 1) {
                        fetchStarted.complete(Unit)
                        releaseFetch.await()
                    }
                    return ChapterContent(
                        chapterId = chapterId,
                        novelId = novelId,
                        title = "Title $count",
                        paragraphs = listOf("P1", "P2")
                    )
                }
            }

            SourceManager.registerSource(gatingSource)
            try {
                coroutineScope {
                    val def1 = async(Dispatchers.IO) { chapterRepository.getChapterContent(novelId, chapterId) }
                    val def2 = async(Dispatchers.IO) { chapterRepository.getChapterContent(novelId, chapterId) }

                    fetchStarted.await()
                    delay(50)
                    releaseFetch.complete(Unit)

                    val res1 = def1.await()
                    val res2 = def2.await()

                    assertEquals("Both concurrent calls get identical content", res1, res2)
                    assertEquals("Two concurrent calls fetch exactly once", 1, fetchCount.get())
                    assertTrue("inFlightFetches must be cleared after completion", chapterRepository.inFlightFetches.isEmpty())
                }

                // Delete cache file to force a cache miss on the third call
                chapterRepository.chapterCache.delete(novelId, chapterId)

                val res3 = chapterRepository.getChapterContent(novelId, chapterId)
                assertEquals("Title 2", res3.title)
                assertEquals("Third call after completion fetches again from source", 2, fetchCount.get())
                assertTrue("inFlightFetches is empty after third call completes", chapterRepository.inFlightFetches.isEmpty())
            } finally {
                releaseFetch.complete(Unit)
                SourceManager.unregisterSource(gatingSource.id)
            }
        }
    }

    private fun isRunningAsRoot(): Boolean = System.getProperty("user.name") == "root"

    // --- REAL-FILE UNREADABLE TESTS (skipped when running as root) ---

    @Test
    fun unreadableDownloadedFile_realFile_throwsChapterReadException_andKeepsTier() = runBlocking {
        Assume.assumeFalse("Skip real-file permission test when running as root", isRunningAsRoot())
        insertTestNovel("novel_unreadable_dl_real")
        val novelId = "novel_unreadable_dl_real"
        val chapterId = "ch_unreadable_dl_real"
        database.chapterDao().insertIgnore(
            ChapterEntity(
                novelId = novelId,
                id = chapterId,
                title = "Chapter DL",
                chapterIndex = 1,
                storageTier = "DOWNLOADED",
                storageLocation = "INTERNAL"
            )
        )
        val file = storageResolver.getDownloadChapterFile(novelId, chapterId)
        file.parentFile?.mkdirs()
        file.writeText("{\"title\":\"DL\"}")
        file.setReadable(false)
        try {
            try {
                chapterRepository.getChapterContent(novelId, chapterId)
                fail("Expected ChapterReadException for unreadable downloaded file")
            } catch (e: ChapterReadException) {
                assertEquals(novelId, e.novelId)
                assertEquals(chapterId, e.chapterId)
            }
            val row = database.chapterDao().getChapter(novelId, chapterId)
            assertNotNull(row)
            assertEquals("DOWNLOADED", row?.storageTier)
        } finally {
            file.setReadable(true)
        }
    }

    @Test
    fun unreadableCacheFile_realFile_fallsThroughToRefetch() = runBlocking {
        Assume.assumeFalse("Skip real-file permission test when running as root", isRunningAsRoot())
        insertTestNovel("novel_unreadable_cache_real")
        val novelId = "novel_unreadable_cache_real"
        val chapterId = "ch_unreadable_cache_real"
        database.chapterDao().insertIgnore(
            ChapterEntity(
                novelId = novelId,
                id = chapterId,
                title = "Chapter Cache",
                chapterIndex = 1,
                storageTier = "NONE",
                storageLocation = "INTERNAL"
            )
        )
        val file = chapterRepository.chapterCache.getCacheFile(novelId, chapterId)
        file.parentFile?.mkdirs()
        file.writeText("{\"title\":\"Old Cache\"}")
        file.setReadable(false)
        try {
            // Unreadable cache file falls through to refetch with no Room row change
            val content = chapterRepository.getChapterContent(novelId, chapterId)
            assertNotNull(content)
            assertEquals("Title for ch_unreadable_cache_real", content.title)
            val chapterRow = database.chapterDao().getChapter(novelId, chapterId)
            assertEquals("NONE", chapterRow?.storageTier)
        } finally {
            file.setReadable(true)
        }
    }

    @Test
    fun unreadableEpubFile_realFile_throwsChapterReadException_andKeepsTier() = runBlocking {
        Assume.assumeFalse("Skip real-file permission test when running as root", isRunningAsRoot())
        insertTestNovel("novel_unreadable_epub_real", isEpub = true)
        val novelId = "novel_unreadable_epub_real"
        val chapterId = "ch_unreadable_epub_real"
        database.chapterDao().insertIgnore(
            ChapterEntity(
                novelId = novelId,
                id = chapterId,
                title = "Chapter EPUB",
                chapterIndex = 1,
                storageTier = "EPUB",
                storageLocation = "INTERNAL"
            )
        )
        val file = storageResolver.getEpubChapterFile(novelId, chapterId)
        file.parentFile?.mkdirs()
        file.writeText("{\"title\":\"EPUB\"}")
        file.setReadable(false)
        try {
            try {
                chapterRepository.getChapterContent(novelId, chapterId)
                fail("Expected ChapterReadException for unreadable epub file")
            } catch (e: ChapterReadException) {
                assertEquals(novelId, e.novelId)
                assertEquals(chapterId, e.chapterId)
            }
            val row = database.chapterDao().getChapter(novelId, chapterId)
            assertNotNull(row)
            assertEquals("EPUB", row?.storageTier)
        } finally {
            file.setReadable(true)
        }
    }

    // --- FAKE-STORE UNREADABLE TESTS (run in all environments including root) ---

    @Test
    fun unreadableDownloadedFile_throwsChapterReadException_andKeepsTier() = runBlocking {
        insertTestNovel("novel_unreadable_dl")
        val novelId = "novel_unreadable_dl"
        val chapterId = "ch_unreadable_dl"
        database.chapterDao().insertIgnore(
            ChapterEntity(
                novelId = novelId,
                id = chapterId,
                title = "Chapter DL",
                chapterIndex = 1,
                storageTier = "DOWNLOADED",
                storageLocation = "INTERNAL"
            )
        )
        val file = storageResolver.getDownloadChapterFile(novelId, chapterId)
        file.parentFile?.mkdirs()
        file.writeText("{\"title\":\"DL\"}")
        failingFileStore.failOnRead = true
        try {
            try {
                chapterRepository.getChapterContent(novelId, chapterId)
                fail("Expected ChapterReadException for unreadable downloaded file")
            } catch (e: ChapterReadException) {
                assertEquals(novelId, e.novelId)
                assertEquals(chapterId, e.chapterId)
            }
            val row = database.chapterDao().getChapter(novelId, chapterId)
            assertNotNull(row)
            assertEquals("DOWNLOADED", row?.storageTier)
        } finally {
            failingFileStore.failOnRead = false
        }
    }

    @Test
    fun unparseableFile_isDeleted_andFallsBackToFetch() = runBlocking {
        insertTestNovel("novel_corrupt")
        val novelId = "novel_corrupt"
        val chapterId = "ch_corrupt"
        database.chapterDao().insertIgnore(
            ChapterEntity(
                novelId = novelId,
                id = chapterId,
                title = "Chapter Corrupt",
                chapterIndex = 1,
                storageTier = "DOWNLOADED",
                storageLocation = "INTERNAL"
            )
        )
        val file = storageResolver.getDownloadChapterFile(novelId, chapterId)
        file.parentFile?.mkdirs()
        file.writeText("{corrupt not json content")
        assertTrue(file.exists())

        // Reading unparseable file deletes the corrupt file and falls through to fetch from source into cache
        val content = chapterRepository.getChapterContent(novelId, chapterId)
        assertNotNull(content)
        assertEquals("Title for ch_corrupt", content.title)
        assertFalse("Corrupted file must be deleted", file.exists())
    }

    @Test
    fun corruptCacheFile_fallsThroughToRefetch_andLeavesTierNone() = runBlocking {
        insertTestNovel("novel_unreadable_cache")
        val novelId = "novel_unreadable_cache"
        val chapterId = "ch_unreadable_cache"
        database.chapterDao().insertIgnore(
            ChapterEntity(
                novelId = novelId,
                id = chapterId,
                title = "Chapter Cache",
                chapterIndex = 1,
                storageTier = "NONE",
                storageLocation = "INTERNAL"
            )
        )
        val file = chapterRepository.chapterCache.getCacheFile(novelId, chapterId)
        file.parentFile?.mkdirs()
        file.writeText("corrupt non-json data")
        assertTrue(file.exists())

        // Corrupt cache file returns miss and refetches without throwing ChapterReadException
        val content = chapterRepository.getChapterContent(novelId, chapterId)
        assertNotNull(content)
        assertEquals("Title for ch_unreadable_cache", content.title)
        val chapterRow = database.chapterDao().getChapter(novelId, chapterId)
        assertEquals("NONE", chapterRow?.storageTier)
        assertTrue("Cache now holds the newly fetched valid content", chapterRepository.chapterCache.contains(novelId, chapterId))
    }

    @Test
    fun unreadableEpubFile_throwsChapterReadException_andKeepsTier() = runBlocking {
        insertTestNovel("novel_unreadable_epub", isEpub = true)
        val novelId = "novel_unreadable_epub"
        val chapterId = "ch_unreadable_epub"
        database.chapterDao().insertIgnore(
            ChapterEntity(
                novelId = novelId,
                id = chapterId,
                title = "Chapter EPUB",
                chapterIndex = 1,
                storageTier = "EPUB",
                storageLocation = "INTERNAL"
            )
        )
        val file = storageResolver.getEpubChapterFile(novelId, chapterId)
        file.parentFile?.mkdirs()
        file.writeText("{\"title\":\"EPUB\"}")
        failingFileStore.failOnRead = true
        try {
            try {
                chapterRepository.getChapterContent(novelId, chapterId)
                fail("Expected ChapterReadException for unreadable epub file")
            } catch (e: ChapterReadException) {
                assertEquals(novelId, e.novelId)
                assertEquals(chapterId, e.chapterId)
            }
            val row = database.chapterDao().getChapter(novelId, chapterId)
            assertNotNull(row)
            assertEquals("EPUB", row?.storageTier)
        } finally {
            failingFileStore.failOnRead = false
        }
    }

    @Test
    fun promote_whenCasFails_keepsCacheCopy() = runBlocking {
        insertTestNovel("novel_cas_fail")
        val novelId = "novel_cas_fail"
        val chapterId = "ch_cas_fail"
        // Chapter row in Room has storage_tier EPUB (which updateTierToDownloaded rejects, returning 0 rows)
        database.chapterDao().insertIgnore(
            ChapterEntity(
                novelId = novelId,
                id = chapterId,
                title = "Chapter 1",
                chapterIndex = 1,
                storageTier = "EPUB"
            )
        )
        // Put in ChapterCache
        val cachedContent = ChapterContent(chapterId, novelId, "Title", listOf("Line 1"))
        chapterRepository.chapterCache.write(cachedContent)
        assertTrue("Cache file exists before promotion", chapterRepository.chapterCache.contains(novelId, chapterId))

        val novelModel = Novel(
            id = novelId,
            title = "Test Novel",
            author = "Author",
            description = "Desc",
            sourceId = fakeSource.id,
            sourceName = "Fake Source"
        )
        val chapterModel = Chapter(
            id = chapterId,
            novelId = novelId,
            title = "Chapter 1",
            chapterIndex = 1
        )

        // downloadChapter attempts promote, but Room tier is EPUB (so updateTierToDownloaded updates 0 rows)
        chapterRepository.downloadChapter(novelModel, chapterModel)

        // Cache copy must be kept because CAS did not update any rows
        assertTrue("Cache file must be kept when CAS returns 0 rows", chapterRepository.chapterCache.contains(novelId, chapterId))
    }

    @Test
    fun readerAndDownloader_shareOneInFlightFetch() = runBlocking {
        withTimeout(10_000L) {
            val novelId = "novel_reader_downloader_shared"
            val chapterId = "ch_shared"
            insertTestNovel(novelId)
            database.chapterDao().insertIgnore(
                ChapterEntity(
                    novelId = novelId,
                    id = chapterId,
                    title = "Shared Chapter",
                    chapterIndex = 1,
                    storageTier = "NONE"
                )
            )

            val fetchStarted = CompletableDeferred<Unit>()
            val releaseFetch = CompletableDeferred<Unit>()

            val slowSharedSource = object : NovelSource {
                override val id = "slow_shared_source"
                override val name = "Slow Shared Source"
                override val language = "English"
                override val version = "1.0"
                override val description = "Slow shared source"
                override val isLocal = false

                val fetchCount = java.util.concurrent.atomic.AtomicInteger(0)

                override suspend fun getPopularNovels(page: Int): List<Novel> = emptyList()
                override suspend fun searchNovels(query: String, page: Int): List<Novel> = emptyList()
                override suspend fun getNovelDetails(nId: String): Novel = Novel(
                    id = nId,
                    title = "Test Novel",
                    author = "Author",
                    description = "Desc",
                    sourceId = id,
                    sourceName = name
                )
                override suspend fun getChapterList(nId: String): List<Chapter> = emptyList()
                override suspend fun getChapterContent(nId: String, cId: String): ChapterContent {
                    fetchCount.incrementAndGet()
                    fetchStarted.complete(Unit)
                    releaseFetch.await()
                    return ChapterContent(
                        chapterId = cId,
                        novelId = nId,
                        title = "Shared Title",
                        paragraphs = listOf("P1", "P2")
                    )
                }
            }

            SourceManager.registerSource(slowSharedSource)
            try {
                database.openHelper.writableDatabase.execSQL("UPDATE novels SET source_id = '${slowSharedSource.id}' WHERE id = '$novelId'")

                val novelModel = Novel(
                    id = novelId,
                    title = "Test Novel",
                    author = "Author",
                    description = "Desc",
                    sourceId = slowSharedSource.id,
                    sourceName = slowSharedSource.name
                )
                val chapterModel = Chapter(
                    id = chapterId,
                    novelId = novelId,
                    title = "Shared Chapter",
                    chapterIndex = 1
                )

                coroutineScope {
                    // Reader call
                    val readerJob = async(Dispatchers.IO) {
                        chapterRepository.getChapterContent(novelId, chapterId)
                    }
                    // Downloader call
                    val downloadJob = async(Dispatchers.IO) {
                        chapterRepository.downloadChapter(novelModel, chapterModel)
                    }

                    fetchStarted.await()
                    delay(50)
                    releaseFetch.complete(Unit)

                    val readerResult = readerJob.await()
                    val downloadResult = downloadJob.await()

                    assertEquals("Reader got content title", "Shared Title", readerResult.title)
                    assertTrue("Downloader succeeded", downloadResult.isSuccess)
                    assertEquals("Single-flight ensures source is fetched exactly once", 1, slowSharedSource.fetchCount.get())

                    // Final state: downloader marked row DOWNLOADED
                    val row = database.chapterDao().getChapter(novelId, chapterId)
                    assertEquals("DOWNLOADED", row?.storageTier)
                }
            } finally {
                releaseFetch.complete(Unit)
                SourceManager.unregisterSource(slowSharedSource.id)
            }
        }
    }

    @Test
    fun getChapterContent_whenStorageUnavailable_throwsExceptionAndKeepsTierWithoutRefetch() = runBlocking {
        val novelId = "novel_storage_unavail"
        val chapterId = "ch_storage_unavail"
        insertTestNovel(novelId)
        database.chapterDao().insertIgnore(
            ChapterEntity(
                novelId = novelId,
                id = chapterId,
                title = "Unavailable Chapter",
                chapterIndex = 1,
                storageTier = "DOWNLOADED",
                storageLocation = "SAF:content://unmounted/tree"
            )
        )

        val storeWithUnavailable = object : ChapterFileStore by failingFileStore {
            override suspend fun read(novelId: String, chapterId: String, tier: String, location: String): ChapterContent? {
                throw StorageUnavailableException("Storage unmounted")
            }
        }
        val customStorage = ChapterStorage(
            resolver = storageResolver,
            fileStore = storeWithUnavailable,
            chapterCache = chapterRepository.chapterCache,
            ioDispatcher = Dispatchers.IO
        )
        val repo = ChapterRepository(
            chapterDao = database.chapterDao(),
            novelDao = database.novelDao(),
            chapterStorage = customStorage,
            storageResolver = storageResolver,
            settingsRepository = settingsRepository,
            downloadQueueDao = database.downloadQueueDao(),
            chapterCache = chapterRepository.chapterCache,
            ioDispatcher = Dispatchers.IO
        )

        val beforeFetchCount = fakeSource.fetchCount
        try {
            repo.getChapterContent(novelId, chapterId)
            fail("Expected StorageUnavailableException")
        } catch (_: StorageUnavailableException) {
            // Expected
        }

        assertEquals("No network refetch should happen when storage is unavailable", beforeFetchCount, fakeSource.fetchCount)
        val row = database.chapterDao().getChapter(novelId, chapterId)
        assertEquals("DOWNLOADED tier must be preserved when storage is unavailable", "DOWNLOADED", row?.storageTier)
    }

    @Test
    fun inFlightFetches_cancelledCallerDoesNotRemoveMapEntry_andThirdCallerSharesSameFetch() = runBlocking {
        withTimeout(10_000L) {
            val novelId = "novel_inflight_cancel"
            val chapterId = "ch_inflight_cancel"
            insertTestNovel(novelId)
            database.chapterDao().insertIgnore(
                ChapterEntity(
                    novelId = novelId,
                    id = chapterId,
                    title = "In-Flight Cancel Chapter",
                    chapterIndex = 1,
                    storageTier = "NONE"
                )
            )

            val fetchStarted = CompletableDeferred<Unit>()
            val releaseFetch = CompletableDeferred<Unit>()

            val gatedSource = object : NovelSource {
                override val id = "gated_cancel_source"
                override val name = "Gated Cancel Source"
                override val language = "English"
                override val version = "1.0"
                override val description = "Gated cancel source"
                override val isLocal = false

                val fetchCount = java.util.concurrent.atomic.AtomicInteger(0)

                override suspend fun getPopularNovels(page: Int): List<Novel> = emptyList()
                override suspend fun searchNovels(query: String, page: Int): List<Novel> = emptyList()
                override suspend fun getNovelDetails(nId: String): Novel = Novel(
                    id = nId,
                    title = "Test Novel",
                    author = "Author",
                    description = "Desc",
                    sourceId = id,
                    sourceName = name
                )
                override suspend fun getChapterList(nId: String): List<Chapter> = emptyList()
                override suspend fun getChapterContent(nId: String, cId: String): ChapterContent {
                    fetchCount.incrementAndGet()
                    fetchStarted.complete(Unit)
                    releaseFetch.await()
                    return ChapterContent(
                        chapterId = cId,
                        novelId = nId,
                        title = "Gated Content",
                        paragraphs = listOf("Para 1")
                    )
                }
            }

            SourceManager.registerSource(gatedSource)
            try {
                database.openHelper.writableDatabase.execSQL("UPDATE novels SET source_id = '${gatedSource.id}' WHERE id = '$novelId'")

                coroutineScope {
                    // Two callers start reading the same chapter
                    val caller1 = async(Dispatchers.IO) {
                        chapterRepository.getChapterContent(novelId, chapterId)
                    }
                    val caller2 = async(Dispatchers.IO) {
                        chapterRepository.getChapterContent(novelId, chapterId)
                    }

                    // Wait until the underlying fetch has been entered and is blocked on releaseFetch
                    fetchStarted.await()

                    // Cancel caller1 while fetch is still blocked
                    caller1.cancel()

                    // A third caller arrives while fetch is still blocked
                    val caller3 = async(Dispatchers.IO) {
                        chapterRepository.getChapterContent(novelId, chapterId)
                    }

                    // Release the fetch gate so the blocked fetch can complete
                    releaseFetch.complete(Unit)

                    // caller1 should fail with CancellationException
                    try {
                        caller1.await()
                        fail("Expected caller1 to be cancelled")
                    } catch (e: Throwable) {
                        assertTrue("caller1 threw CancellationException", e is CancellationException)
                    }

                    val res2 = caller2.await()
                    val res3 = caller3.await()

                    assertEquals("caller2 received gated content", "Gated Content", res2.title)
                    assertEquals("caller3 received gated content", "Gated Content", res3.title)

                    // Source must have been fetched exactly once in total
                    assertEquals("Source is fetched only once despite cancellation and 3 callers", 1, gatedSource.fetchCount.get())

                    // inFlightFetches must be empty at the end
                    assertTrue("inFlightFetches must be empty after all callers complete", chapterRepository.inFlightFetches.isEmpty())
                }
            } finally {
                releaseFetch.complete(Unit)
                SourceManager.unregisterSource(gatedSource.id)
            }
        }
    }
}
