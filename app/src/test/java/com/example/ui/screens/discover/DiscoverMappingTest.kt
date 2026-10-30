package com.example.ui.screens.discover

import com.example.data.model.Chapter
import com.example.data.model.ChapterContent
import com.example.data.model.DiscoverSettings
import com.example.data.model.Novel
import com.example.data.source.NovelSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoverMappingTest {

    private class TestNovelSource(
        override val id: String,
        override val name: String,
        override val language: String,
        override val version: String = "1.0.0",
        override val description: String = "",
        override val isLocal: Boolean = false,
        val isAdult: Boolean = false
    ) : NovelSource {
        override suspend fun getPopularNovels(page: Int): List<Novel> = emptyList()
        override suspend fun searchNovels(query: String, page: Int): List<Novel> = emptyList()
        override suspend fun getNovelDetails(novelId: String): Novel = error("Not needed")
        override suspend fun getChapterList(novelId: String): List<Chapter> = emptyList()
        override suspend fun getChapterContent(novelId: String, chapterId: String): ChapterContent = error("Not needed")
    }

    private val sourceA = TestNovelSource("src-a", "Alpha Novel", "English")
    private val sourceB = TestNovelSource("src-b", "Beta Stories", "English")
    private val sourceC = TestNovelSource("src-c", "Celestial Pavilion", "Chinese")
    private val sourceD = TestNovelSource("src-d", "Dragon Realm", "Chinese")
    private val sourceE = TestNovelSource("src-e", "Echoing Winds", "Spanish", isAdult = true)

    private val allSources = listOf(sourceB, sourceD, sourceA, sourceE, sourceC)

    @Test
    fun testEmptyEnabledLanguages_allowsAllLanguages() {
        val result = filterSourcesByLanguage(allSources, emptyList())
        assertEquals(allSources.size, result.size)
    }

    @Test
    fun testEnabledLanguagesFilter_filtersOutOtherLanguagesCaseInsensitive() {
        val result = filterSourcesByLanguage(allSources, listOf("english", "SPANISH"))
        assertEquals(3, result.size)
        assertTrue(result.any { it.id == "src-a" })
        assertTrue(result.any { it.id == "src-b" })
        assertTrue(result.any { it.id == "src-e" })
        assertFalse(result.any { it.language == "Chinese" })
    }

    @Test
    fun testQueryFilter_matchesNameOrLanguageCaseInsensitive() {
        // Query matching name
        val nameMatch = filterSourcesByQuery(allSources, "alpha")
        assertEquals(1, nameMatch.size)
        assertEquals("src-a", nameMatch[0].id)

        // Query matching language
        val langMatch = filterSourcesByQuery(allSources, "chinese")
        assertEquals(2, langMatch.size)
        assertTrue(langMatch.any { it.id == "src-c" })
        assertTrue(langMatch.any { it.id == "src-d" })

        // Blank query returns all
        val blankMatch = filterSourcesByQuery(allSources, "   ")
        assertEquals(allSources.size, blankMatch.size)
    }

    @Test
    fun testPinnedOrder_preservesPinnedSourceIdsOrder() {
        // Given pinnedSourceIds in a specific order [src-d, src-a]
        val pinnedIds = listOf("src-d", "src-a")
        val pinnedItems = buildPinnedSources(allSources, pinnedIds)

        assertEquals(2, pinnedItems.size)
        assertEquals("src-d", pinnedItems[0].id)
        assertTrue(pinnedItems[0].isPinned)
        assertEquals("src-a", pinnedItems[1].id)
        assertTrue(pinnedItems[1].isPinned)
    }

    @Test
    fun testGrouping_unpinnedSourcesGroupedByLanguageAndSortedByName() {
        val pinnedIds = listOf("src-a") // src-a is pinned
        val groups = buildLanguageGroups(allSources, pinnedIds)

        // Chinese and English and Spanish groups, sorted by language name
        assertEquals(3, groups.size)
        assertEquals("Chinese", groups[0].language)
        assertEquals("English", groups[1].language)
        assertEquals("Spanish", groups[2].language)

        // Chinese sources sorted by name: Celestial Pavilion before Dragon Realm
        assertEquals(2, groups[0].sources.size)
        assertEquals("src-c", groups[0].sources[0].id)
        assertFalse(groups[0].sources[0].isPinned)
        assertEquals("src-d", groups[0].sources[1].id)
        assertFalse(groups[0].sources[1].isPinned)

        // English sources: only Beta Stories because Alpha Novel is pinned
        assertEquals(1, groups[1].sources.size)
        assertEquals("src-b", groups[1].sources[0].id)
        assertFalse(groups[1].sources[0].isPinned)

        // Spanish sources
        assertEquals(1, groups[2].sources.size)
        assertEquals("src-e", groups[2].sources[0].id)
        assertTrue(groups[2].sources[0].isAdult)
    }

    @Test
    fun testMapToDiscoverUiState_pureMappingIntegration() {
        val settings = DiscoverSettings(
            pinnedSourceIds = listOf("src-e", "src-c"),
            enabledLanguages = listOf("Chinese", "Spanish")
        )

        val uiState = mapToDiscoverUiState(
            sources = allSources,
            discoverSettings = settings,
            query = "",
            isSearchOpen = true,
            message = "Test message"
        )

        assertEquals(DiscoverTab.SOURCES, uiState.tab)
        assertFalse(uiState.extensionsEnabled)
        assertTrue(uiState.isSearchOpen)
        assertEquals("Test message", uiState.message)

        // Pinned items: src-e (Spanish) and src-c (Chinese)
        assertEquals(2, uiState.pinned.size)
        assertEquals("src-e", uiState.pinned[0].id)
        assertTrue(uiState.pinned[0].isPinned)
        assertTrue(uiState.pinned[0].isAdult)
        assertNull(uiState.pinned[0].iconUrl)

        assertEquals("src-c", uiState.pinned[1].id)
        assertTrue(uiState.pinned[1].isPinned)

        // Groups: unpinned sources in Chinese & Spanish => only src-d (Dragon Realm) in Chinese
        assertEquals(1, uiState.sourceGroups.size)
        assertEquals("Chinese", uiState.sourceGroups[0].language)
        assertEquals(1, uiState.sourceGroups[0].sources.size)
        assertEquals("src-d", uiState.sourceGroups[0].sources[0].id)
        assertFalse(uiState.sourceGroups[0].sources[0].isPinned)
    }

    @Test
    fun testMapToDiscoverUiState_whenEmpty_hasEmptyPinnedAndGroups() {
        val uiState = mapToDiscoverUiState(
            sources = emptyList(),
            discoverSettings = DiscoverSettings(),
            query = ""
        )

        assertTrue(uiState.pinned.isEmpty())
        assertTrue(uiState.sourceGroups.isEmpty())
        assertFalse(uiState.extensionsEnabled)
    }
}
