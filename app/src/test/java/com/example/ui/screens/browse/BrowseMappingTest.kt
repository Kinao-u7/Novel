package com.example.ui.screens.browse

import com.example.data.model.Novel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowseMappingTest {

    private fun createNovel(id: String, title: String) = Novel(
        id = id,
        title = title,
        author = "Author $id",
        description = "Description $id",
        sourceId = "test_source",
        sourceName = "Test Source",
        rating = 4.5f,
        coverUrl = "http://example.com/cover_$id.jpg"
    )

    @Test
    fun testMapNovels_popularAssigns1BasedRanks() {
        val novels = listOf(createNovel("1", "Novel 1"), createNovel("2", "Novel 2"))
        val mapped = BrowseMapping.mapNovels(novels, BrowseMode.POPULAR, startingRank = 1)

        assertEquals(2, mapped.size)
        assertEquals("1", mapped[0].id)
        assertEquals(1, mapped[0].rank)
        assertEquals("2", mapped[1].id)
        assertEquals(2, mapped[1].rank)
    }

    @Test
    fun testMapNovels_latestAssignsNullRanks() {
        val novels = listOf(createNovel("1", "Novel 1"), createNovel("2", "Novel 2"))
        val mapped = BrowseMapping.mapNovels(novels, BrowseMode.LATEST, startingRank = 1)

        assertEquals(2, mapped.size)
        assertNull(mapped[0].rank)
        assertNull(mapped[1].rank)
    }

    @Test
    fun testAppendAndDedupe_deduplicatesByIdAndReindexesPopular() {
        val existing = listOf(
            BrowseNovelItem("1", "Novel 1", "Author 1", 4.0f, null, 1),
            BrowseNovelItem("2", "Novel 2", "Author 2", 4.2f, null, 2)
        )
        val newItems = listOf(
            BrowseNovelItem("2", "Novel 2 Duplicate", "Author 2", 4.2f, null, 1),
            BrowseNovelItem("3", "Novel 3", "Author 3", 4.8f, null, 2)
        )

        val combined = BrowseMapping.appendAndDedupe(existing, newItems, BrowseMode.POPULAR)

        assertEquals(3, combined.size)
        assertEquals(listOf("1", "2", "3"), combined.map { it.id })
        assertEquals(listOf(1, 2, 3), combined.map { it.rank })
    }

    @Test
    fun testAppendAndDedupe_latestPreservesNullRanks() {
        val existing = listOf(
            BrowseNovelItem("1", "Novel 1", "Author 1", 4.0f, null, null)
        )
        val newItems = listOf(
            BrowseNovelItem("2", "Novel 2", "Author 2", 4.2f, null, null)
        )

        val combined = BrowseMapping.appendAndDedupe(existing, newItems, BrowseMode.LATEST)

        assertEquals(2, combined.size)
        assertNull(combined[0].rank)
        assertNull(combined[1].rank)
    }

    @Test
    fun testHasMorePages() {
        assertTrue(BrowseMapping.hasMorePages(10))
        assertTrue(BrowseMapping.hasMorePages(1))
        assertFalse(BrowseMapping.hasMorePages(0))
    }
}
