package com.example.data.source.plugin

import com.example.data.model.Novel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LnPluginDetailsTest {

    private val samplePluginScript = """
        const plugin = {
            id: 'test_details',
            name: 'Test Details Plugin',
            site: 'https://example.com',
            version: '1.0.0',
            resolveUrl(path, isNovel) {
                return 'https://example.com/' + path;
            },
            async parseNovel(path) {
                return {
                    name: 'My Test Novel',
                    path: path,
                    author: 'Author San',
                    summary: 'A great summary of the novel.',
                    genres: 'Action, , Adventure , Fantasy ',
                    status: 'Publishing Finished',
                    rating: 4.5,
                    cover: 'https://example.com/cover.jpg',
                    chapters: [
                        { name: 'Ch 1', path: 'ch1', releaseTime: '2025-01-01', chapterNumber: 1 }
                    ]
                };
            }
        };
        module.exports = { default: plugin };
    """.trimIndent()

    @Test
    fun testNovelDetailsMappingAllFields() = runBlocking<Unit> {
        val source = LnPluginSource(pluginScript = samplePluginScript)
        try {
            val novelId = source.encodeNovelId("novel/123")
            val novel = source.getNovelDetails(novelId)

            assertEquals(novelId, novel.id)
            assertEquals("My Test Novel", novel.title)
            assertEquals("Author San", novel.author)
            assertEquals("A great summary of the novel.", novel.description)
            assertEquals(listOf("Action", "Adventure", "Fantasy"), novel.genres)
            assertEquals("Completed", novel.status)
            assertEquals(4.5f, novel.rating ?: 0f, 0.01f)
            assertEquals("https://example.com/cover.jpg", novel.coverUrl)
            assertEquals("ln_test_details", novel.sourceId)
            assertEquals("Test Details Plugin", novel.sourceName)
            assertEquals("novel/123", novel.rawId)
            assertEquals("https://example.com/novel/123", novel.sourceUrl)
            assertEquals(1, novel.totalChapters)
        } finally {
            source.close()
        }
    }

    @Test
    fun testStatusMappingTable() {
        assertEquals("Completed", LnPluginMapping.normalizeNovelStatus("Completed"))
        assertEquals("Completed", LnPluginMapping.normalizeNovelStatus("Publishing Finished"))
        assertEquals("Ongoing", LnPluginMapping.normalizeNovelStatus("Ongoing"))
        assertEquals("On Hiatus", LnPluginMapping.normalizeNovelStatus("On Hiatus"))
        assertEquals("Cancelled", LnPluginMapping.normalizeNovelStatus("Cancelled"))
        assertEquals("Unknown", LnPluginMapping.normalizeNovelStatus("Licensed"))
        assertEquals("Unknown", LnPluginMapping.normalizeNovelStatus("Unknown"))
        assertEquals("Unknown", LnPluginMapping.normalizeNovelStatus(""))
        assertEquals("Unknown", LnPluginMapping.normalizeNovelStatus(null))
    }

    @Test
    fun testDefaultCoverMappingNull() = runBlocking<Unit> {
        val scriptWithDefaultCover = """
            const plugin = {
                id: 'test_cover',
                name: 'Test Cover Plugin',
                async parseNovel(path) {
                    return {
                        name: 'Novel Without Cover',
                        path: path,
                        cover: 'https://github.com/LNReader/lnreader-plugins/blob/main/icons/src/coverNotAvailable.jpg?raw=true',
                        chapters: []
                    };
                }
            };
            module.exports = { default: plugin };
        """.trimIndent()

        val source = LnPluginSource(pluginScript = scriptWithDefaultCover)
        try {
            val novelId = source.encodeNovelId("novel/no-cover")
            val novel = source.getNovelDetails(novelId)
            assertNull("Default cover should map to null", novel.coverUrl)
        } finally {
            source.close()
        }
    }

    @Test
    fun testSourceUrlNullWhenResolveUrlMissing() = runBlocking<Unit> {
        val scriptWithoutResolve = """
            const plugin = {
                id: 'test_no_resolve',
                name: 'No Resolve Plugin',
                async parseNovel(path) {
                    return { name: 'Novel', path: path, chapters: [] };
                }
            };
            module.exports = { default: plugin };
        """.trimIndent()

        val source = LnPluginSource(pluginScript = scriptWithoutResolve)
        try {
            val novelId = source.encodeNovelId("novel/abc")
            val novel = source.getNovelDetails(novelId)
            assertNull("sourceUrl must be null when resolveUrl missing", novel.sourceUrl)
        } finally {
            source.close()
        }
    }

    @Test
    fun testJsonNullFieldsMapToDefaultOrNull() = runBlocking<Unit> {
        val scriptWithNulls = """
            const plugin = {
                id: 'test_nulls',
                name: 'Test Nulls Plugin',
                async parseNovel(path) {
                    return {
                        name: 'Novel With Nulls',
                        path: path,
                        author: null,
                        cover: null,
                        summary: null,
                        rating: null,
                        status: null,
                        genres: null,
                        chapters: [
                            { name: null, path: 'ch1', releaseTime: null, chapterNumber: null }
                        ]
                    };
                }
            };
            module.exports = { default: plugin };
        """.trimIndent()

        val source = LnPluginSource(pluginScript = scriptWithNulls)
        try {
            val novelId = source.encodeNovelId("novel/nulls")
            val novel = source.getNovelDetails(novelId)

            assertEquals("Novel With Nulls", novel.title)
            assertEquals("", novel.author)
            assertNull("Cover should be null, not 'null'", novel.coverUrl)
            assertEquals("", novel.description)
            assertNull("Rating should be null, not 'null'", novel.rating)
            assertEquals("Unknown", novel.status)
            assertTrue("Genres should be empty", novel.genres.isEmpty())
            assertNull("SourceUrl should be null, not 'null'", novel.sourceUrl)

            val chapters = source.getChapterList(novelId)
            assertEquals(1, chapters.size)
            assertEquals("ch1", chapters[0].title)
            assertEquals("Recent", chapters[0].releaseDate)
        } finally {
            source.close()
        }
    }
}
