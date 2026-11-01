package com.example.ui.screens.detail

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.db.NovelDatabase
import com.example.data.local.db.NovelDatabaseHolder
import com.example.data.local.db.toEntity
import com.example.data.model.Chapter
import com.example.data.model.Novel
import com.example.data.repository.NovelRepository
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
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

    private lateinit var mainExecutor: ExecutorService
    private lateinit var mainDispatcher: ExecutorCoroutineDispatcher
    private var viewModel: DetailViewModel? = null

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
        inLibrary = true,
        totalChapters = 2,
        isEpub = true
    )

    private val testChapters = listOf(
        Chapter("ch_0", "novel_123", "Beginning", 0, isRead = false, isDownloaded = true),
        Chapter("ch_1", "novel_123", "Ascension", 1, isRead = false, isDownloaded = true)
    )

    @Before
    fun setup() {
        mainExecutor = Executors.newSingleThreadExecutor()
        mainDispatcher = mainExecutor.asCoroutineDispatcher()
        Dispatchers.setMain(mainDispatcher)

        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, NovelDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        NovelDatabaseHolder.setInstanceForTesting(database)

        // Prepopulate novel & chapters in Room before repository init
        runBlocking {
            database.novelDao().insertIgnore(testNovel.toEntity().copy(inLibrary = true))
            database.chapterDao().insertIgnore(testChapters.map { it.toEntity(storageTier = "EPUB", storageLocation = "INTERNAL") })
        }

        repository = NovelRepository(context)
    }

    @After
    fun tearDown() {
        viewModel?.viewModelScope?.cancel()
        repository.shutdownForTesting()
        NovelDatabaseHolder.clearInstanceForTesting()
        database.close()
        Dispatchers.resetMain()
        mainDispatcher.close()
        com.example.data.download.DownloadCoordinator.resetInstanceForTesting()
        com.example.data.source.SourceManager.setCustomSources(emptyList())
    }

    private suspend fun collectState(
        vm: DetailViewModel,
        timeoutMs: Long = 5000L,
        predicate: (DetailUiState) -> Boolean
    ): DetailUiState {
        var lastState: DetailUiState = vm.uiState.value
        return try {
            withTimeout(timeoutMs) {
                vm.uiState.first { state ->
                    lastState = state
                    predicate(state)
                }
            }
        } catch (e: TimeoutCancellationException) {
            System.err.println("Timeout waiting for DetailUiState condition. Last state was: $lastState")
            throw AssertionError("Timeout waiting for DetailUiState condition. Last state was: $lastState", e)
        }
    }

    @Test
    fun testInitialLoad_findsRoomNovelAndChapters() = runBlocking {
        val savedStateHandle = SavedStateHandle(mapOf("novelId" to "novel_123"))
        val vm = DetailViewModel(repository, savedStateHandle)
        viewModel = vm

        val state = collectState(vm) { !it.isLoading && it.novel != null }

        assertNotNull(state.novel)
        assertEquals("Immortal Mountain", state.novel?.title)
        assertEquals("Sage Lin", state.novel?.author)
        assertTrue(state.novel?.inLibrary == true)
        assertEquals(2, state.chapters.size)
        assertTrue(state.oldestFirst)
    }

    @Test
    fun testToggleLibrary_addsAndRemoves() = runBlocking {
        // Test non-library novel: insert novel (inLibrary = false) and chapters first, then load
        val nonLibNovel = testNovel.copy(id = "novel_non_lib", inLibrary = false)
        database.novelDao().insertIgnore(nonLibNovel.toEntity().copy(inLibrary = false))
        database.chapterDao().insertIgnore(
            testChapters.map { it.copy(novelId = "novel_non_lib").toEntity(storageTier = "EPUB", storageLocation = "INTERNAL") }
        )

        val savedStateHandle = SavedStateHandle(mapOf("novelId" to "novel_non_lib"))
        val vm = DetailViewModel(repository, savedStateHandle)
        viewModel = vm

        collectState(vm) { !it.isLoading && it.novel != null }

        // Add to library
        vm.onToggleLibrary()
        val inLibraryState = collectState(vm) { it.novel?.inLibrary == true }
        assertTrue(inLibraryState.novel?.inLibrary == true)

        // Remove from library
        vm.onToggleLibrary()
        val outOfLibraryState = collectState(vm) { it.novel?.inLibrary == false }
        assertFalse(outOfLibraryState.novel?.inLibrary ?: true)
    }

    @Test
    fun testMarkAllRead_updatesChapters() = runBlocking {
        val savedStateHandle = SavedStateHandle(mapOf("novelId" to "novel_123"))
        val vm = DetailViewModel(repository, savedStateHandle)
        viewModel = vm

        collectState(vm) { !it.isLoading && it.chapters.isNotEmpty() }

        vm.onMarkAllRead(true)
        val readState = collectState(vm) { it.chapters.all { ch -> ch.isRead } }
        assertEquals(2, readState.novel?.readCount)

        vm.onMarkAllRead(false)
        val unreadState = collectState(vm) { it.chapters.all { ch -> !ch.isRead } }
        assertEquals(0, unreadState.novel?.readCount)
    }

    @Test
    fun testToggleSortOrder_reversesChapters() = runBlocking {
        val savedStateHandle = SavedStateHandle(mapOf("novelId" to "novel_123"))
        val vm = DetailViewModel(repository, savedStateHandle)
        viewModel = vm

        collectState(vm) { !it.isLoading && it.chapters.size == 2 }

        vm.onToggleSortOrder()
        val descState = collectState(vm) { !it.oldestFirst }
        assertEquals(1, descState.chapters[0].index)
        assertEquals(0, descState.chapters[1].index)
    }

    @Test
    fun testSaveDetails_updatesNovelInRoom() = runBlocking {
        val savedStateHandle = SavedStateHandle(mapOf("novelId" to "novel_123"))
        val vm = DetailViewModel(repository, savedStateHandle)
        viewModel = vm

        collectState(vm) { !it.isLoading && it.novel != null }

        vm.onSaveDetails(title = "Renamed Mountain", author = "Grandmaster Lin")
        val updatedState = collectState(vm) { it.novel?.title == "Renamed Mountain" }
        assertEquals("Renamed Mountain", updatedState.novel?.title)
        assertEquals("Grandmaster Lin", updatedState.novel?.author)
    }
}
