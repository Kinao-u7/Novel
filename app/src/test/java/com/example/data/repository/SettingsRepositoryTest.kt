package com.example.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.AppSettings
import com.example.data.model.AppThemeMode
import com.example.data.model.AutoBackup
import com.example.data.model.BackupSettings
import com.example.data.model.CultivationSettings
import com.example.data.model.DefaultShelf
import com.example.data.model.DiscoverSettings
import com.example.data.model.DownloadSettings
import com.example.data.model.LibrarySettings
import com.example.data.model.LibrarySort
import com.example.data.model.LibraryViewMode
import com.example.data.model.MarginType
import com.example.data.model.NavTab
import com.example.data.model.ProfileSettings
import com.example.data.model.ReaderSettings
import com.example.data.model.ReaderTextAlign
import com.example.data.model.ReaderTheme
import com.example.data.model.ThemeSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsRepositoryTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
        prettyPrint = true
    }

    @Test
    fun defaultSettings_matchArchitecturalSpecifications() {
        val defaults = AppSettings()

        // Reader defaults: Sepia, Bookish Serif, 18sp, Justified, 20dp margin, volumeKeysScroll false
        assertEquals(ReaderTheme.SEPIA, defaults.reader.theme)
        assertEquals(18, defaults.reader.fontSizeSp)
        assertEquals(ReaderTextAlign.JUSTIFIED, defaults.reader.textAlign)
        assertTrue(defaults.reader.isJustified)
        assertEquals(20, defaults.reader.horizontalMarginDp)
        assertEquals(MarginType.NORMAL, defaults.reader.marginType)
        assertFalse(defaults.reader.volumeKeysScroll)

        // Theme defaults: Dark mode, Warm Gold / Amber accent (#DEB887), Dynamic color disabled
        assertEquals(AppThemeMode.DARK, defaults.theme.themeMode)
        assertEquals("#DEB887", defaults.theme.accentColorHex)
        assertFalse(defaults.theme.useDynamicColor)

        // Download defaults: WiFi only true, 3 simultaneous downloads, autoDownloadAhead 3, deleteAfterReading false
        assertTrue(defaults.downloads.wifiOnly)
        assertFalse(defaults.downloads.allowMobileData)
        assertEquals(3, defaults.downloads.simultaneousDownloads)
        assertEquals(3, defaults.downloads.autoDownloadAhead)
        assertFalse(defaults.downloads.deleteAfterReading)

        // Notification defaults: Notify on download and novel update
        assertTrue(defaults.notifications.notifyOnDownload)
        assertTrue(defaults.notifications.notifyOnNovelUpdate)

        // Library defaults: updateOnLaunch true, showUnreadBadges true, defaultShelf ALL, viewMode GRID, sort RECENTLY_READ
        assertTrue(defaults.library.updateOnLaunch)
        assertTrue(defaults.library.showUnreadBadges)
        assertEquals(DefaultShelf.ALL, defaults.library.defaultShelf)
        assertEquals(LibraryViewMode.GRID, defaults.library.viewMode)
        assertEquals(LibrarySort.RECENTLY_READ, defaults.library.sort)
        assertTrue(defaults.isLibraryGridView)

        // Cultivation defaults
        assertTrue(defaults.cultivation.qiRewards)
        assertTrue(defaults.cultivation.breakthroughEffects)
        assertTrue(defaults.cultivation.haptics)
        assertFalse(defaults.cultivation.reduceMotion)
        assertEquals(20, defaults.cultivation.dailyGoalMinutes)
        assertTrue(defaults.cultivation.reminderEnabled)
        assertEquals(1230, defaults.cultivation.reminderMinutesOfDay)

        // Backup defaults
        assertEquals(AutoBackup.WEEKLY, defaults.backup.autoBackup)
        assertEquals(0L, defaults.backup.lastBackupAtMillis)

        // Profile defaults
        assertEquals("Reader", defaults.profile.displayName)

        // Bottom bar defaults
        assertEquals(
            listOf(NavTab.LIBRARY, NavTab.DISCOVER, NavTab.CULTIVATE, NavTab.PROFILE),
            defaults.bottomBar.tabs
        )

        // Discover defaults: empty pinnedSourceIds, empty enabledLanguages
        assertEquals(emptyList<String>(), defaults.discover.pinnedSourceIds)
        assertEquals(emptyList<String>(), defaults.discover.enabledLanguages)
    }

    @Test
    fun duplicateFields_isJustified_correctlyMergedWithTextAlign() {
        val justifiedSettings = ReaderSettings(textAlign = ReaderTextAlign.JUSTIFIED)
        assertTrue(justifiedSettings.isJustified)

        val leftSettings = ReaderSettings(textAlign = ReaderTextAlign.LEFT)
        assertFalse(leftSettings.isJustified)

        // Using copy with isJustified = false should update textAlign to LEFT
        val updated = justifiedSettings.copy(isJustified = false)
        assertEquals(ReaderTextAlign.LEFT, updated.textAlign)
        assertFalse(updated.isJustified)

        // Using copy with isJustified = true should update textAlign to JUSTIFIED
        val updatedBack = updated.copy(isJustified = true)
        assertEquals(ReaderTextAlign.JUSTIFIED, updatedBack.textAlign)
        assertTrue(updatedBack.isJustified)
    }

    @Test
    fun duplicateFields_marginType_correctlyMergedWithHorizontalMarginDp() {
        val normalSettings = ReaderSettings(horizontalMarginDp = 20)
        assertEquals(MarginType.NORMAL, normalSettings.marginType)

        val compactSettings = ReaderSettings(horizontalMarginDp = 12)
        assertEquals(MarginType.COMPACT, compactSettings.marginType)

        val wideSettings = ReaderSettings(horizontalMarginDp = 32)
        assertEquals(MarginType.WIDE, wideSettings.marginType)

        // Using copy with marginType should update horizontalMarginDp
        val modified = normalSettings.copy(marginType = MarginType.WIDE)
        assertEquals(32, modified.horizontalMarginDp)
        assertEquals(MarginType.WIDE, modified.marginType)
    }

    @Test
    fun serialization_wholeObjectRoundtrip() {
        val original = AppSettings(
            reader = ReaderSettings(
                theme = ReaderTheme.WARM,
                fontSizeSp = 22,
                horizontalMarginDp = 28,
                volumeKeysScroll = true
            ),
            theme = ThemeSettings(
                themeMode = AppThemeMode.AMOLED,
                accentColorHex = "#6366F1",
                useDynamicColor = false
            ),
            downloads = DownloadSettings(
                autoDownloadAhead = 5,
                deleteAfterReading = true
            ),
            library = LibrarySettings(
                updateOnLaunch = false,
                showUnreadBadges = false,
                defaultShelf = DefaultShelf.READING,
                viewMode = LibraryViewMode.LIST,
                sort = LibrarySort.TITLE
            ),
            cultivation = CultivationSettings(
                qiRewards = false,
                breakthroughEffects = false,
                haptics = false,
                reduceMotion = true,
                dailyGoalMinutes = 45,
                reminderEnabled = false,
                reminderMinutesOfDay = 600
            ),
            backup = BackupSettings(
                autoBackup = AutoBackup.DAILY,
                lastBackupAtMillis = 1700000000000L
            ),
            profile = ProfileSettings(
                displayName = "Daoist Reader"
            )
        )

        val encoded = json.encodeToString(AppSettings.serializer(), original)
        val decoded = json.decodeFromString(AppSettings.serializer(), encoded)

        assertEquals(original, decoded)
        assertEquals(ReaderTheme.WARM, decoded.reader.theme)
        assertEquals(22, decoded.reader.fontSizeSp)
        assertEquals(28, decoded.reader.horizontalMarginDp)
        assertTrue(decoded.reader.volumeKeysScroll)
        assertEquals(AppThemeMode.AMOLED, decoded.theme.themeMode)
        assertEquals("#6366F1", decoded.theme.accentColorHex)
        assertEquals(5, decoded.downloads.autoDownloadAhead)
        assertTrue(decoded.downloads.deleteAfterReading)
        assertEquals(LibraryViewMode.LIST, decoded.library.viewMode)
        assertFalse(decoded.isLibraryGridView)
        assertEquals(LibrarySort.TITLE, decoded.library.sort)
        assertEquals(DefaultShelf.READING, decoded.library.defaultShelf)
        assertFalse(decoded.cultivation.qiRewards)
        assertEquals(45, decoded.cultivation.dailyGoalMinutes)
        assertEquals(AutoBackup.DAILY, decoded.backup.autoBackup)
        assertEquals(1700000000000L, decoded.backup.lastBackupAtMillis)
        assertEquals("Daoist Reader", decoded.profile.displayName)
    }

    @Test
    fun serialization_ignoresUnknownKeysGracefully() {
        val jsonWithUnknownFields = """
            {
                "reader": {
                    "theme": "LIGHT",
                    "fontSizeSp": 20,
                    "unknownFutureField": "some_value"
                },
                "theme": {
                    "accentColorHex": "#EA580C",
                    "someDeprecatedField": 123
                },
                "brandNewTopLevelSetting": true
            }
        """.trimIndent()

        val decoded = json.decodeFromString(AppSettings.serializer(), jsonWithUnknownFields)

        assertEquals(ReaderTheme.LIGHT, decoded.reader.theme)
        assertEquals(20, decoded.reader.fontSizeSp)
        assertEquals("#EA580C", decoded.theme.accentColorHex)
        // Defaults preserved for missing fields
        assertEquals(ReaderTextAlign.JUSTIFIED, decoded.reader.textAlign)
        assertEquals(3, decoded.downloads.simultaneousDownloads)
        assertEquals(3, decoded.downloads.autoDownloadAhead)
        assertEquals(LibraryViewMode.GRID, decoded.library.viewMode)
        assertEquals("Reader", decoded.profile.displayName)
        assertEquals(AutoBackup.WEEKLY, decoded.backup.autoBackup)
    }

    @Test
    fun serialization_legacyJsonWithAutoDownloadNext_loadsWithoutErrorAndUsesDefaults() {
        val legacyJson = """
            {
                "downloads": {
                    "wifiOnly": true,
                    "allowMobileData": false,
                    "simultaneousDownloads": 4,
                    "autoDownloadNext": true
                }
            }
        """.trimIndent()

        val decoded = json.decodeFromString(AppSettings.serializer(), legacyJson)
        val sanitized = SettingsRepository.sanitizeSettings(decoded)

        assertEquals(4, sanitized.downloads.simultaneousDownloads)
        assertEquals(3, sanitized.downloads.autoDownloadAhead)
        assertFalse(sanitized.downloads.deleteAfterReading)
    }

    @Test
    fun resetPreserves_downloadLocation_displayName_andLastBackup() {
        val currentDownloads = DownloadSettings(
            customDownloadLocationUri = "content://com.android.externalstorage.documents/tree/primary%3ANovels",
            customDownloadLocationDisplayName = "Novels",
            simultaneousDownloads = 5,
            autoDownloadAhead = 10
        )
        val currentProfile = ProfileSettings(
            displayName = "Custom Cultivator"
        )
        val currentBackup = BackupSettings(
            autoBackup = AutoBackup.DAILY,
            lastBackupAtMillis = 9876543210L
        )

        // Construct reset AppSettings as resetAllSettings does
        val resetSettings = AppSettings(
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

        // Preserved fields
        assertEquals("content://com.android.externalstorage.documents/tree/primary%3ANovels", resetSettings.downloads.customDownloadLocationUri)
        assertEquals("Novels", resetSettings.downloads.customDownloadLocationDisplayName)
        assertEquals("Custom Cultivator", resetSettings.profile.displayName)
        assertEquals(9876543210L, resetSettings.backup.lastBackupAtMillis)

        // Everything else reset to default
        assertEquals(3, resetSettings.downloads.simultaneousDownloads)
        assertEquals(3, resetSettings.downloads.autoDownloadAhead)
        assertEquals(AutoBackup.WEEKLY, resetSettings.backup.autoBackup)
        assertEquals(ReaderTheme.SEPIA, resetSettings.reader.theme)
        assertEquals(AppThemeMode.DARK, resetSettings.theme.themeMode)
        assertEquals(LibraryViewMode.GRID, resetSettings.library.viewMode)
        assertEquals(20, resetSettings.cultivation.dailyGoalMinutes)
    }

    @Test
    fun coercion_autoDownloadAhead_nearestAllowedValues() {
        assertEquals(0, SettingsRepository.coerceAutoDownloadAhead(-5))
        assertEquals(0, SettingsRepository.coerceAutoDownloadAhead(0))
        assertEquals(1, SettingsRepository.coerceAutoDownloadAhead(1))
        // 2 is equidistant to 1 and 3; minByOrNull chooses 1
        assertEquals(1, SettingsRepository.coerceAutoDownloadAhead(2))
        assertEquals(3, SettingsRepository.coerceAutoDownloadAhead(3))
        // 4 is equidistant to 3 and 5; minByOrNull chooses 3
        assertEquals(3, SettingsRepository.coerceAutoDownloadAhead(4))
        assertEquals(5, SettingsRepository.coerceAutoDownloadAhead(5))
        assertEquals(5, SettingsRepository.coerceAutoDownloadAhead(7))
        assertEquals(10, SettingsRepository.coerceAutoDownloadAhead(8))
        assertEquals(10, SettingsRepository.coerceAutoDownloadAhead(10))
        assertEquals(10, SettingsRepository.coerceAutoDownloadAhead(99))
    }

    @Test
    fun coercion_dailyGoalMinutes_and_reminderMinutesOfDay_sanitized() {
        val invalidSettings = AppSettings(
            downloads = DownloadSettings(autoDownloadAhead = 7),
            cultivation = CultivationSettings(
                dailyGoalMinutes = 200,
                reminderMinutesOfDay = 2500
            )
        )
        val sanitized = SettingsRepository.sanitizeSettings(invalidSettings)
        assertEquals(5, sanitized.downloads.autoDownloadAhead)
        assertEquals(120, sanitized.cultivation.dailyGoalMinutes)
        assertEquals(1439, sanitized.cultivation.reminderMinutesOfDay)

        val underMinSettings = AppSettings(
            cultivation = CultivationSettings(
                dailyGoalMinutes = 2,
                reminderMinutesOfDay = -50
            )
        )
        val sanitizedUnder = SettingsRepository.sanitizeSettings(underMinSettings)
        assertEquals(5, sanitizedUnder.cultivation.dailyGoalMinutes)
        assertEquals(0, sanitizedUnder.cultivation.reminderMinutesOfDay)
    }

    @Test
    fun updateFunctions_coerceAutoDownloadAhead_dailyGoalMinutes_and_reminderMinutesOfDay() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = SettingsRepository.createForTest(context, Dispatchers.IO)

        repo.updateDownloadSettings { it.copy(autoDownloadAhead = 7) }
        assertEquals(5, repo.settings.value.downloads.autoDownloadAhead)

        repo.updateCultivationSettings {
            it.copy(dailyGoalMinutes = 200, reminderMinutesOfDay = 2500)
        }
        assertEquals(120, repo.settings.value.cultivation.dailyGoalMinutes)
        assertEquals(1439, repo.settings.value.cultivation.reminderMinutesOfDay)

        repo.updateCultivationSettings {
            it.copy(dailyGoalMinutes = 2, reminderMinutesOfDay = -50)
        }
        assertEquals(5, repo.settings.value.cultivation.dailyGoalMinutes)
        assertEquals(0, repo.settings.value.cultivation.reminderMinutesOfDay)
    }

    @Test
    fun resetAllSettings_preservesCustomDownloadLocation_displayName_andLastBackup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = SettingsRepository.createForTest(context, Dispatchers.IO)

        repo.updateDownloadSettings {
            it.copy(
                customDownloadLocationUri = "content://saf/tree/test",
                customDownloadLocationDisplayName = "MyFolder",
                simultaneousDownloads = 5
            )
        }
        repo.updateProfileSettings {
            it.copy(displayName = "Daoist Master")
        }
        repo.updateBackupSettings {
            it.copy(
                autoBackup = AutoBackup.DAILY,
                lastBackupAtMillis = 123456789L
            )
        }
        repo.updateReaderSettings {
            it.copy(fontSizeSp = 30)
        }
        repo.updateBottomBarSettings {
            it.copy(tabs = listOf(NavTab.LIBRARY, NavTab.DOWNLOADS, NavTab.CULTIVATE, NavTab.PROFILE))
        }
        repo.updateDiscoverSettings {
            it.copy(pinnedSourceIds = listOf("pinned_1"), enabledLanguages = listOf("English"))
        }

        repo.resetAllSettings()

        val reset = repo.settings.value
        assertEquals("content://saf/tree/test", reset.downloads.customDownloadLocationUri)
        assertEquals("MyFolder", reset.downloads.customDownloadLocationDisplayName)
        assertEquals("Daoist Master", reset.profile.displayName)
        assertEquals(123456789L, reset.backup.lastBackupAtMillis)

        // Other fields reset to default
        assertEquals(3, reset.downloads.simultaneousDownloads)
        assertEquals(18, reset.reader.fontSizeSp)
        assertEquals(AutoBackup.WEEKLY, reset.backup.autoBackup)
        assertEquals(
            listOf(NavTab.LIBRARY, NavTab.DISCOVER, NavTab.CULTIVATE, NavTab.PROFILE),
            reset.bottomBar.tabs
        )
        assertEquals(emptyList<String>(), reset.discover.pinnedSourceIds)
        assertEquals(emptyList<String>(), reset.discover.enabledLanguages)
    }

    @Test
    fun librarySort_enumAndSerialization() {
        val expectedEntries = listOf(
            LibrarySort.RECENTLY_READ,
            LibrarySort.TITLE,
            LibrarySort.AUTHOR,
            LibrarySort.TOTAL_CHAPTERS,
            LibrarySort.RATING
        )
        assertEquals(expectedEntries, LibrarySort.entries)

        for (sort in expectedEntries) {
            val encoded = json.encodeToString(LibrarySort.serializer(), sort)
            val decoded = json.decodeFromString(LibrarySort.serializer(), encoded)
            assertEquals(sort, decoded)
        }
    }

    @Test
    fun discoverSettings_serializationRoundTrip() {
        val original = AppSettings(
            discover = DiscoverSettings(
                pinnedSourceIds = listOf("source-a", "source-b"),
                enabledLanguages = listOf("English", "Japanese")
            )
        )
        val encoded = json.encodeToString(AppSettings.serializer(), original)
        val decoded = json.decodeFromString(AppSettings.serializer(), encoded)

        assertEquals(listOf("source-a", "source-b"), decoded.discover.pinnedSourceIds)
        assertEquals(listOf("English", "Japanese"), decoded.discover.enabledLanguages)
    }

    @Test
    fun discoverSettings_oldJsonWithoutFieldLoadsDefaults() {
        val oldJson = """
            {
                "reader": {
                    "fontSizeSp": 20
                },
                "theme": {
                    "accentColorHex": "#EA580C"
                }
            }
        """.trimIndent()
        val decoded = json.decodeFromString(AppSettings.serializer(), oldJson)
        assertEquals(emptyList<String>(), decoded.discover.pinnedSourceIds)
        assertEquals(emptyList<String>(), decoded.discover.enabledLanguages)
    }

    @Test
    fun discoverSettings_sanitizing_removesDuplicates() {
        val duplicateSettings = AppSettings(
            discover = DiscoverSettings(
                pinnedSourceIds = listOf("src-1", "src-2", "src-1"),
                enabledLanguages = listOf("English", "Chinese", "English")
            )
        )
        val sanitized = SettingsRepository.sanitizeSettings(duplicateSettings)
        assertEquals(listOf("src-1", "src-2"), sanitized.discover.pinnedSourceIds)
        assertEquals(listOf("English", "Chinese"), sanitized.discover.enabledLanguages)
    }

    @Test
    fun discoverSettings_updateFlowsAndPersistence() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = SettingsRepository.createForTest(context, Dispatchers.IO)

        assertEquals(emptyList<String>(), repo.settings.value.discover.pinnedSourceIds)
        assertEquals(emptyList<String>(), repo.settings.value.discover.enabledLanguages)

        repo.updateDiscoverSettings {
            it.copy(
                pinnedSourceIds = listOf("wtrlab", "classics"),
                enabledLanguages = listOf("English")
            )
        }

        // 1. Immediate in-memory check via settings.value.discover
        assertEquals(listOf("wtrlab", "classics"), repo.settings.value.discover.pinnedSourceIds)
        assertEquals(listOf("English"), repo.settings.value.discover.enabledLanguages)

        // 2. Derived flow check waiting with withTimeout
        val derived = withTimeout(2000L) {
            repo.discoverSettings.first { it.pinnedSourceIds == listOf("wtrlab", "classics") }
        }
        assertEquals(listOf("English"), derived.enabledLanguages)

        // 3. Persistence check across repository instances
        repo.flush()
        val secondRepo = SettingsRepository.createForTest(context, Dispatchers.IO)
        assertEquals(listOf("wtrlab", "classics"), secondRepo.settings.value.discover.pinnedSourceIds)
        assertEquals(listOf("English"), secondRepo.settings.value.discover.enabledLanguages)
    }
}
