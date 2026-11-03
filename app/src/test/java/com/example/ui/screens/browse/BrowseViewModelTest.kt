package com.example.ui.screens.browse

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.db.NovelDatabase
import com.example.data.local.db.NovelDatabaseHolder
import com.example.data.model.Chapter
import com.example.data.model.ChapterContent
import com.example.data.model.Novel
import com.example.data.repository.NovelRepository
import com.example.data.source.NovelSource
import com.example.data.source.SourceManager
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BrowseViewModelTest {

    private lateinit var mainExecutor: ExecutorService
    private lateinit var mainDispatcher: ExecutorCoroutineDispatcher
    private var viewModel: BrowseViewModel? = null

    private lateinit var context: Context
    private lateinit var database: NovelDatabase
    private lateinit var repository: NovelRepository

    private val testSource = object : NovelSource {
        override val id: String = "test_browse_source"
        override val name: String = "Test Pavilion"
        override val language: String = "English"
        override val version: String = "1.2"
        override val description: String = "Test source for browsing"
        override val isLocal: Boolean = false
        override val latestSupported: Boolean = true
        override val filtersSupported: Boolean = true

        override suspend fun getPopularNovels(page: Int): List<Novel> {
            return when (page) {
                1 -> listOf(
                    Novel(id = "pop_1", title = "Popular Novel 1", author = "Author One", description = "Description 1", sourceId = id, sourceName = name),
                    Novel(id = "pop_2", title = "Popular Novel 2", author = "Author Two", description = "Description 2", sourceId = id, sourceName = name)
                )
                2 -> listOf(
                    Novel(id = "pop_3", title = "Popular Novel 3", author = "Author Three", description = "Description 3", sourceId = id, sourceName = name)
                )
                else -> emptyList()
            }
        }

        override suspend fun getLatestNovels(page: Int): List<Novel> {
            return when (page) {
                1 -> listOf(
                    Novel(id = "lat_1", title = "Latest Novel 1", author = "Author Lat", description = "Description Lat", sourceId = id, sourceName = name)
                )
                else -> emptyList()
            }
        }

        override suspend fun searchNovels(query: String, page: Int): List<Novel> = emptyList()
        override suspend fun getNovelDetails(novelId: String): Novel = throw NotImplementedError()
        override suspend fun getChapterList(novelId: String): List<Chapter> = emptyList()
        override suspend fun getChapterContent(novelId: String, chapterId: String): ChapterContent = throw NotImplementedError()
    }

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

        repository = NovelRepository(context)
        SourceManager.registerSource(testSource)
    }

    @After
    fun tearDown() {
        viewModel?.viewModelScope?.cancel()
        repository.shutdownForTesting()
        NovelDatabaseHolder.clearInstanceForTesting()
        database.close()
        Dispatchers.resetMain()
        mainDispatcher.close()
        SourceManager.unregisterSource(testSource.id)
    }

    private suspend fun collectState(
        vm: BrowseViewModel,
        timeoutMs: Long = 5000L,
        predicate: (BrowseUiState) -> Boolean
    ): BrowseUiState {
        var lastState: BrowseUiState = vm.uiState.value
        return try {
            withTimeout(timeoutMs) {
                vm.uiState.first { state ->
                    lastState = state
                    predicate(state)
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError("Timed out waiting for condition. Last state: $lastState", e)
        }
    }

    @Test
    fun testInitialLoad_loadsPopularNovelsAndAssignsRanks() {
        val savedStateHandle = SavedStateHandle(mapOf("sourceId" to testSource.id))
        val vm = BrowseViewModel(repository, savedStateHandle, SourceManager)
        viewModel = vm

        val state = kotlinx.coroutines.runBlocking {
            collectState(vm) { !it.isLoading && it.novels.isNotEmpty() }
        }

        assertEquals("Test Pavilion", state.sourceName)
        assertEquals("English · v1.2", state.subtitle)
        assertTrue(state.latestSupported)
        assertTrue(state.filtersSupported)
        assertEquals(2, state.novels.size)
        assertEquals("pop_1", state.novels[0].id)
        assertEquals(1, state.novels[0].rank)
        assertEquals("pop_2", state.novels[1].id)
        assertEquals(2, state.novels[1].rank)

        // Verify novels are saved to Room but not added to library
        val roomNovel = kotlinx.coroutines.runBlocking {
            database.novelDao().getNovelById("pop_1")
        }
        assertNotNull(roomNovel)
        assertFalse(roomNovel!!.inLibrary)
    }

    @Test
    fun testLoadNextPage_appendsAndDedupes() {
        val savedStateHandle = SavedStateHandle(mapOf("sourceId" to testSource.id))
        val vm = BrowseViewModel(repository, savedStateHandle, SourceManager)
        viewModel = vm

        kotlinx.coroutines.runBlocking {
            collectState(vm) { !it.isLoading && it.novels.size == 2 }
            vm.loadNextPage()
            val state = collectState(vm) { !it.isLoadingMore && it.novels.size == 3 }
            assertEquals(3, state.novels.size)
            assertEquals("pop_3", state.novels[2].id)
            assertEquals(3, state.novels[2].rank)
        }
    }

    @Test
    fun testModeChange_switchesToLatestAndResetsRanks() {
        val savedStateHandle = SavedStateHandle(mapOf("sourceId" to testSource.id))
        val vm = BrowseViewModel(repository, savedStateHandle, SourceManager)
        viewModel = vm

        kotlinx.coroutines.runBlocking {
            collectState(vm) { !it.isLoading && it.novels.size == 2 }
            vm.onModeChange(BrowseMode.LATEST)
            val state = collectState(vm) { !it.isLoading && it.mode == BrowseMode.LATEST && it.novels.isNotEmpty() }
            assertEquals(BrowseMode.LATEST, state.mode)
            assertEquals(1, state.novels.size)
            assertEquals("lat_1", state.novels[0].id)
            assertNull(state.novels[0].rank)
        }
    }

    @Test
    fun testSourceUnavailable_setsErrorState() {
        val savedStateHandle = SavedStateHandle(mapOf("sourceId" to "unknown_source_xyz"))
        val vm = BrowseViewModel(repository, savedStateHandle, SourceManager)
        viewModel = vm

        val state = kotlinx.coroutines.runBlocking {
            collectState(vm) { !it.isLoading }
        }

        assertNotNull(state.error)
        assertTrue(state.novels.isEmpty())
    }

    @Test
    fun testFilters_openDismissAndApply() {
        val savedStateHandle = SavedStateHandle(mapOf("sourceId" to testSource.id))
        val vm = BrowseViewModel(repository, savedStateHandle, SourceManager)
        viewModel = vm

        assertFalse(vm.uiState.value.showFilters)
        vm.onOpenFilters()
        assertTrue(vm.uiState.value.showFilters)
        vm.onDismissFilters()
        assertFalse(vm.uiState.value.showFilters)

        val newFilters = BrowseFilters(status = BrowseStatus.ONGOING, genres = setOf("Fantasy"))
        vm.onApplyFilters(newFilters)
        assertEquals(newFilters, vm.uiState.value.filters)
        assertFalse(vm.uiState.value.showFilters)
    }
}
