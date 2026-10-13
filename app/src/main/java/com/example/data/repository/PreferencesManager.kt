package com.example.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.example.data.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    // Reading Stats
    private val _readingStats = MutableStateFlow(loadReadingStats())
    val readingStats: StateFlow<ReadingStats> = _readingStats.asStateFlow()

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

    // --- NOTIFICATION SETTINGS ---

    fun updateNotificationSettings(settings: NotificationSettings) {
        settingsRepository.updateNotificationSettingsSync(settings)
    }

    fun resetAppPreferences() {
        settingsRepository.resetAllSettingsSync()
    }

    // --- CHANGELOG / WHAT'S NEW ---

    fun getLastSeenChangelogVersion(): Int = prefs.getInt("last_seen_changelog_version", 0)

    fun setLastSeenChangelogVersion(versionCode: Int) {
        prefs.edit().putInt("last_seen_changelog_version", versionCode).apply()
    }
}
