package com.example.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.db.NovelDatabase
import com.example.data.local.db.entity.BookmarkEntity
import com.example.data.local.db.entity.ChapterEntity
import com.example.data.local.db.entity.CustomSourceEntity
import com.example.data.local.db.entity.NovelEntity
import com.example.data.local.db.entity.ReadingSessionEntity
import com.example.data.local.db.entity.TermReplacementEntity
import com.example.data.local.storage.ChapterStorage
import com.example.data.local.storage.StorageResolver
import com.example.data.model.AppSettings
import com.example.data.model.DownloadSettings
import com.example.data.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
class BackupManagerTest {

    private lateinit var context: Context
    private lateinit var database: NovelDatabase
    private lateinit var testDir: File
    private lateinit var storageResolver: StorageResolver
    private lateinit var chapterStorage: ChapterStorage
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var backupManager: BackupManager

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        testDir = File(context.cacheDir, "backup_test_${System.currentTimeMillis()}").apply { mkdirs() }

        val filesDir = File(testDir, "files").apply { mkdirs() }
        val cacheDir = File(testDir, "cache").apply { mkdirs() }

        storageResolver = StorageResolver(filesDir = filesDir, cacheDir = cacheDir)
        chapterStorage = ChapterStorage(resolver = storageResolver, ioDispatcher = Dispatchers.Unconfined)

        database = Room.inMemoryDatabaseBuilder(context, NovelDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        settingsRepository = SettingsRepository.createForTest(context, Dispatchers.Unconfined)

        backupManager = BackupManager(
            context = context,
            database = database,
            novelDao = database.novelDao(),
            chapterDao = database.chapterDao(),
            bookmarkDao = database.bookmarkDao(),
            termReplacementDao = database.termReplacementDao(),
            customSourceDao = database.customSourceDao(),
            readingSessionDao = database.readingSessionDao(),
            settingsRepository = settingsRepository,
            storageResolver = storageResolver,
            chapterStorage = chapterStorage,
            ioDispatcher = Dispatchers.Unconfined
        )
    }

    @After
    fun tearDown() {
        database.close()
        testDir.deleteRecursively()
    }

    @Test
    fun createBackup_and_readManifest_roundTrip() = runBlocking {
        // Seed database
        val novel = NovelEntity(
            id = "novel_1",
            title = "A Will Eternal",
            author = "Er Gen",
            description = "Cultivation epic",
            sourceId = "wtrlab",
            sourceName = "WtrLab",
            status = "COMPLETED",
            inLibrary = true
        )
        database.novelDao().insertIgnore(novel)

        val chapter = ChapterEntity(
            novelId = "novel_1",
            id = "ch_1",
            title = "Chapter 1",
            chapterIndex = 1,
            isRead = true
        )
        database.chapterDao().insertIgnore(chapter)

        val bookmark = BookmarkEntity(
            id = "bm_1",
            novelId = "novel_1",
            chapterId = "ch_1",
            chapterTitle = "Chapter 1",
            paragraphIndex = 2,
            charOffset = 10,
            excerpt = "Bai Xiaochun stole a chicken"
        )
        database.bookmarkDao().insertIgnore(bookmark)

        val baos = ByteArrayOutputStream()
        val summary = backupManager.createBackup(
            output = baos,
            categories = setOf(BackupCategory.LIBRARY, BackupCategory.BOOKMARKS)
        )

        assertEquals(1, summary.novels)
        assertEquals(1, summary.chapters)
        assertEquals(1, summary.bookmarks)
        assertTrue(summary.bytes > 0)

        val backupBytes = baos.toByteArray()
        val manifest = backupManager.readManifest(ByteArrayInputStream(backupBytes))

        assertEquals(1, manifest.schemaVersion)
        assertEquals("1.0.0", manifest.appVersion)
        assertTrue(manifest.categories.contains("LIBRARY"))
        assertTrue(manifest.categories.contains("BOOKMARKS"))
        assertEquals(1, manifest.counts["novels"])
        assertEquals(1, manifest.counts["chapters"])
        assertEquals(1, manifest.counts["bookmarks"])
    }

    @Test
    fun restore_replaceMode_replacesAllData() = runBlocking {
        // 1. Initial database data
        val oldNovel = NovelEntity(
            id = "old_novel",
            title = "Old Book",
            author = "Old Author",
            description = "Desc",
            sourceId = "local",
            sourceName = "Local",
            status = "ONGOING",
            inLibrary = true
        )
        database.novelDao().insertIgnore(oldNovel)

        // 2. Prepare backup archive with new novel
        val backupBaos = ByteArrayOutputStream()
        val manifest = BackupManifest(
            schemaVersion = 1,
            categories = listOf("LIBRARY"),
            counts = mapOf("novels" to 1)
        )
        val backupData = BackupData(
            novels = listOf(
                BackupNovel(
                    id = "restored_novel",
                    title = "Restored Book",
                    author = "Restored Author",
                    sourceId = "wtrlab",
                    sourceName = "WtrLab",
                    inLibrary = true
                )
            ),
            chapters = listOf(
                BackupChapter(
                    novelId = "restored_novel",
                    id = "r_ch_1",
                    title = "Ch 1",
                    chapterIndex = 1,
                    isRead = true
                )
            )
        )

        ZipOutputStream(backupBaos).use { zos ->
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(json.encodeToString(BackupManifest.serializer(), manifest).toByteArray())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("data.json"))
            zos.write(json.encodeToString(BackupData.serializer(), backupData).toByteArray())
            zos.closeEntry()
        }

        val backupBytes = backupBaos.toByteArray()
        val openInput: () -> InputStream = { ByteArrayInputStream(backupBytes) }

        val result = backupManager.restore(
            openInput = openInput,
            categories = setOf(BackupCategory.LIBRARY),
            mode = RestoreMode.REPLACE
        )

        assertEquals(1, result.novelsAdded)
        assertEquals(1, result.chaptersMarkedRead)

        // Old novel should be gone in REPLACE mode
        assertNull(database.novelDao().getNovelById("old_novel"))
        assertNotNull(database.novelDao().getNovelById("restored_novel"))
    }

    @Test
    fun restore_mergeMode_unionsReadState_andKeepsExistingMetadata() = runBlocking {
        // Existing novel in library with chapter 1 unread and chapter 2 read
        val existingNovel = NovelEntity(
            id = "novel_1",
            title = "Original Title",
            author = "Original Author",
            description = "Original Description",
            sourceId = "source_1",
            sourceName = "Source 1",
            status = "ONGOING",
            inLibrary = true
        )
        database.novelDao().insertIgnore(existingNovel)

        database.chapterDao().insertIgnore(
            ChapterEntity(
                novelId = "novel_1",
                id = "ch_1",
                title = "Chapter 1",
                chapterIndex = 1,
                isRead = false,
                readParagraphIndex = 0,
                readCharOffset = 0
            )
        )
        database.chapterDao().insertIgnore(
            ChapterEntity(
                novelId = "novel_1",
                id = "ch_2",
                title = "Chapter 2",
                chapterIndex = 2,
                isRead = true,
                readParagraphIndex = 5,
                readCharOffset = 100
            )
        )

        // Backup has modified title (which should be IGNORED in MERGE),
        // marks chapter 1 as read with position (which should be ADOPTED),
        // and marks chapter 2 as unread with position 0 (which should NOT overwrite local read state or position).
        val backupBaos = ByteArrayOutputStream()
        val manifest = BackupManifest(
            schemaVersion = 1,
            categories = listOf("LIBRARY")
        )
        val backupData = BackupData(
            novels = listOf(
                BackupNovel(
                    id = "novel_1",
                    title = "Backup Changed Title",
                    author = "Backup Changed Author",
                    sourceId = "source_1",
                    sourceName = "Source 1",
                    inLibrary = true
                )
            ),
            chapters = listOf(
                BackupChapter(
                    novelId = "novel_1",
                    id = "ch_1",
                    chapterIndex = 1,
                    isRead = true,
                    readParagraphIndex = 12,
                    readCharOffset = 45
                ),
                BackupChapter(
                    novelId = "novel_1",
                    id = "ch_2",
                    chapterIndex = 2,
                    isRead = false,
                    readParagraphIndex = 0,
                    readCharOffset = 0
                )
            )
        )

        ZipOutputStream(backupBaos).use { zos ->
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(json.encodeToString(BackupManifest.serializer(), manifest).toByteArray())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("data.json"))
            zos.write(json.encodeToString(BackupData.serializer(), backupData).toByteArray())
            zos.closeEntry()
        }

        val backupBytes = backupBaos.toByteArray()
        val result = backupManager.restore(
            openInput = { ByteArrayInputStream(backupBytes) },
            categories = setOf(BackupCategory.LIBRARY),
            mode = RestoreMode.MERGE
        )

        assertEquals(0, result.novelsAdded)
        assertEquals(1, result.novelsUpdated)
        assertEquals(1, result.chaptersMarkedRead)

        // Metadata must NOT have been changed in MERGE
        val novelAfter = database.novelDao().getNovelById("novel_1")!!
        assertEquals("Original Title", novelAfter.title)
        assertEquals("Original Author", novelAfter.author)

        // Chapter 1 should now be read with position adopted
        val ch1 = database.chapterDao().getChapter("novel_1", "ch_1")!!
        assertTrue(ch1.isRead)
        assertEquals(12, ch1.readParagraphIndex)
        assertEquals(45, ch1.readCharOffset)

        // Chapter 2 must remain read with original position retained
        val ch2 = database.chapterDao().getChapter("novel_1", "ch_2")!!
        assertTrue(ch2.isRead)
        assertEquals(5, ch2.readParagraphIndex)
        assertEquals(100, ch2.readCharOffset)
    }

    @Test
    fun zipSlip_pathTraversal_isRejected() = runBlocking {
        val backupBaos = ByteArrayOutputStream()
        ZipOutputStream(backupBaos).use { zos ->
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(json.encodeToString(BackupManifest.serializer(), BackupManifest()).toByteArray())
            zos.closeEntry()

            // Illegal path traversal
            zos.putNextEntry(ZipEntry("../../evil.txt"))
            zos.write("malicious payload".toByteArray())
            zos.closeEntry()
        }

        try {
            backupManager.restore(
                openInput = { ByteArrayInputStream(backupBaos.toByteArray()) },
                categories = setOf(BackupCategory.LIBRARY),
                mode = RestoreMode.MERGE
            )
            fail("Expected BackupException.Corrupt")
        } catch (e: BackupException.Corrupt) {
            assertTrue(e.message?.contains("Forbidden or malformed") == true || e.message?.contains("Restore failed") == true)
        }
    }

    @Test
    fun nonManifest_firstEntry_throwsNotABackup() = runBlocking {
        val backupBaos = ByteArrayOutputStream()
        ZipOutputStream(backupBaos).use { zos ->
            zos.putNextEntry(ZipEntry("data.json"))
            zos.write("{}".toByteArray())
            zos.closeEntry()
        }

        try {
            backupManager.readManifest(ByteArrayInputStream(backupBaos.toByteArray()))
            fail("Expected BackupException.NotABackup")
        } catch (e: BackupException.NotABackup) {
            // Success
        }
    }

    @Test
    fun futureSchemaVersion_throwsTooNew() = runBlocking {
        val backupBaos = ByteArrayOutputStream()
        val manifest = BackupManifest(schemaVersion = 99)
        ZipOutputStream(backupBaos).use { zos ->
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(json.encodeToString(BackupManifest.serializer(), manifest).toByteArray())
            zos.closeEntry()
        }

        try {
            backupManager.readManifest(ByteArrayInputStream(backupBaos.toByteArray()))
            fail("Expected BackupException.TooNew")
        } catch (e: BackupException.TooNew) {
            assertEquals(99, e.version)
        }
    }

    @Test
    fun dependentCategories_dropped_whenLibraryOmitted() = runBlocking {
        // BOOKMARKS depends on LIBRARY
        assertEquals(setOf(BackupCategory.LIBRARY), BackupCategory.BOOKMARKS.dependsOn())
        assertEquals(setOf(BackupCategory.LIBRARY), BackupCategory.COVERS.dependsOn())
        assertEquals(setOf(BackupCategory.LIBRARY), BackupCategory.EPUB_FILES.dependsOn())
        assertEquals(setOf(BackupCategory.LIBRARY), BackupCategory.STATS.dependsOn())
        assertEquals(emptySet<BackupCategory>(), BackupCategory.LIBRARY.dependsOn())
        assertEquals(emptySet<BackupCategory>(), BackupCategory.SETTINGS.dependsOn())

        // Create backup requesting only BOOKMARKS (without LIBRARY)
        val baos = ByteArrayOutputStream()
        val summary = backupManager.createBackup(
            output = baos,
            categories = setOf(BackupCategory.BOOKMARKS)
        )
        val manifest = backupManager.readManifest(ByteArrayInputStream(baos.toByteArray()))

        // BOOKMARKS should have been dropped because LIBRARY was absent
        assertFalse(manifest.categories.contains("BOOKMARKS"))
        assertEquals(0, summary.bookmarks)
    }

    @Test
    fun restoreSettings_dropsInvalidDownloadFolderPermission() = runBlocking {
        val backupBaos = ByteArrayOutputStream()
        val manifest = BackupManifest(
            schemaVersion = 1,
            categories = listOf("SETTINGS")
        )
        val settings = AppSettings(
            downloads = DownloadSettings(
                customDownloadLocationUri = "content://com.android.externalstorage.documents/tree/invalid",
                customDownloadLocationDisplayName = "Invalid Folder"
            )
        )

        ZipOutputStream(backupBaos).use { zos ->
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(json.encodeToString(BackupManifest.serializer(), manifest).toByteArray())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("settings.json"))
            zos.write(json.encodeToString(AppSettings.serializer(), settings).toByteArray())
            zos.closeEntry()
        }

        val result = backupManager.restore(
            openInput = { ByteArrayInputStream(backupBaos.toByteArray()) },
            categories = setOf(BackupCategory.SETTINGS),
            mode = RestoreMode.MERGE
        )

        assertTrue(result.settingsApplied)
        // Since URI permission is not granted in the test context, custom download location must be dropped
        val appliedDownloads = settingsRepository.settings.value.downloads
        assertNull(appliedDownloads.customDownloadLocationUri)
        assertNull(appliedDownloads.customDownloadLocationDisplayName)
    }

    @Test
    fun suggestedFileName_matchesExpectedPattern() {
        val fileName = backupManager.suggestedFileName(1700000000000L)
        assertTrue(fileName.startsWith("xian-backup-"))
        assertTrue(fileName.endsWith(".zip"))
        assertTrue(fileName.matches(Regex("^xian-backup-\\d{4}-\\d{2}-\\d{2}_\\d{4}\\.zip$")))
    }
}
