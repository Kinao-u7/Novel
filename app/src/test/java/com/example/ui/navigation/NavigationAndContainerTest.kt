package com.example.ui.navigation

import com.example.NovelApplication
import com.example.data.model.AppSettings
import com.example.data.model.BottomBarSettings
import com.example.data.model.NavTab
import com.example.data.repository.SettingsRepository
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NavigationAndContainerTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun routes_roundTripThroughKotlinxSerialization() {
        // Library
        val libEncoded = json.encodeToString(Library.serializer(), Library)
        val libDecoded = json.decodeFromString(Library.serializer(), libEncoded)
        assertEquals(Library, libDecoded)

        // Discover
        val discEncoded = json.encodeToString(Discover.serializer(), Discover)
        val discDecoded = json.decodeFromString(Discover.serializer(), discEncoded)
        assertEquals(Discover, discDecoded)

        // Cultivate
        val cultEncoded = json.encodeToString(Cultivate.serializer(), Cultivate)
        val cultDecoded = json.decodeFromString(Cultivate.serializer(), cultEncoded)
        assertEquals(Cultivate, cultDecoded)

        // Downloads
        val downEncoded = json.encodeToString(Downloads.serializer(), Downloads)
        val downDecoded = json.decodeFromString(Downloads.serializer(), downEncoded)
        assertEquals(Downloads, downDecoded)

        // Profile (null subPage)
        val profNull = Profile()
        val profNullEncoded = json.encodeToString(Profile.serializer(), profNull)
        val profNullDecoded = json.decodeFromString(Profile.serializer(), profNullEncoded)
        assertEquals(profNull, profNullDecoded)

        // Profile (with subPage)
        val profSub = Profile(subPage = "appearance")
        val profSubEncoded = json.encodeToString(Profile.serializer(), profSub)
        val profSubDecoded = json.decodeFromString(Profile.serializer(), profSubEncoded)
        assertEquals(profSub, profSubDecoded)

        // NovelDetail
        val detail = NovelDetail(novelId = "novel_42")
        val detailEncoded = json.encodeToString(NovelDetail.serializer(), detail)
        val detailDecoded = json.decodeFromString(NovelDetail.serializer(), detailEncoded)
        assertEquals(detail, detailDecoded)

        // Reader
        val reader = Reader(novelId = "novel_99", chapterIndex = 17)
        val readerEncoded = json.encodeToString(Reader.serializer(), reader)
        val readerDecoded = json.decodeFromString(Reader.serializer(), readerEncoded)
        assertEquals(reader, readerDecoded)

        // BrowseSource
        val browse = BrowseSource(sourceId = "wtrlab")
        val browseEncoded = json.encodeToString(BrowseSource.serializer(), browse)
        val browseDecoded = json.decodeFromString(BrowseSource.serializer(), browseEncoded)
        assertEquals(browse, browseDecoded)

        // WebBrowser (null initialUrl)
        val webNull = WebBrowser()
        val webNullEncoded = json.encodeToString(WebBrowser.serializer(), webNull)
        val webNullDecoded = json.decodeFromString(WebBrowser.serializer(), webNullEncoded)
        assertEquals(webNull, webNullDecoded)

        // WebBrowser (with initialUrl)
        val webUrl = WebBrowser(initialUrl = "https://example.com/novel")
        val webUrlEncoded = json.encodeToString(WebBrowser.serializer(), webUrl)
        val webUrlDecoded = json.decodeFromString(WebBrowser.serializer(), webUrlEncoded)
        assertEquals(webUrl, webUrlDecoded)
    }

    @Test
    fun novelApplication_containerIsLazy_andUninitializedAfterOnCreate() {
        val app = NovelApplication()
        assertFalse(app.isContainerInitialized)

        app.onCreate()
        assertFalse(app.isContainerInitialized)

        val container = app.container
        assertTrue(app.isContainerInitialized)
        assertEquals(container, app.container)
    }

    @Test
    fun bottomBarSettings_defaults_haveExactFourTabsInDefaultOrder() {
        val settings = BottomBarSettings()
        assertEquals(
            listOf(NavTab.LIBRARY, NavTab.DISCOVER, NavTab.CULTIVATE, NavTab.PROFILE),
            settings.tabs
        )
    }

    @Test
    fun bottomBarSettings_sanitizing_deduplicatesTabs() {
        val input = listOf(NavTab.LIBRARY, NavTab.LIBRARY, NavTab.PROFILE, NavTab.DISCOVER)
        val sanitized = SettingsRepository.sanitizeBottomBarTabs(input)
        assertEquals(4, sanitized.size)
        assertEquals(sanitized.distinct().size, sanitized.size)
        assertTrue(sanitized.contains(NavTab.LIBRARY))
        assertTrue(sanitized.contains(NavTab.PROFILE))
        assertEquals(listOf(NavTab.LIBRARY, NavTab.PROFILE, NavTab.DISCOVER, NavTab.CULTIVATE), sanitized)
    }

    @Test
    fun bottomBarSettings_sanitizing_enforcesPinnedTabs_whenMissing() {
        val input = listOf(NavTab.DISCOVER, NavTab.CULTIVATE, NavTab.DOWNLOADS)
        val sanitized = SettingsRepository.sanitizeBottomBarTabs(input)
        assertEquals(4, sanitized.size)
        assertTrue(sanitized.contains(NavTab.LIBRARY))
        assertTrue(sanitized.contains(NavTab.PROFILE))
    }

    @Test
    fun bottomBarSettings_sanitizing_fillsShortfall_fromDefaultOrder() {
        val input = listOf(NavTab.LIBRARY, NavTab.PROFILE)
        val sanitized = SettingsRepository.sanitizeBottomBarTabs(input)
        assertEquals(
            listOf(NavTab.LIBRARY, NavTab.PROFILE, NavTab.DISCOVER, NavTab.CULTIVATE),
            sanitized
        )
    }

    @Test
    fun bottomBarSettings_sanitizing_trimsExcess_preservingPinnedTabs() {
        // All 5 tabs provided with pinned tabs at the very end
        val input = listOf(NavTab.DISCOVER, NavTab.CULTIVATE, NavTab.DOWNLOADS, NavTab.LIBRARY, NavTab.PROFILE)
        val sanitized = SettingsRepository.sanitizeBottomBarTabs(input)
        assertEquals(4, sanitized.size)
        assertTrue(sanitized.contains(NavTab.LIBRARY))
        assertTrue(sanitized.contains(NavTab.PROFILE))
        // Should drop extras from the end without dropping pinned tabs
        assertEquals(listOf(NavTab.DISCOVER, NavTab.CULTIVATE, NavTab.LIBRARY, NavTab.PROFILE), sanitized)
    }

    @Test
    fun sanitizeSettings_sanitizesBottomBarSettingsInsideAppSettings() {
        val appSettings = AppSettings(
            bottomBar = BottomBarSettings(
                tabs = listOf(NavTab.DOWNLOADS)
            )
        )
        val sanitized = SettingsRepository.sanitizeSettings(appSettings)
        assertEquals(4, sanitized.bottomBar.tabs.size)
        assertTrue(sanitized.bottomBar.tabs.contains(NavTab.LIBRARY))
        assertTrue(sanitized.bottomBar.tabs.contains(NavTab.PROFILE))
        assertTrue(sanitized.bottomBar.tabs.contains(NavTab.DOWNLOADS))
    }
}
