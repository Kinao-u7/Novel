package com.example.data.source.plugin

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LnPluginIdTest {

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
                    chapters: []
                };
            }
        };
        module.exports = { default: plugin };
    """.trimIndent()

    @Test
    fun testMalformedNovelIdThrowsIllegalArgumentException() = runBlocking<Unit> {
        val source = LnPluginSource(pluginScript = samplePluginScript)
        try {
            // 1. Wrong prefix
            val badPrefixIds = listOf(
                "not_a_valid_id",
                "ln_other_plugin_bm92ZWwvMTIz",
                "ln_",
                "ln_test_details"
            )
            for (id in badPrefixIds) {
                try {
                    source.getNovelDetails(id)
                    fail("Expected IllegalArgumentException for wrong prefix: $id")
                } catch (e: IllegalArgumentException) {
                    assertTrue("Exception message should note prefix issue: ${e.message}", e.message?.contains("cannot decode novelId") == true)
                }
            }

            // 2. Empty encoded part
            try {
                source.getNovelDetails("ln_test_details_")
                fail("Expected IllegalArgumentException for empty encoded part")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message?.contains("malformed novelId") == true)
            }

            // 3. Invalid characters (non [A-Za-z0-9_-]+)
            val badCharIds = listOf(
                "ln_test_details_???invalid_base64???",
                "ln_test_details_novel/123",
                "ln_test_details_bm92ZWw+MTIz",
                "ln_test_details_bm92 ZWw",
                "ln_test_details_bm92ZWw="
            )
            for (id in badCharIds) {
                try {
                    source.getNovelDetails(id)
                    fail("Expected IllegalArgumentException for invalid characters: $id")
                } catch (e: IllegalArgumentException) {
                    assertTrue(e.message?.contains("malformed novelId") == true)
                }
            }

            // 4. Non-round-tripping text
            val nonRoundTripIds = listOf(
                "ln_test_details_AB",
                "ln_test_details_A",
                "ln_test_details_abd"
            )
            for (id in nonRoundTripIds) {
                try {
                    source.getNovelDetails(id)
                    fail("Expected IllegalArgumentException for non-round-tripping id: $id")
                } catch (e: IllegalArgumentException) {
                    // Expected either round-trip or decode exception
                }
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
            val overlongId = source.encodeNovelId(longPath)

            // 1. getNovelDetails
            try {
                val res = source.getNovelDetails(overlongId)
                fail("Expected IllegalArgumentException for getNovelDetails with overlong novelId, but got: $res")
            } catch (e: IllegalArgumentException) {
                val msg = e.message ?: "<null>"
                assertTrue(
                    "Expected message containing 'exceeds safe length limit' in getNovelDetails, but got: '$msg'",
                    msg.contains("exceeds safe length limit")
                )
            }

            // 2. getChapterList
            try {
                val res = source.getChapterList(overlongId)
                fail("Expected IllegalArgumentException for getChapterList with overlong novelId, but got: $res")
            } catch (e: IllegalArgumentException) {
                val msg = e.message ?: "<null>"
                assertTrue(
                    "Expected message containing 'exceeds safe length limit' in getChapterList, but got: '$msg'",
                    msg.contains("exceeds safe length limit")
                )
            }

            // 3. getChapterContent with overlong novelId
            try {
                val res = source.getChapterContent(overlongId, "ch1")
                fail("Expected IllegalArgumentException for getChapterContent with overlong novelId, but got: $res")
            } catch (e: IllegalArgumentException) {
                val msg = e.message ?: "<null>"
                assertTrue(
                    "Expected message containing 'exceeds safe length limit' in getChapterContent (novelId), but got: '$msg'",
                    msg.contains("exceeds safe length limit")
                )
            }

            // 4. getChapterContent with overlong chapterId
            val normalId = source.encodeNovelId("normal-novel")
            val overlongChapterId = "c".repeat(250)
            try {
                val res = source.getChapterContent(normalId, overlongChapterId)
                fail("Expected IllegalArgumentException for getChapterContent with overlong chapterId, but got: $res")
            } catch (e: IllegalArgumentException) {
                val msg = e.message ?: "<null>"
                assertTrue(
                    "Expected message containing 'exceeds safe length limit' in getChapterContent (chapterId), but got: '$msg'",
                    msg.contains("exceeds safe length limit")
                )
            }
        } finally {
            source.close()
        }
    }
}
