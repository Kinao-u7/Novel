package com.example.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.example.data.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

class PreferencesManager(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("novel_reader_prefs", Context.MODE_PRIVATE)

    // Reader Settings
    private val _readerSettings = MutableStateFlow(loadReaderSettings())
    val readerSettings: StateFlow<ReaderSettings> = _readerSettings.asStateFlow()

    // App Theme Settings
    private val _themeSettings = MutableStateFlow(loadThemeSettings())
    val themeSettings: StateFlow<ThemeSettings> = _themeSettings.asStateFlow()

    // Download Settings
    private val _downloadSettings = MutableStateFlow(loadDownloadSettings())
    val downloadSettings: StateFlow<DownloadSettings> = _downloadSettings.asStateFlow()

    // Notification Settings
    private val _notificationSettings = MutableStateFlow(loadNotificationSettings())
    val notificationSettings: StateFlow<NotificationSettings> = _notificationSettings.asStateFlow()

    // Notifications history
    private val _notifications = MutableStateFlow<List<AppNotificationItem>>(loadNotifications())
    val notifications: StateFlow<List<AppNotificationItem>> = _notifications.asStateFlow()

    // Reading Stats
    private val _readingStats = MutableStateFlow(loadReadingStats())
    val readingStats: StateFlow<ReadingStats> = _readingStats.asStateFlow()

    // Term Replacement Rules
    private val _termReplacements = MutableStateFlow<List<TermReplacementRule>>(emptyList())
    val termReplacements: StateFlow<List<TermReplacementRule>> = _termReplacements.asStateFlow()

    // Custom Sources
    private val _customSources = MutableStateFlow<List<CustomNovelSource>>(emptyList())
    val customSources: StateFlow<List<CustomNovelSource>> = _customSources.asStateFlow()

    // Requested Sources
    private val _requestedSources = MutableStateFlow<List<String>>(emptyList())
    val requestedSources: StateFlow<List<String>> = _requestedSources.asStateFlow()

    init {
        loadTermReplacements()
        loadCustomSources()
        loadRequestedSources()
    }

    // --- LIBRARY & GENERAL PREFERENCES ---

    fun isLibraryGridView(): Boolean {
        return prefs.getBoolean("library_is_grid_view", true)
    }

    fun setLibraryGridView(isGrid: Boolean) {
        prefs.edit().putBoolean("library_is_grid_view", isGrid).apply()
    }

    fun loadLibraryJson(): String? {
        return prefs.getString("library_novels_json", null)
    }

    fun saveLibraryJson(jsonString: String) {
        prefs.edit().putString("library_novels_json", jsonString).apply()
    }

    fun loadDownloadedIndexJson(): String? {
        return prefs.getString("downloaded_index_json", null)
    }

    fun saveDownloadedIndexJson(jsonString: String) {
        prefs.edit().putString("downloaded_index_json", jsonString).apply()
    }

    fun loadReadChaptersJson(): String? {
        return prefs.getString("read_chapters_json", null)
    }

    fun saveReadChaptersJson(jsonString: String) {
        prefs.edit().putString("read_chapters_json", jsonString).apply()
    }

    fun loadBookmarksJson(): String? {
        return prefs.getString("bookmarks_json", null)
    }

    fun saveBookmarksJson(jsonString: String) {
        prefs.edit().putString("bookmarks_json", jsonString).apply()
    }

    // --- READER SETTINGS ---

    fun updateReaderSettings(settings: ReaderSettings) {
        _readerSettings.value = settings
        prefs.edit().apply {
            putString("reader_theme", settings.theme.name)
            putInt("reader_font_size", settings.fontSizeSp)
            putString("reader_font_weight", settings.fontWeight.name)
            putFloat("reader_line_spacing", settings.lineSpacingMultiplier)
            putInt("reader_para_spacing", settings.paragraphSpacingDp)
            putFloat("reader_letter_spacing", settings.letterSpacingSp)
            putString("reader_text_align", settings.textAlign.name)
            putInt("reader_horizontal_margin", settings.horizontalMarginDp)
            putInt("reader_vertical_margin", settings.verticalMarginDp)
            putInt("reader_content_width_pct", settings.contentWidthPercent)
            putBoolean("reader_para_indent", settings.paragraphIndentation)
            putString("reader_margin", settings.marginType.name)
            putString("reader_font", settings.fontType.name)
            putString("reader_mode", settings.readingMode.name)
            putInt("reader_auto_scroll_speed", settings.autoScrollSpeed)
            putBoolean("reader_justified", settings.isJustified)
            putString("reader_toolbar_pos", settings.toolbarPosition.name)
            putBoolean("reader_show_status_bar", settings.showStatusBar)
            putString("reader_toolbar_actions", settings.enabledToolbarActions.joinToString(","))
            putBoolean("reader_global_replacements", settings.globalTermReplacementsEnabled)
            putFloat("reader_tts_speed", settings.ttsSpeechRate)
            putFloat("reader_tts_pitch", settings.ttsPitch)
            putString("reader_tts_voice", settings.ttsVoice)
            putBoolean("reader_save_pos", settings.saveReadingPosition)
            apply()
        }
    }

    private fun loadReaderSettings(): ReaderSettings {
        val themeName = prefs.getString("reader_theme", ReaderTheme.SEPIA.name) ?: ReaderTheme.SEPIA.name
        val theme = runCatching { ReaderTheme.valueOf(themeName) }.getOrDefault(ReaderTheme.SEPIA)

        val fontName = prefs.getString("reader_font", FontType.SERIF.name) ?: FontType.SERIF.name
        val font = runCatching { FontType.valueOf(fontName) }.getOrDefault(FontType.SERIF)

        val weightName = prefs.getString("reader_font_weight", ReaderFontWeight.NORMAL.name) ?: ReaderFontWeight.NORMAL.name
        val weight = runCatching { ReaderFontWeight.valueOf(weightName) }.getOrDefault(ReaderFontWeight.NORMAL)

        val alignName = prefs.getString("reader_text_align", ReaderTextAlign.JUSTIFIED.name) ?: ReaderTextAlign.JUSTIFIED.name
        val textAlign = runCatching { ReaderTextAlign.valueOf(alignName) }.getOrDefault(ReaderTextAlign.JUSTIFIED)

        val marginName = prefs.getString("reader_margin", MarginType.NORMAL.name) ?: MarginType.NORMAL.name
        val margin = runCatching { MarginType.valueOf(marginName) }.getOrDefault(MarginType.NORMAL)

        val modeName = prefs.getString("reader_mode", ReadingMode.CONTINUOUS.name) ?: ReadingMode.CONTINUOUS.name
        val mode = runCatching { ReadingMode.valueOf(modeName) }.getOrDefault(ReadingMode.CONTINUOUS)

        val toolbarPosName = prefs.getString("reader_toolbar_pos", ToolbarPosition.BOTTOM.name) ?: ToolbarPosition.BOTTOM.name
        val toolbarPos = runCatching { ToolbarPosition.valueOf(toolbarPosName) }.getOrDefault(ToolbarPosition.BOTTOM)

        val showStatusBar = prefs.getBoolean("reader_show_status_bar", true)

        val defaultActions = listOf("prev_chapter", "next_chapter", "toc", "tts", "bookmark", "display")
        val savedActionsStr = prefs.getString("reader_toolbar_actions", null)
        val actions = if (!savedActionsStr.isNullOrBlank()) {
            savedActionsStr.split(",").filter { it.isNotBlank() }
        } else {
            defaultActions
        }

        return ReaderSettings(
            theme = theme,
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
            marginType = margin,
            fontType = font,
            readingMode = mode,
            autoScrollSpeed = prefs.getInt("reader_auto_scroll_speed", 0),
            isJustified = prefs.getBoolean("reader_justified", true),
            toolbarPosition = toolbarPos,
            showStatusBar = showStatusBar,
            enabledToolbarActions = actions,
            globalTermReplacementsEnabled = prefs.getBoolean("reader_global_replacements", true),
            ttsSpeechRate = prefs.getFloat("reader_tts_speed", 1.0f),
            ttsPitch = prefs.getFloat("reader_tts_pitch", 1.0f),
            ttsVoice = prefs.getString("reader_tts_voice", null),
            saveReadingPosition = prefs.getBoolean("reader_save_pos", true)
        )
    }

    // --- CHAPTER READING POSITION ---

    fun getChapterReadingPosition(novelId: String, chapterId: String): Int {
        val safeChapterId = chapterId.replace("[^a-zA-Z0-9_.-]".toRegex(), "_")
        return prefs.getInt("pos_${novelId}_$safeChapterId", 0)
    }

    fun saveChapterReadingPosition(novelId: String, chapterId: String, position: Int) {
        val safeChapterId = chapterId.replace("[^a-zA-Z0-9_.-]".toRegex(), "_")
        prefs.edit().putInt("pos_${novelId}_$safeChapterId", position).apply()
    }

    // --- TERM REPLACEMENT RULES ---

    fun addTermReplacementRule(rule: TermReplacementRule) {
        val current = _termReplacements.value.toMutableList()
        current.add(rule)
        _termReplacements.value = current
        saveTermReplacements()
    }

    fun updateTermReplacementRule(rule: TermReplacementRule) {
        val current = _termReplacements.value.toMutableList()
        val idx = current.indexOfFirst { it.id == rule.id }
        if (idx >= 0) {
            current[idx] = rule
            _termReplacements.value = current
            saveTermReplacements()
        }
    }

    fun deleteTermReplacementRule(id: String) {
        val current = _termReplacements.value.filterNot { it.id == id }
        _termReplacements.value = current
        saveTermReplacements()
    }

    fun toggleTermReplacementRule(id: String, isEnabled: Boolean) {
        val current = _termReplacements.value.toMutableList()
        val idx = current.indexOfFirst { it.id == id }
        if (idx >= 0) {
            current[idx] = current[idx].copy(isEnabled = isEnabled)
            _termReplacements.value = current
            saveTermReplacements()
        }
    }

    private fun loadTermReplacements() {
        val str = prefs.getString("term_replacements_json", null) ?: return
        try {
            val arr = JSONArray(str)
            val list = mutableListOf<TermReplacementRule>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    TermReplacementRule(
                        id = o.getString("id"),
                        original = o.getString("original"),
                        replacement = o.getString("replacement"),
                        isEnabled = o.optBoolean("isEnabled", true)
                    )
                )
            }
            _termReplacements.value = list
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun saveTermReplacements() {
        val arr = JSONArray()
        for (r in _termReplacements.value) {
            arr.put(JSONObject().apply {
                put("id", r.id)
                put("original", r.original)
                put("replacement", r.replacement)
                put("isEnabled", r.isEnabled)
            })
        }
        prefs.edit().putString("term_replacements_json", arr.toString()).apply()
    }

    // --- THEME SETTINGS ---

    fun updateThemeSettings(settings: ThemeSettings) {
        _themeSettings.value = settings
        prefs.edit().apply {
            putString("app_theme_mode", settings.themeMode.name)
            putString("app_accent_hex", settings.accentColorHex)
            putBoolean("app_use_dynamic_color", settings.useDynamicColor)
            apply()
        }
    }

    private fun loadThemeSettings(): ThemeSettings {
        val modeName = prefs.getString("app_theme_mode", AppThemeMode.DARK.name) ?: AppThemeMode.DARK.name
        val mode = runCatching { AppThemeMode.valueOf(modeName) }.getOrDefault(AppThemeMode.DARK)
        val accentHex = prefs.getString("app_accent_hex", "#6366F1") ?: "#6366F1"
        val useDynamic = prefs.getBoolean("app_use_dynamic_color", false)
        return ThemeSettings(
            themeMode = mode,
            accentColorHex = accentHex,
            useDynamicColor = useDynamic
        )
    }

    // --- READING STATS ---

    fun recordReadingSession(minutesDelta: Int, wordsDelta: Int) {
        val current = _readingStats.value
        val updated = current.copy(
            todayMinutesRead = current.todayMinutesRead + minutesDelta,
            totalWordsRead = current.totalWordsRead + wordsDelta,
            totalChaptersRead = current.totalChaptersRead + if (wordsDelta > 1000) 1 else 0,
            lastSessionTimestamp = System.currentTimeMillis()
        )
        _readingStats.value = updated
        saveReadingStats(updated)
    }

    private fun loadReadingStats(): ReadingStats {
        val minutes = prefs.getInt("stats_today_minutes", 48)
        val streak = prefs.getInt("stats_streak_days", 7)
        val chapters = prefs.getInt("stats_total_chapters", 128)
        val words = prefs.getLong("stats_total_words", 345200L)
        return ReadingStats(
            todayMinutesRead = minutes,
            currentStreakDays = streak,
            totalChaptersRead = chapters,
            totalWordsRead = words
        )
    }

    private fun saveReadingStats(stats: ReadingStats) {
        prefs.edit().apply {
            putInt("stats_today_minutes", stats.todayMinutesRead)
            putInt("stats_streak_days", stats.currentStreakDays)
            putInt("stats_total_chapters", stats.totalChaptersRead)
            putLong("stats_total_words", stats.totalWordsRead)
            apply()
        }
    }

    // --- DOWNLOAD SETTINGS ---

    fun updateDownloadSettings(settings: DownloadSettings) {
        _downloadSettings.value = settings
        prefs.edit().apply {
            putBoolean("dl_wifi_only", settings.wifiOnly)
            putBoolean("dl_allow_mobile", settings.allowMobileData)
            putInt("dl_simultaneous", settings.simultaneousDownloads)
            putBoolean("dl_auto_next", settings.autoDownloadNext)
            putString("dl_custom_location_uri", settings.customDownloadLocationUri)
            putString("dl_custom_location_display", settings.customDownloadLocationDisplayName)
            putBoolean("dl_throttling_enabled", settings.throttlingEnabled)
            putInt("dl_throttle_batch_size", settings.throttleBatchSize)
            putFloat("dl_throttle_delay_sec", settings.throttleDelaySeconds)
            putBoolean("dl_throttle_random_jitter", settings.throttleRandomJitter)
            apply()
        }
    }

    private fun loadDownloadSettings(): DownloadSettings {
        return DownloadSettings(
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
    }

    // --- NOTIFICATIONS ---

    fun updateNotificationSettings(settings: NotificationSettings) {
        _notificationSettings.value = settings
        prefs.edit().apply {
            putBoolean("notif_download", settings.notifyOnDownload)
            putBoolean("notif_update", settings.notifyOnNovelUpdate)
            apply()
        }
    }

    private fun loadNotificationSettings(): NotificationSettings {
        return NotificationSettings(
            notifyOnDownload = prefs.getBoolean("notif_download", true),
            notifyOnNovelUpdate = prefs.getBoolean("notif_update", true)
        )
    }

    private fun loadNotifications(): List<AppNotificationItem> {
        val json = prefs.getString("notifications_json", null) ?: return listOf(
            AppNotificationItem(
                title = "Welcome to Novel Reader",
                message = "Explore millions of web novels and import your local EPUB files.",
                timestamp = System.currentTimeMillis() - 3600_000 * 2,
                type = "system"
            )
        )
        return try {
            val arr = JSONArray(json)
            val list = mutableListOf<AppNotificationItem>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    AppNotificationItem(
                        id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                        title = obj.getString("title"),
                        message = obj.getString("message"),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                        type = obj.optString("type", "info")
                    )
                )
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveNotifications(list: List<AppNotificationItem>) {
        val arr = JSONArray()
        list.forEach { item ->
            val obj = JSONObject().apply {
                put("id", item.id)
                put("title", item.title)
                put("message", item.message)
                put("timestamp", item.timestamp)
                put("type", item.type)
            }
            arr.put(obj)
        }
        prefs.edit().putString("notifications_json", arr.toString()).apply()
    }

    fun addNotification(title: String, message: String, type: String = "info") {
        val newItem = AppNotificationItem(title = title, message = message, type = type)
        val updated = listOf(newItem) + _notifications.value
        _notifications.value = updated
        saveNotifications(updated)
    }

    fun clearNotifications() {
        _notifications.value = emptyList()
        prefs.edit().remove("notifications_json").apply()
    }

    fun resetAppPreferences() {
        _readerSettings.value = ReaderSettings()
        updateReaderSettings(ReaderSettings())

        _themeSettings.value = ThemeSettings()
        updateThemeSettings(ThemeSettings())

        _downloadSettings.value = DownloadSettings()
        updateDownloadSettings(DownloadSettings())

        _notificationSettings.value = NotificationSettings()
        updateNotificationSettings(NotificationSettings())
    }

    // --- CUSTOM SOURCES ---

    private fun loadCustomSources() {
        val jsonStr = prefs.getString("custom_novel_sources_json", null)
        if (!jsonStr.isNullOrBlank()) {
            try {
                val arr = JSONArray(jsonStr)
                val list = mutableListOf<CustomNovelSource>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    list.add(
                        CustomNovelSource(
                            id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                            name = obj.getString("name"),
                            url = obj.getString("url"),
                            language = obj.optString("language", "English"),
                            description = obj.optString("description", "Custom web source")
                        )
                    )
                }
                _customSources.value = list
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun addCustomSource(name: String, url: String, language: String = "English", description: String = "Custom web source") {
        val normalizedUrl = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
        val newSource = CustomNovelSource(
            name = name,
            url = normalizedUrl,
            language = language,
            description = description
        )
        val current = _customSources.value.toMutableList()
        current.add(newSource)
        _customSources.value = current
        saveCustomSources()
    }

    fun removeCustomSource(id: String) {
        val current = _customSources.value.filter { it.id != id }
        _customSources.value = current
        saveCustomSources()
    }

    private fun saveCustomSources() {
        val arr = JSONArray()
        for (src in _customSources.value) {
            val obj = JSONObject().apply {
                put("id", src.id)
                put("name", src.name)
                put("url", src.url)
                put("language", src.language)
                put("description", src.description)
            }
            arr.put(obj)
        }
        prefs.edit().putString("custom_novel_sources_json", arr.toString()).apply()
    }

    // --- REQUESTED SOURCES ---

    private fun loadRequestedSources() {
        val set = prefs.getStringSet("requested_sources_set", emptySet()) ?: emptySet()
        _requestedSources.value = set.toList()
    }

    fun requestSource(siteName: String, url: String) {
        val entry = if (url.isNotBlank()) "$siteName ($url)" else siteName
        val current = _requestedSources.value.toMutableList()
        if (!current.contains(entry)) {
            current.add(entry)
            _requestedSources.value = current
            prefs.edit().putStringSet("requested_sources_set", current.toSet()).apply()
        }
    }
}
