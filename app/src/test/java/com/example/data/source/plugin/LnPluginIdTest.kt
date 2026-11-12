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
