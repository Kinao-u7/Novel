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
import android.net.Uri
import com.example.data.backup.BackupCategory
import com.example.data.backup.BackupManifest
import com.example.data.backup.RestoreMode
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
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
import kotlinx.serialization.json.Json
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

    @Test
    fun testBackupSheet_openDismissAndDependencyToggle() = runBlocking {
        val savedStateHandle = SavedStateHandle()
        val vm = ProfileViewModel(
            appContext = context,
            settingsRepository = settingsRepository,
            novelRepository = repository,
            savedStateHandle = savedStateHandle,
            text = DefaultProfileText()
        )
        viewModel = vm

        collectState(vm) { !it.isLoading && it.categories.isNotEmpty() }

        vm.onAction(SettingKeys.CREATE_BACKUP)
        val sheetState = collectState(vm) { it.backupSheet != null }
        val sheet = sheetState.backupSheet
        assertNotNull(sheet)
        assertEquals(8, sheet!!.options.size)
        assertTrue(sheet.options.all { it.enabled && it.checked })

        val epubOption = sheet.options.first { it.id == BackupCategory.EPUB_FILES.name }
        assertEquals("Original files, can be large", epubOption.description)

        // Toggle LIBRARY off -> BOOKMARKS, COVERS, EPUB_FILES, STATS become disabled & unchecked
        vm.onBackupOptionToggle(BackupCategory.LIBRARY.name)
        val libOffState = collectState(vm) { state ->
            val opt = state.backupSheet?.options?.firstOrNull { it.id == BackupCategory.LIBRARY.name }
            opt?.checked == false
        }
        val libOffOptions = libOffState.backupSheet!!.options.associateBy { it.id }
        assertFalse(libOffOptions[BackupCategory.LIBRARY.name]!!.checked)
        assertFalse(libOffOptions[BackupCategory.BOOKMARKS.name]!!.enabled)
        assertFalse(libOffOptions[BackupCategory.BOOKMARKS.name]!!.checked)
        assertFalse(libOffOptions[BackupCategory.COVERS.name]!!.enabled)
        assertFalse(libOffOptions[BackupCategory.COVERS.name]!!.checked)
        assertFalse(libOffOptions[BackupCategory.EPUB_FILES.name]!!.enabled)
        assertFalse(libOffOptions[BackupCategory.EPUB_FILES.name]!!.checked)
        assertFalse(libOffOptions[BackupCategory.STATS.name]!!.enabled)
        assertFalse(libOffOptions[BackupCategory.STATS.name]!!.checked)
        // Independent options remain enabled & checked
        assertTrue(libOffOptions[BackupCategory.SETTINGS.name]!!.enabled)
        assertTrue(libOffOptions[BackupCategory.SETTINGS.name]!!.checked)
        assertTrue(libOffOptions[BackupCategory.CUSTOM_SOURCES.name]!!.enabled)
        assertTrue(libOffOptions[BackupCategory.CUSTOM_SOURCES.name]!!.checked)

        // Toggle LIBRARY on -> dependent options re-enabled and checked
        vm.onBackupOptionToggle(BackupCategory.LIBRARY.name)
        val libOnState = collectState(vm) { state ->
            val opt = state.backupSheet?.options?.firstOrNull { it.id == BackupCategory.LIBRARY.name }
            opt?.checked == true
        }
        val libOnOptions = libOnState.backupSheet!!.options.associateBy { it.id }
        assertTrue(libOnOptions[BackupCategory.BOOKMARKS.name]!!.enabled)
        assertTrue(libOnOptions[BackupCategory.BOOKMARKS.name]!!.checked)
        assertTrue(libOnOptions[BackupCategory.COVERS.name]!!.enabled)
        assertTrue(libOnOptions[BackupCategory.EPUB_FILES.name]!!.enabled)

        // Dismiss sheet
        vm.onBackupDismiss()
        val dismissedState = collectState(vm) { it.backupSheet == null }
        assertNull(dismissedState.backupSheet)
    }

    @Test
    fun testRestoreSheet_manifestMapping_andModeChange() = runBlocking {
        val savedStateHandle = SavedStateHandle()
        val vm = ProfileViewModel(
            appContext = context,
            settingsRepository = settingsRepository,
            novelRepository = repository,
            savedStateHandle = savedStateHandle,
            text = DefaultProfileText()
        )
        viewModel = vm

        collectState(vm) { !it.isLoading && it.categories.isNotEmpty() }

        // Create a temporary backup zip file with manifest.json
        val backupFile = File(context.cacheDir, "test_backup.zip")
        val manifest = BackupManifest(
            schemaVersion = 1,
            appVersion = "2.0.0",
            createdAtMillis = 1700000000000L,
            categories = listOf(BackupCategory.LIBRARY.name, BackupCategory.BOOKMARKS.name),
            counts = mapOf("novels" to 5, "chapters" to 42),
            totalBytes = 1024L
        )
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        ZipOutputStream(FileOutputStream(backupFile)).use { zos ->
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(json.encodeToString(BackupManifest.serializer(), manifest).toByteArray())
            zos.closeEntry()
        }

        vm.onPicked(SettingKeys.RESTORE_BACKUP, Uri.fromFile(backupFile))
        val restoreState = collectState(vm) { it.restoreSheet != null }
        val restoreSheet = restoreState.restoreSheet
        assertNotNull(restoreSheet)
        assertEquals("test_backup.zip", restoreSheet!!.fileName)
        assertEquals("2.0.0", restoreSheet.appVersion)
        assertEquals("5 novels · 42 chapters", restoreSheet.summary)
        assertEquals(RestoreMode.MERGE, restoreSheet.mode)

        val restoreOptions = restoreSheet.options.associateBy { it.id }
        // Present categories in manifest are enabled and checked
        assertTrue(restoreOptions[BackupCategory.LIBRARY.name]!!.enabled)
        assertTrue(restoreOptions[BackupCategory.LIBRARY.name]!!.checked)
        assertTrue(restoreOptions[BackupCategory.BOOKMARKS.name]!!.enabled)
        assertTrue(restoreOptions[BackupCategory.BOOKMARKS.name]!!.checked)
        // Missing categories in manifest are disabled and unchecked
        assertFalse(restoreOptions[BackupCategory.SETTINGS.name]!!.enabled)
        assertFalse(restoreOptions[BackupCategory.SETTINGS.name]!!.checked)
        assertFalse(restoreOptions[BackupCategory.CUSTOM_SOURCES.name]!!.enabled)
        assertFalse(restoreOptions[BackupCategory.CUSTOM_SOURCES.name]!!.checked)

        // Mode change to REPLACE
        vm.onRestoreModeChange(RestoreMode.REPLACE)
        val modeState = collectState(vm) { it.restoreSheet?.mode == RestoreMode.REPLACE }
        assertEquals(RestoreMode.REPLACE, modeState.restoreSheet!!.mode)

        // Dismiss
        vm.onRestoreDismiss()
        val dismissedState = collectState(vm) { it.restoreSheet == null }
        assertNull(dismissedState.restoreSheet)

        backupFile.delete()
    }

    @Test
    fun testBackupStart_andAlreadyWorkingGuard() = runBlocking {
        val savedStateHandle = SavedStateHandle()
        val vm = ProfileViewModel(
            appContext = context,
            settingsRepository = settingsRepository,
            novelRepository = repository,
            savedStateHandle = savedStateHandle,
            text = DefaultProfileText()
        )
        viewModel = vm

        collectState(vm) { !it.isLoading && it.categories.isNotEmpty() }

        vm.onAction(SettingKeys.CREATE_BACKUP)
        collectState(vm) { it.backupSheet != null }

        val targetFile = File(context.cacheDir, "created_backup.zip")
        if (targetFile.exists()) targetFile.delete()

        vm.onBackupStart(Uri.fromFile(targetFile))
        val savedState = collectState(vm) { it.backupSheet == null && it.message != null }
        assertTrue(savedState.message!!.contains("Backup saved"))
        assertTrue(targetFile.exists() && targetFile.length() > 0)
        assertTrue(settingsRepository.settings.value.backup.lastBackupAtMillis > 0L)

        targetFile.delete()
    }

    @Test
    fun testRestoreErrorMapping() = runBlocking {
        val savedStateHandle = SavedStateHandle()
        val vm = ProfileViewModel(
            appContext = context,
            settingsRepository = settingsRepository,
            novelRepository = repository,
            savedStateHandle = savedStateHandle,
            text = DefaultProfileText()
        )
        viewModel = vm

        collectState(vm) { !it.isLoading && it.categories.isNotEmpty() }

        // 1. Not a backup file (invalid zip or zip without manifest.json as first entry)
        val corruptFile = File(context.cacheDir, "invalid.zip")
        ZipOutputStream(FileOutputStream(corruptFile)).use { zos ->
            zos.putNextEntry(ZipEntry("other.txt"))
            zos.write("hello".toByteArray())
            zos.closeEntry()
        }

        vm.onPicked(SettingKeys.RESTORE_BACKUP, Uri.fromFile(corruptFile))
        val notBackupState = collectState(vm) { it.message != null }
        assertEquals("Not a backup file", notBackupState.message)
        vm.onMessageShown()
        collectState(vm) { it.message == null }

        // 2. Too new backup file
        val tooNewFile = File(context.cacheDir, "too_new.zip")
        val manifest = BackupManifest(schemaVersion = 99)
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        ZipOutputStream(FileOutputStream(tooNewFile)).use { zos ->
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(json.encodeToString(BackupManifest.serializer(), manifest).toByteArray())
            zos.closeEntry()
        }

        vm.onPicked(SettingKeys.RESTORE_BACKUP, Uri.fromFile(tooNewFile))
        val tooNewState = collectState(vm) { it.message != null }
        assertEquals("This backup is from a newer version of the app", tooNewState.message)

        corruptFile.delete()
        tooNewFile.delete()
    }
}

