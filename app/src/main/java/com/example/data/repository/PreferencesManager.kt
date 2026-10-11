package com.example.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.example.data.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

class PreferencesManager(
    private val context: Context,
    private val settingsRepository: SettingsRepository = SettingsRepository.getInstance(context)
) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("novel_reader_prefs", Context.MODE_PRIVATE)

    // Reader Settings - routed through SettingsRepository (Stage 1a adapter)
    val readerSettings: StateFlow<ReaderSettings> = settingsRepository.readerSettings

    // App Theme Settings - routed through SettingsRepository (Stage 1a adapter)
    val themeSettings: StateFlow<ThemeSettings> = settingsRepository.themeSettings

    // Download Settings - routed through SettingsRepository (Stage 1a adapter)
    val downloadSettings: StateFlow<DownloadSettings> = settingsRepository.downloadSettings

    // Notification Settings - routed through SettingsRepository (Stage 1a adapter)
    val notificationSettings: StateFlow<NotificationSettings> = settingsRepository.notificationSettings

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
        return settingsRepository.isLibraryGridView.value
    }

    fun setLibraryGridView(isGrid: Boolean) {
        settingsRepository.setLibraryGridViewSync(isGrid)
        prefs.edit().putBoolean("library_is_grid_view", isGrid).apply()
    }

    fun isEpubCoversMigrated(): Boolean {
        return prefs.getBoolean("epub_covers_migrated", false)
    }

    fun setEpubCoversMigrated(migrated: Boolean) {
        prefs.edit().putBoolean("epub_covers_migrated", migrated).apply()
    }

    fun loadDownloadedIndexJson(): String? {
        return prefs.getString("downloaded_index_json", null)
    }

    fun saveDownloadedIndexJson(jsonString: String) {
        prefs.edit().putString("downloaded_index_json", jsonString).apply()
    }

    // --- READER SETTINGS ---

    fun updateReaderSettings(settings: ReaderSettings) {
        settingsRepository.updateReaderSettingsSync(settings)
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
        settingsRepository.updateThemeSettingsSync(settings)
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
        settingsRepository.updateDownloadSettingsSync(settings)
    }

    // --- NOTIFICATIONS ---

    fun updateNotificationSettings(settings: NotificationSettings) {
        settingsRepository.updateNotificationSettingsSync(settings)
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
        settingsRepository.resetAllSettingsSync()
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

    // --- CHANGELOG / WHAT'S NEW ---

    fun getLastSeenChangelogVersion(): Int = prefs.getInt("last_seen_changelog_version", 0)

    fun setLastSeenChangelogVersion(versionCode: Int) {
        prefs.edit().putInt("last_seen_changelog_version", versionCode).apply()
    }
}
