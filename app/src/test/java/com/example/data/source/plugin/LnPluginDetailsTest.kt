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
    fun testMalformedNovelIdThrowsIllegalArgumentException() = runBlocking<Unit> {
        val source = LnPluginSource(pluginScript = samplePluginScript)
        try {
            try {
                source.getNovelDetails("not_a_valid_id")
                fail("Expected IllegalArgumentException for invalid prefix")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message?.contains("cannot decode novelId") == true)
            }

            try {
                source.getNovelDetails("ln_test_details_???invalid_base64???")
                fail("Expected IllegalArgumentException for invalid base64")
            } catch (e: IllegalArgumentException) {
                // Expected
            }
        } finally {
            source.close()
        }
    }

    @Test
    fun testOverlongPathThrowsIllegalArgumentException() = runBlocking<Unit> {
        val source = LnPluginSource(pluginScript = samplePluginScript)
        try {
            val longPath = "a".repeat(250)
            val overlongId = "ln_test_details_" + android.util.Base64.encodeToString(
                longPath.toByteArray(Charsets.UTF_8),
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
            )
            try {
                source.getNovelDetails(overlongId)
                fail("Expected IllegalArgumentException for overlong path")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message?.contains("exceeds safe length limit") == true)
            }
        } finally {
            source.close()
        }
    }
}
