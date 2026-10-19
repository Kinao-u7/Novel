package com.example.data.storage

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.db.NovelDatabase
import com.example.data.local.db.NovelDatabaseHolder
import com.example.data.local.db.entity.ChapterEntity
import com.example.data.local.db.entity.NovelEntity
import com.example.data.local.storage.CacheReconciler
import com.example.data.local.storage.ChapterFileStore
import com.example.data.local.storage.ChapterStorage
import com.example.data.local.storage.InternalChapterFileStore
import com.example.data.local.storage.StorageResolver
import com.example.data.local.storage.StorageUnavailableException
import com.example.data.model.Chapter
import com.example.data.model.ChapterContent
import com.example.data.model.DownloadSettings
import com.example.data.model.Novel
import com.example.data.repository.ChapterRepository
import com.example.data.repository.SettingsRepository
import com.example.data.source.NovelSource
import com.example.data.source.SourceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Fake implementation of ChapterFileStore enabling complete in-memory deterministic testing
 * of SAF download folders and permission failures without requiring Android SAF APIs (Spec 1).
 */
class FakeChapterFileStore(
    val internalStore: ChapterFileStore
) : ChapterFileStore {
    var isSafWritable: Boolean = true
    var isSafReachable: Boolean = true

    // Simulating SAF storage in-memory: key = "$location|$novelId|$chapterId"
    val safFiles = ConcurrentHashMap<String, String>()

    override suspend fun read(novelId: String, chapterId: String, tier: String, location: String): ChapterContent? {
        if (location.startsWith("SAF:")) {
            if (!isSafReachable) {
                throw StorageUnavailableException("SAF folder is unavailable: $location")
            }
            val key = "$location|$novelId|$chapterId"
            val text = safFiles[key] ?: return null
            return InternalChapterFileStore.deserialize(text, isDownloaded = true)
        }
        return internalStore.read(novelId, chapterId, tier, location)
    }

    override suspend fun writeCache(content: ChapterContent): File? {
        return internalStore.writeCache(content)
    }

    override suspend fun writeDownload(content: ChapterContent, location: String): Boolean {
        if (location.startsWith("SAF:")) {
            if (!isSafWritable) {
                throw StorageUnavailableException("SAF folder is unwritable: $location")
            }
            val key = "$location|${content.novelId}|${content.chapterId}"
            safFiles[key] = InternalChapterFileStore.serialize(content.copy(isDownloaded = true))
            return true
        }
        return internalStore.writeDownload(content, location)
    }

    override suspend fun writeEpub(content: ChapterContent): File? {
        return internalStore.writeEpub(content)
    }

    override suspend fun promoteCacheToDownload(novelId: String, chapterId: String, targetLocation: String): Boolean {
        if (targetLocation == "INTERNAL") {
            return internalStore.promoteCacheToDownload(novelId, chapterId, "INTERNAL")
        }
        if (targetLocation.startsWith("SAF:")) {
            if (!isSafWritable) {
                throw StorageUnavailableException("SAF folder is unwritable: $targetLocation")
            }
            val cached = internalStore.read(novelId, chapterId, "CACHE", "INTERNAL") ?: return false
            writeDownload(cached, targetLocation)
            internalStore.deleteChapter(novelId, chapterId, "CACHE", "INTERNAL")
            return true
        }
        return false
    }

    override suspend fun deleteChapter(novelId: String, chapterId: String, tier: String, location: String): Boolean {
        if (location.startsWith("SAF:")) {
            val key = "$location|$novelId|$chapterId"
            return safFiles.remove(key) != null
        }
        return internalStore.deleteChapter(novelId, chapterId, tier, location)
    }

    override suspend fun deleteNovelDownloads(novelId: String, locations: Set<String>): Boolean {
        var deleted = internalStore.deleteNovelDownloads(novelId, locations)
        for (loc in locations) {
            if (loc.startsWith("SAF:")) {
                val prefix = "$loc|$novelId|"
                val keysToRemove = safFiles.keys.filter { it.startsWith(prefix) }
                for (k in keysToRemove) {
                    safFiles.remove(k)
                    deleted = true
                }
            }
        }
        return deleted
    }

    override suspend fun deleteNovelCache(novelId: String): Boolean {
        return internalStore.deleteNovelCache(novelId)
    }

    override suspend fun deleteAllDownloads(locations: Set<String>): Boolean {
        var deleted = internalStore.deleteAllDownloads(locations)
        if (safFiles.isNotEmpty()) {
            safFiles.clear()
            deleted = true
        }
        return deleted
    }

    override suspend fun clearCache(): Long {
        return internalStore.clearCache()
    }

    override suspend fun removeNovelCompletely(novelId: String, isEpub: Boolean, locations: Set<String>): Unit {
        internalStore.removeNovelCompletely(novelId, isEpub, locations)
        for (loc in locations) {
            if (loc.startsWith("SAF:")) {
                val prefix = "$loc|$novelId|"
                safFiles.keys.filter { it.startsWith(prefix) }.forEach { safFiles.remove(it) }
            }
        }
    }

    override suspend fun getCacheSizeBytes(): Long = internalStore.getCacheSizeBytes()

    override suspend fun getDownloadsSizeBytes(): Long =
        internalStore.getDownloadsSizeBytes() + safFiles.values.sumOf { it.length.toLong() }

    override suspend fun getEpubsSizeBytes(): Long = internalStore.getEpubsSizeBytes()

    override fun isLocationReachable(location: String): Boolean {
        return if (location.startsWith("SAF:")) isSafReachable else internalStore.isLocationReachable(location)
    }

    override fun isLocationWritable(location: String): Boolean {
        return if (location.startsWith("SAF:")) isSafWritable else internalStore.isLocationWritable(location)
    }
}

private class TestFakeSource : NovelSource {
    override val id: String = "test_source_saf"
    override val name: String = "Test Source SAF"
    override val language: String = "en"
    override val version: String = "1.0.0"
    override val description: String = "Test Source"
    override val isLocal: Boolean = false

    override suspend fun getPopularNovels(page: Int): List<Novel> = emptyList()
    override suspend fun searchNovels(query: String, page: Int): List<Novel> = emptyList()
    override suspend fun getNovelDetails(novelId: String): Novel = Novel(
        id = novelId,
        title = "Novel $novelId",
        author = "Author",
        description = "Description for $novelId",
        sourceId = id,
        sourceName = name
    )
    override suspend fun getChapterList(novelId: String): List<Chapter> = emptyList()

    override suspend fun getChapterContent(novelId: String, chapterId: String): ChapterContent {
        return ChapterContent(
            chapterId = chapterId,
            novelId = novelId,
            title = "Title for $chapterId",
            paragraphs = listOf("Paragraph 1 of $chapterId", "Paragraph 2 of $chapterId")
        )
    }
}

@RunWith(RobolectricTestRunner::class)
class SafStorageAndReconcilerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var database: NovelDatabase
    private lateinit var storageResolver: StorageResolver
    private lateinit var internalFileStore: InternalChapterFileStore
    private lateinit var fakeFileStore: FakeChapterFileStore
    private lateinit var chapterStorage: ChapterStorage
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var chapterRepository: ChapterRepository
    private lateinit var cacheReconciler: CacheReconciler
    private lateinit var fakeSource: TestFakeSource

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, NovelDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        NovelDatabaseHolder.setInstanceForTesting(database)

        storageResolver = StorageResolver(tempFolder.root)
        internalFileStore = InternalChapterFileStore(storageResolver, Dispatchers.IO)
        fakeFileStore = FakeChapterFileStore(internalFileStore)
        chapterStorage = ChapterStorage(storageResolver, fakeFileStore, Dispatchers.IO)

        settingsRepository = SettingsRepository.createForTest(context, Dispatchers.IO)
        chapterRepository = ChapterRepository(
            chapterDao = database.chapterDao(),
            novelDao = database.novelDao(),
            chapterStorage = chapterStorage,
            storageResolver = storageResolver,
            settingsRepository = settingsRepository,
            downloadQueueDao = database.downloadQueueDao(),
            notificationDao = database.notificationDao(),
            ioDispatcher = Dispatchers.IO
        )
        cacheReconciler = CacheReconciler(
            chapterDao = database.chapterDao(),
            storageResolver = storageResolver,
            ioDispatcher = Dispatchers.IO
        )

        fakeSource = TestFakeSource()
        SourceManager.registerSource(fakeSource)
    }

    @After
    fun tearDown() {
        SourceManager.unregisterSource(fakeSource.id)
        NovelDatabaseHolder.clearInstanceForTesting()
        database.close()
    }

    private fun testNovel(
        id: String = "test_novel_saf",
        title: String = "Test Novel",
        author: String = "Author",
        coverUrl: String? = null,
        description: String = "Test Description",
        genres: List<String> = listOf("Fantasy"),
        status: String = "Ongoing",
        sourceId: String = "test_source_saf",
        sourceName: String = "Test Source SAF",
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

    private fun testChapter(
        id: String = "test_ch_saf",
        novelId: String = "test_novel_saf",
        title: String = "Test Chapter",
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

    private suspend fun insertTestNovel(novelId: String): Novel {
        val novel = testNovel(
            id = novelId,
            title = "Title $novelId",
            author = "Author $novelId",
            description = "Description $novelId",
            sourceId = fakeSource.id,
            sourceName = fakeSource.name,
            inLibrary = true
        )
        database.novelDao().insertIgnore(
            NovelEntity(
                id = novel.id,
                title = novel.title,
                author = novel.author,
                description = novel.description,
                status = novel.status,
                sourceId = novel.sourceId,
                sourceName = novel.sourceName,
                inLibrary = true,
                createdAt = System.currentTimeMillis()
            )
        )
        return novel
    }

    @Test
    fun download_withUnwritableSaf_fallsBackToInternalAndRecordsIt() = runBlocking {
        withTimeout(10_000L) {
            val novel = insertTestNovel("novel_saf_unwritable")
            val chapter = Chapter("ch_saf_1", novel.id, "Ch 1", 1)

            // Configure custom download location
            settingsRepository.updateDownloadSettings {
                it.copy(customDownloadLocationUri = "content://saf/tree/unwritable")
            }

            // Simulate SAF folder being unwritable
            fakeFileStore.isSafWritable = false

            // Download chapter
            val success = chapterRepository.downloadChapter(novel, chapter)
            assertTrue("Download must succeed even if SAF is unwritable (falls back to internal)", success)

            // Verify Room row records storage_location = "INTERNAL"
            val row = database.chapterDao().getChapter(novel.id, chapter.id)
            assertNotNull("Chapter row must exist", row)
            assertEquals("storageTier must be DOWNLOADED", "DOWNLOADED", row!!.storageTier)
            assertEquals("storageLocation must fall back to INTERNAL", "INTERNAL", row.storageLocation)

            // Verify file written under internal storage
            val internalFile = storageResolver.getDownloadChapterFile(novel.id, chapter.id)
            assertTrue("Internal file must exist", internalFile.exists())

            // Verify warning event was emitted
            val warning = chapterRepository.storageWarnings.first()
            assertTrue("Warning event must mention unwritable or fallback", warning.contains("unwritable") || warning.contains("Falling back"))
        }
    }

    @Test
    fun readingUsesRecordedLocation_afterFolderSettingChanges() = runBlocking {
        withTimeout(10_000L) {
            val novel = insertTestNovel("novel_saf_loc")
            val chapter = Chapter("ch_saf_loc_1", novel.id, "Ch Loc 1", 1)

            // First location
            val initialTree = "content://saf/tree/folder_a"
            settingsRepository.updateDownloadSettings {
                it.copy(customDownloadLocationUri = initialTree)
            }
            fakeFileStore.isSafWritable = true
            fakeFileStore.isSafReachable = true

            // Download chapter to initialTree
            val downloaded = chapterRepository.downloadChapter(novel, chapter)
            assertTrue(downloaded)

            val rowBefore = database.chapterDao().getChapter(novel.id, chapter.id)
            assertNotNull(rowBefore)
            assertEquals("SAF:$initialTree", rowBefore!!.storageLocation)

            // Change folder setting to a new folder
            val newTree = "content://saf/tree/folder_b"
            settingsRepository.updateDownloadSettings {
                it.copy(customDownloadLocationUri = newTree)
            }

            // Read the chapter: must use recorded location from row (folder_a), NOT folder_b
            val content = chapterRepository.getChapterContent(novel.id, chapter.id)
            assertNotNull(content)
            assertEquals("ch_saf_loc_1", content.chapterId)

            val rowAfter = database.chapterDao().getChapter(novel.id, chapter.id)
            assertEquals("Row must retain original recorded location without orphaning", "SAF:$initialTree", rowAfter!!.storageLocation)
        }
    }

    @Test
    fun read_whenFolderIsUnavailable_doesNotResetTier() = runBlocking {
        withTimeout(10_000L) {
            val novel = insertTestNovel("novel_unavail")
            val chapterId = "ch_unavail_1"
            val safLoc = "SAF:content://saf/tree/sdcard"

            // Seed chapter in Room as DOWNLOADED in SAF
            database.chapterDao().insertIgnore(
                ChapterEntity(
                    novelId = novel.id,
                    id = chapterId,
                    title = "Ch Unavailable",
                    chapterIndex = 1,
                    storageTier = "DOWNLOADED",
                    storageLocation = safLoc,
                    fileHash = storageResolver.sha256(chapterId),
                    lastAccessedAt = System.currentTimeMillis()
                )
            )

            // Store content in fake SAF
            fakeFileStore.safFiles["$safLoc|${novel.id}|$chapterId"] = InternalChapterFileStore.serialize(
                ChapterContent(chapterId, novel.id, "Ch Unavailable", listOf("Text"), isDownloaded = true)
            )

            // Simulate SD card removed / permission lost
            fakeFileStore.isSafReachable = false

            try {
                chapterRepository.getChapterContent(novel.id, chapterId)
                fail("Expected StorageUnavailableException when folder is unreachable")
            } catch (e: StorageUnavailableException) {
                // Expected exception
            }

            // CRITICAL: tier must NOT be reset to NONE when folder is unreachable
            val row = database.chapterDao().getChapter(novel.id, chapterId)
            assertNotNull(row)
            assertEquals("Tier must remain DOWNLOADED when storage is unavailable", "DOWNLOADED", row!!.storageTier)
            assertEquals("Location must remain unchanged", safLoc, row.storageLocation)
        }
    }

    @Test
    fun read_missingFileInReachableFolder_resetsTierToNone() = runBlocking {
        withTimeout(10_000L) {
            val novel = insertTestNovel("novel_missing")
            val chapterId = "ch_missing_1"
            val safLoc = "SAF:content://saf/tree/reachable"

            // Seed chapter in Room as DOWNLOADED in SAF, but do NOT put file in fakeFileStore
            database.chapterDao().insertIgnore(
                ChapterEntity(
                    novelId = novel.id,
                    id = chapterId,
                    title = "Ch Missing",
                    chapterIndex = 1,
                    storageTier = "DOWNLOADED",
                    storageLocation = safLoc,
                    fileHash = storageResolver.sha256(chapterId),
                    lastAccessedAt = System.currentTimeMillis()
                )
            )

            // Folder is reachable, but file is really missing
            fakeFileStore.isSafReachable = true

            // Read chapter: should detect miss, reset tier to NONE, and fetch fresh from source into CACHE
            val content = chapterRepository.getChapterContent(novel.id, chapterId)
            assertNotNull("Content must be fetched from source", content)

            val row = database.chapterDao().getChapter(novel.id, chapterId)
            assertNotNull(row)
            assertEquals("Missing file in reachable folder must reset tier and now cache it", "CACHE", row!!.storageTier)
            assertEquals("INTERNAL", row.storageLocation)
        }
    }

    @Test
    fun deletesRemoveSafFiles_andNeverEpub() = runBlocking {
        withTimeout(10_000L) {
            val novel = insertTestNovel("novel_del_saf")
            val safLoc = "SAF:content://saf/tree/downloads"

            // 1. Download chapter in SAF
            val dlChapterId = "ch_dl_saf"
            database.chapterDao().insertIgnore(
                ChapterEntity(
                    novelId = novel.id,
                    id = dlChapterId,
                    title = "DL Chapter",
                    chapterIndex = 1,
                    storageTier = "DOWNLOADED",
                    storageLocation = safLoc,
                    fileHash = storageResolver.sha256(dlChapterId),
                    lastAccessedAt = System.currentTimeMillis()
                )
            )
            fakeFileStore.safFiles["$safLoc|${novel.id}|$dlChapterId"] = InternalChapterFileStore.serialize(
                ChapterContent(dlChapterId, novel.id, "DL Chapter", listOf("DL"), isDownloaded = true)
            )

            // 2. EPUB chapter in internal storage
            val epubChapterId = "ch_epub_protect"
            database.chapterDao().insertIgnore(
                ChapterEntity(
                    novelId = novel.id,
                    id = epubChapterId,
                    title = "EPUB Chapter",
                    chapterIndex = 2,
                    storageTier = "EPUB",
                    storageLocation = "INTERNAL",
                    fileHash = storageResolver.sha256(epubChapterId),
                    lastAccessedAt = System.currentTimeMillis()
                )
            )
            chapterStorage.writeToEpub(ChapterContent(epubChapterId, novel.id, "EPUB Chapter", listOf("EPUB"), isDownloaded = true))
            val epubFile = storageResolver.getEpubChapterFile(novel.id, epubChapterId)
            assertTrue("EPUB file must exist on disk before deletion", epubFile.exists())

            // Delete downloads for novel
            chapterRepository.deleteNovelDownloads(novel.id)

            // SAF file must be deleted
            assertNull("SAF file must be deleted", fakeFileStore.safFiles["$safLoc|${novel.id}|$dlChapterId"])
            val dlRow = database.chapterDao().getChapter(novel.id, dlChapterId)
            assertEquals("DOWNLOADED tier must be reset to NONE", "NONE", dlRow!!.storageTier)

            // EPUB file and row must NEVER be touched
            assertTrue("EPUB file must NOT be deleted", epubFile.exists())
            val epubRow = database.chapterDao().getChapter(novel.id, epubChapterId)
            assertEquals("EPUB tier must remain EPUB", "EPUB", epubRow!!.storageTier)
        }
    }

    @Test
    fun reconciler_deletesOnlyOldUnregisteredFilesInCacheAndStaging_andResetsMissingInternalRows() = runBlocking {
        withTimeout(10_000L) {
            val novel = insertTestNovel("novel_reconcile")
            val now = System.currentTimeMillis()
            val fifteenMinsAgo = now - 15 * 60 * 1000L
            val fiveMinsAgo = now - 5 * 60 * 1000L

            // A. Cache file older than 10 mins with NO Room row -> MUST be deleted
            val oldOrphanCache = storageResolver.getCacheChapterFile(novel.id, "orphan_old")
            oldOrphanCache.parentFile?.mkdirs()
            oldOrphanCache.writeText("orphan old")
            oldOrphanCache.setLastModified(fifteenMinsAgo)

            // B. Cache file newer than 10 mins with NO Room row -> MUST NOT be deleted
            val newOrphanCache = storageResolver.getCacheChapterFile(novel.id, "orphan_new")
            newOrphanCache.writeText("orphan new")
            newOrphanCache.setLastModified(fiveMinsAgo)

            // C. Cache file older than 10 mins WITH Room row -> MUST NOT be deleted
            val validOldCacheId = "valid_old_cache"
            database.chapterDao().insertIgnore(
                ChapterEntity(
                    novelId = novel.id,
                    id = validOldCacheId,
                    title = "Valid Cache",
                    chapterIndex = 1,
                    storageTier = "CACHE",
                    storageLocation = "INTERNAL",
                    fileHash = storageResolver.sha256(validOldCacheId),
                    lastAccessedAt = fifteenMinsAgo
                )
            )
            val validOldCacheFile = storageResolver.getCacheChapterFile(novel.id, validOldCacheId)
            validOldCacheFile.writeText("valid old")
            validOldCacheFile.setLastModified(fifteenMinsAgo)

            // D. Staging files: old deleted, new preserved
            val stagingDir = storageResolver.getStagingDir()
            stagingDir.mkdirs()
            val oldStaging = File(stagingDir, "old_restore.tmp")
            oldStaging.writeText("old staging")
            oldStaging.setLastModified(fifteenMinsAgo)

            val newStaging = File(stagingDir, "new_restore.tmp")
            newStaging.writeText("new staging")
            newStaging.setLastModified(fiveMinsAgo)

            // E. Download file with NO Room row -> MUST NOT be deleted (downloads/ never touched)
            val orphanDownload = storageResolver.getDownloadChapterFile(novel.id, "orphan_dl")
            orphanDownload.parentFile?.mkdirs()
            orphanDownload.writeText("orphan dl")
            orphanDownload.setLastModified(fifteenMinsAgo)

            // F. Missing internal CACHE row -> reset to NONE
            val missingCacheId = "missing_cache_row"
            database.chapterDao().insertIgnore(
                ChapterEntity(
                    novelId = novel.id,
                    id = missingCacheId,
                    title = "Missing Cache",
                    chapterIndex = 2,
                    storageTier = "CACHE",
                    storageLocation = "INTERNAL",
                    fileHash = storageResolver.sha256(missingCacheId),
                    lastAccessedAt = now
                )
            )

            // G. Missing internal DOWNLOADED row -> reset to NONE
            val missingDlId = "missing_dl_row"
            database.chapterDao().insertIgnore(
                ChapterEntity(
                    novelId = novel.id,
                    id = missingDlId,
                    title = "Missing DL",
                    chapterIndex = 3,
                    storageTier = "DOWNLOADED",
                    storageLocation = "INTERNAL",
                    fileHash = storageResolver.sha256(missingDlId),
                    lastAccessedAt = now
                )
            )

            // H. Missing SAF DOWNLOADED row -> MUST NOT be reset by reconciler
            val missingSafId = "missing_saf_row"
            database.chapterDao().insertIgnore(
                ChapterEntity(
                    novelId = novel.id,
                    id = missingSafId,
                    title = "Missing SAF",
                    chapterIndex = 4,
                    storageTier = "DOWNLOADED",
                    storageLocation = "SAF:content://saf/tree",
                    fileHash = storageResolver.sha256(missingSafId),
                    lastAccessedAt = now
                )
            )

            // Run Reconciler
            cacheReconciler.reconcile(force = true)

            // Verify A: old orphan cache deleted
            assertFalse("Old orphan cache file must be deleted", oldOrphanCache.exists())

            // Verify B: new orphan cache preserved
            assertTrue("New orphan cache file must be preserved", newOrphanCache.exists())

            // Verify C: registered old cache preserved
            assertTrue("Registered old cache file must be preserved", validOldCacheFile.exists())

            // Verify D: old staging deleted, new staging preserved
            assertFalse("Old staging file must be deleted", oldStaging.exists())
            assertTrue("New staging file must be preserved", newStaging.exists())

            // Verify E: download file untouched
            assertTrue("Downloads folder is never touched by reconciler", orphanDownload.exists())

            // Verify F: missing internal cache row reset to NONE
            val rowF = database.chapterDao().getChapter(novel.id, missingCacheId)
            assertEquals("Missing internal CACHE row must be reset to NONE", "NONE", rowF!!.storageTier)

            // Verify G: missing internal download row reset to NONE
            val rowG = database.chapterDao().getChapter(novel.id, missingDlId)
            assertEquals("Missing internal DOWNLOADED row must be reset to NONE", "NONE", rowG!!.storageTier)

            // Verify H: SAF row NOT touched by reconciler
            val rowH = database.chapterDao().getChapter(novel.id, missingSafId)
            assertEquals("SAF row must not be touched by reconciler", "DOWNLOADED", rowH!!.storageTier)
        }
    }

    @Test
    fun folderSetting_savedOnlyWhenPermissionCheckPasses() = runBlocking {
        withTimeout(10_000L) {
            // Test 1: Invalid / non-existent URI where permission check fails
            val invalidUri = Uri.parse("content://invalid.storage.provider/tree/non_existent")
            val resultFail = settingsRepository.setCustomDownloadLocation(invalidUri, "Invalid Folder")
            assertFalse("Setting must return false when permission check fails", resultFail)
            assertNull("Download settings must not save location on failure", settingsRepository.downloadSettings.value.customDownloadLocationUri)

            // Test 2: In Robolectric without DocumentProvider, direct persistFolderPermission returns false
            val canWrite = SettingsRepository.persistFolderPermission(context, invalidUri)
            assertFalse("persistFolderPermission returns false on un-grantable URI", canWrite)
        }
    }
}
