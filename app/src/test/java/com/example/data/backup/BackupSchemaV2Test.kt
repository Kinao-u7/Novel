package com.example.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.db.NovelDatabase
import com.example.data.local.storage.ChapterStorage
import com.example.data.local.storage.StorageResolver
import com.example.data.repository.SettingsRepository
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
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
class BackupSchemaV2Test {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var database: NovelDatabase
    private lateinit var backupManager: BackupManager

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, NovelDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val filesDir = tempFolder.newFolder("files")
        val cacheDir = tempFolder.newFolder("cache")
        val storageResolver = StorageResolver(filesDir = filesDir, cacheDir = cacheDir)
        backupManager = BackupManager(
            context = context,
            database = database,
            novelDao = database.novelDao(),
            chapterDao = database.chapterDao(),
            bookmarkDao = database.bookmarkDao(),
            termReplacementDao = database.termReplacementDao(),
            customSourceDao = database.customSourceDao(),
            readingSessionDao = database.readingSessionDao(),
            settingsRepository = SettingsRepository.createForTest(context, Dispatchers.IO),
            storageResolver = storageResolver,
            chapterStorage = ChapterStorage(resolver = storageResolver, ioDispatcher = Dispatchers.IO),
            ioDispatcher = Dispatchers.IO
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun backupRestore_withSchemaV2_restoresNullDownloadColumnsAndTierNone() = runBlocking<Unit> {
        val baos = ByteArrayOutputStream()
        val manifest = BackupManifest(schemaVersion = 1, categories = listOf("LIBRARY"))
        val data = BackupData(
            novels = listOf(
                BackupNovel(
                    id = "n_backup",
                    title = "Backed Up Novel",
                    author = "Author",
                    sourceId = "webnovel_haven",
                    sourceName = "WebNovel Vault",
                    inLibrary = true
                )
            ),
            chapters = listOf(
                BackupChapter(
                    novelId = "n_backup",
                    id = "ch_backup_1",
                    title = "Chapter 1",
                    chapterIndex = 1,
                    isRead = true
                )
            )
        )
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(json.encodeToString(BackupManifest.serializer(), manifest).toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("data.json"))
            zos.write(json.encodeToString(BackupData.serializer(), data).toByteArray())
            zos.closeEntry()
        }

        val summary = backupManager.restore(
            openInput = { ByteArrayInputStream(baos.toByteArray()) },
            categories = setOf(BackupCategory.LIBRARY),
            mode = RestoreMode.REPLACE
        )

        assertEquals(1, summary.novelsAdded)
        val novel = database.novelDao().getNovelById("n_backup")
        assertNotNull(novel)
        assertNull(novel!!.downloadSourceFolder)
        assertNull(novel.downloadNovelFolder)

        val chapter = database.chapterDao().getChapter("n_backup", "ch_backup_1")
        assertNotNull(chapter)
        assertEquals("NONE", chapter!!.storageTier)
        assertEquals("INTERNAL", chapter.storageLocation)
        assertNull(chapter.downloadFileName)
        assertTrue(chapter.isRead)
    }
}
