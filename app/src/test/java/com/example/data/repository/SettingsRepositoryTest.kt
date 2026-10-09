package com.example.data.repository

import com.example.data.model.AppSettings
import com.example.data.model.AppThemeMode
import com.example.data.model.MarginType
import com.example.data.model.ReaderSettings
import com.example.data.model.ReaderTextAlign
import com.example.data.model.ReaderTheme
import com.example.data.model.ThemeSettings
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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

        // Reader defaults: Sepia, Bookish Serif, 18sp, Justified, 20dp margin
        assertEquals(ReaderTheme.SEPIA, defaults.reader.theme)
        assertEquals(18, defaults.reader.fontSizeSp)
        assertEquals(ReaderTextAlign.JUSTIFIED, defaults.reader.textAlign)
        assertTrue(defaults.reader.isJustified)
        assertEquals(20, defaults.reader.horizontalMarginDp)
        assertEquals(MarginType.NORMAL, defaults.reader.marginType)

        // Theme defaults: Dark mode, Warm Gold / Amber accent (#DEB887), Dynamic color disabled
        assertEquals(AppThemeMode.DARK, defaults.theme.themeMode)
        assertEquals("#DEB887", defaults.theme.accentColorHex)
        assertFalse(defaults.theme.useDynamicColor)

        // Download defaults: WiFi only true, 3 simultaneous downloads
        assertTrue(defaults.downloads.wifiOnly)
        assertFalse(defaults.downloads.allowMobileData)
        assertEquals(3, defaults.downloads.simultaneousDownloads)

        // Notification defaults: Notify on download and novel update
        assertTrue(defaults.notifications.notifyOnDownload)
        assertTrue(defaults.notifications.notifyOnNovelUpdate)

        // Library default: Grid view
        assertTrue(defaults.isLibraryGridView)
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
                horizontalMarginDp = 28
            ),
            theme = ThemeSettings(
                themeMode = AppThemeMode.AMOLED,
                accentColorHex = "#6366F1",
                useDynamicColor = false
            ),
            isLibraryGridView = false
        )

        val encoded = json.encodeToString(AppSettings.serializer(), original)
        val decoded = json.decodeFromString(AppSettings.serializer(), encoded)

        assertEquals(original, decoded)
        assertEquals(ReaderTheme.WARM, decoded.reader.theme)
        assertEquals(22, decoded.reader.fontSizeSp)
        assertEquals(28, decoded.reader.horizontalMarginDp)
        assertEquals(AppThemeMode.AMOLED, decoded.theme.themeMode)
        assertEquals("#6366F1", decoded.theme.accentColorHex)
        assertFalse(decoded.isLibraryGridView)
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
    }
}
