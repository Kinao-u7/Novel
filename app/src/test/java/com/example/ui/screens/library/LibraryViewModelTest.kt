package com.example.ui.screens.library

import android.content.Context
import androidx.lifecycle.SavedStateHandle
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
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

    private lateinit var context: Context
    private lateinit var database: NovelDatabase
    private lateinit var repository: NovelRepository
    private lateinit var settingsRepository: SettingsRepository

    @Before
    fun setup() {
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
        repository.shutdownForTesting()
        NovelDatabaseHolder.clearInstanceForTesting()
        database.close()
    }

    @Test
    fun testInitialState_emptyLibrary() = runBlocking {
        val savedStateHandle = SavedStateHandle()
        val viewModel = LibraryViewModel(repository, settingsRepository, savedStateHandle)

        val state = withTimeout(3000) {
            viewModel.uiState.first { !it.isLoading }
        }

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
        val viewModel = LibraryViewModel(repository, settingsRepository, savedStateHandle)

        val state = withTimeout(3000) {
            viewModel.uiState.first { !it.isLoading && it.libraryCount == 1 }
        }
        assertEquals(1, state.novels.size)
        assertEquals("Apotheosis", state.novels[0].title)

        // Filter out by search
        viewModel.onSearchChange("NonExistent")
        val filteredState = withTimeout(3000) {
            viewModel.uiState.first { it.searchQuery == "NonExistent" && it.novels.isEmpty() }
        }
        assertTrue(filteredState.showNoResults)

        // Reset filters
        viewModel.onResetFilters()
        val resetState = withTimeout(3000) {
            viewModel.uiState.first { it.searchQuery.isEmpty() && it.novels.size == 1 }
        }
        assertEquals(1, resetState.novels.size)
    }

    @Test
    fun testViewModeAndSortChange() = runBlocking {
        val savedStateHandle = SavedStateHandle()
        val viewModel = LibraryViewModel(repository, settingsRepository, savedStateHandle)

        viewModel.onViewModeChange(LibraryViewMode.LIST)
        viewModel.onSortChange(LibrarySort.TOTAL_CHAPTERS)

        val state = withTimeout(3000) {
            viewModel.uiState.first { it.viewMode == LibraryViewMode.LIST && it.sort == LibrarySort.TOTAL_CHAPTERS }
        }
        assertEquals(LibraryViewMode.LIST, state.viewMode)
        assertEquals(LibrarySort.TOTAL_CHAPTERS, state.sort)
    }
}
