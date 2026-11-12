package com.example.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.db.NovelDatabase
import com.example.data.local.db.NovelDatabaseHolder
import com.example.data.local.db.entity.NovelEntity
import com.example.data.model.Chapter
import com.example.data.model.ChapterContent
import com.example.data.model.Novel
import com.example.data.source.NovelSource
import com.example.data.source.SourceManager
import com.example.data.source.plugin.LnPluginSource
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NovelDetailEnricherTest {

    private lateinit var context: Context
    private lateinit var database: NovelDatabase
    private lateinit var repository: NovelRepository
    private lateinit var fakeSource: FakeRichNovelSource

    class FakeRichNovelSource(
        override val id: String = "${LnPluginSource.PREFIX}test_rich_plugin",
        override val name: String = "Rich Plugin",
        override val language: String = "EN",
        override val version: String = "1.0.0",
        override val description: String = "Rich Plugin Description",
        override val isLocal: Boolean = false,
        override val latestSupported: Boolean = false,
        override val filtersSupported: Boolean = false
    ) : NovelSource {
        var detailsCallsCount: Int = 0
        var shouldFail: Boolean = false
        var onDetailsCalled: (suspend () -> Unit)? = null

        var returnedNovel: Novel = Novel(
            id = "test_novel_1",
            title = "Test Novel Title",
            author = "Rich Author Name",
            description = "Rich Synopsis from Plugin",
            genres = listOf("Action", "Adventure", "Fantasy"),
            status = "Completed",
            sourceId = id,
            sourceName = "Rich Plugin",
            rating = 4.85f,
            totalChapters = 250,
            sourceUrl = "https://example.com/novel/test-1"
        )

        override suspend fun getPopularNovels(page: Int): List<Novel> = emptyList()
        override suspend fun getLatestNovels(page: Int): List<Novel> = emptyList()
        override suspend fun searchNovels(query: String, page: Int): List<Novel> = emptyList()

        override suspend fun getNovelDetails(novelId: String): Novel {
            detailsCallsCount++
            onDetailsCalled?.invoke()
            if (shouldFail) throw IOException("Simulated network timeout for plugin")
            return returnedNovel.copy(id = novelId)
        }

        override suspend fun getChapterList(novelId: String): List<Chapter> = emptyList()
        override suspend fun getChapterContent(novelId: String, chapterId: String): ChapterContent =
            ChapterContent(chapterId = chapterId, novelId = novelId, title = "Chapter", paragraphs = emptyList())
    }

    @Before
    fun setup() {
        NovelDetailEnricher.clearAttemptedIdsForTesting()
        com.example.data.download.DownloadCoordinator.resetInstanceForTesting()
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, NovelDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        NovelDatabaseHolder.setInstanceForTesting(database)
        repository = NovelRepository(context, availableBytesProvider = { 1024L * 1024L * 1024L })

        fakeSource = FakeRichNovelSource()
        SourceManager.registerSource(fakeSource)
    }

    @After
    fun tearDown() {
        SourceManager.unregisterSource(fakeSource.id)
        repository.shutdownForTesting()
        NovelDatabaseHolder.clearInstanceForTesting()
        database.close()
        com.example.data.download.DownloadCoordinator.resetInstanceForTesting()
        NovelDetailEnricher.clearAttemptedIdsForTesting()
    }

    private fun insertBlankRoomNovel(
        id: String = "test_novel_1",
        title: String = "Initial Title",
        author: String = "",
        description: String = "",
        sourceId: String = fakeSource.id,
        inLibrary: Boolean = false,
        status: String = "Ongoing",
        rating: Float? = null,
        totalChapters: Int = 0,
        sourceUrl: String? = null
    ): NovelEntity {
        val entity = NovelEntity(
            id = id,
            title = title,
            author = author,
            description = description,
            coverUrl = "https://example.com/cover.jpg",
            sourceId = sourceId,
            sourceName = "Rich Plugin",
            sourceUrl = sourceUrl,
            status = status,
            genresJson = "[]",
            inLibrary = inLibrary,
            isEpub = false,
            totalChapters = totalChapters,
            rating = rating,
            createdAt = System.currentTimeMillis()
        )
        runBlocking {
            database.novelDao().upsertSafe(entity)
        }
        return entity
    }

    @Test
    fun testRoomNovelWithBlankAuthorAndDescriptionGetsFilled() = runBlocking<Unit> {
        val novelId = "novel_blank_details"
        insertBlankRoomNovel(
            id = novelId,
            author = "",
            description = "",
            totalChapters = 0,
            rating = null,
            sourceUrl = null
        )

        val novel = repository.getNovel(novelId)
        assertNotNull("Novel should be retrieved", novel)
        assertEquals("Rich Author Name", novel?.author)
        assertEquals("Rich Synopsis from Plugin", novel?.description)
        assertEquals(listOf("Action", "Adventure", "Fantasy"), novel?.genres)
        assertEquals("Completed", novel?.status)
        assertEquals(4.85f, novel?.rating)
        assertEquals(250, novel?.totalChapters)
        assertEquals("https://example.com/novel/test-1", novel?.sourceUrl)

        val roomEntity = database.novelDao().getNovelById(novelId)
        assertNotNull(roomEntity)
        assertEquals("Rich Author Name", roomEntity?.author)
        assertEquals("Rich Synopsis from Plugin", roomEntity?.description)
        assertEquals(250, roomEntity?.totalChapters)
        assertEquals(4.85f, roomEntity?.rating)
        assertEquals("Completed", roomEntity?.status)
        assertEquals("https://example.com/novel/test-1", roomEntity?.sourceUrl)
    }

    @Test
    fun testNovelWithEditedNonBlankDescriptionIsNotOverwritten() = runBlocking<Unit> {
        val novelId = "novel_edited_desc"
        insertBlankRoomNovel(
            id = novelId,
            author = "",
            description = "My custom user edited description",
            inLibrary = true
        )

        val novel = repository.getNovel(novelId)
        assertNotNull(novel)
        assertEquals("My custom user edited description", novel?.description)
        assertEquals("Rich Author Name", novel?.author)
        assertTrue(novel?.inLibrary == true)

        val roomEntity = database.novelDao().getNovelById(novelId)
        assertEquals("My custom user edited description", roomEntity?.description)
        assertEquals("Rich Author Name", roomEntity?.author)
        assertTrue(roomEntity?.inLibrary == true)
    }

    @Test
    fun testSecondOpenDoesNotCallSourceAgain() = runBlocking<Unit> {
        val novelId = "novel_session_cached"
        insertBlankRoomNovel(id = novelId, author = "", description = "")

        val firstGet = repository.getNovel(novelId)
        assertNotNull(firstGet)
        assertEquals(1, fakeSource.detailsCallsCount)

        val secondGet = repository.getNovel(novelId)
        assertNotNull(secondGet)
        assertEquals(1, fakeSource.detailsCallsCount)
    }

    @Test
    fun testFailingSourceLogsAndNovelStillOpens() = runBlocking<Unit> {
        val novelId = "novel_failing_source"
        insertBlankRoomNovel(
            id = novelId,
            author = "",
            description = "",
            title = "Original Title"
        )
        fakeSource.shouldFail = true

        val novel = repository.getNovel(novelId)
        assertNotNull("Novel must open even if source fails", novel)
        assertEquals("Original Title", novel?.title)
        assertEquals("", novel?.author)
        assertEquals("", novel?.description)
        assertEquals(1, fakeSource.detailsCallsCount)
    }

    @Test
    fun testRaceConditionConcurrentLibraryAndReadingUpdatesPreserved() = runBlocking<Unit> {
        val novelId = "novel_race_test"
        insertBlankRoomNovel(
            id = novelId,
            author = "",
            description = "",
            inLibrary = false
        )

        // While getNovelDetails is suspended, simulate concurrent user action:
        fakeSource.onDetailsCalled = {
            database.novelDao().setInLibrary(novelId, true)
            database.novelDao().updateLastRead(novelId, "chapter_race_99", 888888L)
        }

        val enriched = repository.getNovel(novelId)
        assertNotNull(enriched)
        assertEquals("Rich Author Name", enriched?.author)
        assertEquals("Rich Synopsis from Plugin", enriched?.description)
        // Assert concurrent changes made to Room were NOT clobbered:
        assertEquals(true, enriched?.inLibrary)
        assertEquals("chapter_race_99", enriched?.lastReadChapterId)
        assertEquals(888888L, enriched?.lastReadTimestamp)

        val roomEntity = database.novelDao().getNovelById(novelId)
        assertNotNull(roomEntity)
        assertEquals("Rich Author Name", roomEntity?.author)
        assertEquals("Rich Synopsis from Plugin", roomEntity?.description)
        assertEquals(true, roomEntity?.inLibrary)
        assertEquals("chapter_race_99", roomEntity?.lastReadChapterId)
        assertEquals(888888L, roomEntity?.lastReadTimestamp)
    }

    @Test
    fun testCancellationDoesNotMarkAsAttempted() = runBlocking<Unit> {
        val novelId = "novel_cancel_test"
        insertBlankRoomNovel(id = novelId, author = "", description = "")

        var suspendedJobStarted = false
        val deferred = async {
            repository.getNovel(novelId)
        }

        fakeSource.onDetailsCalled = {
            suspendedJobStarted = true
        }

        deferred.cancelAndJoin()
        assertTrue("Cancellation occurred", deferred.isCancelled)

        // Reset details callback for the subsequent real call
        fakeSource.onDetailsCalled = null

        // Second call must NOT be blocked by attempted cache since previous call was cancelled
        val novel = repository.getNovel(novelId)
        assertNotNull(novel)
        assertEquals("Rich Author Name", novel?.author)
        assertEquals("Rich Synopsis from Plugin", novel?.description)
        assertEquals(2, fakeSource.detailsCallsCount)
    }

    @Test
    fun testBuiltInSourceKeepsOldBehaviorAndIsNotEnriched() = runBlocking<Unit> {
        val novelId = "novel_builtin_source"
        insertBlankRoomNovel(
            id = novelId,
            author = "",
            description = "",
            sourceId = "webnovel_haven" // does NOT start with LnPluginSource.PREFIX ("ln_")
        )

        val novel = repository.getNovel(novelId)
        assertNotNull(novel)
        // Kept old behavior: author/description remain as stored in Room, fake source not called
        assertEquals("", novel?.author)
        assertEquals("", novel?.description)
        assertEquals(0, fakeSource.detailsCallsCount)
    }
}
