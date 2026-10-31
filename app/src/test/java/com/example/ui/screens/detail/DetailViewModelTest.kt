package com.example.ui.screens.detail

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.db.NovelDatabase
import com.example.data.local.db.NovelDatabaseHolder
import com.example.data.local.db.toEntity
import com.example.data.model.Chapter
import com.example.data.model.Novel
import com.example.data.repository.NovelRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DetailViewModelTest {

    private lateinit var context: Context
    private lateinit var database: NovelDatabase
    private lateinit var repository: NovelRepository

    private val testNovel = Novel(
        id = "novel_123",
        title = "Immortal Mountain",
        author = "Sage Lin",
        description = "A tale of martial ascent",
        sourceId = "local_epub",
        sourceName = "Local EPUB",
        inLibrary = false,
        totalChapters = 2,
        isEpub = true
    )

    private val testChapters = listOf(
        Chapter("ch_0", "novel_123", "Beginning", 0, isRead = false, storageTier = "EPUB"),
        Chapter("ch_1", "novel_123", "Ascension", 1, isRead = false, storageTier = "EPUB")
    )

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, NovelDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        NovelDatabaseHolder.setInstanceForTesting(database)
        repository = NovelRepository(context)

        // Prepopulate novel & chapters in Room
        runBlocking {
            database.novelDao().insertIgnore(testNovel.toEntity().copy(inLibrary = false))
            database.chapterDao().insertIgnore(testChapters.map { it.toEntity(storageTier = "EPUB", storageLocation = "INTERNAL") })
        }
    }

    @After
    fun tearDown() {
        repository.shutdownForTesting()
        NovelDatabaseHolder.clearInstanceForTesting()
        database.close()
    }

    @Test
    fun testInitialLoad_findsRoomNovelAndChapters() = runBlocking {
        val savedStateHandle = SavedStateHandle(mapOf("novelId" to "novel_123"))
        val viewModel = DetailViewModel(repository, savedStateHandle)

        val state = withTimeout(3000) {
            viewModel.uiState.first { !it.isLoading && it.novel != null }
        }

        assertNotNull(state.novel)
        assertEquals("Immortal Mountain", state.novel?.title)
        assertEquals("Sage Lin", state.novel?.author)
        assertFalse(state.novel?.inLibrary ?: true)
        assertEquals(2, state.chapters.size)
        assertTrue(state.oldestFirst)
    }

    @Test
    fun testToggleLibrary_addsAndRemoves() = runBlocking {
        val savedStateHandle = SavedStateHandle(mapOf("novelId" to "novel_123"))
        val viewModel = DetailViewModel(repository, savedStateHandle)

        withTimeout(3000) {
            viewModel.uiState.first { !it.isLoading && it.novel != null }
        }

        // Add to library
        viewModel.onToggleLibrary()
        val inLibraryState = withTimeout(3000) {
            viewModel.uiState.first { it.novel?.inLibrary == true }
        }
        assertTrue(inLibraryState.novel?.inLibrary == true)

        // Remove from library
        viewModel.onToggleLibrary()
        val outOfLibraryState = withTimeout(3000) {
            viewModel.uiState.first { it.novel?.inLibrary == false }
        }
        assertFalse(outOfLibraryState.novel?.inLibrary ?: true)
    }

    @Test
    fun testMarkAllRead_updatesChapters() = runBlocking {
        val savedStateHandle = SavedStateHandle(mapOf("novelId" to "novel_123"))
        val viewModel = DetailViewModel(repository, savedStateHandle)

        withTimeout(3000) {
            viewModel.uiState.first { !it.isLoading && it.chapters.isNotEmpty() }
        }

        viewModel.onMarkAllRead(true)
        val readState = withTimeout(3000) {
            viewModel.uiState.first { it.chapters.all { ch -> ch.isRead } }
        }
        assertEquals(2, readState.novel?.readCount)

        viewModel.onMarkAllRead(false)
        val unreadState = withTimeout(3000) {
            viewModel.uiState.first { it.chapters.all { ch -> !ch.isRead } }
        }
        assertEquals(0, unreadState.novel?.readCount)
    }

    @Test
    fun testToggleSortOrder_reversesChapters() = runBlocking {
        val savedStateHandle = SavedStateHandle(mapOf("novelId" to "novel_123"))
        val viewModel = DetailViewModel(repository, savedStateHandle)

        withTimeout(3000) {
            viewModel.uiState.first { !it.isLoading && it.chapters.size == 2 }
        }

        viewModel.onToggleSortOrder()
        val descState = withTimeout(3000) {
            viewModel.uiState.first { !it.oldestFirst }
        }
        assertEquals(1, descState.chapters[0].index)
        assertEquals(0, descState.chapters[1].index)
    }

    @Test
    fun testSaveDetails_updatesNovelInRoom() = runBlocking {
        val savedStateHandle = SavedStateHandle(mapOf("novelId" to "novel_123"))
        val viewModel = DetailViewModel(repository, savedStateHandle)

        withTimeout(3000) {
            viewModel.uiState.first { !it.isLoading && it.novel != null }
        }

        viewModel.onSaveDetails(title = "Renamed Mountain", author = "Grandmaster Lin")
        val updatedState = withTimeout(3000) {
            viewModel.uiState.first { it.novel?.title == "Renamed Mountain" }
        }
        assertEquals("Renamed Mountain", updatedState.novel?.title)
        assertEquals("Grandmaster Lin", updatedState.novel?.author)
    }
}
