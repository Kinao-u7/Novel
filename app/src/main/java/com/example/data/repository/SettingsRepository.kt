package com.example.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.example.data.model.AppSettings
import com.example.data.model.AppThemeMode
import com.example.data.model.DownloadSettings
import com.example.data.model.FontType
import com.example.data.model.NotificationSettings
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

    val isLibraryGridView: StateFlow<Boolean> = _settings
        .map { it.isLibraryGridView }
        .distinctUntilChanged()
        .stateIn(repoScope, SharingStarted.Eagerly, _settings.value.isLibraryGridView)

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
        _settings.value = updated
        scheduleDebouncedWrite()
    }

    suspend fun updateReaderSettings(newSettings: ReaderSettings) {
        updateReaderSettings { newSettings }
    }

    fun updateReaderSettingsSync(newSettings: ReaderSettings) {
        val updated = _settings.value.copy(reader = newSettings)
        _settings.value = updated
        scheduleDebouncedWrite()
    }

    suspend fun updateThemeSettings(transform: (ThemeSettings) -> ThemeSettings) {
        val updated = _settings.value.copy(theme = transform(_settings.value.theme))
        _settings.value = updated
        scheduleDebouncedWrite()
    }

    suspend fun updateThemeSettings(newSettings: ThemeSettings) {
        updateThemeSettings { newSettings }
    }

    fun updateThemeSettingsSync(newSettings: ThemeSettings) {
        val updated = _settings.value.copy(theme = newSettings)
        _settings.value = updated
        scheduleDebouncedWrite()
    }

    suspend fun updateDownloadSettings(transform: (DownloadSettings) -> DownloadSettings) {
        val updated = _settings.value.copy(downloads = transform(_settings.value.downloads))
        _settings.value = updated
        scheduleDebouncedWrite()
    }

    suspend fun updateDownloadSettings(newSettings: DownloadSettings) {
        updateDownloadSettings { newSettings }
    }

    fun updateDownloadSettingsSync(newSettings: DownloadSettings) {
        val updated = _settings.value.copy(downloads = newSettings)
        _settings.value = updated
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
        _settings.value = updated
        scheduleDebouncedWrite()
    }

    suspend fun updateNotificationSettings(newSettings: NotificationSettings) {
        updateNotificationSettings { newSettings }
    }

    fun updateNotificationSettingsSync(newSettings: NotificationSettings) {
        val updated = _settings.value.copy(notifications = newSettings)
        _settings.value = updated
        scheduleDebouncedWrite()
    }

    suspend fun setLibraryGridView(isGrid: Boolean) {
        val updated = _settings.value.copy(isLibraryGridView = isGrid)
        _settings.value = updated
        scheduleDebouncedWrite()
    }

    fun setLibraryGridViewSync(isGrid: Boolean) {
        val updated = _settings.value.copy(isLibraryGridView = isGrid)
        _settings.value = updated
        scheduleDebouncedWrite()
    }

    suspend fun resetAllSettings() {
        val currentDownloads = _settings.value.downloads
        val defaultSettings = AppSettings(
            downloads = DownloadSettings(
                customDownloadLocationUri = currentDownloads.customDownloadLocationUri,
                customDownloadLocationDisplayName = currentDownloads.customDownloadLocationDisplayName
            )
        )
        _settings.value = defaultSettings
        scheduleDebouncedWrite(0L)
    }

    fun resetAllSettingsSync() {
        val currentDownloads = _settings.value.downloads
        val defaultSettings = AppSettings(
            downloads = DownloadSettings(
                customDownloadLocationUri = currentDownloads.customDownloadLocationUri,
                customDownloadLocationDisplayName = currentDownloads.customDownloadLocationDisplayName
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

    // --- INITIAL LOADING & LEGACY PREFS MIGRATION ---

    private fun loadInitialSettings(): AppSettings {
        if (settingsFile.exists()) {
            try {
                val raw = settingsFile.readText()
                if (raw.isNotBlank()) {
                    return json.decodeFromString(AppSettings.serializer(), raw)
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

        // Fallback to legacy SharedPreferences if existing file does not exist
        return migrateFromLegacyPrefs()
    }

    private fun migrateFromLegacyPrefs(): AppSettings {
        val prefs = appContext.getSharedPreferences("novel_reader_prefs", Context.MODE_PRIVATE)
        if (!prefs.contains("reader_theme") && !prefs.contains("app_theme_mode") && !prefs.contains("dl_wifi_only")) {
            return AppSettings()
        }

        return try {
            val themeModeName = prefs.getString("app_theme_mode", AppThemeMode.DARK.name) ?: AppThemeMode.DARK.name
            val themeMode = runCatching { AppThemeMode.valueOf(themeModeName) }.getOrDefault(AppThemeMode.DARK)
            val accentHex = prefs.getString("app_accent_hex", "#DEB887") ?: "#DEB887"
            val useDynamic = prefs.getBoolean("app_use_dynamic_color", false)
            val themeSettings = ThemeSettings(
                themeMode = themeMode,
                accentColorHex = accentHex,
                useDynamicColor = useDynamic
            )

            val readerThemeName = prefs.getString("reader_theme", ReaderTheme.SEPIA.name) ?: ReaderTheme.SEPIA.name
            val readerTheme = runCatching { ReaderTheme.valueOf(readerThemeName) }.getOrDefault(ReaderTheme.SEPIA)
            val fontName = prefs.getString("reader_font", FontType.SERIF.name) ?: FontType.SERIF.name
            val font = runCatching { FontType.valueOf(fontName) }.getOrDefault(FontType.SERIF)
            val weightName = prefs.getString("reader_font_weight", ReaderFontWeight.NORMAL.name) ?: ReaderFontWeight.NORMAL.name
            val weight = runCatching { ReaderFontWeight.valueOf(weightName) }.getOrDefault(ReaderFontWeight.NORMAL)
            val alignName = prefs.getString("reader_text_align", ReaderTextAlign.JUSTIFIED.name) ?: ReaderTextAlign.JUSTIFIED.name
            val textAlign = runCatching { ReaderTextAlign.valueOf(alignName) }.getOrDefault(ReaderTextAlign.JUSTIFIED)
            val modeName = prefs.getString("reader_mode", ReadingMode.CONTINUOUS.name) ?: ReadingMode.CONTINUOUS.name
            val readingMode = runCatching { ReadingMode.valueOf(modeName) }.getOrDefault(ReadingMode.CONTINUOUS)
            val toolbarPosName = prefs.getString("reader_toolbar_pos", ToolbarPosition.BOTTOM.name) ?: ToolbarPosition.BOTTOM.name
            val toolbarPos = runCatching { ToolbarPosition.valueOf(toolbarPosName) }.getOrDefault(ToolbarPosition.BOTTOM)

            val defaultActions = listOf("prev_chapter", "next_chapter", "toc", "tts", "bookmark", "display")
            val savedActionsStr = prefs.getString("reader_toolbar_actions", null)
            val actions = if (!savedActionsStr.isNullOrBlank()) {
                savedActionsStr.split(",").filter { it.isNotBlank() }
            } else {
                defaultActions
            }

            val readerSettings = ReaderSettings(
                theme = readerTheme,
                fontSizeSp = prefs.getInt("reader_font_size", 18),
                fontWeight = weight,
                lineSpacingMultiplier = prefs.getFloat("reader_line_spacing", 1.6f),
                paragraphSpacingDp = prefs.getInt("reader_para_spacing", 12),
                letterSpacingSp = prefs.getFloat("reader_letter_spacing", 0f),
                textAlign = textAlign,
                horizontalMarginDp = prefs.getInt("reader_horizontal_margin", 20),
                verticalMarginDp = prefs.getInt("reader_vertical_margin", 20),
                contentWidthPercent = prefs.getInt("reader_content_width_pct", 100),
                paragraphIndentation = prefs.getBoolean("reader_para_indent", false),
                fontType = font,
                readingMode = readingMode,
                autoScrollSpeed = prefs.getInt("reader_auto_scroll_speed", 0),
                keepScreenOn = prefs.getBoolean("reader_keep_screen_on", true),
                toolbarPosition = toolbarPos,
                showStatusBar = prefs.getBoolean("reader_show_status_bar", true),
                enabledToolbarActions = actions,
                globalTermReplacementsEnabled = prefs.getBoolean("reader_global_replacements", true),
                ttsSpeechRate = prefs.getFloat("reader_tts_speed", 1.0f),
                ttsPitch = prefs.getFloat("reader_tts_pitch", 1.0f),
                ttsVoice = prefs.getString("reader_tts_voice", null),
                saveReadingPosition = prefs.getBoolean("reader_save_pos", true)
            )

            val downloadSettings = DownloadSettings(
                wifiOnly = prefs.getBoolean("dl_wifi_only", true),
                allowMobileData = prefs.getBoolean("dl_allow_mobile", false),
                simultaneousDownloads = prefs.getInt("dl_simultaneous", 3),
                autoDownloadNext = prefs.getBoolean("dl_auto_next", false),
                customDownloadLocationUri = prefs.getString("dl_custom_location_uri", null),
                customDownloadLocationDisplayName = prefs.getString("dl_custom_location_display", null),
                throttlingEnabled = prefs.getBoolean("dl_throttling_enabled", false),
                throttleBatchSize = prefs.getInt("dl_throttle_batch_size", 5),
                throttleDelaySeconds = prefs.getFloat("dl_throttle_delay_sec", 2.0f),
                throttleRandomJitter = prefs.getBoolean("dl_throttle_random_jitter", true)
            )

            val notificationSettings = NotificationSettings(
                notifyOnDownload = prefs.getBoolean("notif_download", true),
                notifyOnNovelUpdate = prefs.getBoolean("notif_update", true)
            )

            val isGrid = prefs.getBoolean("library_is_grid_view", true)

            val initial = AppSettings(
                reader = readerSettings,
                theme = themeSettings,
                downloads = downloadSettings,
                notifications = notificationSettings,
                isLibraryGridView = isGrid
            )

            repoScope.launch {
                persistSettings(initial)
            }
            initial
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            e.printStackTrace()
            AppSettings()
        }
    }

    companion object {
        const val SETTINGS_FILE_NAME = "app_settings.json"

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
