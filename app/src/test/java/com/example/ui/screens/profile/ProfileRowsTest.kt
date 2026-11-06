package com.example.ui.screens.profile

import com.example.data.model.AppSettings
import com.example.data.model.AppThemeMode
import com.example.data.model.AutoBackup
import com.example.data.model.BottomBarSettings
import com.example.data.model.DefaultShelf
import com.example.data.model.FontType
import com.example.data.model.NavTab
import com.example.data.model.ReaderTextAlign
import com.example.data.model.ReadingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileRowsTest {

    private val text = DefaultProfileText()

    @Test
    fun testReduceSetting_themeMapping() {
        var settings = AppSettings()
        settings = reduceSetting(settings, SettingKeys.THEME, "ink")
        assertEquals(AppThemeMode.DARK, settings.theme.themeMode)

        settings = reduceSetting(settings, SettingKeys.THEME, "black")
        assertEquals(AppThemeMode.AMOLED, settings.theme.themeMode)

        settings = reduceSetting(settings, SettingKeys.THEME, "paper")
        assertEquals(AppThemeMode.SEPIA, settings.theme.themeMode)

        settings = reduceSetting(settings, SettingKeys.THEME, "white")
        assertEquals(AppThemeMode.LIGHT, settings.theme.themeMode)
    }

    @Test
    fun testReduceSetting_matchSystemOnAndOff() {
        var settings = AppSettings()
        settings = reduceSetting(settings, SettingKeys.THEME, "paper")
        assertEquals(AppThemeMode.SEPIA, settings.theme.themeMode)

        settings = reduceSetting(settings, SettingKeys.MATCH_SYSTEM, true)
        assertEquals(AppThemeMode.SYSTEM, settings.theme.themeMode)

        // Turning it off when it was SYSTEM restores default DARK (or non-SYSTEM)
        settings = reduceSetting(settings, SettingKeys.MATCH_SYSTEM, false)
        assertEquals(AppThemeMode.DARK, settings.theme.themeMode)
    }

    @Test
    fun testReduceSetting_accent() {
        var settings = AppSettings()
        settings = reduceSetting(settings, SettingKeys.ACCENT, "jade")
        assertEquals("#7cf2c3", settings.theme.accentColorHex)

        settings = reduceSetting(settings, SettingKeys.ACCENT, "gold")
        assertEquals("#f3cb83", settings.theme.accentColorHex)

        settings = reduceSetting(settings, SettingKeys.ACCENT, "rose")
        assertEquals("#ff9ec4", settings.theme.accentColorHex)

        settings = reduceSetting(settings, SettingKeys.ACCENT, "azure")
        assertEquals("#8fd0ff", settings.theme.accentColorHex)

        // Custom / unknown hex preserved
        settings = reduceSetting(settings, SettingKeys.ACCENT, "#123456")
        assertEquals("#123456", settings.theme.accentColorHex)
    }

    @Test
    fun testReduceSetting_bottomBarSlotSwap() {
        var settings = AppSettings(
            bottomBar = BottomBarSettings(
                tabs = listOf(NavTab.LIBRARY, NavTab.DISCOVER, NavTab.CULTIVATE, NavTab.PROFILE)
            )
        )

        // Slot 2 chooses CULTIVATE (which slot 3 has) -> swap them
        settings = reduceSetting(settings, SettingKeys.BAR_SLOT_2, NavTab.CULTIVATE.name)
        assertEquals(listOf(NavTab.LIBRARY, NavTab.CULTIVATE, NavTab.DISCOVER, NavTab.PROFILE), settings.bottomBar.tabs)

        // Slot 3 chooses CULTIVATE (which slot 2 has) -> swap them back
        settings = reduceSetting(settings, SettingKeys.BAR_SLOT_3, NavTab.CULTIVATE.name)
        assertEquals(listOf(NavTab.LIBRARY, NavTab.DISCOVER, NavTab.CULTIVATE, NavTab.PROFILE), settings.bottomBar.tabs)

        // Slot 2 chooses DOWNLOADS (which neither slot has) -> update slot 2, slot 3 unchanged
        settings = reduceSetting(settings, SettingKeys.BAR_SLOT_2, NavTab.DOWNLOADS.name)
        assertEquals(listOf(NavTab.LIBRARY, NavTab.DOWNLOADS, NavTab.CULTIVATE, NavTab.PROFILE), settings.bottomBar.tabs)
    }

    @Test
    fun testReduceSetting_wifiOnlyConsistency() {
        var settings = AppSettings()
        settings = reduceSetting(settings, SettingKeys.WIFI_ONLY, true)
        assertTrue(settings.downloads.wifiOnly)
        assertFalse(settings.downloads.allowMobileData)

        settings = reduceSetting(settings, SettingKeys.WIFI_ONLY, false)
        assertFalse(settings.downloads.wifiOnly)
        assertTrue(settings.downloads.allowMobileData)
    }

    @Test
    fun testReduceSetting_slidersAndChips() {
        var settings = AppSettings()
        settings = reduceSetting(settings, SettingKeys.READER_SIZE, 22)
        assertEquals(22, settings.reader.fontSizeSp)

        settings = reduceSetting(settings, SettingKeys.READER_SPACING, 1.85f)
        assertEquals(1.85f, settings.reader.lineSpacingMultiplier, 0.001f)

        settings = reduceSetting(settings, SettingKeys.AUTO_DOWNLOAD, 5)
        assertEquals(5, settings.downloads.autoDownloadAhead)

        settings = reduceSetting(settings, SettingKeys.THROTTLE_DELAY, 3.0f)
        assertEquals(3.0f, settings.downloads.throttleDelaySeconds, 0.001f)

        settings = reduceSetting(settings, SettingKeys.DAILY_GOAL, 30)
        assertEquals(30, settings.cultivation.dailyGoalMinutes)

        settings = reduceSetting(settings, SettingKeys.REMINDER_TIME, 1200)
        assertEquals(1200, settings.cultivation.reminderMinutesOfDay)
    }

    @Test
    fun testReduceSetting_toggles() {
        var settings = AppSettings()
        settings = reduceSetting(settings, SettingKeys.KEEP_SCREEN_ON, false)
        assertFalse(settings.reader.keepScreenOn)

        settings = reduceSetting(settings, SettingKeys.READER_JUSTIFY, true)
        assertEquals(ReaderTextAlign.JUSTIFIED, settings.reader.textAlign)

        settings = reduceSetting(settings, SettingKeys.READER_JUSTIFY, false)
        assertEquals(ReaderTextAlign.LEFT, settings.reader.textAlign)

        settings = reduceSetting(settings, SettingKeys.VOLUME_KEYS, true)
        assertTrue(settings.reader.volumeKeysScroll)

        settings = reduceSetting(settings, SettingKeys.UPDATE_ON_LAUNCH, false)
        assertFalse(settings.library.updateOnLaunch)

        settings = reduceSetting(settings, SettingKeys.UNREAD_BADGES, false)
        assertFalse(settings.library.showUnreadBadges)

        settings = reduceSetting(settings, SettingKeys.QI_REWARDS, false)
        assertFalse(settings.cultivation.qiRewards)

        settings = reduceSetting(settings, SettingKeys.BREAKTHROUGH_FX, false)
        assertFalse(settings.cultivation.breakthroughEffects)

        settings = reduceSetting(settings, SettingKeys.HAPTICS, false)
        assertFalse(settings.cultivation.haptics)

        settings = reduceSetting(settings, SettingKeys.REDUCE_MOTION, true)
        assertTrue(settings.cultivation.reduceMotion)

        settings = reduceSetting(settings, SettingKeys.REMINDER, false)
        assertFalse(settings.cultivation.reminderEnabled)

        settings = reduceSetting(settings, SettingKeys.DELETE_AFTER_READING, true)
        assertTrue(settings.downloads.deleteAfterReading)

        settings = reduceSetting(settings, SettingKeys.THROTTLING, false)
        assertFalse(settings.downloads.throttlingEnabled)
    }

    @Test
    fun testReduceSetting_enums() {
        var settings = AppSettings()
        settings = reduceSetting(settings, SettingKeys.READER_FONT, FontType.MONOSPACE.name)
        assertEquals(FontType.MONOSPACE, settings.reader.fontType)

        settings = reduceSetting(settings, SettingKeys.READER_MODE, ReadingMode.PAGINATED.name)
        assertEquals(ReadingMode.PAGINATED, settings.reader.readingMode)

        settings = reduceSetting(settings, SettingKeys.DEFAULT_SHELF, DefaultShelf.SAVED.name)
        assertEquals(DefaultShelf.SAVED, settings.library.defaultShelf)

        settings = reduceSetting(settings, SettingKeys.AUTO_BACKUP, AutoBackup.DAILY.name)
        assertEquals(AutoBackup.DAILY, settings.backup.autoBackup)
    }

    @Test
    fun testBuildCategories_flagRowsDisabledAndLabelled() {
        val settings = AppSettings()
        val storage = StorageSizes()
        val categories = buildCategories(settings, storage, "", emptyList(), text)

        val allRows = categories.flatMap { it.rows }

        // Flags that must be false in ProfileFeatures
        val flagKeys = listOf(
            SettingKeys.READER_MODE,
            SettingKeys.VOLUME_KEYS,
            SettingKeys.UPDATE_ON_LAUNCH,
            SettingKeys.UNREAD_BADGES,
            SettingKeys.DEFAULT_SHELF,
            SettingKeys.QI_REWARDS,
            SettingKeys.BREAKTHROUGH_FX,
            SettingKeys.HAPTICS,
            SettingKeys.REDUCE_MOTION,
            SettingKeys.DAILY_GOAL,
            SettingKeys.REMINDER,
            SettingKeys.REMINDER_TIME,
            SettingKeys.DELETE_AFTER_READING,
            SettingKeys.AUTO_BACKUP,
            SettingKeys.CHECK_UPDATES,
            SettingKeys.LICENCES,
            SettingKeys.PRIVACY
        )

        for (k in flagKeys) {
            val row = allRows.firstOrNull { it.key == k }
            assertNotNull("Row with key $k must exist", row)
            assertFalse("Row with key $k must be disabled when feature flag is false", row!!.enabled)
            assertEquals("Coming soon", row.subtitle)
        }

        // CREATE_BACKUP and RESTORE_BACKUP are enabled when BACKUP_RESTORE is true
        val createRow = allRows.firstOrNull { it.key == SettingKeys.CREATE_BACKUP }
        assertNotNull(createRow)
        assertTrue(createRow!!.enabled)
        assertEquals("Never", createRow.subtitle)

        val restoreRow = allRows.firstOrNull { it.key == SettingKeys.RESTORE_BACKUP }
        assertNotNull(restoreRow)
        assertTrue(restoreRow!!.enabled)
        assertEquals(text.restoreBackupSubtitle, restoreRow.subtitle)
    }

    @Test
    fun testBuildCategories_hiddenTabsRow() {
        val settings = AppSettings()
        val storage = StorageSizes()
        val hidden = listOf(NavTab.DOWNLOADS)
        val categories = buildCategories(settings, storage, "", hidden, text)

        val appearance = categories.first { it.id == SettingsCategoryIds.APPEARANCE }
        val hiddenRow = appearance.rows.firstOrNull { it.key == SettingKeys.OPEN_TAB_PREFIX + NavTab.DOWNLOADS.name }
        assertNotNull("Hidden tab row must be generated", hiddenRow)
        assertTrue(hiddenRow is ActionRow)
        assertEquals("Open Downloads", hiddenRow!!.title)
    }

    @Test
    fun testBuildCategories_searchFiltering() {
        val settings = AppSettings()
        val storage = StorageSizes()
        val categories = buildCategories(settings, storage, "Wi-Fi", emptyList(), text)

        assertEquals(1, categories.size)
        assertEquals(SettingsCategoryIds.DOWNLOADS, categories[0].id)
        val rows = categories[0].rows
        assertEquals(1, rows.size)
        assertEquals(SettingKeys.WIFI_ONLY, rows[0].key)

        // Non-matching search drops all categories
        val emptyCategories = buildCategories(settings, storage, "NonExistentTermXYZ", emptyList(), text)
        assertTrue(emptyCategories.isEmpty())
    }

    @Test
    fun testBuildCategories_summaries() {
        val settings = AppSettings(
            theme = com.example.data.model.ThemeSettings(
                themeMode = AppThemeMode.DARK,
                accentColorHex = "#7cf2c3"
            ),
            reader = com.example.data.model.ReaderSettings(
                fontType = FontType.SERIF,
                fontSizeSp = 19
            )
        )
        val storage = StorageSizes(chaptersBytes = 100L * 1024 * 1024, coversBytes = 20L * 1024 * 1024, cacheBytes = 50L * 1024 * 1024)
        val categories = buildCategories(settings, storage, "", emptyList(), text)

        val appearance = categories.first { it.id == SettingsCategoryIds.APPEARANCE }
        assertEquals("Ink · Jade accent", appearance.summary)

        val reader = categories.first { it.id == SettingsCategoryIds.READER }
        assertEquals("Bookish · 19", reader.summary)

        val downloads = categories.first { it.id == SettingsCategoryIds.DOWNLOADS }
        assertEquals("170 MB used", downloads.summary)
    }
}
