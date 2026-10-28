package com.example.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.example.data.model.AppSettings
import com.example.data.model.AppThemeMode
import com.example.data.model.BackupSettings
import com.example.data.model.BottomBarSettings
import com.example.data.model.CultivationSettings
import com.example.data.model.DownloadSettings
import com.example.data.model.NavTab
import com.example.data.model.FontType
import com.example.data.model.LibrarySettings
import com.example.data.model.LibraryViewMode
import com.example.data.model.NotificationSettings
import com.example.data.model.ProfileSettings
import com.example.data.model.ReaderFontWeight
import com.example.data.model.ReaderSettings
import com.example.data.model.ReaderTextAlign
import com.example.data.model.ReaderTheme
import com.example.data.model.ReadingMode
import com.example.data.model.ThemeSettings
import com.example.data.model.ToolbarPosition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Modern, crash-resilient repository for managing application and reader configuration.
 *
 * Adheres to Architectural Invariants:
 * - Whole-object persistence with kotlinx.serialization JSON (Rule 10, Amendment f)
 * - Safe file writes using temporary file and atomic rename (Rule 5)
 * - Coroutine-safe off-main-thread I/O on Dispatchers.IO (Rule 11)
 * - Debounced disk writes to prevent I/O thrashing during frequent updates
 * - CancellationException rethrown without suppression (Rule 13)
 * - Single source of truth for unified settings concepts (Rule 9)
 */
class SettingsRepository private constructor(
    context: Context,
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO
) {

    private val appContext: Context = context.applicationContext ?: context
    private val repoScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val writeMutex = Mutex()
    private var writeJob: Job? = null

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
        prettyPrint = true
    }

    private val settingsFile: File
        get() = File(appContext.filesDir, SETTINGS_FILE_NAME)

    private val tempSettingsFile: File
        get() = File(appContext.filesDir, "$SETTINGS_FILE_NAME.tmp")

    private val corruptedSettingsFile: File
        get() = File(appContext.filesDir, "$SETTINGS_FILE_NAME.corrupted")

    // In-memory single source of truth
    private val _settings = MutableStateFlow(loadInitialSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    // Granular derived flows for UI consumers
    val readerSettings: StateFlow<ReaderSettings> = _settings
        .map { it.reader }
        .distinctUntilChanged()
        .stateIn(repoScope, SharingStarted.Eagerly, _settings.value.reader)

    val themeSettings: StateFlow<ThemeSettings> = _settings
        .map { it.theme }
        .distinctUntilChanged()
        .stateIn(repoScope, SharingStarted.Eagerly, _settings.value.theme)

    val downloadSettings: StateFlow<DownloadSettings> = _settings
        .map { it.downloads }
        .distinctUntilChanged()
        .stateIn(repoScope, SharingStarted.Eagerly, _settings.value.downloads)

    val notificationSettings: StateFlow<NotificationSettings> = _settings
        .map { it.notifications }
        .distinctUntilChanged()
        .stateIn(repoScope, SharingStarted.Eagerly, _settings.value.notifications)

    val librarySettings: StateFlow<LibrarySettings> = _settings
        .map { it.library }
        .distinctUntilChanged()
        .stateIn(repoScope, SharingStarted.Eagerly, _settings.value.library)

    val cultivationSettings: StateFlow<CultivationSettings> = _settings
        .map { it.cultivation }
        .distinctUntilChanged()
        .stateIn(repoScope, SharingStarted.Eagerly, _settings.value.cultivation)

    val backupSettings: StateFlow<BackupSettings> = _settings
        .map { it.backup }
        .distinctUntilChanged()
        .stateIn(repoScope, SharingStarted.Eagerly, _settings.value.backup)

    val profileSettings: StateFlow<ProfileSettings> = _settings
        .map { it.profile }
        .distinctUntilChanged()
        .stateIn(repoScope, SharingStarted.Eagerly, _settings.value.profile)

    val bottomBarSettings: StateFlow<BottomBarSettings> = _settings
        .map { it.bottomBar }
        .distinctUntilChanged()
        .stateIn(repoScope, SharingStarted.Eagerly, _settings.value.bottomBar)

    val isLibraryGridView: StateFlow<Boolean> = _settings
        .map { it.library.viewMode == LibraryViewMode.GRID }
        .distinctUntilChanged()
        .stateIn(repoScope, SharingStarted.Eagerly, _settings.value.library.viewMode == LibraryViewMode.GRID)

    // --- MUTATION METHODS ---

    private fun scheduleDebouncedWrite(delayMs: Long = 300L) {
        writeJob?.cancel()
        writeJob = repoScope.launch {
            if (delayMs > 0) {
                delay(delayMs)
            }
            persistSettings(_settings.value)
        }
    }

    suspend fun flush() = withContext(ioDispatcher) {
        writeJob?.cancel()
        persistSettings(_settings.value)
    }

    suspend fun updateReaderSettings(transform: (ReaderSettings) -> ReaderSettings) {
        val updated = _settings.value.copy(reader = transform(_settings.value.reader))
        _settings.value = sanitizeSettings(updated)
        scheduleDebouncedWrite()
    }

    suspend fun updateReaderSettings(newSettings: ReaderSettings) {
        updateReaderSettings { newSettings }
    }

    fun updateReaderSettingsSync(newSettings: ReaderSettings) {
        val updated = _settings.value.copy(reader = newSettings)
        _settings.value = sanitizeSettings(updated)
        scheduleDebouncedWrite()
    }

    suspend fun updateThemeSettings(transform: (ThemeSettings) -> ThemeSettings) {
        val updated = _settings.value.copy(theme = transform(_settings.value.theme))
        _settings.value = sanitizeSettings(updated)
        scheduleDebouncedWrite()
    }

    suspend fun updateThemeSettings(newSettings: ThemeSettings) {
        updateThemeSettings { newSettings }
    }

    fun updateThemeSettingsSync(newSettings: ThemeSettings) {
        val updated = _settings.value.copy(theme = newSettings)
        _settings.value = sanitizeSettings(updated)
        scheduleDebouncedWrite()
    }

    suspend fun updateDownloadSettings(transform: (DownloadSettings) -> DownloadSettings) {
        val updated = _settings.value.copy(downloads = transform(_settings.value.downloads))
        _settings.value = sanitizeSettings(updated)
        scheduleDebouncedWrite()
    }

    suspend fun updateDownloadSettings(newSettings: DownloadSettings) {
        updateDownloadSettings { newSettings }
    }

    fun updateDownloadSettingsSync(newSettings: DownloadSettings) {
        val updated = _settings.value.copy(downloads = newSettings)
        _settings.value = sanitizeSettings(updated)
        scheduleDebouncedWrite()
    }

    /**
     * Attempts to persist read/write permission for a custom SAF download directory.
     * Checks DocumentFile.canWrite(). Returns true only when the permission was granted and verified writable.
     */
    fun persistFolderPermission(context: Context, uri: Uri): Boolean {
        return Companion.persistFolderPermission(context, uri)
    }

    /**
     * Saves the custom download location setting ONLY when persistFolderPermission returns true.
     * Existing chapter rows and files remain untouched at their recorded locations (Spec 5).
     */
    suspend fun setCustomDownloadLocation(uri: Uri, displayName: String? = null): Boolean {
        val ok = persistFolderPermission(appContext, uri)
        if (ok) {
            updateDownloadSettings { current ->
                current.copy(
                    customDownloadLocationUri = uri.toString(),
                    customDownloadLocationDisplayName = displayName ?: uri.lastPathSegment
                )
            }
        }
        return ok
    }

    suspend fun updateNotificationSettings(transform: (NotificationSettings) -> NotificationSettings) {
        val updated = _settings.value.copy(notifications = transform(_settings.value.notifications))
        _settings.value = sanitizeSettings(updated)
        scheduleDebouncedWrite()
    }

    suspend fun updateNotificationSettings(newSettings: NotificationSettings) {
        updateNotificationSettings { newSettings }
    }

    fun updateNotificationSettingsSync(newSettings: NotificationSettings) {
        val updated = _settings.value.copy(notifications = newSettings)
        _settings.value = sanitizeSettings(updated)
        scheduleDebouncedWrite()
    }

    suspend fun updateLibrarySettings(transform: (LibrarySettings) -> LibrarySettings) {
        val updated = _settings.value.copy(library = transform(_settings.value.library))
        _settings.value = sanitizeSettings(updated)
        scheduleDebouncedWrite()
    }

    suspend fun updateLibrarySettings(newSettings: LibrarySettings) {
        updateLibrarySettings { newSettings }
    }

    fun updateLibrarySettingsSync(newSettings: LibrarySettings) {
        val updated = _settings.value.copy(library = newSettings)
        _settings.value = sanitizeSettings(updated)
        scheduleDebouncedWrite()
    }

    suspend fun setLibraryGridView(isGrid: Boolean) {
        val mode = if (isGrid) LibraryViewMode.GRID else LibraryViewMode.LIST
        val updated = _settings.value.copy(library = _settings.value.library.copy(viewMode = mode))
        _settings.value = sanitizeSettings(updated)
        scheduleDebouncedWrite()
    }

    fun setLibraryGridViewSync(isGrid: Boolean) {
        val mode = if (isGrid) LibraryViewMode.GRID else LibraryViewMode.LIST
        val updated = _settings.value.copy(library = _settings.value.library.copy(viewMode = mode))
        _settings.value = sanitizeSettings(updated)
        scheduleDebouncedWrite()
    }

    suspend fun updateCultivationSettings(transform: (CultivationSettings) -> CultivationSettings) {
        val updated = _settings.value.copy(cultivation = transform(_settings.value.cultivation))
        _settings.value = sanitizeSettings(updated)
        scheduleDebouncedWrite()
    }

    suspend fun updateCultivationSettings(newSettings: CultivationSettings) {
        updateCultivationSettings { newSettings }
    }

    fun updateCultivationSettingsSync(newSettings: CultivationSettings) {
        val updated = _settings.value.copy(cultivation = newSettings)
        _settings.value = sanitizeSettings(updated)
        scheduleDebouncedWrite()
    }

    suspend fun updateBackupSettings(transform: (BackupSettings) -> BackupSettings) {
        val updated = _settings.value.copy(backup = transform(_settings.value.backup))
        _settings.value = sanitizeSettings(updated)
        scheduleDebouncedWrite()
    }

    suspend fun updateBackupSettings(newSettings: BackupSettings) {
        updateBackupSettings { newSettings }
    }

    fun updateBackupSettingsSync(newSettings: BackupSettings) {
        val updated = _settings.value.copy(backup = newSettings)
        _settings.value = sanitizeSettings(updated)
        scheduleDebouncedWrite()
    }

    suspend fun updateProfileSettings(transform: (ProfileSettings) -> ProfileSettings) {
        val updated = _settings.value.copy(profile = transform(_settings.value.profile))
        _settings.value = sanitizeSettings(updated)
        scheduleDebouncedWrite()
    }

    suspend fun updateProfileSettings(newSettings: ProfileSettings) {
        updateProfileSettings { newSettings }
    }

    fun updateProfileSettingsSync(newSettings: ProfileSettings) {
        val updated = _settings.value.copy(profile = newSettings)
        _settings.value = sanitizeSettings(updated)
        scheduleDebouncedWrite()
    }

    suspend fun updateBottomBarSettings(transform: (BottomBarSettings) -> BottomBarSettings) {
        val updated = _settings.value.copy(bottomBar = transform(_settings.value.bottomBar))
        _settings.value = sanitizeSettings(updated)
        scheduleDebouncedWrite()
    }

    suspend fun updateBottomBarSettings(newSettings: BottomBarSettings) {
        updateBottomBarSettings { newSettings }
    }

    fun updateBottomBarSettingsSync(newSettings: BottomBarSettings) {
        val updated = _settings.value.copy(bottomBar = newSettings)
        _settings.value = sanitizeSettings(updated)
        scheduleDebouncedWrite()
    }

    suspend fun resetAllSettings() {
        val currentDownloads = _settings.value.downloads
        val currentProfile = _settings.value.profile
        val currentBackup = _settings.value.backup
        val defaultSettings = sanitizeSettings(
            AppSettings(
                downloads = DownloadSettings(
                    customDownloadLocationUri = currentDownloads.customDownloadLocationUri,
                    customDownloadLocationDisplayName = currentDownloads.customDownloadLocationDisplayName
                ),
                profile = ProfileSettings(
                    displayName = currentProfile.displayName
                ),
                backup = BackupSettings(
                    lastBackupAtMillis = currentBackup.lastBackupAtMillis
                )
            )
        )
        _settings.value = defaultSettings
        scheduleDebouncedWrite(0L)
    }

    fun resetAllSettingsSync() {
        val currentDownloads = _settings.value.downloads
        val currentProfile = _settings.value.profile
        val currentBackup = _settings.value.backup
        val defaultSettings = sanitizeSettings(
            AppSettings(
                downloads = DownloadSettings(
                    customDownloadLocationUri = currentDownloads.customDownloadLocationUri,
                    customDownloadLocationDisplayName = currentDownloads.customDownloadLocationDisplayName
                ),
                profile = ProfileSettings(
                    displayName = currentProfile.displayName
                ),
                backup = BackupSettings(
                    lastBackupAtMillis = currentBackup.lastBackupAtMillis
                )
            )
        )
        _settings.value = defaultSettings
        scheduleDebouncedWrite(0L)
    }

    // --- PERSISTENCE & SAFE WRITES ---

    private suspend fun persistSettings(settingsToSave: AppSettings) = withContext(ioDispatcher) {
        writeMutex.withLock {
            try {
                val jsonString = json.encodeToString(AppSettings.serializer(), settingsToSave)
                tempSettingsFile.writeText(jsonString)
                if (!tempSettingsFile.renameTo(settingsFile)) {
                    tempSettingsFile.copyTo(settingsFile, overwrite = true)
                    tempSettingsFile.delete()
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                e.printStackTrace()
            }
        }
    }

    // --- INITIAL LOADING ---

    private fun loadInitialSettings(): AppSettings {
        if (settingsFile.exists()) {
            try {
                val raw = settingsFile.readText()
                if (raw.isNotBlank()) {
                    val decoded = json.decodeFromString(AppSettings.serializer(), raw)
                    return sanitizeSettings(decoded)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                e.printStackTrace()
                // Preserve unparseable file without overwriting good data (Rule 5)
                try {
                    if (!corruptedSettingsFile.exists()) {
                        settingsFile.copyTo(corruptedSettingsFile, overwrite = false)
                    }
                } catch (ignored: Exception) {}
            }
        }

        return sanitizeSettings(AppSettings())
    }

    companion object {
        const val SETTINGS_FILE_NAME = "app_settings.json"
        private val ALLOWED_AUTO_DOWNLOAD_AHEAD = listOf(0, 1, 3, 5, 10)

        fun coerceAutoDownloadAhead(value: Int): Int {
            return ALLOWED_AUTO_DOWNLOAD_AHEAD.minByOrNull { kotlin.math.abs(it - value) } ?: 3
        }

        val DEFAULT_BOTTOM_BAR_TABS = listOf(
            NavTab.LIBRARY,
            NavTab.DISCOVER,
            NavTab.CULTIVATE,
            NavTab.PROFILE
        )

        fun sanitizeBottomBarTabs(tabs: List<NavTab>): List<NavTab> {
            val result = tabs.distinct().toMutableList()

            // Ensure pinned tabs LIBRARY and PROFILE are present
            if (NavTab.LIBRARY !in result) {
                result.add(0, NavTab.LIBRARY)
            }
            if (NavTab.PROFILE !in result) {
                result.add(NavTab.PROFILE)
            }

            // Fill from default order if fewer than 4 entries
            for (tab in DEFAULT_BOTTOM_BAR_TABS) {
                if (result.size >= 4) break
                if (tab !in result) {
                    result.add(tab)
                }
            }

            // If more than 4 entries, drop extras from the end (preserving pinned tabs)
            while (result.size > 4) {
                val dropIdx = result.indexOfLast { it != NavTab.LIBRARY && it != NavTab.PROFILE }
                if (dropIdx != -1) {
                    result.removeAt(dropIdx)
                } else {
                    result.removeAt(result.lastIndex)
                }
            }

            return result
        }

        fun sanitizeSettings(settings: AppSettings): AppSettings {
            val coercedAhead = coerceAutoDownloadAhead(settings.downloads.autoDownloadAhead)
            val coercedGoal = settings.cultivation.dailyGoalMinutes.coerceIn(5, 120)
            val coercedReminder = settings.cultivation.reminderMinutesOfDay.coerceIn(0, 1439)
            val coercedTabs = sanitizeBottomBarTabs(settings.bottomBar.tabs)

            val updatedDownloads = if (settings.downloads.autoDownloadAhead == coercedAhead) {
                settings.downloads
            } else {
                settings.downloads.copy(autoDownloadAhead = coercedAhead)
            }

            val updatedCultivation = if (settings.cultivation.dailyGoalMinutes == coercedGoal &&
                settings.cultivation.reminderMinutesOfDay == coercedReminder
            ) {
                settings.cultivation
            } else {
                settings.cultivation.copy(
                    dailyGoalMinutes = coercedGoal,
                    reminderMinutesOfDay = coercedReminder
                )
            }

            val updatedBottomBar = if (settings.bottomBar.tabs == coercedTabs) {
                settings.bottomBar
            } else {
                settings.bottomBar.copy(tabs = coercedTabs)
            }

            return if (updatedDownloads === settings.downloads &&
                updatedCultivation === settings.cultivation &&
                updatedBottomBar === settings.bottomBar
            ) {
                settings
            } else {
                settings.copy(
                    downloads = updatedDownloads,
                    cultivation = updatedCultivation,
                    bottomBar = updatedBottomBar
                )
            }
        }

        @Volatile
        private var instance: SettingsRepository? = null

        fun getInstance(context: Context): SettingsRepository {
            return instance ?: synchronized(this) {
                instance ?: SettingsRepository(context.applicationContext ?: context).also { instance = it }
            }
        }

        /**
         * For unit tests requiring an isolated in-memory or distinct filesystem instance.
         */
        fun createForTest(context: Context, dispatcher: kotlinx.coroutines.CoroutineDispatcher): SettingsRepository {
            return SettingsRepository(context, dispatcher)
        }

        fun persistFolderPermission(context: Context, uri: Uri): Boolean {
            return try {
                val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, takeFlags)
                val doc = DocumentFile.fromTreeUri(context, uri)
                doc != null && doc.exists() && doc.canWrite()
            } catch (e: Exception) {
                false
            }
        }
    }
}
