package com.example.ui.screens.library

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.db.NovelDatabase
import com.example.data.local.db.NovelDatabaseHolder
import com.example.data.local.db.toEntity
import com.example.data.model.LibrarySort
import com.example.data.model.LibraryViewMode
import com.example.data.model.Novel
import com.example.data.repository.NovelRepository
import com.example.data.repository.SettingsRepository
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LibraryViewModelTest {

    private lateinit var mainExecutor: ExecutorService
    private lateinit var mainDispatcher: ExecutorCoroutineDispatcher
    private var viewModel: LibraryViewModel? = null

    private lateinit var context: Context
    private lateinit var database: NovelDatabase
    private lateinit var repository: NovelRepository
    private lateinit var settingsRepository: SettingsRepository

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
        settingsRepository = SettingsRepository.getInstance(context)
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
        vm: LibraryViewModel,
        timeoutMs: Long = 5000L,
        predicate: (LibraryUiState) -> Boolean
    ): LibraryUiState {
        var lastState: LibraryUiState = vm.uiState.value
        return try {
            withTimeout(timeoutMs) {
                vm.uiState.first { state ->
                    lastState = state
                    predicate(state)
                }
            }
        } catch (e: TimeoutCancellationException) {
            System.err.println("Timeout waiting for LibraryUiState condition. Last state was: $lastState")
            throw AssertionError("Timeout waiting for LibraryUiState condition. Last state was: $lastState", e)
        }
    }

    @Test
    fun testInitialState_emptyLibrary() = runBlocking {
        val savedStateHandle = SavedStateHandle()
        val vm = LibraryViewModel(repository, settingsRepository, savedStateHandle)
        viewModel = vm

        val state = collectState(vm) { !it.isLoading }

        assertEquals(0, state.libraryCount)
        assertTrue(state.novels.isEmpty())
        assertNull(state.continueReading)
        assertEquals(LibraryFilter.ALL, state.filter)
        assertEquals("", state.searchQuery)
    }

    @Test
    fun testLibraryWithNovels_andSearchQuery() = runBlocking {
        val novel = Novel(
            id = "n1",
            title = "Apotheosis",
            author = "Bai Ren",
            description = "Desc",
            sourceId = "test_src",
            sourceName = "Test Source",
            inLibrary = true,
            totalChapters = 50
        )
        database.novelDao().insertIgnore(novel.toEntity().copy(inLibrary = true))

        val savedStateHandle = SavedStateHandle()
        val vm = LibraryViewModel(repository, settingsRepository, savedStateHandle)
        viewModel = vm

        val state = collectState(vm) { !it.isLoading && it.libraryCount == 1 }
        assertEquals(1, state.novels.size)
        assertEquals("Apotheosis", state.novels[0].title)

        // Filter out by search
        vm.onSearchChange("NonExistent")
        val filteredState = collectState(vm) { it.searchQuery == "NonExistent" && it.novels.isEmpty() }
        assertTrue(filteredState.showNoResults)

        // Reset filters
        vm.onResetFilters()
        val resetState = collectState(vm) { it.searchQuery.isEmpty() && it.novels.size == 1 }
        assertEquals(1, resetState.novels.size)
    }

    @Test
    fun testViewModeAndSortChange() = runBlocking {
        val savedStateHandle = SavedStateHandle()
        val vm = LibraryViewModel(repository, settingsRepository, savedStateHandle)
        viewModel = vm

        vm.onViewModeChange(LibraryViewMode.LIST)
        vm.onSortChange(LibrarySort.TOTAL_CHAPTERS)

        val state = collectState(vm) { it.viewMode == LibraryViewMode.LIST && it.sort == LibrarySort.TOTAL_CHAPTERS }
        assertEquals(LibraryViewMode.LIST, state.viewMode)
        assertEquals(LibrarySort.TOTAL_CHAPTERS, state.sort)
    }
}
