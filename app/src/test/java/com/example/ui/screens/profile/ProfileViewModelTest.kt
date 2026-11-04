package com.example.ui.screens.profile

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.db.NovelDatabase
import com.example.data.local.db.NovelDatabaseHolder
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ProfileViewModelTest {

    private lateinit var mainExecutor: ExecutorService
    private lateinit var mainDispatcher: ExecutorCoroutineDispatcher
    private var viewModel: ProfileViewModel? = null

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
        vm: ProfileViewModel,
        timeoutMs: Long = 5000L,
        predicate: (ProfileUiState) -> Boolean
    ): ProfileUiState {
        var lastState: ProfileUiState = vm.uiState.value
        return try {
            withTimeout(timeoutMs) {
                vm.uiState.first { state ->
                    lastState = state
                    predicate(state)
                }
            }
        } catch (e: TimeoutCancellationException) {
            System.err.println("Timeout waiting for ProfileUiState condition. Last state was: $lastState")
            throw AssertionError("Timeout waiting for ProfileUiState condition. Last state was: $lastState", e)
        }
    }

    @Test
    fun testInitialStateAndSettingMutation() = runBlocking {
        val savedStateHandle = SavedStateHandle()
        val vm = ProfileViewModel(
            appContext = context,
            settingsRepository = settingsRepository,
            novelRepository = repository,
            savedStateHandle = savedStateHandle,
            text = DefaultProfileText()
        )
        viewModel = vm

        val initial = collectState(vm) { !it.isLoading && it.categories.isNotEmpty() }
        assertNotNull(initial)
        assertEquals("Reader", initial.header.displayName)
        assertTrue(initial.categories.size >= 7)

        // Mutate setting: theme to paper
        vm.onSelect(SettingKeys.THEME, "paper")
        val updated = collectState(vm) { state ->
            state.categories.firstOrNull { it.id == SettingsCategoryIds.APPEARANCE }
                ?.summary?.contains("Paper") == true
        }
        assertNotNull(updated)

        // Search query
        vm.onSearchChange("Wi-Fi")
        val searched = collectState(vm) { it.searchQuery == "Wi-Fi" && it.categories.size == 1 }
        assertEquals(SettingsCategoryIds.DOWNLOADS, searched.categories[0].id)

        // Clear cache confirm flow
        vm.onAction(SettingKeys.CLEAR_CACHE)
        val confirmState = collectState(vm) { it.confirm != null }
        assertEquals("Clear cache", confirmState.confirm?.title)

        vm.onConfirm()
        val clearedState = collectState(vm) { it.confirm == null && it.message != null }
        assertTrue(clearedState.message!!.contains("Freed"))
    }
}
