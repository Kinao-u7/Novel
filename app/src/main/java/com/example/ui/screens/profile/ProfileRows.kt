package com.example.ui.screens.profile

import android.content.Context
import com.example.R
import com.example.data.model.AppSettings
import com.example.data.model.AppThemeMode
import com.example.data.model.AutoBackup
import com.example.data.model.DefaultShelf
import com.example.data.model.FontType
import com.example.data.model.NavTab
import com.example.data.model.ReaderTextAlign
import com.example.data.model.ReadingMode
import com.example.data.repository.SettingsRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

data class StorageSizes(
    val chaptersBytes: Long = 0L,
    val coversBytes: Long = 0L,
    val cacheBytes: Long = 0L
)

internal fun formatBytes(b: Long): String {
    if (b <= 0) return "0 MB"
    val mb = b / 1_048_576.0
    return when {
        mb < 1 -> "${(b / 1024.0).roundToInt()} KB"
        mb >= 1024 -> String.format(Locale.US, "%.1f GB", mb / 1024)
        else -> "${mb.roundToInt()} MB"
    }
}

interface ProfileText {
    val comingSoon: String
    val tabLibrary: String
    val tabDiscover: String
    val tabCultivate: String
    val tabDownloads: String
    val tabProfile: String
    fun openTab(tab: NavTab): String

    val appearanceTitle: String
    fun appearanceSummary(settings: AppSettings): String
    val readerTitle: String
    fun readerSummary(settings: AppSettings): String
    val libraryTitle: String
    fun librarySummary(settings: AppSettings): String
    val cultivationTitle: String
    fun cultivationSummary(settings: AppSettings): String
    val downloadsTitle: String
    fun downloadsSummary(storage: StorageSizes): String
    val backupTitle: String
    fun backupSummary(settings: AppSettings): String
    val aboutTitle: String
    val aboutSummary: String

    val matchSystemTitle: String
    val matchSystemSubtitle: String
    val themeTitle: String
    val accentTitle: String
    val slot2Title: String
    val slot2Subtitle: String?
    val slot3Title: String
    val slot3Subtitle: String?

    val readerPreviewTitle: String
    val readerPreviewSample: String
    val readerFontTitle: String
    val readerSizeTitle: String
    val readerSpacingTitle: String
    fun formatSpacing(spacing: Float): String
    val readerJustifyTitle: String
    val readerModeTitle: String
    val keepScreenOnTitle: String
    val volumeKeysTitle: String

    val updateOnLaunchTitle: String
    val unreadBadgesTitle: String
    val defaultShelfTitle: String

    val qiRewardsTitle: String
    val breakthroughFxTitle: String
    val hapticsTitle: String
    val reduceMotionTitle: String
    val dailyGoalTitle: String
    val reminderTitle: String
    val reminderTimeTitle: String

    val autoDownloadTitle: String
    val deleteAfterReadingTitle: String
    val wifiOnlyTitle: String
    val throttlingTitle: String
    val throttleDelayTitle: String
    val downloadFolderTitle: String
    val internalStorage: String
    val storageTitle: String
    val clearCacheTitle: String

    val createBackupTitle: String
    val restoreBackupTitle: String
    val autoBackupTitle: String
    val lastBackupNever: String
    fun lastBackupSubtitle(lastBackupAtMillis: Long): String
    val restoreBackupSubtitle: String
    val backupCatLibrary: String
    val backupCatLibrarySub: String
    val backupCatBookmarks: String
    val backupCatBookmarksSub: String
    val backupCatCovers: String
    val backupCatCoversSub: String
    val backupCatEpubs: String
    val backupCatEpubsSub: String
    val backupCatStats: String
    val backupCatStatsSub: String
    val backupCatTerms: String
    val backupCatTermsSub: String
    val backupCatSources: String
    val backupCatSourcesSub: String
    val backupCatSettings: String
    val backupCatSettingsSub: String

    val checkUpdatesTitle: String
    val licencesTitle: String
    val privacyTitle: String
    val resetAllTitle: String

    val clearCacheDialogTitle: String
    fun clearCacheDialogMessage(formattedSize: String): String
    val clearCacheConfirmLabel: String
    val resetAllDialogTitle: String
    val resetAllDialogMessage: String
    val resetAllConfirmLabel: String
    fun freedMessage(formattedSize: String): String
    val resetSuccessMessage: String
    fun downloadFolderUpdated(name: String): String
    val downloadFolderError: String
}

open class DefaultProfileText : ProfileText {
    override val comingSoon: String = "Coming soon"
    override val tabLibrary: String = "Library"
    override val tabDiscover: String = "Discover"
    override val tabCultivate: String = "Cultivate"
    override val tabDownloads: String = "Downloads"
    override val tabProfile: String = "Profile"
    override fun openTab(tab: NavTab): String = "Open ${tab.name.lowercase().replaceFirstChar { it.titlecase(Locale.US) }}"

    override val appearanceTitle: String = "Appearance"
    override fun appearanceSummary(settings: AppSettings): String {
        val theme = when (settings.theme.themeMode) {
            AppThemeMode.DARK -> "Ink"
            AppThemeMode.AMOLED -> "Black"
            AppThemeMode.SEPIA -> "Paper"
            AppThemeMode.LIGHT -> "White"
            AppThemeMode.SYSTEM -> "System"
        }
        val accent = when (settings.theme.accentColorHex.lowercase()) {
            "#7cf2c3" -> "Jade"
            "#f3cb83" -> "Gold"
            "#ff9ec4" -> "Rose"
            "#8fd0ff" -> "Azure"
            else -> "Accent"
        }
        return "$theme · $accent accent"
    }

    override val readerTitle: String = "Reader"
    override fun readerSummary(settings: AppSettings): String {
        val font = settings.reader.fontType.displayName.substringBefore(" ")
        return "$font · ${settings.reader.fontSizeSp}"
    }

    override val libraryTitle: String = "Library"
    override fun librarySummary(settings: AppSettings): String {
        val badge = if (settings.library.showUnreadBadges) "Badges on" else "Badges off"
        return "${settings.library.defaultShelf.displayName} · $badge"
    }

    override val cultivationTitle: String = "Cultivation"
    override fun cultivationSummary(settings: AppSettings): String =
        "${settings.cultivation.dailyGoalMinutes} min goal"

    override val downloadsTitle: String = "Downloads & storage"
    override fun downloadsSummary(storage: StorageSizes): String {
        val total = storage.chaptersBytes + storage.coversBytes + storage.cacheBytes
        return "${formatBytes(total)} used"
    }

    override val backupTitle: String = "Backup & restore"
    override fun backupSummary(settings: AppSettings): String =
        "${settings.backup.autoBackup.displayName} backup"

    override val aboutTitle: String = "About"
    override val aboutSummary: String = "Updates, licences, privacy"

    override val matchSystemTitle: String = "Match system theme"
    override val matchSystemSubtitle: String = "Switch between light and dark automatically"
    override val themeTitle: String = "Theme"
    override val accentTitle: String = "Accent color"
    override val slot2Title: String = "Bottom bar slot 2"
    override val slot2Subtitle: String? = null
    override val slot3Title: String = "Bottom bar slot 3"
    override val slot3Subtitle: String? = null

    override val readerPreviewTitle: String = "Preview"
    override val readerPreviewSample: String =
        "The lantern had been burning for nine hundred years, and no one on Cold Mountain could say who had lit it."
    override val readerFontTitle: String = "Font"
    override val readerSizeTitle: String = "Text size"
    override val readerSpacingTitle: String = "Line spacing"
    override fun formatSpacing(spacing: Float): String = String.format(Locale.US, "%.1fx", spacing)
    override val readerJustifyTitle: String = "Justify text"
    override val readerModeTitle: String = "Reading mode"
    override val keepScreenOnTitle: String = "Keep screen on"
    override val volumeKeysTitle: String = "Volume keys scroll"

    override val updateOnLaunchTitle: String = "Update on launch"
    override val unreadBadgesTitle: String = "Unread badges"
    override val defaultShelfTitle: String = "Default shelf"

    override val qiRewardsTitle: String = "Qi rewards"
    override val breakthroughFxTitle: String = "Breakthrough effects"
    override val hapticsTitle: String = "Haptics"
    override val reduceMotionTitle: String = "Reduce motion"
    override val dailyGoalTitle: String = "Daily goal"
    override val reminderTitle: String = "Daily reminder"
    override val reminderTimeTitle: String = "Reminder time"

    override val autoDownloadTitle: String = "Auto-download"
    override val deleteAfterReadingTitle: String = "Delete after reading"
    override val wifiOnlyTitle: String = "Download over Wi-Fi only"
    override val throttlingTitle: String = "Download throttling"
    override val throttleDelayTitle: String = "Throttle delay"
    override val downloadFolderTitle: String = "Download folder"
    override val internalStorage: String = "Internal"
    override val storageTitle: String = "Storage"
    override val clearCacheTitle: String = "Clear cache"

    override val createBackupTitle: String = "Create backup"
    override val restoreBackupTitle: String = "Restore backup"
    override val autoBackupTitle: String = "Auto backup"
    override val lastBackupNever: String = "Never"
    override fun lastBackupSubtitle(lastBackupAtMillis: Long): String {
        if (lastBackupAtMillis <= 0L) return lastBackupNever
        val fmt = SimpleDateFormat("MMM d, yyyy, HH:mm", Locale.US)
        return "Last backup: ${fmt.format(Date(lastBackupAtMillis))}"
    }
    override val restoreBackupSubtitle: String = "Choose a backup file"
    override val backupCatLibrary: String = "Library"
    override val backupCatLibrarySub: String = "Novels, chapters, and read history"
    override val backupCatBookmarks: String = "Bookmarks"
    override val backupCatBookmarksSub: String = "Saved passages and notes"
    override val backupCatCovers: String = "Covers"
    override val backupCatCoversSub: String = "Downloaded novel cover art"
    override val backupCatEpubs: String = "EPUB files"
    override val backupCatEpubsSub: String = "Original files, can be large"
    override val backupCatStats: String = "Reading stats"
    override val backupCatStatsSub: String = "Reading sessions and stats"
    override val backupCatTerms: String = "Term replacements"
    override val backupCatTermsSub: String = "Name and term dictionary rules"
    override val backupCatSources: String = "Custom sources"
    override val backupCatSourcesSub: String = "Added third-party sources"
    override val backupCatSettings: String = "Settings"
    override val backupCatSettingsSub: String = "Reader preferences, theme, and downloads"

    override val checkUpdatesTitle: String = "Check for updates"
    override val licencesTitle: String = "Licences"
    override val privacyTitle: String = "Privacy policy"
    override val resetAllTitle: String = "Reset all settings"

    override val clearCacheDialogTitle: String = "Clear cache"
    override fun clearCacheDialogMessage(formattedSize: String): String = "Free $formattedSize of cached chapter files?"
    override val clearCacheConfirmLabel: String = "Clear"
    override val resetAllDialogTitle: String = "Reset all settings"
    override val resetAllDialogMessage: String = "Reset all settings to default values? Your library, history, and downloads will remain intact."
    override val resetAllConfirmLabel: String = "Reset all"
    override fun freedMessage(formattedSize: String): String = "Freed $formattedSize"
    override val resetSuccessMessage: String = "Settings restored to defaults"
    override fun downloadFolderUpdated(name: String): String = "Download location updated: $name"
    override val downloadFolderError: String = "Selected directory is not writable"
}

class AndroidProfileText(private val context: Context) : DefaultProfileText() {
    private fun res(id: Int): String = context.getString(id)
    private fun res(id: Int, vararg args: Any): String = context.getString(id, *args)

    override val comingSoon: String get() = res(R.string.profile_coming_soon)
    override val tabLibrary: String get() = res(R.string.nav_tab_library)
    override val tabDiscover: String get() = res(R.string.nav_tab_discover)
    override val tabCultivate: String get() = res(R.string.nav_tab_cultivate)
    override val tabDownloads: String get() = res(R.string.nav_tab_downloads)
    override val tabProfile: String get() = res(R.string.nav_tab_profile)
    override fun openTab(tab: NavTab): String {
        val name = when (tab) {
            NavTab.LIBRARY -> tabLibrary
            NavTab.DISCOVER -> tabDiscover
            NavTab.CULTIVATE -> tabCultivate
            NavTab.DOWNLOADS -> tabDownloads
            NavTab.PROFILE -> tabProfile
        }
        return res(R.string.profile_open_tab, name)
    }

    override val appearanceTitle: String get() = res(R.string.profile_cat_appearance)
    override val readerTitle: String get() = res(R.string.profile_cat_reader)
    override val libraryTitle: String get() = res(R.string.profile_cat_library)
    override val cultivationTitle: String get() = res(R.string.profile_cat_cultivation)
    override val downloadsTitle: String get() = res(R.string.profile_cat_downloads)
    override val backupTitle: String get() = res(R.string.profile_cat_backup)
    override val aboutTitle: String get() = res(R.string.profile_cat_about)
    override val aboutSummary: String get() = res(R.string.profile_cat_about_summary)

    override val matchSystemTitle: String get() = res(R.string.profile_row_match_system)
    override val matchSystemSubtitle: String get() = res(R.string.profile_row_match_system_sub)
    override val themeTitle: String get() = res(R.string.profile_row_theme)
    override val accentTitle: String get() = res(R.string.profile_row_accent)
    override val slot2Title: String get() = res(R.string.profile_row_slot2)
    override val slot3Title: String get() = res(R.string.profile_row_slot3)

    override val readerPreviewTitle: String get() = res(R.string.profile_row_reader_preview)
    override val readerPreviewSample: String get() = res(R.string.profile_reader_sample)
    override val readerFontTitle: String get() = res(R.string.profile_row_reader_font)
    override val readerSizeTitle: String get() = res(R.string.profile_row_reader_size)
    override val readerSpacingTitle: String get() = res(R.string.profile_row_reader_spacing)
    override val readerJustifyTitle: String get() = res(R.string.profile_row_reader_justify)
    override val readerModeTitle: String get() = res(R.string.profile_row_reader_mode)
    override val keepScreenOnTitle: String get() = res(R.string.profile_row_keep_screen_on)
    override val volumeKeysTitle: String get() = res(R.string.profile_row_volume_keys)

    override val updateOnLaunchTitle: String get() = res(R.string.profile_row_update_on_launch)
    override val unreadBadgesTitle: String get() = res(R.string.profile_row_unread_badges)
    override val defaultShelfTitle: String get() = res(R.string.profile_row_default_shelf)

    override val qiRewardsTitle: String get() = res(R.string.profile_row_qi_rewards)
    override val breakthroughFxTitle: String get() = res(R.string.profile_row_breakthrough_fx)
    override val hapticsTitle: String get() = res(R.string.profile_row_haptics)
    override val reduceMotionTitle: String get() = res(R.string.profile_row_reduce_motion)
    override val dailyGoalTitle: String get() = res(R.string.profile_row_daily_goal)
    override val reminderTitle: String get() = res(R.string.profile_row_reminder)
    override val reminderTimeTitle: String get() = res(R.string.profile_row_reminder_time)

    override val autoDownloadTitle: String get() = res(R.string.profile_row_auto_download)
    override val deleteAfterReadingTitle: String get() = res(R.string.profile_row_delete_after_reading)
    override val wifiOnlyTitle: String get() = res(R.string.profile_row_wifi_only)
    override val throttlingTitle: String get() = res(R.string.profile_row_throttling)
    override val throttleDelayTitle: String get() = res(R.string.profile_row_throttle_delay)
    override val downloadFolderTitle: String get() = res(R.string.profile_row_download_folder)
    override val internalStorage: String get() = res(R.string.profile_internal_storage)
    override val storageTitle: String get() = res(R.string.profile_row_storage)
    override val clearCacheTitle: String get() = res(R.string.profile_row_clear_cache)

    override val createBackupTitle: String get() = res(R.string.profile_row_create_backup)
    override val restoreBackupTitle: String get() = res(R.string.profile_row_restore_backup)
    override val autoBackupTitle: String get() = res(R.string.profile_row_auto_backup)
    override val lastBackupNever: String get() = res(R.string.profile_last_backup_never)
    override fun lastBackupSubtitle(lastBackupAtMillis: Long): String {
        if (lastBackupAtMillis <= 0L) return lastBackupNever
        val fmt = SimpleDateFormat("MMM d, yyyy, HH:mm", Locale.getDefault())
        return res(R.string.profile_last_backup_fmt, fmt.format(Date(lastBackupAtMillis)))
    }
    override val restoreBackupSubtitle: String get() = res(R.string.profile_restore_backup_sub)
    override val backupCatLibrary: String get() = res(R.string.profile_backup_cat_library)
    override val backupCatLibrarySub: String get() = res(R.string.profile_backup_cat_library_sub)
    override val backupCatBookmarks: String get() = res(R.string.profile_backup_cat_bookmarks)
    override val backupCatBookmarksSub: String get() = res(R.string.profile_backup_cat_bookmarks_sub)
    override val backupCatCovers: String get() = res(R.string.profile_backup_cat_covers)
    override val backupCatCoversSub: String get() = res(R.string.profile_backup_cat_covers_sub)
    override val backupCatEpubs: String get() = res(R.string.profile_backup_cat_epubs)
    override val backupCatEpubsSub: String get() = res(R.string.profile_backup_cat_epubs_sub)
    override val backupCatStats: String get() = res(R.string.profile_backup_cat_stats)
    override val backupCatStatsSub: String get() = res(R.string.profile_backup_cat_stats_sub)
    override val backupCatTerms: String get() = res(R.string.profile_backup_cat_terms)
    override val backupCatTermsSub: String get() = res(R.string.profile_backup_cat_terms_sub)
    override val backupCatSources: String get() = res(R.string.profile_backup_cat_sources)
    override val backupCatSourcesSub: String get() = res(R.string.profile_backup_cat_sources_sub)
    override val backupCatSettings: String get() = res(R.string.profile_backup_cat_settings)
    override val backupCatSettingsSub: String get() = res(R.string.profile_backup_cat_settings_sub)

    override val checkUpdatesTitle: String get() = res(R.string.profile_row_check_updates)
    override val licencesTitle: String get() = res(R.string.profile_row_licences)
    override val privacyTitle: String get() = res(R.string.profile_row_privacy)
    override val resetAllTitle: String get() = res(R.string.profile_row_reset_all)

    override val clearCacheDialogTitle: String get() = res(R.string.profile_clear_cache_dialog_title)
    override fun clearCacheDialogMessage(formattedSize: String): String =
        res(R.string.profile_clear_cache_dialog_msg, formattedSize)
    override val clearCacheConfirmLabel: String get() = res(R.string.profile_clear_cache_confirm)
    override val resetAllDialogTitle: String get() = res(R.string.profile_reset_dialog_title)
    override val resetAllDialogMessage: String get() = res(R.string.profile_reset_dialog_msg)
    override val resetAllConfirmLabel: String get() = res(R.string.profile_reset_confirm)
    override fun freedMessage(formattedSize: String): String = res(R.string.profile_msg_freed, formattedSize)
    override val resetSuccessMessage: String get() = res(R.string.profile_msg_reset_done)
    override fun downloadFolderUpdated(name: String): String = res(R.string.profile_msg_folder_updated, name)
    override val downloadFolderError: String get() = res(R.string.profile_msg_folder_error)
}

fun reduceSetting(settings: AppSettings, key: String, value: Any): AppSettings {
    return when (key) {
        SettingKeys.MATCH_SYSTEM -> {
            val checked = value as? Boolean ?: (value.toString().toBooleanStrictOrNull() ?: false)
            if (checked) {
                settings.copy(theme = settings.theme.copy(themeMode = AppThemeMode.SYSTEM))
            } else {
                val restored = if (settings.theme.themeMode == AppThemeMode.SYSTEM) {
                    AppThemeMode.DARK
                } else {
                    settings.theme.themeMode
                }
                settings.copy(theme = settings.theme.copy(themeMode = restored))
            }
        }
        SettingKeys.THEME -> {
            val mode = when (value) {
                is AppThemeMode -> value
                else -> when (value.toString().lowercase()) {
                    "ink" -> AppThemeMode.DARK
                    "black" -> AppThemeMode.AMOLED
                    "paper" -> AppThemeMode.SEPIA
                    "white" -> AppThemeMode.LIGHT
                    else -> AppThemeMode.entries.firstOrNull { it.name.equals(value.toString(), ignoreCase = true) }
                        ?: AppThemeMode.DARK
                }
            }
            settings.copy(theme = settings.theme.copy(themeMode = mode))
        }
        SettingKeys.ACCENT -> {
            val raw = value.toString().trim()
            val hex = when (raw.lowercase()) {
                "jade", "#7cf2c3" -> "#7cf2c3"
                "gold", "#f3cb83" -> "#f3cb83"
                "rose", "#ff9ec4" -> "#ff9ec4"
                "azure", "#8fd0ff" -> "#8fd0ff"
                else -> raw
            }
            settings.copy(theme = settings.theme.copy(accentColorHex = hex))
        }
        SettingKeys.BAR_SLOT_2 -> {
            val targetTab = if (value is NavTab) value else NavTab.valueOf(value.toString())
            val tabs = SettingsRepository.sanitizeBottomBarTabs(settings.bottomBar.tabs).toMutableList()
            val slot2 = tabs[1]
            val slot3 = tabs[2]
            if (slot3 == targetTab) {
                tabs[1] = targetTab
                tabs[2] = slot2
            } else {
                tabs[1] = targetTab
            }
            settings.copy(bottomBar = settings.bottomBar.copy(tabs = tabs))
        }
        SettingKeys.BAR_SLOT_3 -> {
            val targetTab = if (value is NavTab) value else NavTab.valueOf(value.toString())
            val tabs = SettingsRepository.sanitizeBottomBarTabs(settings.bottomBar.tabs).toMutableList()
            val slot2 = tabs[1]
            val slot3 = tabs[2]
            if (slot2 == targetTab) {
                tabs[2] = targetTab
                tabs[1] = slot3
            } else {
                tabs[2] = targetTab
            }
            settings.copy(bottomBar = settings.bottomBar.copy(tabs = tabs))
        }
        SettingKeys.READER_FONT -> {
            val font = if (value is FontType) value else FontType.valueOf(value.toString())
            settings.copy(reader = settings.reader.copy(fontType = font))
        }
        SettingKeys.READER_SIZE -> {
            val size = (value as? Number)?.toInt() ?: value.toString().toIntOrNull() ?: settings.reader.fontSizeSp
            settings.copy(reader = settings.reader.copy(fontSizeSp = size))
        }
        SettingKeys.READER_SPACING -> {
            val spacing = (value as? Number)?.toFloat()
                ?: value.toString().toFloatOrNull()
                ?: settings.reader.lineSpacingMultiplier
            settings.copy(reader = settings.reader.copy(lineSpacingMultiplier = spacing))
        }
        SettingKeys.READER_JUSTIFY -> {
            val justified = value as? Boolean ?: (value.toString().toBooleanStrictOrNull() ?: false)
            val align = if (justified) ReaderTextAlign.JUSTIFIED else ReaderTextAlign.LEFT
            settings.copy(reader = settings.reader.copy(textAlign = align))
        }
        SettingKeys.READER_MODE -> {
            val mode = if (value is ReadingMode) value else ReadingMode.valueOf(value.toString())
            settings.copy(reader = settings.reader.copy(readingMode = mode))
        }
        SettingKeys.KEEP_SCREEN_ON -> {
            val checked = value as? Boolean ?: (value.toString().toBooleanStrictOrNull() ?: false)
            settings.copy(reader = settings.reader.copy(keepScreenOn = checked))
        }
        SettingKeys.VOLUME_KEYS -> {
            val checked = value as? Boolean ?: (value.toString().toBooleanStrictOrNull() ?: false)
            settings.copy(reader = settings.reader.copy(volumeKeysScroll = checked))
        }
        SettingKeys.UPDATE_ON_LAUNCH -> {
            val checked = value as? Boolean ?: (value.toString().toBooleanStrictOrNull() ?: false)
            settings.copy(library = settings.library.copy(updateOnLaunch = checked))
        }
        SettingKeys.UNREAD_BADGES -> {
            val checked = value as? Boolean ?: (value.toString().toBooleanStrictOrNull() ?: false)
            settings.copy(library = settings.library.copy(showUnreadBadges = checked))
        }
        SettingKeys.DEFAULT_SHELF -> {
            val shelf = if (value is DefaultShelf) value else DefaultShelf.valueOf(value.toString())
            settings.copy(library = settings.library.copy(defaultShelf = shelf))
        }
        SettingKeys.QI_REWARDS -> {
            val checked = value as? Boolean ?: (value.toString().toBooleanStrictOrNull() ?: false)
            settings.copy(cultivation = settings.cultivation.copy(qiRewards = checked))
        }
        SettingKeys.BREAKTHROUGH_FX -> {
            val checked = value as? Boolean ?: (value.toString().toBooleanStrictOrNull() ?: false)
            settings.copy(cultivation = settings.cultivation.copy(breakthroughEffects = checked))
        }
        SettingKeys.HAPTICS -> {
            val checked = value as? Boolean ?: (value.toString().toBooleanStrictOrNull() ?: false)
            settings.copy(cultivation = settings.cultivation.copy(haptics = checked))
        }
        SettingKeys.REDUCE_MOTION -> {
            val checked = value as? Boolean ?: (value.toString().toBooleanStrictOrNull() ?: false)
            settings.copy(cultivation = settings.cultivation.copy(reduceMotion = checked))
        }
        SettingKeys.DAILY_GOAL -> {
            val goal = (value as? Number)?.toInt() ?: value.toString().toIntOrNull() ?: settings.cultivation.dailyGoalMinutes
            settings.copy(cultivation = settings.cultivation.copy(dailyGoalMinutes = goal))
        }
        SettingKeys.REMINDER -> {
            val checked = value as? Boolean ?: (value.toString().toBooleanStrictOrNull() ?: false)
            settings.copy(cultivation = settings.cultivation.copy(reminderEnabled = checked))
        }
        SettingKeys.REMINDER_TIME -> {
            val minutes = (value as? Number)?.toInt()
                ?: value.toString().toIntOrNull()
                ?: settings.cultivation.reminderMinutesOfDay
            settings.copy(cultivation = settings.cultivation.copy(reminderMinutesOfDay = minutes))
        }
        SettingKeys.AUTO_DOWNLOAD -> {
            val ahead = (value as? Number)?.toInt() ?: value.toString().toIntOrNull() ?: settings.downloads.autoDownloadAhead
            settings.copy(downloads = settings.downloads.copy(autoDownloadAhead = ahead))
        }
        SettingKeys.DELETE_AFTER_READING -> {
            val checked = value as? Boolean ?: (value.toString().toBooleanStrictOrNull() ?: false)
            settings.copy(downloads = settings.downloads.copy(deleteAfterReading = checked))
        }
        SettingKeys.WIFI_ONLY -> {
            val checked = value as? Boolean ?: (value.toString().toBooleanStrictOrNull() ?: false)
            settings.copy(downloads = settings.downloads.copy(wifiOnly = checked, allowMobileData = !checked))
        }
        SettingKeys.THROTTLING -> {
            val checked = value as? Boolean ?: (value.toString().toBooleanStrictOrNull() ?: false)
            settings.copy(downloads = settings.downloads.copy(throttlingEnabled = checked))
        }
        SettingKeys.THROTTLE_DELAY -> {
            val delay = (value as? Number)?.toFloat()
                ?: value.toString().toFloatOrNull()
                ?: settings.downloads.throttleDelaySeconds
            settings.copy(downloads = settings.downloads.copy(throttleDelaySeconds = delay))
        }
        SettingKeys.AUTO_BACKUP -> {
            val backup = if (value is AutoBackup) value else AutoBackup.valueOf(value.toString())
            settings.copy(backup = settings.backup.copy(autoBackup = backup))
        }
        else -> settings
    }
}

fun buildCategories(
    settings: AppSettings,
    storage: StorageSizes,
    query: String,
    hiddenTabs: List<NavTab>,
    text: ProfileText
): List<SettingsCategory> {
    val sanitizedTabs = SettingsRepository.sanitizeBottomBarTabs(settings.bottomBar.tabs)
    val slot2Tab = sanitizedTabs[1]
    val slot3Tab = sanitizedTabs[2]

    val slotOptions = listOf(
        ChoiceOption(NavTab.DISCOVER.name, text.tabDiscover),
        ChoiceOption(NavTab.CULTIVATE.name, text.tabCultivate),
        ChoiceOption(NavTab.DOWNLOADS.name, text.tabDownloads)
    )

    val themeOptions = listOf(
        SwatchOption("ink", "Ink", 0xFF0A1214L, 0xFF7CF2C3L),
        SwatchOption("black", "Black", 0xFF000000L, 0xFF7CF2C3L),
        SwatchOption("paper", "Paper", 0xFFF6F3ECL, 0xFFA07A2AL),
        SwatchOption("white", "White", 0xFFFFFFFFL, 0xFF1A6B56L)
    )

    val selectedThemeId = when (settings.theme.themeMode) {
        AppThemeMode.DARK -> "ink"
        AppThemeMode.AMOLED -> "black"
        AppThemeMode.SEPIA -> "paper"
        AppThemeMode.LIGHT -> "white"
        AppThemeMode.SYSTEM -> "ink"
    }

    val accentOptions = listOf(
        SwatchOption("#7cf2c3", "Jade", 0xFF7CF2C3L),
        SwatchOption("#f3cb83", "Gold", 0xFFF3CB83L),
        SwatchOption("#ff9ec4", "Rose", 0xFFFF9EC4L),
        SwatchOption("#8fd0ff", "Azure", 0xFF8FD0FFL)
    )

    val selectedAccentId = accentOptions.firstOrNull {
        it.id.equals(settings.theme.accentColorHex, ignoreCase = true)
    }?.id ?: ""

    val appearanceRows = mutableListOf<SettingRow>(
        ToggleRow(
            key = SettingKeys.MATCH_SYSTEM,
            title = text.matchSystemTitle,
            subtitle = text.matchSystemSubtitle,
            checked = settings.theme.themeMode == AppThemeMode.SYSTEM
        ),
        SwatchRow(
            key = SettingKeys.THEME,
            title = text.themeTitle,
            subtitle = null,
            options = themeOptions,
            selectedId = selectedThemeId,
            enabled = settings.theme.themeMode != AppThemeMode.SYSTEM
        ),
        SwatchRow(
            key = SettingKeys.ACCENT,
            title = text.accentTitle,
            subtitle = null,
            options = accentOptions,
            selectedId = selectedAccentId
        ),
        SegmentedRow(
            key = SettingKeys.BAR_SLOT_2,
            title = text.slot2Title,
            subtitle = text.slot2Subtitle,
            options = slotOptions,
            selectedId = slot2Tab.name
        ),
        SegmentedRow(
            key = SettingKeys.BAR_SLOT_3,
            title = text.slot3Title,
            subtitle = text.slot3Subtitle,
            options = slotOptions,
            selectedId = slot3Tab.name
        )
    )

    hiddenTabs.forEach { tab ->
        appearanceRows.add(
            ActionRow(
                key = SettingKeys.OPEN_TAB_PREFIX + tab.name,
                title = text.openTab(tab),
                subtitle = null
            )
        )
    }

    val appearanceCat = SettingsCategory(
        id = SettingsCategoryIds.APPEARANCE,
        group = SettingsGroup.GENERAL,
        title = text.appearanceTitle,
        summary = text.appearanceSummary(settings),
        rows = appearanceRows
    )

    val readerCat = SettingsCategory(
        id = SettingsCategoryIds.READER,
        group = SettingsGroup.GENERAL,
        title = text.readerTitle,
        summary = text.readerSummary(settings),
        rows = listOf(
            TextPreviewRow(
                key = SettingKeys.READER_PREVIEW,
                title = text.readerPreviewTitle,
                subtitle = null,
                sample = text.readerPreviewSample,
                fontSizeSp = settings.reader.fontSizeSp,
                lineSpacing = settings.reader.lineSpacingMultiplier,
                justified = settings.reader.textAlign == ReaderTextAlign.JUSTIFIED
            ),
            SegmentedRow(
                key = SettingKeys.READER_FONT,
                title = text.readerFontTitle,
                subtitle = null,
                options = FontType.entries.map { ChoiceOption(it.name, it.displayName) },
                selectedId = settings.reader.fontType.name
            ),
            SliderRow(
                key = SettingKeys.READER_SIZE,
                title = text.readerSizeTitle,
                subtitle = null,
                value = settings.reader.fontSizeSp.toFloat(),
                min = 15f,
                max = 28f,
                step = 1f,
                valueLabel = "${settings.reader.fontSizeSp} sp"
            ),
            SliderRow(
                key = SettingKeys.READER_SPACING,
                title = text.readerSpacingTitle,
                subtitle = null,
                value = settings.reader.lineSpacingMultiplier,
                min = 1.4f,
                max = 2.2f,
                step = 0.05f,
                valueLabel = text.formatSpacing(settings.reader.lineSpacingMultiplier)
            ),
            ToggleRow(
                key = SettingKeys.READER_JUSTIFY,
                title = text.readerJustifyTitle,
                subtitle = null,
                checked = settings.reader.textAlign == ReaderTextAlign.JUSTIFIED
            ),
            SegmentedRow(
                key = SettingKeys.READER_MODE,
                title = text.readerModeTitle,
                subtitle = if (!ProfileFeatures.PAGED_READER) text.comingSoon else null,
                options = listOf(
                    ChoiceOption(ReadingMode.CONTINUOUS.name, "Scroll"),
                    ChoiceOption(ReadingMode.PAGINATED.name, "Pages")
                ),
                selectedId = settings.reader.readingMode.name,
                enabled = ProfileFeatures.PAGED_READER
            ),
            ToggleRow(
                key = SettingKeys.KEEP_SCREEN_ON,
                title = text.keepScreenOnTitle,
                subtitle = null,
                checked = settings.reader.keepScreenOn
            ),
            ToggleRow(
                key = SettingKeys.VOLUME_KEYS,
                title = text.volumeKeysTitle,
                subtitle = if (!ProfileFeatures.VOLUME_KEYS) text.comingSoon else null,
                checked = settings.reader.volumeKeysScroll,
                enabled = ProfileFeatures.VOLUME_KEYS
            )
        )
    )

    val libraryCat = SettingsCategory(
        id = SettingsCategoryIds.LIBRARY,
        group = SettingsGroup.GENERAL,
        title = text.libraryTitle,
        summary = text.librarySummary(settings),
        rows = listOf(
            ToggleRow(
                key = SettingKeys.UPDATE_ON_LAUNCH,
                title = text.updateOnLaunchTitle,
                subtitle = if (!ProfileFeatures.UPDATE_ON_LAUNCH) text.comingSoon else null,
                checked = settings.library.updateOnLaunch,
                enabled = ProfileFeatures.UPDATE_ON_LAUNCH
            ),
            ToggleRow(
                key = SettingKeys.UNREAD_BADGES,
                title = text.unreadBadgesTitle,
                subtitle = if (!ProfileFeatures.UNREAD_BADGES) text.comingSoon else null,
                checked = settings.library.showUnreadBadges,
                enabled = ProfileFeatures.UNREAD_BADGES
            ),
            SegmentedRow(
                key = SettingKeys.DEFAULT_SHELF,
                title = text.defaultShelfTitle,
                subtitle = if (!ProfileFeatures.DEFAULT_SHELF) text.comingSoon else null,
                options = DefaultShelf.entries.map { ChoiceOption(it.name, it.displayName) },
                selectedId = settings.library.defaultShelf.name,
                enabled = ProfileFeatures.DEFAULT_SHELF
            )
        )
    )

    val cultivationCat = SettingsCategory(
        id = SettingsCategoryIds.CULTIVATION,
        group = SettingsGroup.READING_LIFE,
        title = text.cultivationTitle,
        summary = text.cultivationSummary(settings),
        rows = listOf(
            ToggleRow(
                key = SettingKeys.QI_REWARDS,
                title = text.qiRewardsTitle,
                subtitle = if (!ProfileFeatures.CULTIVATION) text.comingSoon else null,
                checked = settings.cultivation.qiRewards,
                enabled = ProfileFeatures.CULTIVATION
            ),
            ToggleRow(
                key = SettingKeys.BREAKTHROUGH_FX,
                title = text.breakthroughFxTitle,
                subtitle = if (!ProfileFeatures.CULTIVATION) text.comingSoon else null,
                checked = settings.cultivation.breakthroughEffects,
                enabled = ProfileFeatures.CULTIVATION
            ),
            ToggleRow(
                key = SettingKeys.HAPTICS,
                title = text.hapticsTitle,
                subtitle = if (!ProfileFeatures.CULTIVATION) text.comingSoon else null,
                checked = settings.cultivation.haptics,
                enabled = ProfileFeatures.CULTIVATION
            ),
            ToggleRow(
                key = SettingKeys.REDUCE_MOTION,
                title = text.reduceMotionTitle,
                subtitle = if (!ProfileFeatures.CULTIVATION) text.comingSoon else null,
                checked = settings.cultivation.reduceMotion,
                enabled = ProfileFeatures.CULTIVATION
            ),
            ChipsRow(
                key = SettingKeys.DAILY_GOAL,
                title = text.dailyGoalTitle,
                subtitle = if (!ProfileFeatures.CULTIVATION) text.comingSoon else null,
                options = listOf(5, 10, 20, 30, 60).map { ChoiceOption(it.toString(), "$it min") },
                selectedId = settings.cultivation.dailyGoalMinutes.toString(),
                enabled = ProfileFeatures.CULTIVATION
            ),
            ToggleRow(
                key = SettingKeys.REMINDER,
                title = text.reminderTitle,
                subtitle = if (!ProfileFeatures.DAILY_REMINDER) text.comingSoon else null,
                checked = settings.cultivation.reminderEnabled,
                enabled = ProfileFeatures.DAILY_REMINDER
            ),
            TimeRow(
                key = SettingKeys.REMINDER_TIME,
                title = text.reminderTimeTitle,
                subtitle = if (!ProfileFeatures.DAILY_REMINDER) text.comingSoon else null,
                minutesOfDay = settings.cultivation.reminderMinutesOfDay,
                enabled = ProfileFeatures.DAILY_REMINDER
            )
        )
    )

    val downloadsCat = SettingsCategory(
        id = SettingsCategoryIds.DOWNLOADS,
        group = SettingsGroup.READING_LIFE,
        title = text.downloadsTitle,
        summary = text.downloadsSummary(storage),
        rows = listOf(
            ChipsRow(
                key = SettingKeys.AUTO_DOWNLOAD,
                title = text.autoDownloadTitle,
                subtitle = null,
                options = listOf(0 to "Off", 1 to "1", 3 to "3", 5 to "5", 10 to "10").map {
                    ChoiceOption(it.first.toString(), it.second)
                },
                selectedId = settings.downloads.autoDownloadAhead.toString()
            ),
            ToggleRow(
                key = SettingKeys.DELETE_AFTER_READING,
                title = text.deleteAfterReadingTitle,
                subtitle = if (!ProfileFeatures.DELETE_AFTER_READING) text.comingSoon else null,
                checked = settings.downloads.deleteAfterReading,
                enabled = ProfileFeatures.DELETE_AFTER_READING
            ),
            ToggleRow(
                key = SettingKeys.WIFI_ONLY,
                title = text.wifiOnlyTitle,
                subtitle = null,
                checked = settings.downloads.wifiOnly
            ),
            ToggleRow(
                key = SettingKeys.THROTTLING,
                title = text.throttlingTitle,
                subtitle = null,
                checked = settings.downloads.throttlingEnabled
            ),
            ChipsRow(
                key = SettingKeys.THROTTLE_DELAY,
                title = text.throttleDelayTitle,
                subtitle = null,
                options = listOf(1f to "1s", 2f to "2s", 3f to "3s", 5f to "5s").map {
                    ChoiceOption(it.first.toInt().toString(), it.second)
                },
                selectedId = settings.downloads.throttleDelaySeconds.toInt().toString(),
                enabled = settings.downloads.throttlingEnabled
            ),
            ActionRow(
                key = SettingKeys.DOWNLOAD_FOLDER,
                title = text.downloadFolderTitle,
                subtitle = null,
                picker = PickerKind.FOLDER,
                trailing = settings.downloads.customDownloadLocationDisplayName ?: text.internalStorage
            ),
            StorageRow(
                key = SettingKeys.STORAGE,
                title = text.storageTitle,
                subtitle = null,
                chaptersBytes = storage.chaptersBytes,
                coversBytes = storage.coversBytes,
                cacheBytes = storage.cacheBytes
            ),
            ActionRow(
                key = SettingKeys.CLEAR_CACHE,
                title = text.clearCacheTitle,
                subtitle = null,
                trailing = formatBytes(storage.cacheBytes)
            )
        )
    )

    val backupCat = SettingsCategory(
        id = SettingsCategoryIds.BACKUP,
        group = SettingsGroup.DATA_INFO,
        title = text.backupTitle,
        summary = text.backupSummary(settings),
        rows = listOf(
            ActionRow(
                key = SettingKeys.CREATE_BACKUP,
                title = text.createBackupTitle,
                subtitle = if (!ProfileFeatures.BACKUP_RESTORE) text.comingSoon else text.lastBackupSubtitle(settings.backup.lastBackupAtMillis),
                enabled = ProfileFeatures.BACKUP_RESTORE
            ),
            ActionRow(
                key = SettingKeys.RESTORE_BACKUP,
                title = text.restoreBackupTitle,
                subtitle = if (!ProfileFeatures.BACKUP_RESTORE) text.comingSoon else text.restoreBackupSubtitle,
                picker = PickerKind.BACKUP_FILE,
                enabled = ProfileFeatures.BACKUP_RESTORE
            ),
            SegmentedRow(
                key = SettingKeys.AUTO_BACKUP,
                title = text.autoBackupTitle,
                subtitle = if (!ProfileFeatures.AUTO_BACKUP) text.comingSoon else null,
                options = AutoBackup.entries.map { ChoiceOption(it.name, it.displayName) },
                selectedId = settings.backup.autoBackup.name,
                enabled = ProfileFeatures.AUTO_BACKUP
            )
        )
    )

    val aboutCat = SettingsCategory(
        id = SettingsCategoryIds.ABOUT,
        group = SettingsGroup.DATA_INFO,
        title = text.aboutTitle,
        summary = text.aboutSummary,
        rows = listOf(
            ActionRow(
                key = SettingKeys.CHECK_UPDATES,
                title = text.checkUpdatesTitle,
                subtitle = if (!ProfileFeatures.CHECK_UPDATES) text.comingSoon else null,
                enabled = ProfileFeatures.CHECK_UPDATES
            ),
            ActionRow(
                key = SettingKeys.LICENCES,
                title = text.licencesTitle,
                subtitle = if (!ProfileFeatures.LEGAL_PAGES) text.comingSoon else null,
                enabled = ProfileFeatures.LEGAL_PAGES
            ),
            ActionRow(
                key = SettingKeys.PRIVACY,
                title = text.privacyTitle,
                subtitle = if (!ProfileFeatures.LEGAL_PAGES) text.comingSoon else null,
                enabled = ProfileFeatures.LEGAL_PAGES
            ),
            ActionRow(
                key = SettingKeys.RESET_ALL,
                title = text.resetAllTitle,
                subtitle = null,
                isDanger = true
            )
        )
    )

    val allCategories = listOf(
        appearanceCat,
        readerCat,
        libraryCat,
        cultivationCat,
        downloadsCat,
        backupCat,
        aboutCat
    )

    if (query.isBlank()) {
        return allCategories
    }

    return allCategories.mapNotNull { cat ->
        val filtered = cat.rows.filter { row ->
            row.title.contains(query, ignoreCase = true) ||
                row.subtitle?.contains(query, ignoreCase = true) == true
        }
        if (filtered.isNotEmpty()) {
            cat.copy(rows = filtered)
        } else {
            null
        }
    }
}
